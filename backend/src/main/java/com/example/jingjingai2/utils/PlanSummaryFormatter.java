package com.example.jingjingai2.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code [PlanSummary]} 汇总日志的**纯函数**格式化器。
 *
 * 抽出来的原因（真实 bug）：原来这段文本是直接在 {@code ChatController} 里用
 * {@code System.out.println} 拼的，而调用点只有"正常走完"这一条路径 ——
 * 流式生成异常、高危命令待审批、外层异常这些路径都会**提前 return**，
 * 汇总日志就不会被打印（实测 24 次运行里有 2 次日志缺 [PlanSummary]）。
 * 现在把"文本怎么拼"和"什么时候打"分开：
 *   - 文本 → 本类（纯函数，可单测）
 *   - 时机 → ChatController 的 finally + {@link #markOnce(boolean[])} 保证只打一次
 */
public final class PlanSummaryFormatter {

    private PlanSummaryFormatter() {}

    /**
     * 生成汇总日志的三行文本。
     *
     * @param signalsText 已命中的漂移信号（直接沿用调用方的 toString 形式，保持日志格式不变）
     */
    public static List<String> lines(int roundsUsed, int firstCreatePlanRound,
                                     int softCount, int hardCount, String signalsText,
                                     boolean forceFailed, int planInjectChars, int totalPromptChars,
                                     double charsPerToken, String stepsMode, boolean slim,
                                     int fullRefreshEvery, String nudgeMode, String hardStrategy) {
        List<String> out = new ArrayList<>(3);
        boolean planCalled = firstCreatePlanRound >= 0;
        String signals = (signalsText == null || signalsText.isBlank()) ? "无" : signalsText;
        out.add("[PlanSummary] 轮数=" + roundsUsed
                + " | createPlan=" + (planCalled
                        ? "已调用(首次第 " + (firstCreatePlanRound + 1) + " 轮)" : "未调用")
                + " | nudge SOFT=" + softCount + " HARD=" + hardCount
                + " 命中信号=" + signals
                + (forceFailed ? " | 强制请求失败过（已回退）" : ""));
        String ratio = totalPromptChars <= 0 ? "n/a"
                : String.format("%.1f%%", 100.0 * planInjectChars / totalPromptChars);
        out.add("[PlanSummary] 规划注入估算 " + est(planInjectChars, charsPerToken) + " tokens("
                + planInjectChars + " 字符) / 全部注入估算 " + est(totalPromptChars, charsPerToken)
                + " tokens(" + totalPromptChars + " 字符) → 占比 " + ratio + "  【估算，非精确计费】");
        out.add("[PlanSummary] 生效开关: steps.mode=" + stepsMode
                + " | injection.slim=" + slim
                + " fullRefreshEvery=" + fullRefreshEvery
                + " | nudge.mode=" + nudgeMode
                + " hard.strategy=" + hardStrategy);
        return Collections.unmodifiableList(out);
    }

    /** 字符数 → 估算 token 数（四舍五入）；charsPerToken <= 0 时返回 0，避免除零 */
    public static long est(int chars, double charsPerToken) {
        if (charsPerToken <= 0 || chars <= 0) return 0;
        return Math.round(chars / charsPerToken);
    }

    /**
     * "只打印一次"的闸门：第一次调用置位并返回 true，其后恒返回 false。
     *
     * 用在"正常路径先打印、finally 再兜底打印"的结构里 —— 两条路都会调用它，
     * 但只有先到的那次真正输出。
     */
    public static boolean markOnce(boolean[] flag) {
        if (flag == null || flag.length == 0 || flag[0]) return false;
        flag[0] = true;
        return true;
    }
}
