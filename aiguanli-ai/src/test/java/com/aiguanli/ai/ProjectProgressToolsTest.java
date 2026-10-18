package com.aiguanli.ai;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.aiguanli.ai.ProgressSnapshot.Status.*;

class ProjectProgressToolsTest {
    @Test
    void usesSnapshotDateAndExcludesDoneAndTodayFromOverdue() {
        var snapshot = snapshot(List.of(
                task("done", DONE, "2026-10-01", List.of()),
                task("late", IN_PROGRESS, "2026-10-10", List.of()),
                task("today", TODO, "2026-10-11", List.of()),
                task("blocked", BLOCKED, "2026-10-14", List.of("late", "done")),
                task("undated", TODO, null, List.of())));
        var report = new ProjectProgressTools(snapshot, "mock.json").report();
        assertThat(report.completionPercent()).isEqualByComparingTo("20.00");
        assertThat(report.overdueTaskIds()).containsExactly("late");
        assertThat(report.blockedTaskIds()).containsExactly("blocked");
        assertThat(report.dependencyRisks()).containsExactly(
                new ProjectProgressTools.DependencyRisk("blocked", "late", "IN_PROGRESS"));
        assertThat(report.snapshotDate()).isEqualTo("2026-10-11");
    }

    @Test
    void handlesEmptyAndFractionalCompletionRates() {
        assertThat(new ProjectProgressTools(snapshot(List.of()), "empty.json").report().completionPercent()).isNull();
        var report = new ProjectProgressTools(snapshot(List.of(task("a", DONE, null, List.of()),
                task("b", TODO, null, List.of()), task("c", TODO, null, List.of()))), "third.json").report();
        assertThat(report.completionPercent()).isEqualByComparingTo("33.33");
    }

    @Test
    void rejectsUnmarkedDataDuplicateIdsInvalidDatesAndMissingDependencies() {
        assertThatThrownBy(() -> new ProjectProgressTools(new ProgressSnapshot(false, "2026-10-11", "demo", List.of()), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        var task = task("a", TODO, null, List.of());
        assertThatThrownBy(() -> new ProjectProgressTools(snapshot(List.of(task, task)), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> new ProjectProgressTools(snapshot(List.of(task("a", TODO, "2026-02-30", List.of()))), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("日期");
        assertThatThrownBy(() -> new ProjectProgressTools(snapshot(List.of(task("a", BLOCKED, null, List.of("missing")))), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("依赖");
    }

    private static ProgressSnapshot snapshot(List<ProgressSnapshot.Task> tasks) {
        return new ProgressSnapshot(true, "2026-10-11", "模拟 Sprint", tasks);
    }

    private static ProgressSnapshot.Task task(String id, ProgressSnapshot.Status status, String due, List<String> dependencies) {
        return new ProgressSnapshot.Task(id, "模拟任务", status, due, dependencies);
    }
}
