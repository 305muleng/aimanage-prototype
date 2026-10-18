package com.aiguanli.ai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证真正的终端确认操作，不把模型输出或未提交的表单当成人工采纳。 */
class DecisionConsoleTest {
    private final InputStream originalInput = System.in;
    private final PrintStream originalOutput = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private DecisionReviewService reviews;
    private KnowledgeService knowledge;
    private ConsoleChat console;
    private DecisionReviewService.Candidate pending;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        reviews = mock(DecisionReviewService.class);
        knowledge = mock(KnowledgeService.class);
        ObjectProvider<KnowledgeService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(knowledge);
        pending = candidate(DecisionReviewService.Status.PENDING_REVIEW, null);
        when(reviews.get("D-test")).thenReturn(pending);
        console = new ConsoleChat(mock(AssistantService.class), mock(DiscussionService.class), mock(DecompositionService.class),
                mock(ProgressService.class), mock(CollaborationService.class), reviews, new StorageSettings(false),
                new ObjectMapper(), provider, mock(ConfigurableApplicationContext.class));
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @AfterEach void restore() { System.setIn(originalInput); System.setOut(originalOutput); }

    @Test
    void cancellingFinalConfirmationDoesNotSubmitReviewOrPublish() throws Exception {
        run("/review-decision D-test\n1\n审核人\n核对原文\n取消\n/exit\n");
        verify(reviews, never()).review(anyString(), any(), anyString(), anyString(), any());
        verify(reviews, never()).publish(anyString(), any());
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("仍待审核");
    }

    @Test
    void explicitHumanConfirmationSubmitsModifiedTextThenPublishes() throws Exception {
        var review = new DecisionReviewService.Review(DecisionReviewService.Action.MODIFY, "审核人", "修订说明", "2026-10-18T00:00:00Z");
        var approved = candidate(DecisionReviewService.Status.APPROVED, review);
        when(reviews.get("D-test")).thenReturn(pending, approved);
        when(reviews.publish("D-test", knowledge)).thenReturn(new DecisionReviewService.Publication(
                candidate(DecisionReviewService.Status.PUBLISHED, review), new KnowledgeService.ImportResult("决策", 1, false)));
        run("/review-decision D-test\n2\n修改后的完整决策\n审核人\n修订说明\n确认\n/exit\n");
        var order = org.mockito.Mockito.inOrder(reviews);
        order.verify(reviews).get("D-test");
        order.verify(reviews).review("D-test", DecisionReviewService.Action.MODIFY, "审核人", "修订说明", "修改后的完整决策");
        order.verify(reviews).get("D-test");
        order.verify(reviews).publish("D-test", knowledge);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("已加入当前项目知识库", "未写入本地文件或远程数据库");
    }

    @Test
    void confirmedRejectionNeverPublishes() throws Exception {
        run("/review-decision D-test\n3\n审核人\n证据不足\n确认\n/exit\n");
        verify(reviews).review("D-test", DecisionReviewService.Action.REJECT, "审核人", "证据不足", null);
        verify(reviews, never()).publish(anyString(), any());
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("已记录否决");
    }

    @Test
    void menuDigitInModifiedTextIsRejectedBeforeReviewSubmission() throws Exception {
        run("/review-decision D-test\n2\n1\n/exit\n");
        verify(reviews, never()).review(anyString(), any(), anyString(), anyString(), any());
        verify(reviews, never()).publish(anyString(), any());
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("此处不是选项", "未提交，仍待审核", "重新审核并选择 1");
    }

    private void run(String input) throws Exception {
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        console.run();
    }
    private static DecisionReviewService.Candidate candidate(DecisionReviewService.Status status, DecisionReviewService.Review review) {
        return new DecisionReviewService.Candidate("D-test", "原始候选决策", status == DecisionReviewService.Status.PENDING_REVIEW ? null : "修改后的完整决策",
                status, true, "discussion.json", "test-hash", "[]", "2026-10-18T00:00:00Z",
                List.of(new DiscussionMessage("m1", "测试成员", "原始群聊消息")), review, null);
    }
}
