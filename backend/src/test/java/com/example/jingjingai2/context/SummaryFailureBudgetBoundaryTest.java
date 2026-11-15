package com.example.jingjingai2.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.example.jingjingai2.utils.UsageTracker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 摘要失败后的硬上限使用配置窗口，不使用 65% 压缩触发线。 */
class SummaryFailureBudgetBoundaryTest {

    @Test
    @DisplayName("650001 到 1000000 之间不因触发线拒绝")
    void betweenTriggerAndWindowIsAllowed() {
        assertTrue(ContextBudget.shouldCompact(650_001, 0, 1_000_000, 65, 24_000));
        assertFalse(ContextBudget.exceedsWindow(650_001, 1_000_000));
        assertFalse(ContextBudget.exceedsWindow(700_000, 1_000_000));
        assertFalse(ContextBudget.exceedsWindow(1_000_000, 1_000_000));
    }

    @Test
    @DisplayName("历史低于触发线但完整请求超过窗口时拒绝")
    void fixedOverheadCanCrossWindow() {
        long history = 600_000;
        long fixed = 450_000;
        assertFalse(ContextBudget.shouldCompact(history, 0, 1_000_000, 65, 24_000));
        assertTrue(ContextBudget.exceedsWindow(history + fixed, 1_000_000));
    }

    @Test
    @DisplayName("完整请求包含系统消息和工具定义")
    void requestTokensIncludeFixedParts() {
        List<Message> messages = List.of(
                new SystemMessage("系统"),
                new UserMessage("历史"));
        long request = ContextBudget.requestTokens(messages, null);
        assertTrue(request >= ContextBudget.historyTokens(messages) + ContextBudget.fixedTokens(messages, null));
    }

    @Test
    @DisplayName("超过配置窗口才是硬拒绝")
    void overConfiguredWindowIsRejected() {
        assertTrue(ContextBudget.exceedsWindow(1_000_001, 1_000_000));
        assertFalse(ContextBudget.exceedsWindow(24_001, 0));
    }

    @Test
    @DisplayName("摘要失败后超过硬窗口时拒绝发送，失败用量仍记账")
    void failedSummaryOverHardWindowSkipsMainModel() {
        UserMessage huge = new UserMessage("超".repeat(2000));
        List<Message> messages = List.of(huge);
        ContextCompactor.SummaryUsage failed = new ContextCompactor.SummaryUsage("", 15, 2, false, true);
        ContextCompactor.CompactStats totalFailed = new ContextCompactor.CompactStats(
                false, 3000, 3000, 0, "SUMMARY_UNAVAILABLE");

        assertTrue(ContextBudget.exceedsWindow(ContextBudget.requestTokens(messages, null), 1000));
        assertFalse(ContextBudget.shouldCompact(600, 0, 1000, 65, 100), "软阈值与硬窗口是两个概念");

        for (boolean total : new boolean[]{false, true}) {
            boolean rejected = WindowGuard.reject(messages, null, total ? null : failed,
                    total ? totalFailed : null, total, 1000);
            assertTrue(rejected, total ? "total 口径应拒绝" : "history 口径应拒绝");
            UsageTracker usage = new UsageTracker();
            usage.acceptMeasured(failed.inputTokens(), failed.outputTokens(), failed.cachedPromptTokens());
            assertEquals(1, usage.getResponsesWithUsage(), "失败摘要的用量仍要记账");
        }
        assertFalse(WindowGuard.reject(messages, null, failed, null, false, 1_000_000),
                "没超过硬窗口时不拒绝");
        assertFalse(WindowGuard.reject(messages, null,
                new ContextCompactor.SummaryUsage("成功", 15, 2, false, false), null, false, 1000),
                "摘要成功时不走这条拒绝");
    }
}
