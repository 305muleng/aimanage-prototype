package com.aimanage.prototype.domain;

public enum TaskStatus {
    TODO("待处理"),
    IN_PROGRESS("进行中"),
    DONE("已完成");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
