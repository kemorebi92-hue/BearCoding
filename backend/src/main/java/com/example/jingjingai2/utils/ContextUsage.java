package com.example.jingjingai2.utils;

import java.util.List;

/**
 * 上下文预算占用环用的用量。只描述「本轮主 Agent 单次模型调用里最大的那一次 prompt」，
 * 不累计多轮，也不碰 {@link UsageTracker} 的计费累计。
 */
public final class ContextUsage {

    /** 本轮主 Agent 单次模型调用的最大 promptTokens。 */
    public final long promptTokens;
    /** Harness 压缩触发预算：context-window-tokens × compact-window-percent / 100。 */
    public final long contextBudget;

    public ContextUsage(long promptTokens, long contextBudget) {
        this.promptTokens = promptTokens;
        this.contextBudget = contextBudget;
    }

    /**
     * @param curve 主 Agent 每轮的 prompt 差值（与 {@code ChatController} 的 promptCurve 同一口径）
     * @return 曲线为空、全是非正数，或预算算不出来时返回 null，调用方不发 usage 字段
     */
    public static ContextUsage of(List<Long> curve, int windowTokens, int compactPercent) {
        long peak = peakOf(curve);
        long budget = budgetOf(windowTokens, compactPercent);
        if (peak <= 0 || budget <= 0) return null;
        return new ContextUsage(peak, budget);
    }

    /** 曲线里的最大值。空、null、非正数都不算有效用量。 */
    public static long peakOf(List<Long> curve) {
        if (curve == null || curve.isEmpty()) return 0;
        long peak = 0;
        for (Long v : curve) {
            if (v != null && v > peak) peak = v;
        }
        return peak;
    }

    /** 与 {@code ChatController#guardRound} 的触发线同一公式。窗口或百分比非正时返回 0。 */
    public static long budgetOf(int windowTokens, int compactPercent) {
        if (windowTokens <= 0 || compactPercent <= 0) return 0;
        return (long) windowTokens * compactPercent / 100;
    }
}
