package com.example.jingjingai2.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 把一批运行记录收成一页纸。 */
public final class EvalReport {

    private EvalReport() {}

    public static String render(List<RunRecord> records) {
        Map<String, List<RunRecord>> byTask = new TreeMap<>();
        for (RunRecord record : records) {
            byTask.computeIfAbsent(record.taskId(), k -> new ArrayList<>()).add(record);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 评测一页纸\n\n");
        sb.append("| 任务 | 做成 | 总 token | 平均轮数 | 失败归类 |\n");
        sb.append("|---|---|---|---|---|\n");
        List<String> unclassified = new ArrayList<>();
        for (Map.Entry<String, List<RunRecord>> entry : byTask.entrySet()) {
            List<RunRecord> rows = entry.getValue();
            int passed = 0;
            long tokens = 0;
            int rounds = 0;
            Map<FailureKind, Integer> kinds = new EnumMap<>(FailureKind.class);
            for (RunRecord row : rows) {
                if (row.passed()) passed++;
                tokens += row.totalTokens();
                rounds += row.rounds();
                if (!row.passed() && row.failureKind() != null) {
                    kinds.merge(row.failureKind(), 1, Integer::sum);
                    if (row.failureKind() == FailureKind.UNCLASSIFIED) {
                        unclassified.add(row.taskId() + "#" + row.attempt());
                    }
                }
            }
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(passed).append('/').append(rows.size())
                    .append(" | ").append(tokens)
                    .append(" | ").append(rows.isEmpty() ? "0" : format(rounds / (double) rows.size()))
                    .append(" | ").append(kinds.isEmpty() ? "—" : formatKinds(kinds))
                    .append(" |\n");
        }
        sb.append("\nUNCLASSIFIED：");
        sb.append(unclassified.isEmpty() ? "无" : String.join("、", unclassified));
        sb.append('\n');
        return sb.toString();
    }

    private static String formatKinds(Map<FailureKind, Integer> kinds) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<FailureKind, Integer> entry : kinds.entrySet()) {
            if (sb.length() > 0) sb.append("，");
            sb.append(entry.getKey().name()).append('×').append(entry.getValue());
        }
        return sb.toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
