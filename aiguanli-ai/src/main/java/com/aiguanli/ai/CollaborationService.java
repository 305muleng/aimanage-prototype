package com.aiguanli.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** 固定角色交接：纪要 → 风险 → 协调。仅返回内存草稿，不执行业务变更或保存。 */
@Service
public class CollaborationService {
    private final DiscussionService discussion;
    private final ObjectMapper mapper;
    private final ChatClient riskClient;
    private final ChatClient coordinator;
    private final String model;

    public CollaborationService(DiscussionService discussion, ObjectMapper mapper, ChatClient.Builder builder,
            @Value("${spring.ai.openai.chat.options.model}") String model) throws IOException {
        this.discussion = discussion;
        this.mapper = mapper;
        this.model = model;
        this.riskClient = builder.clone().defaultSystem(prompt("collaboration-risk.md")).build();
        this.coordinator = builder.clone().defaultSystem(prompt("collaboration-coordinator.md")).build();
    }

    public Run collaborate(Path file) throws IOException {
        return collaborate(file, null);
    }

    public Run collaborate(Path file, KnowledgeService knowledge) throws IOException {
        // 一次读取冻结输入。后续角色只接收该快照，不重读文件。
        byte[] bytes = Files.readAllBytes(file);
        CollaborationInput input = mapper.readValue(bytes, CollaborationInput.class);
        if (input == null || !Boolean.TRUE.equals(input.simulation())) {
            throw new IllegalArgumentException("协作演示输入必须明确标记 simulation=true。");
        }
        Set<String> messages = DiscussionService.validateMessages(input.messages());
        // 先校验全部任务及计算统计，避免输入无效时已经产生模型费用。
        String snapshotStarted = Instant.now().toString();
        var facts = new ProjectProgressTools(input.progress(), file.getFileName().toString()).report();
        Set<String> tasks = facts.tasks().stream().map(ProjectProgressTools.TaskFact::id).collect(Collectors.toUnmodifiableSet());
        List<StageTrace> trace = new ArrayList<>();
        String now = Instant.now().toString();
        trace.add(new StageTrace("snapshot", "Java 快照校验与统计（未调用模型工具）", List.of("input"), "COMPLETED", snapshotStarted, now));
        // 一次检索冻结结果；后续两角色使用相同的已审核正文和审核来源。
        DecisionKnowledge.Context decisions = knowledge == null ? DecisionKnowledge.disabled()
                : stage("decisions", "检索已审核决策（包含明确标记的模拟材料）", List.of("input"), trace,
                        () -> DecisionKnowledge.retrieve(knowledge, input.messages().stream()
                                .map(DiscussionMessage::content).collect(Collectors.joining("\n")), true));
        DiscussionAnalysis minutes = stage("minutes", "纪要整理", List.of("input"), trace,
                () -> discussion.analyze(input.messages()));
        RiskAssessment risks = stage("risk", "风险评估", knowledge == null ? List.of("minutes", "snapshot") : List.of("minutes", "snapshot", "decisions"), trace, () -> {
            String packet = mapper.writeValueAsString(Map.of("sourceMessages", input.messages(),
                    "minutesDraft", minutes, "progressFacts", facts, "reviewedProjectDecisions", decisions.sources()));
            RiskAssessment result = riskClient.prompt().user("以下是本次交接包 JSON：\n" + packet)
                    .call().entity(new CheckedOutputConverter<>(RiskAssessment.class, mapper));
            validateRisks(result, messages, tasks);
            DecisionKnowledge.validate(result.projectDecisions(), decisions);
            return result;
        });
        CoordinationPlan plan = stage("coordination", "协调汇总", knowledge == null ? List.of("minutes", "risk", "snapshot") : List.of("minutes", "risk", "snapshot", "decisions"), trace, () -> {
            String packet = mapper.writeValueAsString(Map.of("sourceMessages", input.messages(), "minutesDraft", minutes,
                    "riskDraft", risks, "progressFacts", facts, "reviewedProjectDecisions", decisions.sources()));
            CoordinationPlan result = coordinator.prompt().user("以下是本次交接包 JSON：\n" + packet)
                    .call().entity(new CheckedOutputConverter<>(CoordinationPlan.class, mapper));
            validatePlan(result, risks, messages, tasks);
            DecisionKnowledge.validate(result.projectDecisions(), decisions);
            return result;
        });
        String runId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        String hash = sha256(bytes);
        String preview = render(runId, createdAt, facts, minutes, risks, plan, trace, hash, decisions);
        return new Run(runId, createdAt, "PENDING_REVIEW", true, model, facts.source(), hash,
                new String(bytes, StandardCharsets.UTF_8), input, facts, minutes, risks, plan,
                List.copyOf(trace), new ReviewRecord("PENDING", null, null, null, null), preview, decisions);
    }

