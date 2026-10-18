package com.aiguanli.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模拟向量只为验证调用链和索引行为，不用于证明模型的语义检索质量。 */
@SpringBootTest(properties = {
        "app.console.enabled=false", "logging.level.root=ERROR", "app.rag.enabled=true",
        "app.storage.local-persistence-enabled=true",
        "app.rag.api-key=local-embedding-key", "app.rag.model=local-vector-model",
        "app.rag.embeddings-path=/v1/embeddings", "spring.ai.openai.api-key=local-chat-key",
        "AI_CHAT_PATH=/chat/completions", "AI_MODEL=local-chat-model"
})
class KnowledgeServiceIntegrationTest {

    private static final String REQUIREMENT_DRAFT = """
            {"userStories":[{"id":"US-1","title":"创建任务","role":"项目负责人",
              "goal":"创建任务","benefit":"记录工作安排","acceptanceCriteria":["Given 需求允许创建任务，When 项目负责人提交明确的任务内容，Then 任务可被查看。"]}],
             "developmentTasks":[{"id":"TASK-1","title":"实现任务创建","description":"实现明确需求中的任务创建能力。","storyIds":["US-1"]}],
             "openQuestions":["哪些字段必填？"],"optionalSuggestions":[]}
            """;
    private static final String DECISION_ANALYSIS = """
            {"summary":[{"text":"讨论开发顺序。","sourceMessageIds":["m1","m2"]}],
             "decisions":[{"text":"先完成任务创建，再做任务分配。","sourceMessageIds":["m1","m2"]}],
             "suggestions":[{"text":"建议增加任务通知。","sourceMessageIds":["m5"]}],
             "actionItems":[],"openQuestions":[{"text":"是否支持多人分配？","sourceMessageIds":["m4"]}]}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicReference<JsonNode> EMBEDDING_REQUEST = new AtomicReference<>();
    private static final AtomicReference<JsonNode> CHAT_REQUEST = new AtomicReference<>();
    private static final AtomicReference<String> EMBEDDING_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> CHAT_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> ANSWER = new AtomicReference<>();
    private static final AtomicReference<Function<JsonNode, String>> CHAT_RESPONDER = new AtomicReference<>();
    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();
    private static final AtomicInteger CHAT_CALLS = new AtomicInteger();
    private static final AtomicBoolean FAIL_EMBEDDING = new AtomicBoolean();
    private static final HttpServer SERVER = createServer();

    @TempDir
    static Path temporaryDirectory;

    @Autowired private RagProperties configured;
    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private ChatClient.Builder builder;

    private RagProperties properties;
    private KnowledgeService knowledge;

    @DynamicPropertySource
    static void localProviders(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("spring.ai.openai.base-url", () -> base);
        registry.add("app.rag.base-url", () -> base);
        registry.add("app.rag.index-file", () -> temporaryDirectory.resolve("context-index.json").toString());
    }

    @BeforeEach
    void createFreshIndex() throws IOException {
        EMBEDDING_CALLS.set(0);
        CHAT_CALLS.set(0);
        FAIL_EMBEDDING.set(false);
        CHAT_RESPONDER.set(null);
        ANSWER.set("根据文档，需求拆解计划在 Sprint 2。[1]");
        properties = new RagProperties(true, configured.apiKey(), configured.baseUrl(),
                configured.embeddingsPath(), configured.model(),
                temporaryDirectory.resolve(UUID.randomUUID() + ".json").toString(), 0.5, 4);
        knowledge = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(true));
    }

    @Test
    void importsRetrievesAndPassesOnlyRelevantChunksToChatModel() throws Exception {
        Path plan = write("计划.md", "需求拆解计划在 Sprint 2，由人工审核。");
        knowledge.importFile(plan);
        knowledge.importFile(write("无关.txt", "食堂午餐菜单，面条和米饭。"));
        var result = knowledge.ask("需求拆解在哪个 Sprint？");

        assertThat(result.answer()).contains("Sprint 2");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).source()).isEqualTo(plan.getFileName().toString());
        assertThat(CHAT_REQUEST.get().toString()).contains("人工审核").doesNotContain("午餐菜单");
        assertThat(EMBEDDING_REQUEST.get().path("model").asText()).isEqualTo("local-vector-model");
        assertThat(EMBEDDING_REQUEST.get().path("encoding_format").asText()).isEqualTo("float");
        assertThat(EMBEDDING_REQUEST.get().has("thinking")).isFalse();
        assertThat(EMBEDDING_AUTH.get()).isEqualTo("Bearer local-embedding-key");
        assertThat(CHAT_AUTH.get()).isEqualTo("Bearer local-chat-key");
    }

    @Test
    void persistsAndReloadsWithoutReembeddingUnchangedFiles() throws Exception {
        Path file = write("重新加载.md", "需求拆解计划在 Sprint 2。");
        var first = knowledge.importFile(file);
        int calls = EMBEDDING_CALLS.get();
        var reloaded = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(true));
        assertThat(reloaded.listSources()).hasSize(1);
        var repeated = reloaded.importFile(file);
        assertThat(repeated.unchanged()).isTrue();
        assertThat(repeated.chunks()).isEqualTo(first.chunks());
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
        assertThat(Files.readString(Path.of(properties.indexFile()))).doesNotContain("local-embedding-key", "local-chat-key");
        assertThat(reloaded.ask("需求拆解的 Sprint？").sources()).hasSize(1);
    }

    @Test
    void replacesChangedFileInsteadOfAccumulatingOldChunks() throws Exception {
        Path file = write("更新.md", "需求拆解旧版安排 Sprint 2。");
        knowledge.importFile(file);
        Files.writeString(file, "需求拆解新版安排 Sprint 3。", StandardCharsets.UTF_8);
        knowledge.importFile(file);
        var result = knowledge.ask("需求拆解安排？");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).text()).contains("Sprint 3").doesNotContain("旧版");
        assertThat(knowledge.listSources()).hasSize(1);
    }

    @Test
    void leavesOldIndexIntactWhenEmbeddingFails() throws Exception {
        Path file = write("回滚.md", "需求拆解旧版 Sprint 2。");
        knowledge.importFile(file);
        String persisted = Files.readString(Path.of(properties.indexFile()));
        Files.writeString(file, "需求拆解新版 Sprint 3。", StandardCharsets.UTF_8);
        FAIL_EMBEDDING.set(true);
        assertThatThrownBy(() -> knowledge.importFile(file)).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(Path.of(properties.indexFile()))).isEqualTo(persisted);
        FAIL_EMBEDDING.set(false);
        assertThat(knowledge.ask("需求拆解安排？").sources().get(0).text()).contains("旧版");
    }

    @Test
    void abstainsWithoutChatCallForEmptyOrIrrelevantKnowledge() throws Exception {
        assertThat(knowledge.ask("需求拆解安排？").sources()).isEmpty();
        assertThat(EMBEDDING_CALLS.get()).isZero();
        knowledge.importFile(write("不相关.md", "午餐菜单。"));
        var result = knowledge.ask("需求拆解安排？");
        assertThat(result.answer()).contains("无法确定");
        assertThat(result.sources()).isEmpty();
        assertThat(CHAT_CALLS.get()).isZero();
    }

    @Test
    void rejectsIndexFromAnotherEmbeddingModel() throws Exception {
        knowledge.importFile(write("模型.md", "需求拆解安排。"));
        var other = new RagProperties(true, properties.apiKey(), properties.baseUrl(), properties.embeddingsPath(),
                "different-model", properties.indexFile(), 0.5, 4);
        assertThatThrownBy(() -> new KnowledgeService(other, embeddingModel, MAPPER, builder, new StorageSettings(true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("配置不一致");
    }

    @Test
    void rejectsInventedCitationNumber() throws Exception {
        knowledge.importFile(write("引用.md", "需求拆解安排。"));
        ANSWER.set("不正确的引用。[99]");
        assertThatThrownBy(() -> knowledge.ask("需求拆解安排？"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("来源编号");
    }

    @Test
    void extractsWordAndPdfText() throws Exception {
        Path word = temporaryDirectory.resolve(UUID.randomUUID() + ".docx");
        try (var document = new XWPFDocument(); var stream = Files.newOutputStream(word)) {
            document.createParagraph().createRun().setText("需求拆解计划在 Sprint 2。");
            document.write(stream);
        }
        assertThat(knowledge.importFile(word).chunks()).isGreaterThan(0);

        Path pdf = temporaryDirectory.resolve(UUID.randomUUID() + ".pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Sprint 2 plan for requirements.");
                content.endText();
            }
            document.save(pdf.toFile());
        }
        assertThat(knowledge.importFile(pdf).chunks()).isGreaterThan(0);
        assertThat(knowledge.listSources()).hasSize(2);
    }

    private Path write(String name, String text) throws IOException {
        return Files.writeString(temporaryDirectory.resolve(UUID.randomUUID() + "-" + name), text, StandardCharsets.UTF_8);
    }

    @Test
    void disabledPersistenceImportsUpdatesRetrievesAndRollsBackWithoutDiskWrites() throws Exception {
        Path index = Path.of(properties.indexFile());
        Files.writeString(index, "已有索引不读取、不修改");
        Path source = write("内存.md", "需求拆解旧版安排 Sprint 2。");
        Path other = write("其他.md", "需求分析背景。");
        List<Path> before;
        try (var files = Files.list(temporaryDirectory)) { before = files.sorted().toList(); }
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        assertThat(memory.listSources()).isEmpty();
        memory.importFile(source);
        memory.importFile(other);
        assertThat(memory.ask("需求拆解安排？").sources()).hasSize(2);
        int calls = EMBEDDING_CALLS.get();
        assertThat(memory.importFile(source).unchanged()).isTrue();
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
        Files.writeString(source, "需求拆解新版安排 Sprint 3。");
        FAIL_EMBEDDING.set(true);
        assertThatThrownBy(() -> memory.importFile(source)).isInstanceOf(RuntimeException.class);
        FAIL_EMBEDDING.set(false);
        assertThat(memory.ask("需求拆解安排？").sources()).anySatisfy(snippet -> assertThat(snippet.text()).contains("旧版"));
        memory.importFile(source);
        assertThat(memory.ask("需求拆解安排？").sources()).noneSatisfy(snippet -> assertThat(snippet.text()).contains("旧版"));
        assertThat(Files.readString(index)).isEqualTo("已有索引不读取、不修改");
        try (var files = Files.list(temporaryDirectory)) { assertThat(files.sorted().toList()).isEqualTo(before); }
        var restarted = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        assertThat(restarted.listSources()).isEmpty();
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void publicAndProjectKnowledgeStayIsolatedWithLinksAndNoMemoryModeWrites() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        memory.importFile(write("项目.md", "需求：项目内部决定 Sprint 2 上线。"));
        List<Path> before;
        try (var files = Files.list(temporaryDirectory)) { before = files.sorted().toList(); }
        var source = PublicKnowledgeCatalog.find("scrum");
        var loaded = new PublicKnowledgeLoader.LoadedSource(source,
                "需求：Sprint 的通用定义，公共指南原文。", source.url().toString(), "2026-10-11T00:00:00Z");
        memory.importPublic(loaded);
        var publicAnswer = memory.askPublic("Sprint 需求？");
        assertThat(publicAnswer.sources()).hasSize(1);
        assertThat(publicAnswer.sources().get(0).scope()).isEqualTo("PUBLIC");
        assertThat(publicAnswer.sources().get(0).url()).isEqualTo(source.url().toString());
        assertThat(publicAnswer.sources().get(0).publisher()).isEqualTo(source.publisher());
        assertThat(publicAnswer.sources().get(0).version()).isEqualTo("2020-11");
        assertThat(publicAnswer.sources().get(0).licenseNotice()).contains("CC BY-SA");
        assertThat(CHAT_REQUEST.get().toString()).contains("公共指南", source.url().toString())
                .doesNotContain("项目内部决定");
        var projectAnswer = memory.ask("Sprint 需求？");
        assertThat(projectAnswer.sources()).hasSize(1);
        assertThat(projectAnswer.sources().get(0).scope()).isEqualTo("PROJECT");
        assertThat(CHAT_REQUEST.get().toString()).contains("项目内部决定").doesNotContain("公共指南原文");
        assertThat(memory.listSources()).extracting(KnowledgeService.SourceFile::scope).containsExactly("PROJECT", "PUBLIC");
        try (var files = Files.list(temporaryDirectory)) { assertThat(files.sorted().toList()).isEqualTo(before); }
    }

    @Test
    void publicImportDeduplicatesAndRollsBackFailedRefresh() throws Exception {
        var source = PublicKnowledgeCatalog.find("scrum");
        var loaded = new PublicKnowledgeLoader.LoadedSource(source,
                "需求：Sprint 旧版通用指南。", source.url().toString(), "2026-10-11T00:00:00Z");
        knowledge.importPublic(loaded);
        int calls = EMBEDDING_CALLS.get();
        assertThat(knowledge.importPublic(new PublicKnowledgeLoader.LoadedSource(source, loaded.text(),
                loaded.resolvedUrl(), "2026-10-11T01:00:00Z")).unchanged()).isTrue();
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
        var updated = new PublicKnowledgeLoader.LoadedSource(source,
                "需求：Sprint 新版通用指南。", source.url().toString(), "2026-10-12T00:00:00Z");
        FAIL_EMBEDDING.set(true);
        assertThatThrownBy(() -> knowledge.importPublic(updated)).isInstanceOf(RuntimeException.class);
        FAIL_EMBEDDING.set(false);
        assertThat(knowledge.askPublic("Sprint 需求？").sources().get(0).text()).contains("旧版");
        knowledge.importPublic(updated);
        var reloaded = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(true));
        assertThat(reloaded.askPublic("Sprint 需求？").sources().get(0).text()).contains("新版");
        assertThat(reloaded.listSources().get(0).fetchedAt()).isEqualTo("2026-10-12T00:00:00Z");
    }

    @Test
    void emptyScopeDoesNotCallModelsAndPublicKnowledgeCannotSupplyProjectFacts() throws Exception {
        assertThat(knowledge.askPublic("什么是 Sprint？").answer()).contains("公共知识库为空");
        var source = PublicKnowledgeCatalog.find("scrum");
        knowledge.importPublic(new PublicKnowledgeLoader.LoadedSource(source,
                "需求：Sprint 的通用定义。", source.url().toString(), "2026-10-11T00:00:00Z"));
        int calls = EMBEDDING_CALLS.get();
        assertThat(knowledge.ask("今天完成几个任务？").answer()).contains("项目知识库为空");
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
        assertThat(CHAT_CALLS.get()).isZero();
    }

    @Test
    void restoresLegacyProjectMetadataWithoutTreatingItAsPublicKnowledge() throws Exception {
        knowledge.importFile(write("旧索引.md", "需求：旧项目计划 Sprint 2。"));
        Path index = Path.of(properties.indexFile());
        var snapshot = MAPPER.readTree(Files.readString(index));
        snapshot.path("sources").elements().forEachRemaining(source -> ((com.fasterxml.jackson.databind.node.ObjectNode) source)
                .remove(List.of("scope", "url", "publisher", "version", "fetchedAt", "licenseNotice")));
        snapshot.path("vectors").elements().forEachRemaining(vector ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) vector.path("metadata")).remove("scope"));
        Files.writeString(index, snapshot.toString());
        var reloaded = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(true));
        assertThat(reloaded.ask("需求？").sources()).hasSize(1);
        assertThat(reloaded.askPublic("需求？").sources()).isEmpty();
    }

    @Test
    void decompositionRetrievesPublicMethodsWithOneChatCallAndKeepsProvenanceWithoutSaving() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        memory.importFile(write("机密.md", "需求：项目内部规则不可进入公共知识拆解。"));
        var source = PublicKnowledgeCatalog.find("acceptance");
        memory.importPublic(new PublicKnowledgeLoader.LoadedSource(source,
                "需求验收方法：Given 是前置条件，When 是动作，Then 检查可观察结果。", source.url().toString(), "2026-10-12T00:00:00Z"));
        ANSWER.set(groundedDraft(1));
        int embeddingsBefore = EMBEDDING_CALLS.get();
        List<Path> filesBefore;
        try (var files = Files.list(temporaryDirectory)) { filesBefore = files.sorted().toList(); }
        Path directory = temporaryDirectory.resolve("no-drafts-" + UUID.randomUUID());
        var service = new DecompositionService(builder, MAPPER, directory.toString(), "local-chat-model", new StorageSettings(false));
        var draft = service.decomposeWithKnowledge("项目负责人创建任务。", memory);
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(embeddingsBefore + 1);
        assertThat(CHAT_CALLS.get()).isEqualTo(1);
        assertThat(CHAT_REQUEST.get().toString()).contains("publicKnowledgeSnippets", "可观察结果", "项目负责人创建任务。")
                .doesNotContain("项目内部规则");
        assertThat(draft.result().userStories()).hasSize(1);
        assertThat(draft.grounding().status()).isEqualTo("RETRIEVED");
        assertThat(draft.grounding().sources()).hasSize(1);
        assertThat(draft.grounding().sources().get(0).url()).isEqualTo(source.url().toString());
        assertThat(draft.grounding().sources().get(0).text()).contains("可观察结果");
        assertThat(draft.grounding().methodReferences().get(0).sourceNumbers()).containsExactly(1);
        assertThat(draft.preview()).contains("公共知识参考", "[K1]", source.url().toString(), "待人工审核", "哪些字段必填？")
                .doesNotContain("```json");
        assertThat(draft.file()).isNull();
        assertThat(directory).doesNotExist();
        try (var files = Files.list(temporaryDirectory)) { assertThat(files.sorted().toList()).isEqualTo(filesBefore); }
    }

    @Test
    void decompositionClearlyReportsEmptyOrIrrelevantKnowledgeInsteadOfInventingReferences() throws Exception {
        Path directory = temporaryDirectory.resolve("no-drafts-" + UUID.randomUUID());
        var service = new DecompositionService(builder, MAPPER, directory.toString(), "local-chat-model", new StorageSettings(false));
        ANSWER.set(REQUIREMENT_DRAFT);
        var empty = service.decomposeWithKnowledge("项目负责人创建任务。", knowledge);
        assertThat(empty.grounding().status()).isEqualTo("EMPTY");
        assertThat(empty.preview()).contains("未导入公共资料");
        assertThat(EMBEDDING_CALLS.get()).isZero();
        assertThat(CHAT_CALLS.get()).isEqualTo(1);
        var source = PublicKnowledgeCatalog.find("agile");
        knowledge.importPublic(new PublicKnowledgeLoader.LoadedSource(source,
                "午餐菜单，面条米饭。", source.url().toString(), "2026-10-12T00:00:00Z"));
        var irrelevant = service.decomposeWithKnowledge("项目负责人创建任务。", knowledge);
        assertThat(irrelevant.grounding().status()).isEqualTo("NO_MATCH");
        assertThat(irrelevant.grounding().sources()).isEmpty();
        assertThat(irrelevant.preview()).contains("未检索到足够相关").doesNotContain("[K1]");
        assertThat(CHAT_CALLS.get()).isEqualTo(2);
        assertThat(directory).doesNotExist();
        assertThat(CHAT_REQUEST.get().path("messages").get(1).path("content").asText()).doesNotContain("publicKnowledgeSnippets");
    }

    @Test
    void rejectsInvalidMethodCitationsAndMissingKnowledgeNotesBeforeSaving() throws Exception {
        var source = PublicKnowledgeCatalog.find("acceptance");
        knowledge.importPublic(new PublicKnowledgeLoader.LoadedSource(source,
                "需求验收方法：Given When Then。", source.url().toString(), "2026-10-12T00:00:00Z"));
        Path directory = temporaryDirectory.resolve("invalid-drafts-" + UUID.randomUUID());
        var service = new DecompositionService(builder, MAPPER, directory.toString(), "local-chat-model", new StorageSettings(true));
        for (int citation : new int[] {0, 2}) {
            ANSWER.set(groundedDraft(citation));
            assertThatThrownBy(() -> service.decomposeWithKnowledge("项目负责人创建任务。", knowledge))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("来源编号");
        }
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(groundedDraft(1));
        response.remove("knowledgeNote");
        ANSWER.set(response.toString());
        assertThatThrownBy(() -> service.decomposeWithKnowledge("项目负责人创建任务。", knowledge))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("知识参考说明");
        assertThat(directory).doesNotExist();
    }

    @Test
    void retrievalFailureStopsDecompositionWithoutCallingChatOrSaving() throws Exception {
        var source = PublicKnowledgeCatalog.find("scrum");
        knowledge.importPublic(new PublicKnowledgeLoader.LoadedSource(source,
                "需求：Sprint 中的用户故事。", source.url().toString(), "2026-10-12T00:00:00Z"));
        Path directory = temporaryDirectory.resolve("failed-drafts-" + UUID.randomUUID());
        var service = new DecompositionService(builder, MAPPER, directory.toString(), "local-chat-model", new StorageSettings(true));
        FAIL_EMBEDDING.set(true);
        assertThatThrownBy(() -> service.decomposeWithKnowledge("项目负责人创建任务。", knowledge)).isInstanceOf(RuntimeException.class);
        assertThat(CHAT_CALLS.get()).isZero();
        assertThat(directory).doesNotExist();
    }

    @Test
    void compatibilitySaveRetainsRawPublicEvidenceAndCanRecordNonUse() throws Exception {
        var source = PublicKnowledgeCatalog.find("acceptance");
        knowledge.importPublic(new PublicKnowledgeLoader.LoadedSource(source,
                "需求参考：Given When Then 的原始公开说明。", source.url().toString(), "2026-10-12T00:00:00Z"));
        Path directory = temporaryDirectory.resolve("saved-drafts-" + UUID.randomUUID());
        var service = new DecompositionService(builder, MAPPER, directory.toString(), "local-chat-model", new StorageSettings(true));
        ANSWER.set(groundedDraft(1));
        var saved = service.decomposeWithKnowledge("项目负责人创建任务。", knowledge);
        assertThat(Files.readString(saved.file())).contains("公共知识检索与方法引用记录", source.url().toString(),
                "Given When Then 的原始公开说明", "\"status\" : \"RETRIEVED\"", "\"sourceNumbers\"");
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(groundedDraft(1));
        response.set("methodReferences", MAPPER.createArrayNode());
        response.put("knowledgeNote", "检索片段不适用，未采用公共资料。");
        ANSWER.set(response.toString());
        var unused = service.decomposeWithKnowledge("项目负责人创建任务。", knowledge);
        assertThat(unused.grounding().methodReferences()).isEmpty();
        assertThat(unused.preview()).contains("模型未声明采用资料", "未采用公共资料");
    }

    private static String groundedDraft(int citation) throws IOException {
        return MAPPER.writeValueAsString(Map.of("decomposition", MAPPER.readTree(REQUIREMENT_DRAFT),
                "methodReferences", List.of(Map.of("explanation", "按 Given/When/Then 组织可观察的验收标准。",
                        "sourceNumbers", List.of(citation))), "knowledgeNote", "公共资料只辅助验收标准写法，业务范围来自输入需求。"));
    }

    @Test
    void reviewedChatDecisionRequiresHumanAcceptanceAndKeepsEvidenceWithoutDefaultDiskWrites() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        var reviews = reviewService();
        Path input = decisionInput();
        List<Path> before;
        try (var files = Files.list(temporaryDirectory)) { before = files.sorted().toList(); }
        var extracted = reviews.extract(input);
        assertThat(extracted.decisions()).hasSize(1);
        var candidate = extracted.decisions().get(0);
        assertThat(candidate.status()).isEqualTo(DecisionReviewService.Status.PENDING_REVIEW);
        assertThat(candidate.sourceMessages()).extracting(DiscussionMessage::id).containsExactly("m1", "m2");
        assertThat(candidate.originalInput()).isEqualTo(Files.readString(input));
        assertThat(memory.listSources()).isEmpty();
        assertThat(EMBEDDING_CALLS.get()).isZero();
        assertThatThrownBy(() -> reviews.publish(candidate.id(), memory)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> memory.importReviewedDecision(null)).isInstanceOf(IllegalArgumentException.class);
        reviews.review(candidate.id(), DecisionReviewService.Action.ACCEPT, "测试审核人", "核对原始消息后采纳", null);
        assertThat(reviews.get(candidate.id()).status()).isEqualTo(DecisionReviewService.Status.APPROVED);
        assertThat(memory.listSources()).isEmpty();
        reviews.publish(candidate.id(), memory);
        assertThat(reviews.get(candidate.id()).status()).isEqualTo(DecisionReviewService.Status.PUBLISHED);
        ANSWER.set("模拟群聊的已审核顺序是先任务创建，再任务分配。[1]");
        var answer = memory.ask("任务创建与分配的顺序是什么？");
        assertThat(answer.sources()).hasSize(1);
        var source = answer.sources().get(0);
        assertThat(source.scope()).isEqualTo("PROJECT");
        assertThat(source.decision().decisionId()).isEqualTo(candidate.id());
        assertThat(source.decision().reviewer()).isEqualTo("测试审核人");
        assertThat(source.decision().simulation()).isTrue();
        assertThat(source.text()).contains("模拟演示", candidate.originalText()).doesNotContain("任务通知", "多人分配");
        assertThat(memory.askPublic("任务创建顺序？").sources()).isEmpty();
        int calls = EMBEDDING_CALLS.get();
        assertThat(reviews.publish(candidate.id(), memory).imported().unchanged()).isTrue();
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
        try (var files = Files.list(temporaryDirectory)) { assertThat(files.sorted().toList()).isEqualTo(before); }
    }

    @Test
    void modifiedDecisionIndexesOnlyHumanAcceptedTextAndRetainsOriginalForReview() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        var reviews = reviewService();
        var candidate = reviews.extract(decisionInput()).decisions().get(0);
        String changed = "本次模拟改为先完成任务分配，再完成任务创建。";
        reviews.review(candidate.id(), DecisionReviewService.Action.MODIFY, "测试审核人", "人工修订开发顺序", changed);
        reviews.publish(candidate.id(), memory);
        ANSWER.set("修改后的模拟顺序见已审核决策。[1]");
        var answer = memory.ask("任务开发顺序？");
        assertThat(answer.sources().get(0).text()).contains(changed).doesNotContain(candidate.originalText());
        assertThat(answer.sources().get(0).decision().reviewAction()).isEqualTo("MODIFY");
        assertThat(CHAT_REQUEST.get().toString()).doesNotContain(candidate.originalText(), "任务通知", "多人分配");
        assertThat(reviews.get(candidate.id()).originalText()).isEqualTo(candidate.originalText());
        assertThat(reviews.get(candidate.id()).approvedText()).isEqualTo(changed);
    }

    @Test
    void rejectedDecisionsCannotBePublishedOrHaveTheirReviewOverwritten() throws Exception {
        var reviews = reviewService();
        var candidate = reviews.extract(decisionInput()).decisions().get(0);
        reviews.review(candidate.id(), DecisionReviewService.Action.REJECT, "测试审核人", "证据不足，不采纳", null);
        assertThatThrownBy(() -> reviews.publish(candidate.id(), knowledge)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reviews.review(candidate.id(), DecisionReviewService.Action.ACCEPT, "其他人", "覆盖审核", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reviews.get(candidate.id()).status()).isEqualTo(DecisionReviewService.Status.REJECTED);
        assertThat(knowledge.listSources()).isEmpty();
        assertThat(EMBEDDING_CALLS.get()).isZero();
    }

    @Test
    void decisionPublishFailureKeepsApprovalForRetryAndDoesNotChangeOldKnowledge() throws Exception {
        var reviews = reviewService();
        knowledge.importFile(write("原资料.md", "任务基础说明。"));
        var candidate = reviews.extract(decisionInput()).decisions().get(0);
        var approved = reviews.review(candidate.id(), DecisionReviewService.Action.ACCEPT, "测试审核人", "核对采纳", null);
        FAIL_EMBEDDING.set(true);
        assertThatThrownBy(() -> reviews.publish(candidate.id(), knowledge)).isInstanceOf(RuntimeException.class);
        assertThat(reviews.get(candidate.id()).status()).isEqualTo(DecisionReviewService.Status.APPROVED);
        assertThat(reviews.get(candidate.id()).review()).isEqualTo(approved.review());
        assertThat(knowledge.listSources()).hasSize(1);
        FAIL_EMBEDDING.set(false);
        reviews.publish(candidate.id(), knowledge);
        assertThat(knowledge.listSources()).hasSize(2);
        assertThat(reviews.get(candidate.id()).status()).isEqualTo(DecisionReviewService.Status.PUBLISHED);
    }

    @Test
    void extractionDeduplicatesInputAndInvalidHumanFieldsDoNotApproveAnything() throws Exception {
        var reviews = reviewService();
        Path input = decisionInput();
        var candidate = reviews.extract(input).decisions().get(0);
        assertThat(reviews.extract(input).reused()).isTrue();
        assertThat(CHAT_CALLS.get()).isEqualTo(1);
        assertThatThrownBy(() -> reviews.review(candidate.id(), DecisionReviewService.Action.ACCEPT, "", "理由", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.review(candidate.id(), DecisionReviewService.Action.ACCEPT, "审核人", "", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.review(candidate.id(), DecisionReviewService.Action.MODIFY, "审核人", "理由", candidate.originalText()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String placeholder : List.of("1", "２", "1.2", "...")) {
            assertThatThrownBy(() -> reviews.review(candidate.id(), DecisionReviewService.Action.MODIFY, "审核人", "理由", placeholder))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能只填写");
        }
        assertThat(reviews.get(candidate.id()).status()).isEqualTo(DecisionReviewService.Status.PENDING_REVIEW);
        reviews.review(candidate.id(), DecisionReviewService.Action.REJECT, "审核人", "否决", null);
        var repeated = reviews.extract(input);
        assertThat(repeated.decisions().get(0).status()).isEqualTo(DecisionReviewService.Status.REJECTED);
        assertThat(CHAT_CALLS.get()).isEqualTo(1);
        assertThat(EMBEDDING_CALLS.get()).isZero();
    }

    @Test
    void inventedDecisionMessageSourcesCannotCreateReviewCandidates() throws Exception {
        var reviews = reviewService();
        ANSWER.set(DECISION_ANALYSIS.replace("\"m1\",\"m2\"", "\"missing-message\""));
        assertThatThrownBy(() -> reviews.extract(decisionInput())).isInstanceOf(IllegalStateException.class);
        assertThat(reviews.list()).isEmpty();
        assertThat(EMBEDDING_CALLS.get()).isZero();
    }

    @Test
    void compatibilityIndexRetainsApprovedDecisionEvidenceAndProvenanceOnReload() throws Exception {
        var reviews = reviewService();
        var candidate = reviews.extract(decisionInput()).decisions().get(0);
        reviews.review(candidate.id(), DecisionReviewService.Action.ACCEPT, "测试审核人", "核对采纳", null);
        reviews.publish(candidate.id(), knowledge);
        JsonNode index = MAPPER.readTree(Files.readString(Path.of(properties.indexFile())));
        var vector = index.path("vectors").elements().next();
        assertThat(vector.path("metadata").path("decisionEvidence").path("originalText").asText()).isEqualTo(candidate.originalText());
        assertThat(vector.path("metadata").path("decisionEvidence").path("originalInput").asText()).isEqualTo(candidate.originalInput());
        var restored = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(true));
        ANSWER.set("模拟开发顺序见审核记录。[1]");
        assertThat(restored.ask("任务开发顺序？").sources().get(0).decision().reviewer()).isEqualTo("测试审核人");
        assertThat(restored.listSources().get(0).decision().sourceMessageIds()).containsExactly("m1", "m2");
    }

    private DecisionReviewService reviewService() throws IOException {
        ANSWER.set(DECISION_ANALYSIS);
        return new DecisionReviewService(new DiscussionService(builder, MAPPER), MAPPER);
    }

    @Test
    void decisionRetrievalFiltersBeforeTopKAndKeepsSimulationSeparateEvenAfterReload() throws Exception {
        var single = new RagProperties(true, configured.apiKey(), configured.baseUrl(), configured.embeddingsPath(),
                configured.model(), properties.indexFile(), 0.5, 1);
        var index = new KnowledgeService(single, embeddingModel, MAPPER, builder, new StorageSettings(true));
        index.importFile(write("普通文档.md", "任务创建是普通文档，不是审核记录。"));
        var simulated = publishedDecision(index, true, "先完成任务创建，再做任务分配。");
        var real = publishedDecision(index, false, "先核对任务创建，再讨论任务分配。");
        var reviews = reviewService();
        var rejected = reviews.extract(decisionInput()).decisions().get(0);
        reviews.review(rejected.id(), DecisionReviewService.Action.REJECT, "测试人", "否决", null);
        var pending = reviews.extract(decisionInput()).decisions().get(0);
        int chats = CHAT_CALLS.get();
        assertThat(index.retrieveReviewedDecisions("任务开发顺序", false)).singleElement()
                .satisfies(s -> assertThat(s.decision().decisionId()).isEqualTo(real.id()));
        assertThat(index.retrieveReviewedDecisions("任务开发顺序", true)).hasSize(1)
                .allSatisfy(s -> assertThat(s.decision().decisionId()).isIn(real.id(), simulated.id()));
        assertThat(CHAT_CALLS.get()).isEqualTo(chats);
        // 兼容缺少新增平铺模拟标记的旧索引。
        var old = MAPPER.readTree(Files.readString(Path.of(single.indexFile())));
        old.path("vectors").elements().forEachRemaining(v ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) v.path("metadata")).remove("decisionSimulation"));
        Files.writeString(Path.of(single.indexFile()), old.toString());
        var restored = new KnowledgeService(single, embeddingModel, MAPPER, builder, new StorageSettings(true));
        assertThat(restored.retrieveReviewedDecisions("任务开发顺序", false)).singleElement()
                .satisfies(s -> assertThat(s.decision().decisionId()).isEqualTo(real.id()));
        assertThat(reviews.get(pending.id()).status()).isEqualTo(DecisionReviewService.Status.PENDING_REVIEW);
    }

    @Test
    void ordinaryDecompositionExcludesSimulationAndUsesOnlyFinalReviewedDecisionWithValidatedCitations() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        var service = new DecompositionService(builder, MAPPER, temporaryDirectory.toString(), "test", new StorageSettings(false));
        publishedDecision(memory, true, "模拟需求：先分配后创建。");
        ANSWER.set(REQUIREMENT_DRAFT);
        int calls = EMBEDDING_CALLS.get();
        var noSim = service.decomposeWithKnowledge("任务创建与分配。", memory);
        assertThat(noSim.grounding().decisions().sources()).isEmpty();
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
        assertThat(CHAT_REQUEST.get().path("messages").get(1).path("content").asText()).doesNotContain("模拟需求");
        var accepted = publishedDecision(memory, false, "需求开发顺序：先完成创建，再做分配；遇到相反要求须核对。");
        memory.importFile(write("未审核项目文件.md", "需求：禁止任务创建，不是已审核决策。"));
        var response = MAPPER.createObjectNode();
        response.set("decomposition", MAPPER.readTree(REQUIREMENT_DRAFT));
        response.putArray("methodReferences");
        response.put("knowledgeNote", "未提供公共资料。");
        response.set("projectDecisions", MAPPER.valueToTree(usage(accepted.id())));
        ANSWER.set(response.toString());
        var draft = service.decomposeWithKnowledge("需求：创建任务并分配。", memory);
        assertThat(draft.file()).isNull();
        assertThat(draft.grounding().decisions().sources()).singleElement()
                .satisfies(s -> assertThat(s.decision().decisionId()).isEqualTo(accepted.id()));
        assertThat(draft.preview()).contains(accepted.id(), "测试审核人", "先完成创建，再做分配");
        String packet = CHAT_REQUEST.get().path("messages").get(1).path("content").asText();
        assertThat(packet).contains("reviewedProjectDecisions").doesNotContain("模拟需求", "禁止任务创建", accepted.originalText(), "decisionEvidence");
        response.set("projectDecisions", MAPPER.valueToTree(usage("D-invented")));
        ANSWER.set(response.toString());
        assertThatThrownBy(() -> service.decomposeWithKnowledge("需求：创建任务并分配。", memory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("决策引用");
    }

    @Test
    void collaborationHandsSameReviewedSimulationToBothRolesAndRejectsInventedDecisionReferences() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        var accepted = publishedDecision(memory, true, "先处理 T-4，再开展 T-6 的联调。");
        var service = new CollaborationService(new DiscussionService(builder, MAPPER), MAPPER, builder, "test");
        var riskPacket = new AtomicReference<JsonNode>();
        var coordinatorPacket = new AtomicReference<JsonNode>();
        var invalid = new AtomicBoolean(false);
        CHAT_RESPONDER.set(request -> {
            String system = request.path("messages").get(0).path("content").asText();
            String user = request.path("messages").get(1).path("content").asText();
            try {
                if (system.contains("协作风险评估")) {
                    riskPacket.set(MAPPER.readTree(user.substring(user.indexOf('{'))));
                    return MAPPER.writeValueAsString(Map.of("risks", List.of(), "informationGaps", List.of(),
                            "projectDecisions", usage(invalid.get() ? "D-invented" : accepted.id())));
                }
                if (system.contains("协作协调汇总")) {
                    coordinatorPacket.set(MAPPER.readTree(user.substring(user.indexOf('{'))));
                    return MAPPER.writeValueAsString(Map.of("recommendations", List.of(), "openQuestions", List.of(),
                            "projectDecisions", usage(accepted.id())));
                }
                return """
                        {"summary":[{"text":"T-4 未完成，T-6 依赖 T-4。","sourceMessageIds":["m1"]}],
                         "decisions":[{"text":"先处理 T-4。","sourceMessageIds":["m2"]}],
                         "suggestions":[],"actionItems":[],"openQuestions":[]}
                        """;
            } catch (IOException ex) { throw new IllegalStateException(ex); }
        });
        int calls = CHAT_CALLS.get();
        var run = service.collaborate(Path.of("examples/collaboration.json"), memory);
        assertThat(CHAT_CALLS.get()).isEqualTo(calls + 3);
        assertThat(run.decisions().sources()).hasSize(1);
        assertThat(run.preview()).contains(accepted.id(), "模拟演示，不代表真实项目规则", "风险评估的决策使用说明", "协调汇总的决策使用说明");
        assertThat(run.trace()).extracting(CollaborationService.StageTrace::id)
                .containsExactly("snapshot", "decisions", "minutes", "risk", "coordination");
        assertThat(riskPacket.get().path("reviewedProjectDecisions"))
                .isEqualTo(coordinatorPacket.get().path("reviewedProjectDecisions"));
        assertThat(riskPacket.get().toString()).doesNotContain("decisionEvidence", accepted.originalText());
        assertThat(coordinatorPacket.get().path("riskDraft").path("projectDecisions").toString()).contains(accepted.id());
        invalid.set(true);
        coordinatorPacket.set(null);
        assertThatThrownBy(() -> service.collaborate(Path.of("examples/collaboration.json"), memory))
                .isInstanceOfSatisfying(CollaborationService.CollaborationFailedException.class,
                        ex -> assertThat(ex.stage()).isEqualTo("risk"));
        assertThat(coordinatorPacket.get()).isNull();
    }

    @Test
    void failedDecisionRetrievalStopsCollaborationBeforeAnyRoleIsCalled() throws Exception {
        var memory = new KnowledgeService(properties, embeddingModel, MAPPER, builder, new StorageSettings(false));
        publishedDecision(memory, true, "先处理 T-4。");
        var service = new CollaborationService(new DiscussionService(builder, MAPPER), MAPPER, builder, "test");
        int calls = CHAT_CALLS.get();
        FAIL_EMBEDDING.set(true);
        assertThatThrownBy(() -> service.collaborate(Path.of("examples/collaboration.json"), memory))
                .isInstanceOfSatisfying(CollaborationService.CollaborationFailedException.class,
                        ex -> assertThat(ex.stage()).isEqualTo("decisions"));
        assertThat(CHAT_CALLS.get()).isEqualTo(calls);
        assertThat(memory.listSources()).hasSize(1);
    }

    private DecisionReviewService.Candidate publishedDecision(KnowledgeService index, boolean simulation, String text) throws Exception {
        var reviews = reviewService();
        Path file = decisionInput();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(file));
        input.put("simulation", simulation);
        Files.writeString(file, input.toString());
        var candidate = reviews.extract(file).decisions().get(0);
        var action = text.equals(candidate.originalText()) ? DecisionReviewService.Action.ACCEPT : DecisionReviewService.Action.MODIFY;
        reviews.review(candidate.id(), action, "测试审核人", "测试用例人工核对材料", action == DecisionReviewService.Action.MODIFY ? text : null);
        reviews.publish(candidate.id(), index);
        return candidate;
    }

    private static DecisionKnowledge.Usage usage(String id) {
        return new DecisionKnowledge.Usage(List.of(new DecisionKnowledge.Reference("参考最终审核正文核对开发顺序；冲突另行确认。", List.of(id))),
                "仅按所提供材料分析，模拟内容不代表真实项目规则。");
    }
    private Path decisionInput() throws IOException {
        return Files.copy(Path.of("examples/decision-discussion.json"), temporaryDirectory.resolve("discussion-" + UUID.randomUUID() + ".json"));
    }

    private static HttpServer createServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/embeddings", exchange -> {
                JsonNode body = MAPPER.readTree(exchange.getRequestBody());
                EMBEDDING_REQUEST.set(body);
                EMBEDDING_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                EMBEDDING_CALLS.incrementAndGet();
                if (FAIL_EMBEDDING.get()) {
                    byte[] error = "{\"error\":{\"message\":\"local simulated failure\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, error.length);
                    try (var output = exchange.getResponseBody()) { output.write(error); }
                    return;
                }
                JsonNode input = body.path("input");
                String text = input.isArray() ? input.get(0).asText() : input.asText();
                List<Double> vector = text.contains("需求") || text.contains("Sprint")
                        ? List.of(1.0, 0.0) : List.of(0.0, 1.0);
                byte[] response = MAPPER.writeValueAsBytes(Map.of("object", "list", "model", "local-vector-model",
                        "data", List.of(Map.of("object", "embedding", "index", 0, "embedding", vector)),
                        "usage", Map.of("prompt_tokens", 1, "total_tokens", 1)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.createContext("/chat/completions", exchange -> {
                CHAT_REQUEST.set(MAPPER.readTree(exchange.getRequestBody()));
                CHAT_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                CHAT_CALLS.incrementAndGet();
                byte[] response = MAPPER.writeValueAsBytes(Map.of("id", "rag-test", "object", "chat.completion", "created", 1,
                        "model", "local-chat-model", "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", CHAT_RESPONDER.get() == null ? ANSWER.get()
                                        : CHAT_RESPONDER.get().apply(CHAT_REQUEST.get())), "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
