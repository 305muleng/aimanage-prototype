package com.aiguanli.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 Spring AI 客户端请求本机模拟服务，不接外部供应商。 */
@SpringBootTest(properties = {
        "app.console.enabled=false", "logging.level.root=ERROR", "app.storage.local-persistence-enabled=true",
        "AI_MODEL=deepseek-flash", "AI_CHAT_PATH=/chat/completions", "AI_THINKING_MODE=disabled"
})
class AssistantServiceIntegrationTest {

    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_METHOD = new AtomicReference<>();
    private static final String MOCK_ANSWER = "这是本地模拟回答，不是真实模型输出。";
    private static final AtomicReference<String> RESPONSE_CONTENT = new AtomicReference<>(MOCK_ANSWER);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpServer SERVER = createServer();
    private static final String DECOMPOSITION = """
            {"userStories":[{"id":"US-1","title":"创建任务","role":"项目负责人",
              "goal":"创建任务","benefit":"记录工作安排","acceptanceCriteria":["提交有效内容后生成任务记录。"]}],
             "developmentTasks":[{"id":"TASK-1","title":"实现任务创建","description":"实现创建及校验逻辑。","storyIds":["US-1"]}],
             "openQuestions":["哪些字段必填？"],"optionalSuggestions":["可选：任务创建通知。"]}
            """;

    @TempDir
    static Path draftDirectory;

    @Autowired
    private AssistantService assistant;

    @Autowired
    private DiscussionService discussion;

    @Autowired
    private DecompositionService decomposition;

    @Autowired
    private ChatClient.Builder builder;

    @BeforeEach
    void resetResponse() {
        RESPONSE_CONTENT.set(MOCK_ANSWER);
        REQUEST_BODY.set(null);
    }

    @DynamicPropertySource
    static void configureLocalModel(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.ai.openai.api-key", () -> "local-test-key");
        registry.add("app.decomposition.draft-directory", () -> draftDirectory.toString());
    }

    @Test
    void sendsChineseSystemAndUserMessagesAndParsesCompletion() throws Exception {
        String answer = assistant.ask("请解释 Sprint。");

        assertThat(answer).isEqualTo("这是本地模拟回答，不是真实模型输出。");
        assertThat(REQUEST_METHOD.get()).isEqualTo("POST");
        JsonNode request = new ObjectMapper().readTree(REQUEST_BODY.get());
        assertThat(request.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(request.path("messages").get(0).path("content").asText()).contains("爱管理 AI 协作助手");
        assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("请解释 Sprint。");
    }

    @Test
    void rejectsEmptyQuestionBeforeCallingTheModel() {
        assertThatThrownBy(() -> assistant.ask("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("问题不能为空。");
    }

    @Test
    void forwardsHistoryWithinOneConversationButIsolatesOtherConversations() throws Exception {
        String id = UUID.randomUUID().toString();
        assistant.ask(id, "我们把项目代号设为青竹。");
        assistant.ask(id, "项目代号是什么？");
        JsonNode messages = MAPPER.readTree(REQUEST_BODY.get()).path("messages");
        assertThat(messages.size()).isEqualTo(4);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).path("content").asText()).contains("青竹");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("content").asText()).isEqualTo(MOCK_ANSWER);
        assertThat(messages.get(2).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(3).path("content").asText()).isEqualTo("项目代号是什么？");

        assistant.ask(UUID.randomUUID().toString(), "项目代号是什么？");
        assertThat(MAPPER.readTree(REQUEST_BODY.get()).path("messages").size()).isEqualTo(2);
    }

    @Test
    void clearsHistoryOnRequest() throws Exception {
        String id = UUID.randomUUID().toString();
        assistant.ask(id, "项目代号是青竹。");
        assistant.clear(id);
        assistant.ask(id, "你记得代号吗？");
        assertThat(MAPPER.readTree(REQUEST_BODY.get()).path("messages").size()).isEqualTo(2);
        assertThat(REQUEST_BODY.get()).doesNotContain("青竹");
    }

