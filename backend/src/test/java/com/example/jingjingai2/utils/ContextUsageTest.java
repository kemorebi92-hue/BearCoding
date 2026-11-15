package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 上下文预算占用环：分子取本轮主 Agent 单次调用的最大 prompt，分母是 Harness 压缩触发预算。
 * 不累计多轮，也不封顶真实 token 数。
 */
class ContextUsageTest {

    private static final int WINDOW = 1_000_000;
    private static final int PERCENT = 65;

    @Test
    @DisplayName("多轮曲线取最大值，不求和")
    void peakNotSum() {
        ContextUsage u = ContextUsage.of(List.of(12_000L, 40_000L, 25_000L), WINDOW, PERCENT);
        assertEquals(40_000L, u.promptTokens);
        assertEquals(650_000L, u.contextBudget);
    }

    @Test
    @DisplayName("强制 createPlan 的那一轮也计入最大值")
    void forcedCreatePlanRoundCounts() {
        // 第 1 轮是强制 createPlan（非流式，单独一次模型调用），后面两轮是普通对话
        ContextUsage u = ContextUsage.of(List.of(55_000L, 30_000L, 48_000L), WINDOW, PERCENT);
        assertEquals(55_000L, u.promptTokens);
    }

    @Test
    @DisplayName("编译自愈重试计入最大值：重试轮比首轮更大时取重试轮")
    void compileRetryCounts() {
        ContextUsage u = ContextUsage.of(List.of(20_000L, 21_500L, 60_000L), WINDOW, PERCENT);
        assertEquals(60_000L, u.promptTokens);
    }

    @Test
    @DisplayName("没有有效用量时不产生 usage：空曲线、全零、全负数都返回 null")
    void noUsageYieldsNull() {
        assertNull(ContextUsage.of(List.of(), WINDOW, PERCENT));
        assertNull(ContextUsage.of(null, WINDOW, PERCENT));
        assertNull(ContextUsage.of(List.of(0L, 0L), WINDOW, PERCENT));
        assertNull(ContextUsage.of(List.of(-5L), WINDOW, PERCENT));
    }

    @Test
    @DisplayName("contextBudget 按 Harness 配置计算，不写死 650000")
    void budgetFollowsConfig() {
        assertEquals(650_000L, ContextUsage.budgetOf(1_000_000, 65));
        assertEquals(500_000L, ContextUsage.budgetOf(1_000_000, 50));
        assertEquals(0L, ContextUsage.budgetOf(0, 65));
        assertEquals(0L, ContextUsage.budgetOf(1_000_000, 0));
        // 预算算不出来时整段 usage 都不发
        assertNull(ContextUsage.of(List.of(1000L), 0, 65));
    }

    @Test
    @DisplayName("真实 token 数不封顶：超过预算仍返回原值，封顶是前端的事")
    void tokensNotCapped() {
        ContextUsage u = ContextUsage.of(List.of(720_000L, 100_000L), WINDOW, PERCENT);
        assertEquals(720_000L, u.promptTokens);
        assertEquals(650_000L, u.contextBudget);
    }

    @Test
    @DisplayName("曲线里的 null 项跳过，不影响其余轮的最大值")
    void nullEntriesSkipped() {
        List<Long> curve = new ArrayList<>();
        curve.add(null);
        curve.add(8_000L);
        curve.add(null);
        assertEquals(8_000L, ContextUsage.peakOf(curve));
    }
}
