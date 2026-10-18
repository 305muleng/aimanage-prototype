package com.aimanage.prototype.domain;
public enum UmlKind { CLASS("类图"), USE_CASE("用例图"), SEQUENCE("时序图"), ACTIVITY("活动图"); private final String label; UmlKind(String label){this.label=label;} public String getLabel(){return label;} }
