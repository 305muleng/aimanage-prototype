package com.aimanage.prototype.domain;
public enum MemberStatus {
    ACTIVE("正常参与"), PAUSED("暂停参与");
    private final String label;
    MemberStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
