package com.example.jingjingai2.context;

import com.example.jingjingai2.utils.UsageTracker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryUsageAccountingTest {

    @Test
    void providerNumbersStayRealAndMissingCacheStaysUnknown() {
        ContextCompactor.SummaryUsage real = ContextCompactor.interpretUsage("提示", "摘要", 11, 3, null);
        assertFalse(real.estimated());
        assertEquals(11, real.inputTokens());
        assertEquals(3, real.outputTokens());
        assertNull(real.cachedPromptTokens());

        ContextCompactor.SummaryUsage cached = ContextCompactor.interpretUsage("提示", "摘要", 11, 3, 0);
        assertEquals(0, cached.cachedPromptTokens());
        assertFalse(cached.estimated());
    }

    @Test
    void missingProviderUsageIsEstimatedAndDoesNotInventACacheZero() {
        ContextCompactor.SummaryUsage estimated = ContextCompactor.interpretUsage("提示", "摘要", null, null, null);
        assertTrue(estimated.estimated());
        assertTrue(estimated.inputTokens() > 0);
        assertNull(estimated.cachedPromptTokens());
        assertNull(ContextCompactor.reportedCachedTokens(new DefaultUsage(11, 3, 14)));
    }

    @Test
    void reportedCacheIsReadOnlyWhenTheProviderSendsIt() {
        OpenAiApi.Usage.PromptTokensDetails details = new OpenAiApi.Usage.PromptTokensDetails(0, 7);
        OpenAiApi.Usage nativeUsage = new OpenAiApi.Usage(3, 11, 14, details, null);
        Usage usage = new DefaultUsage(11, 3, 14, nativeUsage);
        assertEquals(7, ContextCompactor.reportedCachedTokens(usage));
    }

    @Test
    void twoSummariesWithTheSameTokenCountAreBothCounted() {
        UsageTracker tracker = new UsageTracker();
        tracker.acceptMeasured(20, 5, null);
        tracker.acceptMeasured(20, 5, 4);
        assertEquals(2, tracker.getResponsesWithUsage());
        assertEquals(40, tracker.getPromptTokens());
        assertEquals(10, tracker.getCompletionTokens());
        assertEquals(4, tracker.getCachedPromptTokens());
        assertTrue(tracker.cacheFieldSeen());
    }

    @Test
    void measuredSummaryDoesNotLookLikeTheNextStreamChunk() {
        UsageTracker tracker = new UsageTracker();
        tracker.acceptMeasured(100, 8, null);
        tracker.accept(new org.springframework.ai.chat.model.ChatResponse(
                java.util.List.of(),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(100, 9, 109)).build()));
        assertEquals(2, tracker.getResponsesWithUsage());
        assertEquals(200, tracker.getPromptTokens());
        assertFalse(tracker.cacheFieldSeen());
    }

    @Test
    void zeroMeasuredUsageIsNotRecordedAsARealResponse() {
        UsageTracker tracker = new UsageTracker();
        tracker.acceptMeasured(0, 0, null);
        assertEquals(0, tracker.getResponsesWithUsage());
        assertFalse(tracker.cacheFieldSeen());
    }
}
