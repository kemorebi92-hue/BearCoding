package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 token 用量累计器。
 *
 * 存在的意义：原来 [PlanSummary] 里的 token 是**字符估算**，没法回答"这一轮到底花了多少"。
 * 这里把服务商返回的 usage 累加起来，日志里多一行 [Usage]。
 */
class UsageTrackerTest {

    private static ChatResponse resp(Usage usage) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(usage).build());
    }

    @Test
    @DisplayName("无用量响应：不污染统计，且不打日志（Spring AI 默认 metadata 带的是全零 EmptyUsage）")
    void noUsage() {
        UsageTracker t = new UsageTracker();
        t.accept(null);
        t.accept(new ChatResponse(List.of()));
        t.accept(new ChatResponse(List.of(), ChatResponseMetadata.builder().build()));
        t.accept(resp(new DefaultUsage(0, 0, 0)));   // 全零也算"没有用量信息"
        assertFalse(t.hasData(), "拿不到用量就不该打 [Usage]");
        assertEquals(0, t.getResponsesWithUsage());
    }

    @Test
    @DisplayName("多次响应累加 prompt/completion/total（不同 prompt = 不同 API 请求）")
    void accumulate() {
        UsageTracker t = new UsageTracker();
        t.accept(resp(new DefaultUsage(100, 20, 120)));
        t.accept(resp(new DefaultUsage(50, 10, 60)));
        assertTrue(t.hasData());
        assertEquals(2, t.getResponsesWithUsage());
        assertEquals(150, t.getPromptTokens());
        assertEquals(30, t.getCompletionTokens());
        assertEquals(180, t.getTotalTokens());
        assertTrue(t.line().contains("prompt=150"));
        assertTrue(t.line().contains("total=180"));
    }

    @Test
    @DisplayName("流式同请求多片 usage（prompt 相同）→ 不重复累加 prompt，响应数只计 1（回归：opt/S1 双计）")
    void streamChunksSamePromptAreDeduped() {
        UsageTracker t = new UsageTracker();
        // 同一轮 stream：两片都报 prompt=7082，completion 递增
        t.accept(resp(new DefaultUsage(7082, 10, 7092)));
        t.accept(resp(new DefaultUsage(7082, 50, 7132)));
        assertEquals(1, t.getResponsesWithUsage(), "同一次 stream 只算 1 个响应");
        assertEquals(7082, t.getPromptTokens(), "prompt 不得双计（曾出现 13940≈2×7082）");
        assertEquals(50, t.getCompletionTokens(), "completion 取到最终值（10 + 增量 40）");
    }

    @Test
    @DisplayName("同 prompt 多片之后再来一个不同 prompt → 仍按新请求累加")
    void dedupeThenNewRequest() {
        UsageTracker t = new UsageTracker();
        t.accept(resp(new DefaultUsage(7082, 10, 7092)));
        t.accept(resp(new DefaultUsage(7082, 40, 7122))); // 同请求分片
        t.accept(resp(new DefaultUsage(7200, 20, 7220))); // 下一轮新请求
        assertEquals(2, t.getResponsesWithUsage());
        assertEquals(7082 + 7200, t.getPromptTokens());
        assertEquals(40 + 20, t.getCompletionTokens());
    }

    @Test
    @DisplayName("total 缺失时用 prompt+completion 兜底，不能变成 null/负数")
    void totalFallback() {
        UsageTracker t = new UsageTracker();
        t.accept(resp(new DefaultUsage(100, 20)));   // 两参构造：不给 total
        assertEquals(120, t.getTotalTokens());
    }

    @Test
    @DisplayName("缓存命中 token：能读到 OpenAI 兼容协议的 prompt_tokens_details.cached_tokens")
    void cachedTokens() {
        OpenAiApi.Usage.PromptTokensDetails details =
                new OpenAiApi.Usage.PromptTokensDetails(0, 640);   // (audioTokens, cachedTokens)
        OpenAiApi.Usage nativeUsage = new OpenAiApi.Usage(30, 700, 730, details, null);
        Usage u = new DefaultUsage(700, 30, 730, nativeUsage);
        assertEquals(640L, UsageTracker.cachedTokensOf(u));
        UsageTracker t = new UsageTracker();
        t.accept(resp(u));
        assertEquals(640L, t.getCachedPromptTokens(), "缓存命中要能进汇总");
        assertTrue(t.line().contains("命中缓存 prompt=640"));
    }

    @Test
    @DisplayName("原生用量对象不是 OpenAI 结构时 → 缓存命中记 0，且绝不抛异常")
    void cachedTokensForeignNative() {
        Usage weird = new DefaultUsage(10, 5, 15, "not-an-openai-usage");
        assertEquals(0L, UsageTracker.cachedTokensOf(weird));
        assertEquals(0L, UsageTracker.cachedTokensOf(null));
    }
}
