package com.aiguanli.ai;

import java.util.List;

/** 解析结果始终是草稿；每一项均携带消息来源。 */
public record DiscussionAnalysis(
        List<EvidenceItem> summary,
        List<EvidenceItem> decisions,
        List<EvidenceItem> suggestions,
        List<ActionItem> actionItems,
        List<EvidenceItem> openQuestions) {

    public record EvidenceItem(String text, List<String> sourceMessageIds) {
    }

    public record ActionItem(String task, String owner, String deadline, List<String> sourceMessageIds) {
    }
}
