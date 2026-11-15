package com.example.jingjingai2.utils;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 一次请求内的**真实 token 用量**累计器（纯观测，不改变任何行为）。
 *
 * <p>为什么需要它：项目里原有的 "规划注入估算 N tokens" 是<strong>按字符数估算</strong>的（见
 * {@code ChatController#estimateTokens}），只能做横向对照，不能回答"这一轮到底花了多少钱"。
 * 服务商返回的 {@code ChatResponse.metadata.usage} 才是真实计费值，这里把它累计起来，
 * 请求结束时打一行 {@code [Usage]} 日志。
 *
 * <p>流式去重（2026-09 实测）：部分服务商（如 MiMo）会在<strong>同一轮 stream 的多片</strong>上
 * 各自带回 usage，且每片的 {@code prompt_tokens} 都是整次请求的全量。若每片都累加，
 * PromptCurve / 成本会<strong>大约翻倍</strong>（opt/S1 首轮 13940 ≈ 2× off 的 7082）。
 * 规则：若本片 {@code prompt} 与上一片相同（同一次 API 请求的后续分片），只把
 * completion / cached 的<strong>增量</strong>补上，不重复加 prompt，也不再增加「响应数」。
 *
 * <p>线程模型：一个请求一个实例（方法内局部变量），流式链路里被分片 lambda 捕获，故不可做成字段。
 */
public final class UsageTracker {

    /** 实际带回用量的<strong>独立 API 响应</strong>数（同一次 stream 多片只计 1） */
    private int responsesWithUsage;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long cachedPromptTokens;
    /** 至少有一次响应明确带了缓存字段。没有则为 false，不能把累计 0 当成真实未命中。 */
    private boolean cacheFieldSeen;

    /** 上一片已计入的 prompt / completion / cached（用于同请求分片去重） */
    private int lastPrompt = -1;
    private int lastCompletion;
    private long lastCached;

    /**
     * 累计一次模型响应里的用量。null 安全：拿不到用量就跳过（例如某些流式分片不带 usage）。
     */
    public void accept(ChatResponse resp) {
        if (resp == null || resp.getMetadata() == null) return;
        Usage u = resp.getMetadata().getUsage();
        if (u == null) return;
        Integer p = u.getPromptTokens();
        Integer c = u.getCompletionTokens();
        int pi = (p == null ? 0 : p);
        int ci = (c == null ? 0 : c);
        // 全零要当成"没有用量信息"：Spring AI 的 ChatResponseMetadata 默认带的是 EmptyUsage（各项为 0），
        // 不是 null —— 不排掉它，日志会打出一堆 total=0 的假数据。
        if (pi == 0 && ci == 0) return;
        Integer t = u.getTotalTokens();
        long cached = cachedTokensOf(u);

        // 同一轮 stream 的后续分片：prompt 全量重复上报 → 只补 completion / cached 增量
        if (lastPrompt >= 0 && pi == lastPrompt) {
            int dComp = Math.max(0, ci - lastCompletion);
            long dCached = Math.max(0L, cached - lastCached);
            completionTokens += dComp;
            cachedPromptTokens += dCached;
            // prompt 没变 → 本次请求的 total 只应随 completion 增长，增量即 dComp
            // （不直接取分片上报的 total：那个值同样是整次请求的全量，重复加会再次翻倍）
            totalTokens += dComp;
            lastCompletion = ci;
            lastCached = cached;
            return;
        }

        // 新的一次 API 请求
        responsesWithUsage++;
        promptTokens += pi;
        completionTokens += ci;
        totalTokens += (t != null && t > 0) ? t : (pi + ci);
        cachedPromptTokens += cached;
        lastPrompt = pi;
        lastCompletion = ci;
        lastCached = cached;
    }

    /**
     * 记一次已经解析好的摘要调用。每次调用都算新请求，不用 prompt 数值去重，
     * 否则两次真实摘要的 prompt 相同会被误当成同一次流式分片。
     * cached 为 null 时不把缺失记成 0。
     */
    public void acceptMeasured(int prompt, int completion, Integer cached) {
        if (prompt == 0 && completion == 0) return;
        responsesWithUsage++;
        promptTokens += prompt;
        completionTokens += completion;
        totalTokens += (long) prompt + completion;
        if (cached != null) {
            cachedPromptTokens += cached;
            cacheFieldSeen = true;
        }
        lastPrompt = -1;
        lastCompletion = 0;
        lastCached = 0;
    }

    /**
     * 从原生用量对象里取"命中缓存的 prompt tokens"。
     *
     * 不同服务商的字段不同，这里只认 OpenAI 兼容协议的
     * {@code usage.prompt_tokens_details.cached_tokens}（DeepSeek / 小米 MiMo 等都提供）。
     * 取不到就返回 0，不影响其它字段，也绝不抛异常。
     */
    public static long cachedTokensOf(Usage u) {
        if (u == null) return 0;
        try {
            Object nativeUsage = u.getNativeUsage();
            if (nativeUsage instanceof OpenAiApi.Usage ou
                    && ou.promptTokensDetails() != null
                    && ou.promptTokensDetails().cachedTokens() != null) {
                return ou.promptTokensDetails().cachedTokens();
            }
        } catch (Exception ignored) {
            // 观测代码绝不允许影响主流程
        }
        return 0;
    }

    /** 本次请求是否真的拿到过任何用量数据（用来判断"该不该打这行日志"） */
    public boolean hasData() {
        return responsesWithUsage > 0;
    }

    public int getResponsesWithUsage() { return responsesWithUsage; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getTotalTokens() { return totalTokens; }
    public long getCachedPromptTokens() { return cachedPromptTokens; }
    public boolean cacheFieldSeen() { return cacheFieldSeen; }

    /** 汇总日志正文（纯函数，便于单测：内容只由字段决定，不依赖任何外部状态） */
    public String line() {
        return "[Usage] 带回用量的响应数=" + responsesWithUsage
                + " | prompt=" + promptTokens
                + " completion=" + completionTokens
                + " total=" + totalTokens
                + " | 命中缓存 prompt=" + cachedPromptTokens
                + "  【服务商真实计费值】";
    }
}
