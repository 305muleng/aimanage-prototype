package com.aiguanli.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 本次分析冻结的已审核决策和模型声明的使用依据；不持久化。 */
public final class DecisionKnowledge {
    private DecisionKnowledge() {}

    public record Context(String status, String query, List<KnowledgeService.SourceSnippet> sources) {
        public Context { sources = List.copyOf(sources); }
    }
    public record Reference(String explanation, List<String> decisionIds) {}
    public record Usage(List<Reference> references, String note) {}

    public static Context disabled() { return new Context("DISABLED", "", List.of()); }

    public static Context retrieve(KnowledgeService knowledge, String query, boolean allowSimulation) {
        var sources = knowledge.retrieveReviewedDecisions(query, allowSimulation);
        return new Context(sources.isEmpty() ? "NO_MATCH" : "RETRIEVED", query, sources);
    }

    public static Usage validate(Usage usage, Context context) {
        if (usage == null && context.sources().isEmpty()) {
            return new Usage(List.of(), "本次未向模型提供已审核决策。");
        }
        if (usage == null || usage.references() == null || usage.note() == null || usage.note().isBlank()) {
            throw new IllegalStateException("已审核决策的引用或使用说明缺失。");
        }
        Set<String> allowed = context.sources().stream().map(s -> s.decision().decisionId()).collect(Collectors.toSet());
        for (var ref : usage.references()) {
            if (ref == null || ref.explanation() == null || ref.explanation().isBlank() || ref.decisionIds() == null
                    || ref.decisionIds().isEmpty() || ref.decisionIds().stream().anyMatch(id -> !allowed.contains(id))
                    || new HashSet<>(ref.decisionIds()).size() != ref.decisionIds().size()) {
                throw new IllegalStateException("决策引用包含不存在、重复或缺失的编号。");
            }
        }
        return new Usage(List.copyOf(usage.references()), usage.note());
    }

    public static void renderContext(StringBuilder output, Context context) {
        output.append("\n## 已审核项目决策参考\n\n");
        if (context.sources().isEmpty()) {
            output.append(context.status().equals("DISABLED") ? "RAG 未启用或本次未请求检索。\n"
                    : "没有符合范围的已入库决策，或本次没有相关片段命中。\n");
            return;
        }
        output.append("以下为提供给模型的检索材料，不表示全部被采用，也不表示业务操作已执行。\n\n");
        for (var source : context.sources()) {
            var decision = source.decision();
            output.append("- [").append(decision.decisionId()).append("] ")
                    .append(decision.simulation() ? "模拟演示，不代表真实项目规则" : "用户提供材料，经本机人工审核，平台身份未验证")
                    .append("；消息：").append(decision.sourceMessageIds())
                    .append("；来源：").append(line(decision.sourceFile()))
                    .append("；输入 SHA-256：").append(decision.inputSha256())
                    .append("\n  审核人：").append(line(decision.reviewer())).append("；处理：").append(decision.reviewAction())
                    .append("；审核时间（UTC）：").append(decision.reviewedAt())
                    .append("\n  审核理由：").append(line(decision.reason()))
                    .append("\n  片段：").append(line(source.text())).append('\n');
        }
    }

    public static void renderUsage(StringBuilder output, String role, Usage usage) {
        output.append("\n").append(role).append("的决策使用说明（模型声明，待核对）：").append(line(usage.note())).append('\n');
        for (var ref : usage.references()) {
            output.append("- ").append(line(ref.explanation())).append("；决策：").append(ref.decisionIds()).append('\n');
        }
    }

    private static String line(String value) { return value.replace('\r', ' ').replace('\n', ' '); }
}
