package com.aiguanli.ai;

import java.util.List;

/** 日期使用 ISO yyyy-MM-dd；第一版只接受明确标记的模拟数据。 */
public record ProgressSnapshot(Boolean simulation, String snapshotDate, String sprintName, List<Task> tasks) {
    public enum Status { TODO, IN_PROGRESS, BLOCKED, DONE }

    public record Task(String id, String title, Status status, String dueDate, List<String> dependencyIds) {}
}
