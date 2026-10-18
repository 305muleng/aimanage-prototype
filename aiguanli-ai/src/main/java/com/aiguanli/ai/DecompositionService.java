package com.aiguanli.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class DecompositionService {

    private final ChatClient client;
    private final ObjectMapper mapper;
    private final Path draftDirectory;
    private final String model;
    private final StorageSettings storage;

    public DecompositionService(ChatClient.Builder builder, ObjectMapper mapper,
            @Value("${app.decomposition.draft-directory:.local/drafts}") String draftDirectory,
            @Value("${spring.ai.openai.chat.options.model}") String model, StorageSettings storage) throws IOException {
        this.mapper = mapper;
        this.draftDirectory = Path.of(draftDirectory).toAbsolutePath().normalize();
        this.model = model;
        this.storage = storage;
        String prompt = new ClassPathResource("prompts/decomposition.md").getContentAsString(StandardCharsets.UTF_8);
        this.client = builder.clone().defaultSystem(prompt).build();
    }

    public SavedDraft decompose(String requirement) throws IOException {
        return generate(requirement, new KnowledgeGrounding("NOT_REQUESTED", "", List.of(), List.of(), "本次未检索公共知识。"));
    }

    /** 使用公共方法和非模拟的已审核决策；不自动下载资料，不读取普通项目文件或聊天。 */
    public SavedDraft decomposeWithKnowledge(String requirement, KnowledgeService knowledge) throws IOException {
        if (blank(requirement)) {
            throw new IllegalArgumentException("需求不能为空。");
        }
        String query = "用户故事 user stories 与可观察的验收标准 acceptance criteria，Given When Then。具体需求：" + requirement;
        var sources = knowledge.retrievePublic(query);
        var decisions = DecisionKnowledge.retrieve(knowledge, requirement, false);
        boolean publicEmpty = knowledge.listSources().stream().noneMatch(source -> source.scope().equals("PUBLIC"));
        return generate(requirement, new KnowledgeGrounding(sources.isEmpty() ? (publicEmpty ? "EMPTY" : "NO_MATCH") : "RETRIEVED", query,
                sources, List.of(), sources.isEmpty() ? (publicEmpty ? "RAG 已启用，但未导入公共资料。" : "未检索到足够相关的公共资料。") : "", decisions, null));
    }

    private SavedDraft generate(String requirement, KnowledgeGrounding grounding) throws IOException {
        if (blank(requirement)) {
            throw new IllegalArgumentException("需求不能为空。");
        }
        RequirementDecomposition result;
        if (grounding.sources().isEmpty() && grounding.decisions().sources().isEmpty()) {
            result = client.prompt().user("请将以下需求整理为待人工审核的草稿。需求文本：\n" + requirement)
                    .call().entity(RequirementDecomposition.class);
        } else {
            String input = mapper.writeValueAsString(Map.of("requirement", requirement,
                    "publicKnowledgeSnippets", grounding.sources(), "reviewedProjectDecisions", grounding.decisions().sources()));
            GroundedOutput output = client.prompt()
                    .user("请依据明确需求拆解，公共资料辅助方法；已审核决策仅作相关项目背景，冲突列为待确认。输入 JSON：\n" + input)
                    .call().entity(GroundedOutput.class);
            validateReferences(output, grounding.sources().size());
            result = output.decomposition();
            grounding = new KnowledgeGrounding(grounding.status(), grounding.query(), grounding.sources(),
                    List.copyOf(output.methodReferences()), output.knowledgeNote(), grounding.decisions(),
                    DecisionKnowledge.validate(output.projectDecisions(), grounding.decisions()));
        }
        validate(result);
        // 格式校验无法证明需求语义正确，草稿始终保留人工审核状态。
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        String preview = render(requirement, result, grounding, createdAt, id);
        if (!storage.localPersistenceEnabled()) {
            return new SavedDraft(result, null, preview, grounding);
        }
        String content = preview + "\n## 模型返回的结构化草稿\n\n```json\n"
                + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n```\n"
                + "\n## 公共知识检索与方法引用记录\n\n```json\n"
                + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(grounding) + "\n```\n";
        Files.createDirectories(draftDirectory);
        Path file = draftDirectory.resolve("decomposition-" + id + ".md");
        Path temporary = Files.createTempFile(draftDirectory, "draft-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new SavedDraft(result, file, preview, grounding);
    }

    private String render(String requirement, RequirementDecomposition result, KnowledgeGrounding grounding,
            String createdAt, String id) {
        StringBuilder output = new StringBuilder("# 需求拆解草稿\n\n");
        output.append("状态：待人工审核。未创建平台任务或修改项目数据。\n\n")
                .append("草稿 ID：").append(id).append("\n\n")
                .append("生成时间（UTC）：").append(createdAt).append("\n\n")
                .append("配置的模型：").append(line(model)).append("\n\n")
                .append("## 原始需求\n\n");
        requirement.lines().forEach(text -> output.append("> ").append(text).append('\n'));
        output.append("\n## 用户故事与验收标准\n\n");
        for (var story : result.userStories()) {
            output.append("### ").append(line(story.id())).append("：").append(line(story.title())).append("\n\n")
                    .append("作为").append(line(story.role())).append("，我希望").append(line(story.goal()))
                    .append("，以便").append(line(story.benefit())).append("。\n\n验收标准：\n\n");
            story.acceptanceCriteria().forEach(text -> output.append("- ").append(line(text)).append('\n'));
            output.append('\n');
        }
        output.append("## 开发任务\n\n");
        for (var task : result.developmentTasks()) {
            output.append("- ").append(line(task.id())).append(" ").append(line(task.title()))
                    .append("（关联故事：").append(String.join("、", task.storyIds())).append("）：")
                    .append(line(task.description())).append('\n');
        }
        appendList(output, "待确认问题", result.openQuestions());
        appendList(output, "可选建议（未纳入需求范围）", result.optionalSuggestions());
        output.append("\n## 公共知识参考\n\n");
        if (grounding.sources().isEmpty()) {
            output.append(line(grounding.note())).append('\n');
        } else {
            output.append("已检索并向模型提供 ").append(grounding.sources().size())
                    .append(" 个公共资料片段，仅作为方法参考，不表示项目需求已确认。\n\n")
                    .append("模型说明（待核对）：").append(line(grounding.note())).append("\n\n方法引用（待核对）：\n\n");
            if (grounding.methodReferences().isEmpty()) {
                output.append("- 模型未声明采用资料，请核对上述说明。\n");
            }
            for (var reference : grounding.methodReferences()) {
                output.append("- ").append(line(reference.explanation())).append(' ');
                reference.sourceNumbers().forEach(number -> output.append("[K").append(number).append("]"));
                output.append('\n');
            }
            output.append("\n检索来源（不表示每个片段均被采用）：\n\n");
            for (var source : grounding.sources()) {
                output.append("- [K").append(source.number()).append("] ").append(line(source.source()))
                        .append("，片段 ").append(source.chunk()).append("；版本：").append(line(source.version()))
                        .append("\n  原文：").append(source.url()).append("\n  发布方：").append(line(source.publisher()))
                        .append("；抓取时间（UTC）：").append(source.fetchedAt())
                        .append("\n  署名/许可：").append(line(source.licenseNotice())).append('\n');
            }
        }
        DecisionKnowledge.renderContext(output, grounding.decisions());
        DecisionKnowledge.renderUsage(output, "需求拆解", grounding.decisionUsage());
        output.append("\n## 人工审核记录\n\n")
                .append("- 审核人：待填写\n- 处理结论：待填写（采纳 / 修改后采纳 / 否决）\n")
                .append("- 修改内容与理由：待填写\n");
        return output.toString();
    }

    private static void appendList(StringBuilder output, String heading, List<String> items) {
        output.append("\n## ").append(heading).append("\n\n");
        if (items.isEmpty()) {
            output.append("无。\n");
        } else {
            items.forEach(text -> output.append("- ").append(line(text)).append('\n'));
        }
    }

    private static void validate(RequirementDecomposition result) {
        if (result == null || result.userStories() == null || result.userStories().isEmpty()
                || result.developmentTasks() == null || result.developmentTasks().isEmpty()) {
            throw new IllegalStateException("需求拆解缺少用户故事或开发任务。");
        }
        Set<String> storyIds = new HashSet<>();
        for (var story : result.userStories()) {
            if (story == null || blank(story.id()) || blank(story.title()) || blank(story.role())
                    || blank(story.goal()) || blank(story.benefit()) || !storyIds.add(story.id())) {
                throw new IllegalStateException("用户故事字段缺失或标识重复。");
            }
            validateTexts(story.acceptanceCriteria(), "验收标准");
            if (story.acceptanceCriteria().isEmpty()) {
                throw new IllegalStateException("用户故事必须包含验收标准。");
            }
        }
        Set<String> taskIds = new HashSet<>();
        for (var task : result.developmentTasks()) {
            if (task == null || blank(task.id()) || blank(task.title()) || blank(task.description())
                    || !taskIds.add(task.id()) || task.storyIds() == null || task.storyIds().isEmpty()
                    || task.storyIds().stream().anyMatch(id -> !storyIds.contains(id))) {
                throw new IllegalStateException("开发任务字段缺失、标识重复或引用了不存在的用户故事。");
            }
        }
        validateTexts(result.openQuestions(), "待确认问题");
        validateTexts(result.optionalSuggestions(), "可选建议");
    }

    private static void validateReferences(GroundedOutput output, int count) {
        if (output == null || output.methodReferences() == null || blank(output.knowledgeNote())) {
            throw new IllegalStateException("知识参考说明或方法引用结构缺失。");
        }
        for (var reference : output.methodReferences()) {
            if (reference == null || blank(reference.explanation()) || reference.sourceNumbers() == null
                    || reference.sourceNumbers().isEmpty()
                    || reference.sourceNumbers().stream().anyMatch(number -> number == null || number < 1 || number > count)
                    || new HashSet<>(reference.sourceNumbers()).size() != reference.sourceNumbers().size()) {
                throw new IllegalStateException("方法引用包含无效或不存在的来源编号。");
            }
        }
    }

    private static void validateTexts(List<String> items, String name) {
        if (items == null || items.stream().anyMatch(DecompositionService::blank)) {
            throw new IllegalStateException(name + "格式无效。");
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String line(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    public record MethodReference(String explanation, List<Integer> sourceNumbers) {}
    public record GroundedOutput(RequirementDecomposition decomposition, List<MethodReference> methodReferences,
            String knowledgeNote, DecisionKnowledge.Usage projectDecisions) {}
    public record KnowledgeGrounding(String status, String query, List<KnowledgeService.SourceSnippet> sources,
            List<MethodReference> methodReferences, String note, DecisionKnowledge.Context decisions,
            DecisionKnowledge.Usage decisionUsage) {
        public KnowledgeGrounding {
            if (decisions == null) { decisions = DecisionKnowledge.disabled(); }
            if (decisionUsage == null) { decisionUsage = DecisionKnowledge.validate(null, DecisionKnowledge.disabled()); }
        }
        public KnowledgeGrounding(String status, String query, List<KnowledgeService.SourceSnippet> sources,
                List<MethodReference> methodReferences, String note) {
            this(status, query, sources, methodReferences, note, DecisionKnowledge.disabled(), null);
        }
    }
    public record SavedDraft(RequirementDecomposition result, Path file, String preview, KnowledgeGrounding grounding) {}
}