    @Test
    void rollsBackMemoryAfterEmptyModelResponse() throws Exception {
        String id = UUID.randomUUID().toString();
        assistant.ask(id, "保留这条消息。");
        RESPONSE_CONTENT.set("");
        assertThatThrownBy(() -> assistant.ask(id, "失败问题不应留在历史。"))
                .isInstanceOf(IllegalStateException.class);
        RESPONSE_CONTENT.set(MOCK_ANSWER);
        assistant.ask(id, "继续讨论。");
        assertThat(REQUEST_BODY.get()).contains("保留这条消息").doesNotContain("失败问题");
        assertThat(MAPPER.readTree(REQUEST_BODY.get()).path("messages").size()).isEqualTo(4);
    }

    @Test
    void evictsOldMessagesFromTheWindow() {
        String id = UUID.randomUUID().toString();
        assistant.ask(id, "窗口中最早的独特标记");
        for (int i = 0; i < 11; i++) {
            assistant.ask(id, "后续消息" + i);
        }
        assertThat(REQUEST_BODY.get()).doesNotContain("窗口中最早的独特标记");
    }

    @Test
    void parsesStructuredDiscussionWithoutNormalChatHistory() throws Exception {
        String id = UUID.randomUUID().toString();
        assistant.ask(id, "这条私人聊天不应进入群聊解析。");
        RESPONSE_CONTENT.set("""
                {"summary":[{"text":"讨论字段草稿。","sourceMessageIds":["m1"]}],
                 "decisions":[],"suggestions":[],
                 "actionItems":[{"task":"整理字段草稿","owner":null,"deadline":null,"sourceMessageIds":["m1"]}],
                 "openQuestions":[]}
                """);
        var result = discussion.analyze(List.of(new DiscussionMessage("m1", "成员", "需要整理字段草稿。")));
        assertThat(result.actionItems().get(0).owner()).isNull();
        assertThat(result.actionItems().get(0).deadline()).isNull();
        assertThat(result.summary().get(0).sourceMessageIds()).containsExactly("m1");
        assertThat(REQUEST_BODY.get()).doesNotContain("这条私人聊天");
        JsonNode messages = MAPPER.readTree(REQUEST_BODY.get()).path("messages");
        assertThat(messages.get(0).path("content").asText()).contains("爱管理聊天解析助手");
        assertThat(messages.size()).isEqualTo(2);
    }

