package com.aimanage.prototype.domain;

public enum Role {
    PROJECT_MANAGER("项目负责人"),
    DEVELOPER("开发人员");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
