package com.example.jingjingai2.eval;

import java.util.List;

/**
 * T04 / 同类压缩任务的有效性。无效运行不进通过率的分母。
 * DIGEST 算真正跑过压缩；COMPACTION_FALLBACK 不算。
 */
public final class T04Validity {

    private T04Validity() {}

    /** 无效时返回原因，有效时返回 null。 */
    public static String invalidReason(List<JudgeContext.TurnView> turns) {
        boolean model = false;
        boolean fallback = false;
        boolean digest = false;
        boolean summaryRow = false;
        boolean summaryOnModel = false;
        if (turns != null) {
            for (JudgeContext.TurnView turn : turns) {
                if (isModelRound(turn)) model = true;
                String end = turn.endReason() == null ? "" : turn.endReason();
                String mode = turn.compactMode() == null ? "" : turn.compactMode();
                if (end.contains("FALLBACK")) fallback = true;
                if ("DIGEST".equals(mode) && !end.contains("FALLBACK")) digest = true;
                if ("SUMMARY".equals(mode) && "COMPACTION".equals(turn.phase()) && !end.contains("FALLBACK")) {
                    summaryRow = true;
                }
                if ("SUMMARY".equals(mode) && "MODEL_TURN".equals(turn.phase())) summaryOnModel = true;
                if ("SUMMARY".equals(mode) && turn.phase() == null && !end.contains("FALLBACK")) {
                    if (end.isEmpty() || end.startsWith("COMPACTION")) summaryRow = true;
                    else summaryOnModel = true;
                }
            }
        }
        boolean real = digest || summaryRow || (summaryOnModel && !fallback);
        if (!model) return "NO_MODEL_ROUNDS";
        if (real) return null;
        if (fallback) return "FALLBACK_COMPACTION";
        return "NO_REAL_SUMMARY";
    }

    public static boolean hasRealCompaction(List<JudgeContext.TurnView> turns) {
        return turns != null && invalidReason(withAModelRound(turns)) == null
                && (digestOrSummary(turns));
    }

    /** 给「只问压缩有没有发生」用，不要求同时有模型轮。 */
    private static boolean digestOrSummary(List<JudgeContext.TurnView> turns) {
        for (JudgeContext.TurnView turn : turns) {
            if (isRealCompaction(turn)) return true;
        }
        return false;
    }

    private static List<JudgeContext.TurnView> withAModelRound(List<JudgeContext.TurnView> turns) {
        for (JudgeContext.TurnView turn : turns) {
            if (isModelRound(turn)) return turns;
        }
        java.util.ArrayList<JudgeContext.TurnView> copy = new java.util.ArrayList<>(turns);
        copy.add(new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 0));
        return copy;
    }

    static boolean isModelRound(JudgeContext.TurnView turn) {
        if ("MODEL_TURN".equals(turn.phase())) return true;
        if (turn.phase() != null) return false;
        String end = turn.endReason();
        return end != null && !end.startsWith("COMPACTION") && !"APPROVAL_RESOLVED".equals(end);
    }

    static boolean isRealCompaction(JudgeContext.TurnView turn) {
        String mode = turn.compactMode();
        if (!"SUMMARY".equals(mode) && !"DIGEST".equals(mode)) return false;
        String end = turn.endReason() == null ? "" : turn.endReason();
        if (end.contains("FALLBACK")) return false;
        if ("DIGEST".equals(mode)) return true;
        if ("COMPACTION".equals(turn.phase()) || turn.phase() == null) return true;
        return "MODEL_TURN".equals(turn.phase());
    }
}
