package com.aimanage.prototype.domain;
public enum SprintStatus {
 NOT_STARTED("未开始"), IN_PROGRESS("进行中"), COMPLETED("已完成");
 private final String label;
 SprintStatus(String label){this.label=label;}
 public String getLabel(){return label;}
}
