package com.aiguanli.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** 审核状态由程序和操作者维护，模型只能生成候选文本。全部审核记录只保存在本次运行内存。 */
@Service
public class DecisionReviewService {
    private final DiscussionService discussion;
    private final ObjectMapper mapper;
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private final Map<String, List<String>> batches = new LinkedHashMap<>();

    public DecisionReviewService(DiscussionService discussion, ObjectMapper mapper) {
        this.discussion = discussion;
        this.mapper = mapper;
    }

    public synchronized Extraction extract(Path file) throws IOException {
        Path actual = file.toRealPath();
        byte[] bytes = Files.readAllBytes(actual);
        DecisionInput input = mapper.readValue(bytes, DecisionInput.class);
        if (input == null || input.simulation() == null) {
            throw new IllegalArgumentException("决策提取输入需要 simulation 布尔标记和 messages 列表。");
        }
        DiscussionService.validateMessages(input.messages());
        String hash = sha256(bytes);
        String batchKey = actual + ":" + hash;
        List<String> previous = batches.get(batchKey);
        if (previous != null) {
            return new Extraction(previous.stream().map(candidates::get).toList(), true);
        }
        DiscussionAnalysis analysis = discussion.analyze(input.messages());
        List<Candidate> pending = new ArrayList<>();
        for (var item : analysis.decisions()) {
            List<DiscussionMessage> evidence = input.messages().stream()
                    .filter(message -> item.sourceMessageIds().contains(message.id())).toList();
            String id;
            do { id = "D-" + UUID.randomUUID().toString().substring(0, 8); }
            while (candidates.containsKey(id) || containsId(pending, id));
            pending.add(new Candidate(id, item.text(), null, Status.PENDING_REVIEW, input.simulation(),
                    actual.getFileName().toString(), hash, new String(bytes, StandardCharsets.UTF_8),
                    Instant.now().toString(), evidence, null, null));
        }
        pending.forEach(candidate -> candidates.put(candidate.id(), candidate));
        batches.put(batchKey, pending.stream().map(Candidate::id).toList());
        return new Extraction(List.copyOf(pending), false);
    }

    public synchronized List<Candidate> list() { return List.copyOf(candidates.values()); }

    public synchronized Candidate get(String id) {
        Candidate candidate = candidates.get(id);
        if (candidate == null) { throw new IllegalArgumentException("没有该决策编号，请用 /decisions 查看本次运行的候选决策。"); }
        return candidate;
    }

    public synchronized Candidate review(String id, Action action, String reviewer, String reason, String modifiedText) {
        Candidate current = get(id);
        if (current.status() != Status.PENDING_REVIEW) {
            throw new IllegalStateException("该条目已经审核，不能覆盖原审核记录。");
        }
        if (action == null || blank(reviewer) || blank(reason)) {
            throw new IllegalArgumentException("处理结论、审核人和审核理由不能为空。");
        }
        if (action == Action.MODIFY && (blank(modifiedText) || current.originalText().equals(modifiedText.strip()))) {
            throw new IllegalArgumentException("修改后采纳需要提供与原草稿不同的完整文本。");
        }
        if (action != Action.MODIFY && !blank(modifiedText)) {
            throw new IllegalArgumentException("只有修改后采纳可以提交修改文本。");
        }
        String text = action == Action.REJECT ? null : action == Action.ACCEPT ? current.originalText() : modifiedText.strip();
        if (action != Action.REJECT && !hasDecisionContent(text)) {
            throw new IllegalArgumentException("采纳文本必须包含实际文字，不能只填写菜单编号、数字或标点。");
        }
        Review record = new Review(action, reviewer.strip(), reason.strip(), Instant.now().toString());
        Candidate reviewed = copy(current, text, action == Action.REJECT ? Status.REJECTED : Status.APPROVED, record, null);
        candidates.put(id, reviewed);
        return reviewed;
    }

    public synchronized Publication publish(String id, KnowledgeService knowledge) throws IOException {
        Candidate current = get(id);
        if (current.status() != Status.APPROVED && current.status() != Status.PUBLISHED) {
            throw new IllegalStateException("只有人工采纳或修改后采纳的决策才能加入知识库。");
        }
        if (knowledge == null) { throw new IllegalArgumentException("请先启用 RAG，当前没有可用知识库。"); }
        // 只有本服务能构建此对象；模型文本或调用者拼接的 status 字段不能成为入库凭据。
        KnowledgeService.ImportResult imported = knowledge.importReviewedDecision(new ApprovedDecision(current));
        Candidate published = current.status() == Status.PUBLISHED ? current
                : copy(current, current.approvedText(), Status.PUBLISHED, current.review(), Instant.now().toString());
        candidates.put(id, published);
        return new Publication(published, imported);
    }

    private static Candidate copy(Candidate source, String approvedText, Status status, Review review, String publishedAt) {
        return new Candidate(source.id(), source.originalText(), approvedText, status, source.simulation(), source.sourceFile(),
                source.inputSha256(), source.originalInput(), source.createdAt(), source.sourceMessages(), review, publishedAt);
    }
    private static boolean containsId(List<Candidate> items, String id) {
        return items.stream().anyMatch(candidate -> candidate.id().equals(id));
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    // 只拦截数字、标点等明显的占位输入；内容语义仍需操作者核对。
    static boolean hasDecisionContent(String text) {
        return text != null && text.codePoints().anyMatch(Character::isLetter);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public enum Status { PENDING_REVIEW, APPROVED, REJECTED, PUBLISHED }
    public enum Action { ACCEPT, MODIFY, REJECT }
    public record DecisionInput(Boolean simulation, List<DiscussionMessage> messages) {}
    public record Review(Action action, String reviewer, String reason, String reviewedAt) {}
    public record Candidate(String id, String originalText, String approvedText, Status status, boolean simulation,
            String sourceFile, String inputSha256, String originalInput, String createdAt,
            List<DiscussionMessage> sourceMessages, Review review, String publishedAt) {
        public Candidate { sourceMessages = List.copyOf(sourceMessages); }
    }
    public record Extraction(List<Candidate> decisions, boolean reused) {}
    public record Publication(Candidate decision, KnowledgeService.ImportResult imported) {}
    public static final class ApprovedDecision {
        private final Candidate candidate;
        private ApprovedDecision(Candidate candidate) { this.candidate = candidate; }
        public Candidate candidate() { return candidate; }
    }
}
