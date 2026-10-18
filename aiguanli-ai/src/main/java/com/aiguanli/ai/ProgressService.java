package com.aiguanli.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ProgressService {
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final Path draftDirectory;
    private final String model;
    private final StorageSettings storage;

    public ProgressService(ChatClient.Builder builder, ObjectMapper mapper,
            @Value("${app.progress.draft-directory:${app.decomposition.draft-directory:.local/drafts}}") String draftDirectory,
            @Value("${spring.ai.openai.chat.options.model}") String model, StorageSettings storage) throws IOException {
        this.mapper = mapper;
        this.draftDirectory = Path.of(draftDirectory).toAbsolutePath().normalize();
        this.model = model;
        this.storage = storage;
        String prompt = new ClassPathResource("prompts/progress.md").getContentAsString(StandardCharsets.UTF_8);
        this.client = builder.clone().defaultSystem(prompt).build();
    }

    public Analysis analyze(Path file) throws IOException {
        // 一次读取冻结本次输入，模型调用期间源文件变化不会影响本次留存内容。
        byte[] input = Files.readAllBytes(file);
        ProgressSnapshot snapshot = mapper.readValue(input, ProgressSnapshot.class);
        var tools = new ProjectProgressTools(snapshot, file.getFileName().toString());
        // 初始请求不直接附带快照。由模型请求工具，Spring AI 执行后回传统计结果。
        String answer = client.prompt().user("请先调用 getSprintProgress 工具，再分析本次快照的依赖风险并给出推进建议。")
                .tools(tools).call().content();
        if (tools.callCount() == 0) {
            throw new ToolNotCalledException();
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("进度分析回答为空。");
        }
        Path saved = storage.localPersistenceEnabled() ? save(file, input, tools.report(), answer, tools.callCount()) : null;
        return new Analysis(tools.report(), answer, tools.callCount(), saved);
    }

    private Path save(Path source, byte[] input, ProjectProgressTools.ProgressReport report,
            String answer, int toolCalls) throws IOException {
        String id = UUID.randomUUID().toString();
        StringBuilder content = new StringBuilder("# 进度分析草稿\n\n");
        content.append("状态：待人工审核。仅基于模拟快照，不代表真实项目进度。\n\n")
                .append("草稿 ID：").append(id).append("\n\n")
                .append("生成时间（UTC）：").append(Instant.now()).append("\n\n")
                .append("配置的模型：").append(line(model)).append("\n\n")
                .append("源文件：").append(line(source.toAbsolutePath().normalize().toString())).append("\n\n")
                .append("输入文件 SHA-256：").append(sha256(input)).append("\n\n")
                .append("快照日期：").append(report.snapshotDate()).append("\n\n")
                .append("Sprint：").append(line(report.sprintName())).append("\n\n")
                .append("实际执行工具：getSprintProgress；调用次数：").append(toolCalls).append("\n\n")
                .append("## Java 统计事实\n\n")
                .append("- 已完成任务：").append(report.completedTasks()).append('/').append(report.totalTasks()).append('\n')
                .append("- 按任务数量计算的完成率：")
                .append(report.completionPercent() == null ? "无法计算（没有任务）" : report.completionPercent() + "%").append('\n')
                .append("- 延期任务：").append(ids(report.overdueTaskIds())).append('\n')
                .append("- 阻塞任务：").append(ids(report.blockedTaskIds())).append('\n')
                .append("- 未完成依赖关系数：").append(report.dependencyRisks().size()).append('\n')
                .append("- 统计口径：").append(report.calculationRule()).append("\n\n")
                .append("## AI 分析原文（待核对）\n\n").append(answer).append("\n\n")
                .append("## 人工审核记录\n\n")
                .append("- 审核人：待填写\n- 审核时间：待填写\n")
                .append("- 处理结论：待填写（采纳 / 修改后采纳 / 否决）\n")
                .append("- 修改内容与理由：待填写\n\n")
                .append("审核意见填写在此处；保留以上 AI 分析原文和以下输入快照、工具结果，便于追溯。\n\n")
                .append("## 本次输入快照（JSON）\n\n```json\n")
                .append(new String(input, StandardCharsets.UTF_8)).append("\n```\n\n")
                .append("## Java 工具返回结果（JSON）\n\n```json\n")
                .append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)).append("\n```\n");
        Files.createDirectories(draftDirectory);
        Path target = draftDirectory.resolve("progress-" + id + ".md");
        Path temporary = Files.createTempFile(draftDirectory, "progress-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    private static String sha256(byte[] input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用。", ex); }
    }

    private static String line(String input) { return input.replace('\r', ' ').replace('\n', ' '); }

    private static String ids(List<String> values) {
        return values.isEmpty() ? "无" : values.stream().map(ProgressService::line).collect(java.util.stream.Collectors.joining("、"));
    }

    public record Analysis(ProjectProgressTools.ProgressReport report, String answer, int toolCalls, Path file) {}

    public static class ToolNotCalledException extends IllegalStateException {
        public ToolNotCalledException() { super("模型未调用进度工具。"); }
    }
}
