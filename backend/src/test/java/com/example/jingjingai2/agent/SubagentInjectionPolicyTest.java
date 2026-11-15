package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件注入判定：**决定成本的那条规则**。
 *
 * 背景（第一轮实测）：S1 那种单文件小问题，开组比关组贵 29% —— 因为工具定义 +
 * 派发规则是**每个主轮都在付**的固定开销（约 1645 tok/轮，S1 关组 7k、开组 8.7k）。
 * 所以"什么请求才下发子代理"必须有明确规则，且可被单测钉死。
 */
class SubagentInjectionPolicyTest {

    /** 与 application.yml 默认放宽后的模式对齐（测试用精简子集 + 新加的多问题特征） */
    private static final String PATTERNS =
            "[\"(?i).*(所有|全部|整个).*(有关|相关|逻辑).*\",\"(?i).*(梳理|通读).*\",\"(?i).*(两个|两件|并行).*(explore|子代理|调研).*\",\"(?i).*(互不相关|互相独立).*(问题|事).*\",\"(?i).*(派发预算|最多能派|续跑几次|失败.*重拆|重新拆分).*\"]";

    @Test
    @DisplayName("条件注入关闭 → 完全由 enabled 决定（保持现状行为，零风险）")
    void disabledKeepsLegacyBehavior() {
        assertTrue(SubagentInjectionPolicy.shouldInject(false, true, PATTERNS, "随便一句话", 0, 15000));
        assertFalse(SubagentInjectionPolicy.shouldInject(false, false, PATTERNS, "随便一句话", 999999, 15000));
    }

    @Test
    @DisplayName("总开关关闭 → 无论条件如何都不注入")
    void masterSwitchWins() {
        assertFalse(SubagentInjectionPolicy.shouldInject(true, false, PATTERNS, "找出所有有关的东西", 999999, 15000));
    }

    @Test
    @DisplayName("窄问题 + 小上下文 → 不注入（S1 场景：不该付固定开销）")
    void narrowQuestionSmallContextSkips() {
        String s1 = "SubagentProperties.java 里子代理的轮数预算是怎么配的？给文件:行号。";
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, PATTERNS, s1, 7082, 15000));
    }

    @Test
    @DisplayName("宽泛目标 → 注入（S3 场景：子代理的主场，哪怕上下文很小）")
    void broadGoalInjects() {
        assertTrue(SubagentInjectionPolicy.shouldInject(
                true, true, PATTERNS, "找出项目里所有与重试有关的逻辑并总结", 7000, 15000));
        assertTrue(SubagentInjectionPolicy.shouldInject(
                true, true, PATTERNS, "把子代理的预算逻辑梳理一遍", 7000, 15000));
    }

    @Test
    @DisplayName("放宽后：S2/S2P 多问题提示也能命中（修复前永远 false）")
    void multiQuestionPromptInjectsAfterWiden() {
        String s2 = "两件互不相关的事，请用两个 explore 子代理**并行**调研，各给结论 + 文件:行号";
        assertTrue(SubagentInjectionPolicy.shouldInject(true, true, PATTERNS, s2, 1000, 15000),
                "S2 提示应命中「两个…explore/子代理」或「互不相关」");
        String s2b = "同一个目标最多能派几次、最多能续跑几次？失败几次会触发重新拆分？";
        assertTrue(SubagentInjectionPolicy.shouldInject(true, true, PATTERNS, s2b, 1000, 15000),
                "预算/重拆问法应命中");
    }

    @Test
    @DisplayName("新旧阈值口径：仅落库摘要 < 阈值 → false；加上已读正文估算 ≥ 阈值 → true")
    void readLedgerPushesOverThreshold() {
        String narrow = "这个方法的第三行是什么意思？"; // 不命中正则
        long historyOnly = 2000;          // 旧口径：落库摘要
        long withReads = 2000 + 14000;    // 新口径：+ 已 readFile 正文估算
        assertFalse(SubagentInjectionPolicy.shouldInject(
                true, true, PATTERNS, narrow, historyOnly, 15000),
                "旧口径：预热后历史仍小 → 不注入");
        assertTrue(SubagentInjectionPolicy.shouldInject(
                true, true, PATTERNS, narrow, withReads, 15000),
                "新口径：计入已读正文后跨过阈值 → 注入");
    }

    @Test
    @DisplayName("上下文超阈值 → 注入（哪怕问句很窄：大上下文里派子代理能隔离噪音）")
    void bigContextInjects() {
        assertTrue(SubagentInjectionPolicy.shouldInject(
                true, true, PATTERNS, "这个方法的第三行是什么意思？", 200000, 15000));
    }

    @Test
    @DisplayName("阈值边界：等于阈值算命中，差 1 不算")
    void thresholdBoundary() {
        assertTrue(SubagentInjectionPolicy.shouldInject(true, true, "", "随便", 15000, 15000));
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, "", "随便", 14999, 15000));
    }

    @Test
    @DisplayName("模式列表为空 / 为 null / 空串 → 只按上下文阈值判断，不抛异常")
    void emptyPatternsFallBackToThreshold() {
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, "", "找出所有有关的东西", 1000, 15000));
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, null, "找出所有有关的东西", 1000, 15000));
        assertTrue(SubagentInjectionPolicy.shouldInject(true, true, "", "找出所有有关的东西", 20000, 15000));
    }

    @Test
    @DisplayName("非法 JSON / 非法正则 → 保守地不注入（宁可少派，不可白付开销）")
    void malformedInputsFailClosed() {
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, "这不是 JSON", "找出所有有关的逻辑", 1000, 15000));
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, "[\"[unclosed\"]", "找出所有有关的逻辑", 1000, 15000));
        // 一条非法 + 一条合法 → 合法那条仍然生效
        assertTrue(SubagentInjectionPolicy.shouldInject(
                true, true, "[\"[unclosed\",\"(?i).*找出.*\"]", "找出所有有关的逻辑", 1000, 15000));
    }

    @Test
    @DisplayName("用户输入为 null / 空白 → 不因特征命中，只看上下文")
    void nullUserMessageOnlyUsesContext() {
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, PATTERNS, null, 1000, 15000));
        assertFalse(SubagentInjectionPolicy.shouldInject(true, true, PATTERNS, "   ", 1000, 15000));
        assertTrue(SubagentInjectionPolicy.shouldInject(true, true, PATTERNS, null, 20000, 15000));
    }
}