    private static void validateRisks(RiskAssessment result, Set<String> messages, Set<String> tasks) {
        if (result == null || result.risks() == null || result.informationGaps() == null) {
            throw new IllegalStateException("风险评估缺少必要列表。");
        }
        Set<String> ids = new HashSet<>();
        for (RiskItem item : result.risks()) {
            if (item == null || blank(item.id()) || !ids.add(item.id()) || blank(item.observation()) || blank(item.potentialImpact())) {
                throw new IllegalStateException("风险条目字段缺失或 ID 重复。");
            }
            evidence(item.sourceMessageIds(), item.sourceTaskIds(), item.referencesSnapshot(), messages, tasks);
        }
        validateNotes(result.informationGaps(), messages, tasks);
    }

    private static void validatePlan(CoordinationPlan plan, RiskAssessment risks, Set<String> messages, Set<String> tasks) {
        if (plan == null || plan.recommendations() == null || plan.openQuestions() == null) {
            throw new IllegalStateException("协调汇总缺少必要列表。");
        }
        Set<String> riskIds = risks.risks().stream().map(RiskItem::id).collect(Collectors.toSet());
        for (Recommendation item : plan.recommendations()) {
            if (item == null || blank(item.title()) || blank(item.rationale()) || item.riskIds() == null
                    || new HashSet<>(item.riskIds()).size() != item.riskIds().size()
                    || item.riskIds().stream().anyMatch(id -> id == null || !riskIds.contains(id))) {
                throw new IllegalStateException("协调建议字段无效或引用了不存在的风险。");
            }
            // 引用风险本身也可作为建议的交接依据；直接引用材料时仍须校验原始来源。
            if (item.riskIds().isEmpty()) {
                evidence(item.sourceMessageIds(), item.sourceTaskIds(), item.referencesSnapshot(), messages, tasks);
            } else {
                checkIds(item.sourceMessageIds(), messages);
                checkIds(item.sourceTaskIds(), tasks);
            }
        }
        validateNotes(plan.openQuestions(), messages, tasks);
    }

    private static void validateNotes(List<EvidenceNote> notes, Set<String> messages, Set<String> tasks) {
        for (var note : notes) {
            if (note == null || blank(note.text())) { throw new IllegalStateException("证据说明缺少内容。"); }
            evidence(note.sourceMessageIds(), note.sourceTaskIds(), note.referencesSnapshot(), messages, tasks);
        }
    }

    private static void evidence(List<String> messages, List<String> tasks, boolean snapshot,
            Set<String> allowedMessages, Set<String> allowedTasks) {
        checkIds(messages, allowedMessages);
        checkIds(tasks, allowedTasks);
        if (messages.isEmpty() && tasks.isEmpty() && !snapshot) {
            throw new IllegalStateException("缺少消息、任务或整体快照来源。");
        }
    }

