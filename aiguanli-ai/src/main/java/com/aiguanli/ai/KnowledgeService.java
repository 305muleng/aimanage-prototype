package com.aiguanli.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class KnowledgeService {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");
    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper mapper;
    private final ChatClient client;
    private final Path indexPath;
    private final StorageSettings storage;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(400).withMinChunkSizeChars(100).withMinChunkLengthToEmbed(1)
            .withMaxNumChunks(10000).build();
    private SnapshotVectorStore store;
    private Map<String, SourceFile> sources = new LinkedHashMap<>();
    private JsonNode vectors;

    public KnowledgeService(RagProperties properties, EmbeddingModel embeddingModel,
            ObjectMapper mapper, ChatClient.Builder builder, StorageSettings storage) throws IOException {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.mapper = mapper;
        this.storage = storage;
        this.indexPath = Path.of(properties.indexFile()).toAbsolutePath().normalize();
        String prompt = new ClassPathResource("prompts/knowledge.md").getContentAsString(StandardCharsets.UTF_8);
        this.client = builder.clone().defaultSystem(prompt).build();
        this.vectors = mapper.createObjectNode();
        this.store = newStore(vectors);
        if (storage.localPersistenceEnabled() && Files.exists(indexPath)) {
            IndexSnapshot snapshot = mapper.readValue(Files.readString(indexPath, StandardCharsets.UTF_8), IndexSnapshot.class);
            if (snapshot.schemaVersion() != 1 || !properties.embeddingSignature().equals(snapshot.embeddingSignature())) {
                throw new IllegalStateException("知识索引与当前向量模型配置不一致。请恢复原配置，或通过 RAG_INDEX_FILE 指定新的索引文件并重新导入。");
            }
            if (snapshot.sources() == null || snapshot.vectors() == null || !snapshot.vectors().isObject()) {
                throw new IllegalStateException("知识索引结构不完整，请检查索引文件。");
            }
            this.sources = new LinkedHashMap<>(snapshot.sources());
            this.vectors = snapshot.vectors();
            this.store = newStore(vectors);
        }
    }

    /** 导入完成后替换内存索引；仅在明确启用本地持久化时读写索引文件。 */
    public synchronized ImportResult importFile(Path path) throws IOException {
        Path actual = path.toRealPath();
        String name = actual.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!List.of("md", "txt", "pdf", "docx").contains(extension)) {
            throw new IllegalArgumentException("目前支持 UTF-8 Markdown/TXT、PDF 和 DOCX 文件。");
        }
        byte[] bytes = Files.readAllBytes(actual);
        String digest = sha256(bytes);
        String key = actual.toString();
        SourceFile old = sources.get(key);
        if (old != null && old.sha256().equals(digest)) {
            return new ImportResult(name, old.chunkIds().size(), true);
        }
        String text;
        if (extension.equals("md") || extension.equals("txt")) {
            text = Files.readString(actual, StandardCharsets.UTF_8);
        } else {
            text = new TikaDocumentReader(new FileSystemResource(actual)).get().stream()
                    .map(Document::getText).filter(t -> t != null).reduce("", (a, b) -> a + "\n" + b);
        }
        return importText(key, name, text, digest, Map.of("scope", "PROJECT"));
    }

    /** 公共资料按编号和来源追踪；正文及向量只遵循当前持久化设置。 */
    public synchronized ImportResult importPublic(PublicKnowledgeLoader.LoadedSource loaded) throws IOException {
        var source = loaded.source();
        Map<String, Object> metadata = Map.of("scope", "PUBLIC", "url", source.url().toString(),
                "resolvedUrl", loaded.resolvedUrl(), "publisher", source.publisher(), "version", source.version(),
                "fetchedAt", loaded.fetchedAt(), "topics", source.topics(), "licenseNotice", source.licenseNotice());
        String digest = sha256((loaded.text() + "\n" + source.url() + "\n" + source.version()
                + "\n" + source.publisher() + "\n" + source.topics() + "\n" + source.licenseNotice())
                .getBytes(StandardCharsets.UTF_8));
        return importText("public:" + source.id(), source.title(), loaded.text(), digest, metadata);
    }

    /** 只接受审核服务产生的采纳凭据。原始群聊及 AI 原稿只作为审计元数据，不混入检索正文。 */
    public synchronized ImportResult importReviewedDecision(DecisionReviewService.ApprovedDecision approved) throws IOException {
        if (approved == null) { throw new IllegalArgumentException("缺少人工审核凭据。"); }
        var candidate = approved.candidate();
        var review = candidate.review();
        if (review == null || review.action() == DecisionReviewService.Action.REJECT
                || !DecisionReviewService.hasDecisionContent(candidate.approvedText())) {
            throw new IllegalArgumentException("决策尚未被采纳，不能加入知识库。");
        }
        var provenance = new DecisionProvenance(candidate.id(), candidate.simulation(), review.reviewer(),
                review.action().name(), review.reviewedAt(), review.reason(), candidate.sourceFile(), candidate.inputSha256(),
                candidate.sourceMessages().stream().map(DiscussionMessage::id).toList());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", "PROJECT");
        metadata.put("sourceType", "REVIEWED_DECISION");
        metadata.put("decisionSimulation", candidate.simulation());
        metadata.put("decisionProvenance", mapper.convertValue(provenance, Map.class));
        metadata.put("decisionEvidence", mapper.convertValue(candidate, Map.class));
        String text = "群聊已审核决策" + (candidate.simulation() ? "（模拟演示，不代表真实项目决定）" : "（用户提供材料，本机审核）")
                + "\n确认内容：" + candidate.approvedText()
                + "\n审核不表示已在平台执行业务操作。";
        String digest = sha256((text + "\n" + mapper.writeValueAsString(provenance)).getBytes(StandardCharsets.UTF_8));
        return importText("reviewed-decision:" + candidate.id(), "群聊决策 " + candidate.id(), text, digest, metadata);
    }

    private ImportResult importText(String key, String name, String text, String digest,
            Map<String, Object> metadata) throws IOException {
        SourceFile old = sources.get(key);
        if (old != null && old.sha256().equals(digest)) {
            return new ImportResult(name, old.chunkIds().size(), true);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("文档未提取到文字。扫描版 PDF 需要先做 OCR。");
        }
        var pieces = splitter.apply(List.of(new Document(text)));
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("文档没有可索引的文本片段。");
        }
        if ("PUBLIC".equals(metadata.get("scope")) && pieces.size() > 120) {
            throw new IllegalArgumentException("公共资料超过 120 个片段，未调用向量模型；请缩小资料范围。");
        }
        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            String id = UUID.nameUUIDFromBytes((key + "\n" + digest + "\n" + i)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            Map<String, Object> chunkMetadata = new LinkedHashMap<>(metadata);
            chunkMetadata.put("source", name);
            chunkMetadata.put("sourcePath", key);
            chunkMetadata.put("chunk", i + 1);
            chunks.add(new Document(id, pieces.get(i).getText(), chunkMetadata));
        }
        SnapshotVectorStore candidate = newStore(vectors);
        if (old != null) {
            candidate.delete(old.chunkIds());
        }
        // SimpleVectorStore 逐片段调用 EmbeddingModel，避免超出供应商批大小限制。
        candidate.add(chunks);
        Map<String, SourceFile> nextSources = new LinkedHashMap<>(sources);
        nextSources.put(key, new SourceFile(name, digest, chunks.stream().map(Document::getId).toList(),
                (String) metadata.get("scope"), (String) metadata.get("url"), (String) metadata.get("publisher"),
                (String) metadata.get("version"), (String) metadata.get("fetchedAt"), (String) metadata.get("licenseNotice"),
                decisionProvenance(metadata)));
        JsonNode nextVectors = candidate.snapshot(mapper);
        if (storage.localPersistenceEnabled()) {
            persist(new IndexSnapshot(1, properties.embeddingSignature(), nextSources, nextVectors));
        }
        this.store = candidate;
        this.sources = nextSources;
        this.vectors = nextVectors;
        return new ImportResult(name, chunks.size(), false);
    }

    /** 独立文档问答，不混用普通聊天记忆。 */
    public synchronized KnowledgeAnswer ask(String question) throws IOException {
        return ask(question, "PROJECT");
    }

    public synchronized KnowledgeAnswer askPublic(String question) throws IOException {
        return ask(question, "PUBLIC");
    }

    /** 只检索公开资料，不生成回答，供需求拆解等服务复用。 */
    public synchronized List<SourceSnippet> retrievePublic(String question) {
        return retrieve(question, "PUBLIC");
    }

    /** 过滤先于 topK；普通项目文件、未审核及否决候选不参与决策检索。 */
    public synchronized List<SourceSnippet> retrieveReviewedDecisions(String question, boolean allowSimulation) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空。");
        }
        if (sources.values().stream().noneMatch(s -> "PROJECT".equals(s.scope()) && s.decision() != null
                && (allowSimulation || !s.decision().simulation()))) {
            return List.of();
        }
        String filter = "scope == 'PROJECT' && sourceType == 'REVIEWED_DECISION'";
        if (!allowSimulation) { filter += " && decisionSimulation == false"; }
        return snippets(store.similaritySearch(SearchRequest.builder().query(question)
                .topK(properties.topK()).similarityThreshold(properties.threshold()).filterExpression(filter).build()));
    }

    private KnowledgeAnswer ask(String question, String scope) throws IOException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("文档问题不能为空。");
        }
        if (sources.values().stream().noneMatch(s -> scope.equals(s.scope()))) {
            return new KnowledgeAnswer(scope.equals("PUBLIC")
                    ? "公共知识库为空，请先用 /sources 查看来源，再用 /import-public 编号 导入。"
                    : "项目知识库为空，请先用 /import 导入项目文档。", List.of());
        }
        List<SourceSnippet> snippets = retrieve(question, scope);
        if (snippets.isEmpty()) {
            return new KnowledgeAnswer("当前知识库没有检索到足够相关的资料，无法确定。", List.of());
        }
        // JSON 序列化，保留资料边界；来源编号由程序生成。
        String answer = client.prompt().user("知识范围：" + scope + "\n问题：" + question
                + "\n检索片段 JSON：\n" + mapper.writeValueAsString(snippets))
                .call().content();
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("模型返回了空回答。");
        }
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int number;
            try {
                number = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("回答包含无效来源编号。");
            }
            if (number < 1 || number > snippets.size()) {
                throw new IllegalStateException("回答包含不存在的来源编号。");
            }
        }
        return new KnowledgeAnswer(answer, List.copyOf(snippets));
    }

    private List<SourceSnippet> retrieve(String question, String scope) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空。");
        }
        if (sources.values().stream().noneMatch(s -> scope.equals(s.scope()))) {
            return List.of();
        }
        List<Document> matches = store.similaritySearch(SearchRequest.builder().query(question)
                .topK(properties.topK()).similarityThreshold(properties.threshold())
                .filterExpression("scope == '" + scope + "'").build());
        return snippets(matches);
    }

    private List<SourceSnippet> snippets(List<Document> matches) {
        List<SourceSnippet> snippets = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Document doc = matches.get(i);
            snippets.add(new SourceSnippet(i + 1, (String) doc.getMetadata().get("source"),
                    ((Number) doc.getMetadata().get("chunk")).intValue(), doc.getText(), doc.getScore(),
                    (String) doc.getMetadata().get("scope"), (String) doc.getMetadata().get("url"),
                    (String) doc.getMetadata().get("publisher"), (String) doc.getMetadata().get("version"),
                    (String) doc.getMetadata().get("fetchedAt"), (String) doc.getMetadata().get("licenseNotice"),
                    decisionProvenance(doc.getMetadata())));
        }
        return List.copyOf(snippets);
    }

    public synchronized List<SourceFile> listSources() {
        return List.copyOf(sources.values());
    }

    private DecisionProvenance decisionProvenance(Map<String, Object> metadata) {
        Object value = metadata.get("decisionProvenance");
        return value == null ? null : mapper.convertValue(value, DecisionProvenance.class);
    }

    private SnapshotVectorStore newStore(JsonNode content) throws IOException {
        // 兼容旧索引：原有文件片段都属于项目资料，只在内存补充范围标记。
        JsonNode normalized = content.deepCopy();
        normalized.elements().forEachRemaining(value -> {
            if (value.path("metadata") instanceof ObjectNode metadata) {
                if (!metadata.has("scope")) { metadata.put("scope", "PROJECT"); }
                if (metadata.has("decisionProvenance")) {
                    metadata.put("decisionSimulation", metadata.path("decisionProvenance").path("simulation").asBoolean());
                }
            }
        });
        var result = new SnapshotVectorStore(embeddingModel);
        result.load(new ByteArrayResource(mapper.writeValueAsBytes(normalized)));
        return result;
    }

    // 直接在内存中复制向量快照，关闭持久化时也不借助临时磁盘文件。
    private static class SnapshotVectorStore extends SimpleVectorStore {
        SnapshotVectorStore(EmbeddingModel model) { super(SimpleVectorStore.builder(model)); }
        JsonNode snapshot(ObjectMapper mapper) { return mapper.valueToTree(store); }
    }

    private void persist(IndexSnapshot snapshot) throws IOException {
        Files.createDirectories(indexPath.getParent());
        Path temporary = Files.createTempFile(indexPath.getParent(), "knowledge-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            try {
                Files.move(temporary, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record SourceFile(String name, String sha256, List<String> chunkIds, String scope, String url,
            String publisher, String version, String fetchedAt, String licenseNotice, DecisionProvenance decision) {
        public String scope() { return scope == null ? "PROJECT" : scope; }
    }
    public record ImportResult(String name, int chunks, boolean unchanged) {}
    public record SourceSnippet(int number, String source, int chunk, String text, Double score,
            String scope, String url, String publisher, String version, String fetchedAt, String licenseNotice,
            DecisionProvenance decision) {}
    public record DecisionProvenance(String decisionId, boolean simulation, String reviewer, String reviewAction,
            String reviewedAt, String reason, String sourceFile, String inputSha256, List<String> sourceMessageIds) {}
    public record KnowledgeAnswer(String answer, List<SourceSnippet> sources) {}
    public record IndexSnapshot(int schemaVersion, String embeddingSignature, Map<String, SourceFile> sources, JsonNode vectors) {}
}
