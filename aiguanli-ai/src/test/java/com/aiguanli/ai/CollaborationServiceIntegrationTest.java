package com.aiguanli.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三个角色均通过真实 Spring AI 客户端访问本机模拟 HTTP 服务，无真实模型费用。 */
@SpringBootTest(properties = {"app.console.enabled=false", "app.rag.enabled=false", "logging.level.root=ERROR",
        "app.storage.local-persistence-enabled=true", "AI_MODEL=deepseek-flash", "AI_CHAT_PATH=/chat/completions"})
class CollaborationServiceIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> FAIL = new AtomicReference<>("");
    private static final AtomicReference<Runnable> AFTER_MINUTES = new AtomicReference<>();
    private static final HttpServer SERVER = server();
    private static final String MINUTES = """
            {"summary":[{"text":"T-4 尚未完成。","sourceMessageIds":["m1"]}],
             "decisions":[{"text":"优先处理 T-4。","sourceMessageIds":["m2"]}],
             "suggestions":[],"actionItems":[{"task":"整理 T-4 问题清单","owner":"小林","deadline":"下午讨论前","sourceMessageIds":["m2","m3"]}],
             "openQuestions":[{"text":"T-5 是否推迟？","sourceMessageIds":["m4"]}]}
            """;
    private static final String RISKS = """
            {"risks":[{"id":"R-1","observation":"T-4 未完成，T-6 依赖 T-4。","potentialImpact":"可能影响联调，需要核实。",
              "sourceMessageIds":["m1"],"sourceTaskIds":["T-4","T-6"],"referencesSnapshot":false}],
             "informationGaps":[{"text":"未提供实际工时，不能预测完成日期。","sourceMessageIds":[],"sourceTaskIds":[],"referencesSnapshot":true}]}
            """;
    private static final String PLAN = """
            {"recommendations":[{"title":"核实 T-4 的未完成事项","rationale":"依据问题清单讨论后续安排，待团队确认。",
              "riskIds":["R-1"],"sourceMessageIds":["m2"],"sourceTaskIds":["T-4"],"referencesSnapshot":false}],
             "openQuestions":[{"text":"T-5 是否调整需团队确认。","sourceMessageIds":["m4"],"sourceTaskIds":["T-5"],"referencesSnapshot":false}]}
            """;

    @TempDir static Path temporaryDirectory;
    @Autowired CollaborationService collaboration;
    @Autowired AssistantService assistant;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("spring.ai.openai.api-key", () -> "local-test-key");
        properties.add("app.decomposition.draft-directory", () -> temporaryDirectory.resolve("forbidden-drafts").toString());
    }

    @BeforeEach void reset() {
        REQUESTS.clear();
        FAIL.set("");
        AFTER_MINUTES.set(null);
    }

    @Test
    void passesValidatedOutputsBetweenThreeRolesWithoutSavingOrUsingChatHistory() throws Exception {
        assistant.ask(UUID.randomUUID().toString(), "私人标记不得进入协作。模型应忽略这条历史。");
        REQUESTS.clear();
        Path input = input();
        String original = Files.readString(input);
        List<Path> before = files();
        var run = collaboration.collaborate(input);
        assertThat(REQUESTS).hasSize(3);
        assertThat(REQUESTS.get(0).toString()).contains("爱管理聊天解析助手").doesNotContain("progressFacts");
        String riskPacket = REQUESTS.get(1).path("messages").get(1).path("content").asText();
        String coordinationPacket = REQUESTS.get(2).path("messages").get(1).path("content").asText();
        assertThat(riskPacket).contains("minutesDraft", "优先处理 T-4", "progressFacts", "50.00");
        assertThat(coordinationPacket).contains("riskDraft", "R-1", "minutesDraft", "progressFacts");
        assertThat(REQUESTS.toString()).doesNotContain("私人标记");
        assertThat(run.facts().completionPercent()).isEqualByComparingTo("50");
        assertThat(run.facts().overdueTaskIds()).containsExactly("T-4");
        assertThat(run.status()).isEqualTo("PENDING_REVIEW");
        assertThat(run.simulation()).isTrue();
        assertThat(run.review().status()).isEqualTo("PENDING");
        assertThat(run.review().reviewer()).isNull();
        assertThat(run.inputSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
        assertThat(run.originalInput()).isEqualTo(original);
        assertThat(run.trace()).extracting(CollaborationService.StageTrace::id)
                .containsExactly("snapshot", "minutes", "risk", "coordination");
        assertThat(run.trace()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
        assertThat(run.preview()).contains("模拟数据", "待人工审核", "50.00%", "模型推断", "待团队确认", "消息", "T-4");
        assertThat(Files.readString(input)).isEqualTo(original);
        assertThat(files()).isEqualTo(before);
        assertThat(temporaryDirectory.resolve("forbidden-drafts")).doesNotExist();
    }

    @Test
    void invalidSimulationMessagesAndTasksFailBeforeAnyModelCall() throws Exception {
        Path input = input();
        ObjectNode valid = (ObjectNode) MAPPER.readTree(Files.readString(input));
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(valid.deepCopy().put("simulation", false));
        ObjectNode duplicate = valid.deepCopy();
        ((ObjectNode) duplicate.path("messages").get(1)).put("id", "m1");
        invalid.add(duplicate);
        ObjectNode badTask = valid.deepCopy();
        ((ObjectNode) badTask.path("progress")).put("simulation", false);
        invalid.add(badTask);
        for (var value : invalid) {
            Files.writeString(input, value.toString());
            assertThatThrownBy(() -> collaboration.collaborate(input)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void invalidMinutesStopRiskAndCoordinationStages() throws Exception {
        FAIL.set("minutes");
        assertThatThrownBy(() -> collaboration.collaborate(input()))
                .isInstanceOfSatisfying(CollaborationService.CollaborationFailedException.class, ex -> {
                    assertThat(ex.stage()).isEqualTo("minutes");
                    assertThat(ex.trace()).extracting(CollaborationService.StageTrace::status).containsExactly("COMPLETED", "FAILED");
                });
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    void invalidRiskEvidenceStopsCoordinatorAndNoReportIsSaved() throws Exception {
        FAIL.set("risk");
        Path input = input();
        List<Path> before = files();
        assertThatThrownBy(() -> collaboration.collaborate(input))
                .isInstanceOfSatisfying(CollaborationService.CollaborationFailedException.class,
                        ex -> assertThat(ex.stage()).isEqualTo("risk"));
        assertThat(REQUESTS).hasSize(2);
        assertThat(files()).isEqualTo(before);
    }

    @Test
    void coordinatorCannotInventAHandoffRiskReference() throws Exception {
        FAIL.set("coordination");
        assertThatThrownBy(() -> collaboration.collaborate(input()))
                .isInstanceOfSatisfying(CollaborationService.CollaborationFailedException.class, ex -> {
                    assertThat(ex.stage()).isEqualTo("coordination");
                    assertThat(ex.trace()).hasSize(4);
                    assertThat(ex.trace().get(3).status()).isEqualTo("FAILED");
                });
        assertThat(REQUESTS).hasSize(3);
    }

    @Test
    void coordinatorIdenticalDuplicateFieldsCompleteWithoutExtraModelCallsOrSaving() throws Exception {
        FAIL.set("duplicate-identical");
        Path input = input();
        var before = files();
        var run = collaboration.collaborate(input);
        assertThat(REQUESTS).hasSize(3);
        assertThat(run.coordination().recommendations().get(0).referencesSnapshot()).isFalse();
        assertThat(run.coordination().recommendations().get(0).riskIds()).containsExactly("R-1");
        assertThat(run.trace()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
        assertThat(run.preview()).contains("核实 T-4 的未完成事项");
        assertThat(files()).isEqualTo(before);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void conflictingOrTruncatedCoordinatorOutputFailsWithoutLeakingResponse(CapturedOutput output) throws Exception {
        Path input = input();
        var before = files();
        for (String mode : List.of("duplicate-conflict", "truncated")) {
            REQUESTS.clear();
            FAIL.set(mode);
            assertThatThrownBy(() -> collaboration.collaborate(input))
                    .isInstanceOfSatisfying(CollaborationService.CollaborationFailedException.class, ex -> {
                        assertThat(ex.stage()).isEqualTo("coordination");
                        assertThat(ex.getCause()).isInstanceOf(CheckedOutputConverter.InvalidOutputException.class).hasNoCause();
                        assertThat(ex.trace()).extracting(CollaborationService.StageTrace::status)
                                .containsExactly("COMPLETED", "COMPLETED", "COMPLETED", "FAILED");
                    });
            assertThat(REQUESTS).hasSize(3);
            assertThat(files()).isEqualTo(before);
        }
        assertThat(output.getAll()).doesNotContain("TEST_PRIVATE_OUTPUT_NEVER_LOG", "Could not parse the given text");
    }

    @Test
    void sourceFileChangeDuringModelCallDoesNotChangeFrozenInputOrFacts() throws Exception {
        Path input = input();
        String original = Files.readString(input);
        AFTER_MINUTES.set(() -> {
            try { Files.writeString(input, "源文件已经变化"); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        });
        var run = collaboration.collaborate(input);
        assertThat(run.originalInput()).isEqualTo(original);
        assertThat(run.facts().totalTasks()).isEqualTo(6);
        assertThat(REQUESTS.get(2).toString()).doesNotContain("源文件已经变化");
        assertThat(Files.readString(input)).isEqualTo("源文件已经变化");
    }

    @Test
    void changedAndEmptySnapshotsUseTheirOwnFactsInsteadOfEarlierStatistics() throws Exception {
        Path input = input();
        var original = (ObjectNode) MAPPER.readTree(Files.readString(input));
        ((ObjectNode) original.path("progress").path("tasks").get(3)).put("status", "DONE");
        Files.writeString(input, original.toString());
        var updated = collaboration.collaborate(input);
        assertThat(updated.facts().completionPercent()).isEqualByComparingTo("66.67");
        assertThat(updated.facts().overdueTaskIds()).isEmpty();
        assertThat(updated.facts().blockedTaskIds()).containsExactly("T-6");
        assertThat(updated.facts().dependencyRisks()).isEmpty();
        REQUESTS.clear();
        FAIL.set("empty");
        ((ObjectNode) original.path("progress")).set("tasks", MAPPER.createArrayNode());
        Files.writeString(input, original.toString());
        var empty = collaboration.collaborate(input);
        assertThat(empty.facts().completionPercent()).isNull();
        assertThat(empty.preview()).contains("无法计算（没有任务）");
        String packet = REQUESTS.get(1).path("messages").get(1).path("content").asText();
        String json = packet.substring(packet.indexOf('{'));
        assertThat(MAPPER.readTree(json).path("progressFacts").path("tasks")).isEmpty();
        assertThat(empty.risks().risks()).isEmpty();
        assertThat(empty.coordination().recommendations()).isEmpty();
    }

    private Path input() throws IOException {
        return Files.copy(Path.of("examples/collaboration.json"), temporaryDirectory.resolve(UUID.randomUUID() + ".json"));
    }
    private static List<Path> files() throws IOException {
        try (var files = Files.walk(temporaryDirectory)) { return files.sorted().toList(); }
    }
    @AfterAll static void stop() { SERVER.stop(0); }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                REQUESTS.add(request);
                String system = request.path("messages").get(0).path("content").asText();
                String answer;
                if (system.contains("爱管理聊天解析助手")) {
                    answer = FAIL.get().equals("minutes") ? MINUTES.replace("\"m1\"", "\"m-missing\"") : MINUTES;
                    Runnable action = AFTER_MINUTES.getAndSet(null);
                    if (action != null) { action.run(); }
                } else if (system.contains("爱管理协作风险评估助手")) {
                    answer = FAIL.get().equals("empty") ? "{\"risks\":[],\"informationGaps\":[]}"
                            : FAIL.get().equals("risk") ? RISKS.replace("\"T-4\",\"T-6\"", "\"T-missing\"") : RISKS;
                } else if (system.contains("爱管理协作协调汇总助手")) {
                    answer = FAIL.get().equals("empty") ? "{\"recommendations\":[],\"openQuestions\":[]}"
                            : FAIL.get().equals("coordination") ? PLAN.replace("\"R-1\"", "\"R-missing\"") : PLAN;
                    if (FAIL.get().equals("duplicate-identical")) {
                        answer = PLAN.replace("\"referencesSnapshot\":false", "\"referencesSnapshot\":false,\"referencesSnapshot\":false");
                    } else if (FAIL.get().equals("duplicate-conflict")) {
                        answer = PLAN.replace("核实 T-4 的未完成事项", "TEST_PRIVATE_OUTPUT_NEVER_LOG")
                                .replace("\"referencesSnapshot\":false", "\"referencesSnapshot\":false,\"referencesSnapshot\":true");
                    } else if (FAIL.get().equals("truncated")) {
                        answer = "{\"recommendations\":[{\"title\":\"TEST_PRIVATE_OUTPUT_NEVER_LOG\"";
                    }
                } else { answer = "本机模拟回答。"; }
                byte[] response = MAPPER.writeValueAsBytes(Map.of("id", "local-collaboration", "object", "chat.completion",
                        "created", 1, "model", "local-test-model", "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", answer), "finish_reason", "stop"))));
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
