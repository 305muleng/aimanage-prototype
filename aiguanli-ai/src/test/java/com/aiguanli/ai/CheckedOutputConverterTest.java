package com.aiguanli.ai;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckedOutputConverterTest {
    private final CheckedOutputConverter<CollaborationService.CoordinationPlan> converter =
            new CheckedOutputConverter<>(CollaborationService.CoordinationPlan.class, new ObjectMapper());

    @Test
    void identicalDuplicateFieldsPreserveRecommendationsDecisionsAndQuotedText() {
        String input = """
                {"recommendations":[{"title":"核对前置任务","rationale":"原文字段：\\\"referencesSnapshot\\\":false",
                 "riskIds":["R-1"],"sourceMessageIds":["c1"],"sourceTaskIds":["T-CREATE"],
                 "referencesSnapshot":true,"referencesSnapshot":true}],"openQuestions":[],
                 "projectDecisions":{"references":[{"explanation":"核对开发顺序","decisionIds":["D-example"]}],"note":"仅用于模拟"},
                 "projectDecisions":{"note":"仅用于模拟","references":[{"decisionIds":["D-example"],"explanation":"核对开发顺序"}]}}
                """;
        var plan = converter.convert(input);
        assertThat(plan.recommendations()).hasSize(1);
        var item = plan.recommendations().get(0);
        assertThat(item.referencesSnapshot()).isTrue();
        assertThat(item.rationale()).isEqualTo("原文字段：\"referencesSnapshot\":false");
        assertThat(item.riskIds()).containsExactly("R-1");
        assertThat(item.sourceMessageIds()).containsExactly("c1");
        assertThat(plan.projectDecisions().references().get(0).decisionIds()).containsExactly("D-example");
    }

    @Test
    void conflictingDuplicatesNeverPickEitherValueOrDiscardEvidence() {
        for (String value : List.of(
                "{\"openQuestions\":[],\"openQuestions\":[{\"text\":\"不同内容\"}]}",
                "{\"recommendations\":[{\"referencesSnapshot\":true,\"referencesSnapshot\":false}]}",
                "{\"projectDecisions\":null,\"projectDecisions\":{\"references\":[],\"note\":\"不同\"}}")) {
            assertThatThrownBy(() -> converter.convert(value))
                    .isInstanceOf(CheckedOutputConverter.InvalidOutputException.class).hasMessageContaining("值冲突");
        }
    }

    @Test
    void acceptsOnlyOneCompleteObjectWithOptionalJsonFence() {
        var plan = converter.convert("```json\n{\"recommendations\":[],\"openQuestions\":[]}\n```");
        assertThat(plan.recommendations()).isEmpty();
        for (String text : List.of("", "null", "[]", "{}{}", "说明：{}", "{\"recommendations\":[", "```json\n{}",
                "{\"recommendations\":\"wrong-type\",\"openQuestions\":[]}")) {
            assertThatThrownBy(() -> converter.convert(text)).isInstanceOf(CheckedOutputConverter.InvalidOutputException.class);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void parseFailuresDoNotLogPayloadOrIncludeItInExceptionChains(CapturedOutput output) {
        String marker = "TEST_PRIVATE_OUTPUT_NEVER_LOG";
        assertThatThrownBy(() -> converter.convert("{\"recommendations\":\"" + marker + "\"}"))
                .isInstanceOf(CheckedOutputConverter.InvalidOutputException.class)
                .hasNoCause().hasMessageNotContaining(marker);
        assertThat(output.getAll()).doesNotContain(marker, "Could not parse the given text");
    }
}