    @Test
    void refusesInventedEvidenceReferences() {
        RESPONSE_CONTENT.set("""
                {"summary":[{"text":"无法溯源的结论","sourceMessageIds":["missing-id"]}],
                 "decisions":[],"suggestions":[],"actionItems":[],"openQuestions":[]}
                """);
        assertThatThrownBy(() -> discussion.analyze(List.of(new DiscussionMessage("m1", "成员", "讨论。"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("消息来源");
    }

    @Test
    void rejectsDuplicateMessageIdsBeforeModelCall() {
        assertThatThrownBy(() -> discussion.analyze(List.of(
                new DiscussionMessage("m1", "甲", "第一条"), new DiscussionMessage("m1", "乙", "第二条"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能重复");
        assertThat(REQUEST_BODY.get()).isNull();
    }

    @Test
    void decomposesAndSavesAnUnreviewedDraftWithOriginalRequirement() throws Exception {
        assistant.ask(UUID.randomUUID().toString(), "私人聊天不应成为需求依据。");
        RESPONSE_CONTENT.set(DECOMPOSITION);
        var saved = decomposition.decompose("项目负责人可以创建任务。");

        assertThat(saved.result().userStories().get(0).id()).isEqualTo("US-1");
        assertThat(saved.result().developmentTasks().get(0).storyIds()).containsExactly("US-1");
        assertThat(saved.file()).startsWith(draftDirectory);
        assertThat(saved.file()).exists();
        String content = Files.readString(saved.file(), StandardCharsets.UTF_8);
        assertThat(content).contains("项目负责人可以创建任务。", "待人工审核", "审核人：待填写",
                "处理结论：待填写", "```json", "deepseek-flash");
        assertThat(saved.preview()).contains("用户故事与验收标准", "开发任务", "待确认问题", "可选建议")
                .doesNotContain("```json");
        assertThat(REQUEST_BODY.get()).doesNotContain("私人聊天");
        JsonNode messages = MAPPER.readTree(REQUEST_BODY.get()).path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("content").asText()).contains("爱管理需求拆解助手");
    }

    @Test
    void rejectsUnknownStoryReferencesWithoutSavingDraft() throws Exception {
        long before = countDrafts();
        RESPONSE_CONTENT.set(DECOMPOSITION.replace("\"storyIds\":[\"US-1\"]", "\"storyIds\":[\"US-missing\"]"));
        assertThatThrownBy(() -> decomposition.decompose("项目负责人创建任务。"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不存在的用户故事");
        assertThat(countDrafts()).isEqualTo(before);
    }

    @Test
    void rejectsStoryWithoutAcceptanceCriteria() throws Exception {
        long before = countDrafts();
        RESPONSE_CONTENT.set(DECOMPOSITION.replace("[\"提交有效内容后生成任务记录。\"]", "[]"));
        assertThatThrownBy(() -> decomposition.decompose("项目负责人创建任务。"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("验收标准");
        assertThat(countDrafts()).isEqualTo(before);
    }

    @Test
    void rejectsDuplicateStoryIdentifiers() throws Exception {
        long before = countDrafts();
        JsonNode response = MAPPER.readTree(DECOMPOSITION);
        var stories = (com.fasterxml.jackson.databind.node.ArrayNode) response.path("userStories");
        stories.add(stories.get(0).deepCopy());
        RESPONSE_CONTENT.set(MAPPER.writeValueAsString(response));
        assertThatThrownBy(() -> decomposition.decompose("项目负责人创建任务。"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("标识重复");
        assertThat(countDrafts()).isEqualTo(before);
    }

    @Test
    void givesRepeatedGenerationsSeparateDraftFiles() throws Exception {
        RESPONSE_CONTENT.set(DECOMPOSITION);
        var first = decomposition.decompose("项目负责人创建任务。");
        String original = Files.readString(first.file());
        var second = decomposition.decompose("项目负责人创建任务。");
        assertThat(second.file()).isNotEqualTo(first.file()).exists();
        assertThat(Files.readString(first.file())).isEqualTo(original);
    }

    @Test
    void rejectsEmptyRequirementBeforeCallingModelOrSaving() throws Exception {
        long before = countDrafts();
        assertThatThrownBy(() -> decomposition.decompose("  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("需求不能为空");
        assertThat(REQUEST_BODY.get()).isNull();
        assertThat(countDrafts()).isEqualTo(before);
    }

    private static long countDrafts() throws IOException {
        try (var files = Files.list(draftDirectory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md")).count();
        }
    }

    @Test
    void generatesDecompositionWithoutCreatingLocalDraftDirectoryWhenDisabled() throws Exception {
        RESPONSE_CONTENT.set(DECOMPOSITION);
        long before = countDrafts();
        Path disabledDirectory = draftDirectory.resolve("disabled-" + UUID.randomUUID());
        var service = new DecompositionService(builder, MAPPER, disabledDirectory.toString(),
                "local-test-model", new StorageSettings(false));
        var result = service.decompose("项目负责人创建任务。");
        assertThat(result.result().userStories()).hasSize(1);
        assertThat(result.preview()).contains("待人工审核");
        assertThat(result.file()).isNull();
        assertThat(disabledDirectory).doesNotExist();
        assertThat(countDrafts()).isEqualTo(before);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    private static HttpServer createServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                REQUEST_METHOD.set(exchange.getRequestMethod());
                REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = MAPPER.writeValueAsBytes(Map.of(
                        "id", "local-test", "object", "chat.completion", "created", 1,
                        "model", "local-test-model", "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", RESPONSE_CONTENT.get()),
                                "finish_reason", "stop")),
                        "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
