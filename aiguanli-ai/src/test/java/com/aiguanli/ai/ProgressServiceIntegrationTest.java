package com.aiguanli.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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

/** 真正经过 Spring AI 的工具调用协议，但模型端使用本机模拟 HTTP 服务。 */
@SpringBootTest(properties = {
        "app.console.enabled=false", "app.rag.enabled=false", "logging.level.root=ERROR",
        "app.storage.local-persistence-enabled=true",
        "AI_MODEL=deepseek-flash", "AI_CHAT_PATH=/chat/completions", "AI_THINKING_MODE=disabled"
})
class ProgressServiceIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean SKIP_TOOL = new AtomicBoolean();
    private static final HttpServer SERVER = createServer();
    private static final String ANSWER = "模拟数据分析草稿：3/6，完成率50%，延期T-4，阻塞T-6。不代表真实进度。";

    @TempDir
    static Path temporaryDirectory;

    @Autowired
    ProgressService progress;

    @Autowired
    ChatClient.Builder builder;

    @Autowired
    ObjectMapper mapper;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.ai.openai.api-key", () -> "local-test-key");
        registry.add("app.decomposition.draft-directory", () -> temporaryDirectory.toString());
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        SKIP_TOOL.set(false);
    }

    @Test
    void executesToolAndReturnsComputedFactsToModelBeforeAnswering() throws Exception {
        var result = progress.analyze(Path.of("examples/tasks.json"));
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.report().completionPercent()).isEqualByComparingTo("50");
        assertThat(result.report().overdueTaskIds()).containsExactly("T-4");
        assertThat(result.report().blockedTaskIds()).containsExactly("T-6");
        assertThat(result.answer()).isEqualTo(ANSWER);
        assertThat(REQUESTS).hasSize(2);
        JsonNode first = REQUESTS.get(0);
        assertThat(first.path("tools").get(0).path("function").path("name").asText()).isEqualTo("getSprintProgress");
        assertThat(first.path("messages").toString()).doesNotContain("T-4", "T-6");
        JsonNode toolMessage = null;
        for (JsonNode message : REQUESTS.get(1).path("messages")) {
            if (message.path("role").asText().equals("tool")) { toolMessage = message; }
        }
        assertThat(toolMessage).isNotNull();
        assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call-progress");
        JsonNode facts = MAPPER.readTree(toolMessage.path("content").asText());
        assertThat(facts.path("simulation").asBoolean()).isTrue();
        assertThat(facts.path("totalTasks").asInt()).isEqualTo(6);
        assertThat(facts.path("completedTasks").asInt()).isEqualTo(3);
        assertThat(facts.path("completionPercent").asDouble()).isEqualTo(50.0);
        assertThat(facts.path("dependencyRisks").get(0).path("dependsOn").asText()).isEqualTo("T-4");
        assertThat(result.file()).exists();
        String draft = Files.readString(result.file());
        String original = Files.readString(Path.of("examples/tasks.json"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("examples/tasks.json"))));
        assertThat(draft).contains("待人工审核", "审核人：待填写", "处理结论：待填写", "deepseek-flash",
                "调用次数：1", "50.00%", ANSWER, original, hash, "Java 工具返回结果");
    }

    @Test
    void rejectsAnswerWhenModelSkipsTool() throws Exception {
        long before = countDrafts();
        SKIP_TOOL.set(true);
        assertThatThrownBy(() -> progress.analyze(Path.of("examples/tasks.json")))
                .isInstanceOf(ProgressService.ToolNotCalledException.class);
        assertThat(REQUESTS).hasSize(1);
        assertThat(countDrafts()).isEqualTo(before);
    }

    @Test
    void invalidInputFailsBeforeAnyModelRequest() throws Exception {
        long before = countDrafts();
        Path invalid = temporaryDirectory.resolve("invalid.json");
        Files.writeString(invalid, "{\"simulation\":false,\"snapshotDate\":\"2026-10-11\",\"sprintName\":\"demo\",\"tasks\":[]}");
        assertThatThrownBy(() -> progress.analyze(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(REQUESTS).isEmpty();
        assertThat(countDrafts()).isEqualTo(before);
    }

    @Test
    void secondFileDoesNotReusePreviousSnapshot() throws Exception {
        progress.analyze(Path.of("examples/tasks.json"));
        REQUESTS.clear();
        Path empty = temporaryDirectory.resolve("empty.json");
        Files.writeString(empty, "{\"simulation\":true,\"snapshotDate\":\"2026-10-12\",\"sprintName\":\"新的模拟\",\"tasks\":[]}");
        var result = progress.analyze(empty);
        assertThat(result.report().totalTasks()).isZero();
        assertThat(result.report().completionPercent()).isNull();
        assertThat(REQUESTS.get(1).toString()).doesNotContain("T-4", "T-6");
        assertThat(Files.readString(result.file())).contains("无法计算（没有任务）", "2026-10-12");
    }

    @Test
    void retainsInputEvenAfterSourceChangesAndNeverOverwritesPreviousDraft() throws Exception {
        Path input = temporaryDirectory.resolve("snapshot.json");
        String original = Files.readString(Path.of("examples/tasks.json"));
        Files.writeString(input, original);
        var first = progress.analyze(input);
        String firstContent = Files.readString(first.file());
        Files.writeString(input, "{\"simulation\":true,\"snapshotDate\":\"2026-10-12\",\"sprintName\":\"空快照\",\"tasks\":[]}");
        var second = progress.analyze(input);
        assertThat(first.file()).isNotEqualTo(second.file());
        assertThat(Files.readString(first.file())).isEqualTo(firstContent).contains(original);
        assertThat(Files.readString(second.file())).contains("空快照", "无法计算（没有任务）");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.filter(path -> path.toString().endsWith(".tmp")).count()).isZero();
        }
    }

    @Test
    void reportsSaveFailureWithoutOverwritingExistingFile() throws Exception {
        Path invalidDirectory = temporaryDirectory.resolve("not-a-directory.txt");
        Files.writeString(invalidDirectory, "保留原内容");
        var service = new ProgressService(builder, mapper, invalidDirectory.toString(), "local-test-model", new StorageSettings(true));
        assertThatThrownBy(() -> service.analyze(Path.of("examples/tasks.json"))).isInstanceOf(IOException.class);
        assertThat(Files.readString(invalidDirectory)).isEqualTo("保留原内容");
        assertThat(REQUESTS).hasSize(2);
    }

    private static long countDrafts() throws IOException {
        try (var files = Files.list(temporaryDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("progress-")
                    && path.toString().endsWith(".md")).count();
        }
    }

    @Test
    void disabledPersistenceStillCallsToolWithoutWritingOrChangingExistingFiles() throws Exception {
        Path output = temporaryDirectory.resolve("disabled-output.txt");
        Files.writeString(output, "保留已有文件");
        long before = countDrafts();
        var service = new ProgressService(builder, mapper, output.toString(), "local-test-model", new StorageSettings(false));
        var result = service.analyze(Path.of("examples/tasks.json"));
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.report().completionPercent()).isEqualByComparingTo("50");
        assertThat(result.file()).isNull();
        assertThat(Files.readString(output)).isEqualTo("保留已有文件");
        assertThat(countDrafts()).isEqualTo(before);
    }

    @AfterAll
    static void stop() { SERVER.stop(0); }

    private static HttpServer createServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                REQUESTS.add(request);
                boolean hasToolResult = false;
                for (JsonNode message : request.path("messages")) {
                    if (message.path("role").asText().equals("tool")) { hasToolResult = true; }
                }
                boolean invoke = !hasToolResult && !SKIP_TOOL.get();
                Map<String, Object> message = invoke ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                        Map.of("id", "call-progress", "type", "function", "function",
                                Map.of("name", "getSprintProgress", "arguments", "{}"))))
                        : Map.of("role", "assistant", "content", ANSWER);
                byte[] response = MAPPER.writeValueAsBytes(Map.of("id", "mock-progress", "object", "chat.completion",
                        "created", 1, "model", "local-test-model", "choices", List.of(Map.of("index", 0,
                                "message", message, "finish_reason", invoke ? "tool_calls" : "stop")),
                        "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
}
