package com.example.jingjingai2.eval;

import java.util.List;
import java.util.Map;

/** 导出用的 token 合计。totalTokens 只加工具循环轮次，和改口径之前的算法一致。 */
public final class TurnTokenTotals {

    public record Result(long totalTokens, long compactionTokens, long grandTotalTokens) {}

    private TurnTokenTotals() {}

    public static Result of(List<Map<String, Object>> turnRows) {
        long loop = 0;
        long compaction = 0;
        if (turnRows != null) {
            for (Map<String, Object> row : turnRows) {
                String phase = row.get("phase") == null ? "" : String.valueOf(row.get("phase"));
                long tokens = number(row.get("inputTokens")) + number(row.get("outputTokens"));
                if ("COMPACTION".equals(phase)) compaction += tokens;
                else if ("MODEL_TURN".equals(phase) || phase.isEmpty()) loop += tokens;
            }
        }
        return new Result(loop, compaction, loop + compaction);
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return 0;
    }
}
