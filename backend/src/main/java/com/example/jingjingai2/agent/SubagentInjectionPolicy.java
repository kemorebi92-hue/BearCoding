package com.example.jingjingai2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * 子代理「条件注入」的判定策略（纯函数，便于单测）。
 *
 * <p>为什么单独摘出来：这段逻辑原本写在 {@code ChatController} 的私有方法里，
 * 只能靠启动整个应用来验证。摘成静态纯函数后，"什么请求该下发子代理"这条规则
 * 可以被单测直接钉死 —— 它决定了成本（S1 那种单文件小问题不该付固定开销）。
 *
 * <p>判定顺序（任一命中即下发）：
 * <ol>
 *   <li>用户请求命中 {@code patterns} 里任意一条正则；</li>
 *   <li>上下文估算 token ≥ {@code threshold}。</li>
 * </ol>
 * 两者都不满足 → 不下发（工具与提示词规则都不出现，行为等同关组）。
 *
 * <p>失败模式刻意选"不注入"：正则解析失败、模式列表为空、历史读不到，一律返回 false。
 * 宁可少派（少花钱），不可在窄任务上白付固定开销 —— 第一轮实测 S1 开组比关组贵 29%。
 */
public final class SubagentInjectionPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubagentInjectionPolicy() {}

    /**
     * 是否应向主 agent 下发子代理工具与派发规则。
     *
     * @param conditionalInjection 开关；false 时直接返回 {@code enabled}（保持现状行为）
     * @param enabled              子代理总开关
     * @param patternsJson        宽泛探索特征，JSON 数组字符串（可为 null/空）
     * @param userMessage         本轮用户输入（可为 null）
     * @param contextTokens       当前上下文估算 token（含历史 + 本轮输入）
     * @param threshold           上下文阈值
     */
    public static boolean shouldInject(boolean conditionalInjection, boolean enabled,
                                       String patternsJson, String userMessage,
                                       long contextTokens, int threshold) {
        if (!enabled) return false;
        if (!conditionalInjection) return true;
        if (matchesAnyPattern(patternsJson, userMessage)) return true;
        return contextTokens >= threshold;
    }

    /** 命中模式列表里任意一条正则即返回 true；列表为空/非法/文本为空一律 false。 */
    static boolean matchesAnyPattern(String patternsJson, String text) {
        if (text == null || text.isBlank()) return false;
        if (patternsJson == null || patternsJson.isBlank()) return false;
        try {
            JsonNode node = MAPPER.readTree(patternsJson);
            if (node == null || !node.isArray()) return false;
            for (JsonNode p : node) {
                if (!p.isTextual()) continue;
                try {
                    if (Pattern.compile(p.asText()).matcher(text).find()) return true;
                } catch (RuntimeException ignored) {
                    // 单条正则非法 → 跳过这一条，不影响其它条
                }
            }
        } catch (Exception ignored) {
            // JSON 解析失败 → 不注入（保守）
        }
        return false;
    }
}