    private static void checkIds(List<String> ids, Set<String> allowed) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || !allowed.contains(id))
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalStateException("引用了不存在、重复或缺失的来源编号。");
        }
    }

    private <T> T stage(String id, String role, List<String> inputs, List<StageTrace> trace, StageWork<T> work) {
        String started = Instant.now().toString();
        try {
            T result = work.run();
            trace.add(new StageTrace(id, role, inputs, "COMPLETED", started, Instant.now().toString()));
            return result;
        } catch (IOException | RuntimeException ex) {
            trace.add(new StageTrace(id, role, inputs, "FAILED", started, Instant.now().toString()));
            throw new CollaborationFailedException(id, List.copyOf(trace), ex);
        }
    }

    private String render(String id, String createdAt, ProjectProgressTools.ProgressReport facts, DiscussionAnalysis minutes,
            RiskAssessment risks, CoordinationPlan plan, List<StageTrace> trace, String hash, DecisionKnowledge.Context decisions) {
        StringBuilder output = new StringBuilder("# AI 角色协作草稿\n\n");
        output.append("模拟数据，待人工审核，不代表真实项目进度。未创建或修改任何平台任务。\n\n")
                .append("运行 ID：").append(id).append("\n生成时间（UTC）：").append(createdAt)
                .append("\n配置的模型：").append(line(model)).append("\n输入文件：").append(line(facts.source()))
                .append("\n输入 SHA-256：").append(hash).append("\n\n## 实际执行与交接\n\n");
        trace.forEach(step -> output.append("- ").append(step.id()).append("：").append(step.role())
                .append("；输入：").append(String.join("、", step.inputs())).append("；").append(step.status()).append('\n'));
        output.append("\n## Java 快照统计\n\n快照日期：").append(facts.snapshotDate())
                .append("；Sprint：").append(line(facts.sprintName())).append("\n\n- 完成：")
                .append(facts.completedTasks()).append('/').append(facts.totalTasks()).append("；按任务数量完成率：")
                .append(facts.completionPercent() == null ? "无法计算（没有任务）" : facts.completionPercent() + "%")
                .append("\n- 延期任务：").append(facts.overdueTaskIds()).append("\n- 阻塞任务：").append(facts.blockedTaskIds())
                .append("\n- 未完成依赖：").append(facts.dependencyRisks()).append("\n\n## 纪要草稿（模型整理，待核对）\n\n");
        notes(output, "讨论要点", minutes.summary());
        notes(output, "原文明确决定（仍待核对）", minutes.decisions());
        notes(output, "建议", minutes.suggestions());
        notes(output, "待确认问题", minutes.openQuestions());
        output.append("\n行动项（只整理原文明示安排）：\n");
        for (var item : minutes.actionItems()) {
            output.append("- ").append(line(item.task())).append("；负责人：").append(item.owner() == null ? "未提供" : line(item.owner()))
                    .append("；时间原文：").append(item.deadline() == null ? "未提供" : line(item.deadline()))
                    .append("；消息：").append(item.sourceMessageIds()).append('\n');
        }
        output.append("\n## 候选风险（模型推断，待人工核对）\n\n");
        if (risks.risks().isEmpty()) { output.append("未提出候选风险，不表示风险不存在。\n"); }
        for (var item : risks.risks()) {
            output.append("- ").append(line(item.id())).append("：").append(line(item.observation()))
                    .append("\n  可能影响：").append(line(item.potentialImpact())).append("\n  依据：")
                    .append(refs(item.sourceMessageIds(), item.sourceTaskIds(), item.referencesSnapshot())).append('\n');
        }
        output.append("\n信息缺口：\n");
        risks.informationGaps().forEach(note -> output.append("- ").append(line(note.text())).append("；")
                .append(refs(note.sourceMessageIds(), note.sourceTaskIds(), note.referencesSnapshot())).append('\n'));
        output.append("\n## 协调建议（未执行，待团队确认）\n\n");
        for (var item : plan.recommendations()) {
            output.append("- ").append(line(item.title())).append("：").append(line(item.rationale()))
                    .append("\n  关联风险：").append(item.riskIds()).append("；")
                    .append(refs(item.sourceMessageIds(), item.sourceTaskIds(), item.referencesSnapshot())).append('\n');
        }
        output.append("\n协调待确认问题：\n");
        plan.openQuestions().forEach(note -> output.append("- ").append(line(note.text())).append("；")
                .append(refs(note.sourceMessageIds(), note.sourceTaskIds(), note.referencesSnapshot())).append('\n'));
        DecisionKnowledge.renderContext(output, decisions);
        DecisionKnowledge.renderUsage(output, "风险评估", DecisionKnowledge.validate(risks.projectDecisions(), decisions));
        DecisionKnowledge.renderUsage(output, "协调汇总", DecisionKnowledge.validate(plan.projectDecisions(), decisions));
        output.append("\n## 人工审核记录\n\n- 状态：待审核\n- 审核人：待填写\n- 审核时间：待填写\n")
                .append("- 处理结论：待填写（采纳 / 修改后采纳 / 否决）\n- 修改内容与理由：待填写\n");
        return output.toString();
    }

    private static void notes(StringBuilder output, String title, List<DiscussionAnalysis.EvidenceItem> items) {
        output.append(title).append("：\n");
        items.forEach(item -> output.append("- ").append(line(item.text())).append("；消息：").append(item.sourceMessageIds()).append('\n'));
    }
    private static String refs(List<String> messages, List<String> tasks, boolean snapshot) {
        return "消息 " + messages + "；任务 " + tasks + (snapshot ? "；整体快照" : "");
    }
    private static String prompt(String name) throws IOException {
        return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String line(String text) { return text.replace('\r', ' ').replace('\n', ' '); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @FunctionalInterface private interface StageWork<T> { T run() throws IOException; }

    public record EvidenceNote(String text, List<String> sourceMessageIds, List<String> sourceTaskIds, boolean referencesSnapshot) {}
    public record RiskItem(String id, String observation, String potentialImpact,
            List<String> sourceMessageIds, List<String> sourceTaskIds, boolean referencesSnapshot) {}
    public record RiskAssessment(List<RiskItem> risks, List<EvidenceNote> informationGaps, DecisionKnowledge.Usage projectDecisions) {}
    public record Recommendation(String title, String rationale, List<String> riskIds,
            List<String> sourceMessageIds, List<String> sourceTaskIds, boolean referencesSnapshot) {}
    public record CoordinationPlan(List<Recommendation> recommendations, List<EvidenceNote> openQuestions, DecisionKnowledge.Usage projectDecisions) {}
    public record StageTrace(String id, String role, List<String> inputs, String status, String startedAt, String completedAt) {}
    public record ReviewRecord(String status, String reviewer, String reviewedAt, String decision, String reason) {}
    public record Run(String id, String createdAt, String status, boolean simulation, String configuredModel, String source,
            String inputSha256, String originalInput, CollaborationInput input, ProjectProgressTools.ProgressReport facts,
            DiscussionAnalysis minutes, RiskAssessment risks, CoordinationPlan coordination, List<StageTrace> trace,
            ReviewRecord review, String preview, DecisionKnowledge.Context decisions) {}

    public static class CollaborationFailedException extends IllegalStateException {
        private final String stage;
        private final List<StageTrace> trace;
        CollaborationFailedException(String stage, List<StageTrace> trace, Throwable cause) {
            super("协作阶段 " + stage + " 失败，后续阶段未执行，未生成完整协作草稿。", cause);
            this.stage = stage;
            this.trace = trace;
        }
        public String stage() { return stage; }
        public List<StageTrace> trace() { return trace; }
    }
}
