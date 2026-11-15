package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [PlanSummary] 汇总日志的格式化与"只打印一次"闸门。
 *
 * 背景（真实 bug）：这段文本原来直接拼在 ChatController 里、只在"正常走完"的路径上打印，
 * 流式异常 / 高危命令待审批 / 外层异常这三条提前 return 的路径都不会打印 ——
 * 实测 24 次运行里有 2 次日志缺 [PlanSummary]。抽成本类后可单测，也便于 finally 兜底复用。
 */
class PlanSummaryFormatterTest {

    private static List<String> lines(int firstRound, String signals, boolean forceFailed,
                                     int injectChars, int totalChars) {
        return PlanSummaryFormatter.lines(7, firstRound, 1, 2, signals, forceFailed,
                injectChars, totalChars, 1.5, "flat", true, 6, "hard", "toolChoice");
    }

    @Test
    @DisplayName("三行结构：createPlan 首次轮次人读化（0 基 → 1 基）、占比、生效开关")
    void threeLines() {
        List<String> ls = lines(3, "[roundsGe8]", true, 1500, 10000);
        assertEquals(3, ls.size(), "固定三行");
        assertTrue(ls.get(0).contains("createPlan=已调用(首次第 4 轮)"), "0 基轮号要显示成第 4 轮");
        assertTrue(ls.get(0).contains("nudge SOFT=1 HARD=2"));
        assertTrue(ls.get(0).contains("命中信号=[roundsGe8]"));
        assertTrue(ls.get(0).contains("强制请求失败过"), "强制失败要标注");
        assertTrue(ls.get(1).contains("占比 15.0%"), "1500/10000 = 15.0%");
        assertTrue(ls.get(2).contains("steps.mode=flat"));
        assertTrue(ls.get(2).contains("nudge.mode=hard"));
        assertTrue(ls.get(2).contains("hard.strategy=toolChoice"));
    }

    @Test
    @DisplayName("未规划 / 无信号 / 无 prompt 时的边界文案")
    void boundaryText() {
        List<String> ls = lines(-1, "", false, 0, 0);
        assertTrue(ls.get(0).contains("createPlan=未调用"));
        assertTrue(ls.get(0).contains("命中信号=无"));
        assertFalse(ls.get(0).contains("强制请求失败过"));
        assertTrue(ls.get(1).contains("占比 n/a"), "totalPromptChars=0 不能除零");
    }

    @Test
    @DisplayName("est：按字符÷系数四舍五入；非法系数或空内容返回 0")
    void tokenEstimate() {
        assertEquals(100L, PlanSummaryFormatter.est(150, 1.5));
        assertEquals(7L, PlanSummaryFormatter.est(10, 1.5));
        assertEquals(0L, PlanSummaryFormatter.est(0, 1.5));
        assertEquals(0L, PlanSummaryFormatter.est(100, 0), "系数 0 → 0，不能除零");
        assertEquals(0L, PlanSummaryFormatter.est(100, -1), "负系数 → 0");
    }

    @Test
    @DisplayName("markOnce：只放行第一次（保证 finally 兜底不会重复打印）")
    void markOnceGate() {
        boolean[] flag = new boolean[1];
        assertTrue(PlanSummaryFormatter.markOnce(flag), "第一次放行");
        assertFalse(PlanSummaryFormatter.markOnce(flag), "第二次必须拦住");
        assertFalse(PlanSummaryFormatter.markOnce(null), "null 安全");
        assertFalse(PlanSummaryFormatter.markOnce(new boolean[0]), "空数组安全");
    }
}
