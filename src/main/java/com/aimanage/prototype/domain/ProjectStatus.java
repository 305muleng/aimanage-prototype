package com.aimanage.prototype.domain;

public enum ProjectStatus {
    PLANNED("规划中"), ACTIVE("进行中"), PAUSED("已暂停"), COMPLETED("已完成");

    private final String label;
    ProjectStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
