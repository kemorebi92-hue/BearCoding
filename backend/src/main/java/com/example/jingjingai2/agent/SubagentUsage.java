package com.example.jingjingai2.agent;

import com.example.jingjingai2.utils.UsageTracker;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 一个子代理（或主 agent 一轮）的**真实 token 用量 + 按价格表折算的成本**。
 *
 * 为什么要单独一个类：本模块要回答的问题不是"跑通没有"，而是"派子代理到底划不划算"。
 * 划不划算只能靠数字说话：input / cache-hit input / output 三档分开记，
 * 因为**缓存命中价通常只有未命中的 1/4**，混在一起算会把子代理的成本算错。
 *
 * 线程模型：一个子代理一个实例（在自己的房间里累加），跑完随 {@link SubagentResult} 交回。
 */
public final class SubagentUsage {

    /** 按价格表折算的**加权成本**（单位：元），价格来自配置（app.subagent.price-*） */
    public static final class Cost {
        public final long promptTokens;
        public final long cachedPromptTokens;
        public final long completionTokens;
        public final double yuan;

        Cost(long promptTokens, long cachedPromptTokens, long completionTokens, double yuan) {
            this.promptTokens = promptTokens;
            this.cachedPromptTokens = cachedPromptTokens;
            this.completionTokens = completionTokens;
            this.yuan = yuan;
        }

        @Override
        public String toString() {
            return String.format("¥%.6f（in=%d cached=%d out=%d）",
                    yuan, promptTokens, cachedPromptTokens, completionTokens);
        }
    }

    private long promptTokens;
    private long cachedPromptTokens;
    private long completionTokens;
    private int calls;

    /** 从一次模型响应里累计用量。拿不到 usage 就跳过（绝不抛异常，观测代码不许影响主流程） */
    public synchronized void accept(ChatResponse resp) {
        if (resp == null || resp.getMetadata() == null) return;
        var u = resp.getMetadata().getUsage();
        if (u == null) return;
        int p = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
        int c = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
        if (p == 0 && c == 0) return;   // Spring AI 默认的 EmptyUsage（全零）＝ 没有用量信息
        calls++;
        promptTokens += p;
        completionTokens += c;
        cachedPromptTokens += UsageTracker.cachedTokensOf(u);
    }

    public synchronized void add(long prompt, long cached, long completion) {
        promptTokens += prompt;
        cachedPromptTokens += cached;
        completionTokens += completion;
    }

    public synchronized long promptTokens() { return promptTokens; }
    public synchronized long cachedPromptTokens() { return cachedPromptTokens; }
    public synchronized long completionTokens() { return completionTokens; }
    public synchronized int calls() { return calls; }

    /** 没命中缓存的输入 = 总输入 - 命中缓存的部分（服务商的 cached 是 prompt 的子集） */
    public synchronized long uncachedPromptTokens() {
        return Math.max(0, promptTokens - cachedPromptTokens);
    }

    public synchronized boolean hasData() {
        return promptTokens > 0 || completionTokens > 0;
    }

    /** 按价格表折算成本（价格是配置项，**请以实际服务商为准**） */
    public Cost cost(SubagentProperties props) {
        long in, cached, out;
        synchronized (this) {
            in = promptTokens;
            cached = cachedPromptTokens;
            out = completionTokens;
        }
        return costOf(in, cached, out, props);
    }

    /** 纯函数：三档 token → 加权成本（便于单测钉死算法） */
    public static Cost costOf(long prompt, long cached, long completion, SubagentProperties props) {
        long hit = Math.max(0, Math.min(cached, prompt));
        long miss = Math.max(0, prompt - hit);
        double yuan = miss / 1_000_000.0 * props.getPriceInputPerMillion()
                + hit / 1_000_000.0 * props.getPriceCachedInputPerMillion()
                + Math.max(0, completion) / 1_000_000.0 * props.getPriceOutputPerMillion();
        return new Cost(prompt, hit, completion, yuan);
    }

    @Override
    public String toString() {
        return "Usage[in=" + promptTokens + " cached=" + cachedPromptTokens
                + " out=" + completionTokens + " calls=" + calls + "]";
    }
}
