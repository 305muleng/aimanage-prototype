package com.aiguanli.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;

/** 每次分析单独创建，只暴露本次快照，不允许模型指定路径或修改任务。 */
public class ProjectProgressTools {
    private final ProgressReport report;
    private final AtomicInteger calls = new AtomicInteger();

    public ProjectProgressTools(ProgressSnapshot snapshot, String source) {
        this.report = calculate(snapshot, source);
    }

    @Tool(name = "getSprintProgress", description = "查询本次模拟 Sprint 任务快照。返回统计日期、来源、任务数量完成率、延期任务、阻塞任务和未完成依赖。只读，不代表真实项目进度。")
    public ProgressReport getSprintProgress() {
        if (calls.incrementAndGet() > 2) {
            throw new IllegalStateException("同一次分析工具调用次数过多。");
        }
        return report;
    }

    public int callCount() { return calls.get(); }

    public ProgressReport report() { return report; }

    private static ProgressReport calculate(ProgressSnapshot snapshot, String source) {
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.simulation())
                || snapshot.sprintName() == null || snapshot.sprintName().isBlank() || snapshot.tasks() == null) {
            throw new IllegalArgumentException("需要完整、明确标记 simulation=true 的模拟任务快照。");
        }
        LocalDate date = parseDate(snapshot.snapshotDate());
        Map<String, ProgressSnapshot.Task> byId = new HashMap<>();
        for (var task : snapshot.tasks()) {
            if (task == null || task.id() == null || task.id().isBlank() || task.title() == null
                    || task.title().isBlank() || task.status() == null || task.dependencyIds() == null
                    || byId.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("任务字段缺失或 ID 重复。");
            }
            if (task.dueDate() != null) { parseDate(task.dueDate()); }
        }
        List<TaskFact> tasks = new ArrayList<>();
        List<String> overdue = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<DependencyRisk> risks = new ArrayList<>();
        int completed = 0;
        for (var task : snapshot.tasks()) {
            var dependencyIds = new HashSet<String>();
            for (String id : task.dependencyIds()) {
                if (id == null || !byId.containsKey(id) || id.equals(task.id()) || !dependencyIds.add(id)) {
                    throw new IllegalArgumentException("任务依赖不存在、自引用或重复。");
                }
            }
            boolean done = task.status() == ProgressSnapshot.Status.DONE;
            boolean late = !done && task.dueDate() != null && parseDate(task.dueDate()).isBefore(date);
            if (done) { completed++; }
            if (late) { overdue.add(task.id()); }
            if (task.status() == ProgressSnapshot.Status.BLOCKED) { blocked.add(task.id()); }
            tasks.add(new TaskFact(task.id(), task.title(), task.status().name(), task.dueDate(),
                    List.copyOf(task.dependencyIds()), late));
            if (!done) {
                for (String dependency : task.dependencyIds()) {
                    var predecessor = byId.get(dependency);
                    if (predecessor.status() != ProgressSnapshot.Status.DONE) {
                        risks.add(new DependencyRisk(task.id(), dependency, predecessor.status().name()));
                    }
                }
            }
        }
        int total = tasks.size();
        BigDecimal percent = total == 0 ? null : BigDecimal.valueOf(completed).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new ProgressReport(true, source, snapshot.snapshotDate(), snapshot.sprintName(),
                "按任务数量：已完成任务数 / 任务总数 × 100；截止日期早于快照日期且未完成才算延期；无任务时完成率未知。",
                total, completed, percent, List.copyOf(overdue), List.copyOf(blocked),
                List.copyOf(risks), List.copyOf(tasks));
    }

    private static LocalDate parseDate(String value) {
        if (value == null) { throw new IllegalArgumentException("快照日期不能为空。"); }
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException("日期应为 yyyy-MM-dd。", ex); }
    }

    public record TaskFact(String id, String title, String status, String dueDate,
                           List<String> dependencyIds, boolean overdue) {}
    public record DependencyRisk(String taskId, String dependsOn, String predecessorStatus) {}
    public record ProgressReport(boolean simulation, String source, String snapshotDate, String sprintName,
                                 String calculationRule, int totalTasks, int completedTasks, BigDecimal completionPercent,
                                 List<String> overdueTaskIds, List<String> blockedTaskIds,
                                 List<DependencyRisk> dependencyRisks, List<TaskFact> tasks) {}
}
