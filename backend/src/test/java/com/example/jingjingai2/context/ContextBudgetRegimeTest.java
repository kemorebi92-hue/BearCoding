package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作集软预算 64000 与历史摘要触发线的实际关系（M1 × M2 的交汇点）。
 *
 * <p>两个数字量的不是同一件事：
 * <ul>
 *   <li>软预算 64000 比较的是「历史 + 工具结果」正文估算，超了就退休更早的可恢复内容；</li>
 *   <li>摘要触发线是「模型窗口 × compact-window-percent」。默认窗口 128000、百分比 65，
 *       触发线是 83200，<strong>比软预算高</strong>。</li>
 * </ul>
 * 所以默认配置下存在一段区间（历史约 64000～83200）：旧助手回复会先被投影退休，
 * 但历史摘要<strong>不会</strong>触发。窗口更小的模型则相反——触发线低于软预算，摘要先发生。
 * 这两条线不互相替代，也不该被写成同一个阈值。
 */
class ContextBudgetRegimeTest {

    private static final int SOFT_BUDGET = 64_000;
    private static final int WINDOW = 128_000;
    private static final int PERCENT = 65;
    /** 128000 × 65% = 83200。 */
    private static final int SUMMARY_LINE = WINDOW * PERCENT / 100;

    @Test
    @DisplayName("默认配置：摘要触发线 83200 高于工作集软预算 64000")
    void defaultSummaryLineIsAboveSoftBudget() {
        assertEquals(83_200, SUMMARY_LINE, "128000 × 65% 的触发线");
        assertTrue(SUMMARY_LINE > SOFT_BUDGET,
                "触发线必须高于软预算，否则这两个阈值就是一回事，文档里的区分没有意义");

        assertFalse(ContextBudget.shouldCompact(SOFT_BUDGET, 0, WINDOW, PERCENT, SOFT_BUDGET),
                "刚好等于软预算时还不触发摘要");
        assertFalse(ContextBudget.shouldCompact(SUMMARY_LINE, 0, WINDOW, PERCENT, SOFT_BUDGET),
                "刚好等于触发线时还不触发摘要");
        assertTrue(ContextBudget.shouldCompact(SUMMARY_LINE + 1, 0, WINDOW, PERCENT, SOFT_BUDGET),
                "超过触发线 1 token 就要触发");
    }

    @Test
    @DisplayName("64000～83200 这段区间：历史退休会先发生，历史摘要不会触发")
    void historyBetweenTheTwoLinesRetiresWithoutSummary() {
        int historyTokens = 70_000;
        ContextCompactor compactor = compactorReturning("摘要" + "甲".repeat(200));

        compactor.assemble(history(historyTokens), "当前问题", "1|convA");

        ContextCompactor.HistorySummaryStats stats = compactor.lastHistorySummary();
        assertTrue(historyTokens > SOFT_BUDGET, "前提：这段历史已经超过软预算");
        assertFalse(stats.triggered(), "但没到 83200 的摘要触发线，不能调用摘要模型");
        assertEquals(0, stats.calls(), "这段区间里摘要调用次数必须是 0");
        assertFalse(stats.applied(), "没有摘要，就没有覆盖");
    }

    @Test
    @DisplayName("窗口更小的模型：触发线低于软预算，摘要先发生，硬窗口仍然是 100%")
    void smallerWindowSummarizesBeforeSoftBudget() {
        int smallWindow = 48_000;                       // 65% = 31200
        ContextCompactor compactor = compactorReturning("摘要" + "乙".repeat(200));

        compactor.assemble(history(70_000), "当前问题", "1|convB");

        // 这段历史按 128000 的窗口不触发；换成 48000 的窗口就必须触发。
        ContextCompactor.HistorySummaryStats big = compactor.lastHistorySummary();
        assertFalse(big.triggered(), "按 128000 窗口不触发");

        ContextCompactor small = new ContextCompactor(
                modelReturning("摘要" + "乙".repeat(200)), 64_000, smallWindow, PERCENT);
        small.assemble(history(70_000), "当前问题", "1|convB");
        assertTrue(small.lastHistorySummary().triggered(),
                "48000 窗口的触发线是 31200，这段历史必须触发摘要");

        // 硬窗口是 100%，与 65% 的触发线不是一回事。
        assertFalse(ContextBudget.exceedsWindow(smallWindow, smallWindow), "刚好等于窗口不算超过");
        assertTrue(ContextBudget.exceedsWindow(smallWindow + 1, smallWindow), "超过窗口 1 token 就必须拦");
    }

    @Test
    @DisplayName("窗口未知时退回 history-max-tokens，不会把回退值写成所有模型的真实窗口")
    void unknownWindowFallsBackToHistoryBudget() {
        assertFalse(ContextBudget.shouldCompact(24_000, 0, 0, PERCENT, 24_000),
                "窗口未知时按 history-max-tokens 判断");
        assertTrue(ContextBudget.shouldCompact(24_001, 0, 0, PERCENT, 24_000));
        assertFalse(ContextBudget.exceedsWindow(999_999, 0), "窗口未知时不做硬窗口判断");
    }

    // ===================== helpers =====================

    private static ContextCompactor compactorReturning(String summary) {
        return new ContextCompactor(modelReturning(summary), 64_000, WINDOW, PERCENT);
    }

    private static OpenAiChatModel modelReturning(String summary) {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(summary))));
        });
        return model;
    }

    /** 造一段估算约等于 tokens 的历史：每回合 user + assistant 各占一半。 */
    private static List<MessageRecord> history(int tokens) {
        int perTurn = tokens / 20;
        List<MessageRecord> rows = new ArrayList<>();
        long id = 1;
        for (int turn = 0; turn < 20; turn++) {
            rows.add(row(id++, "user", "第" + turn + "轮用户：" + "约".repeat(perTurn / 2)));
            rows.add(row(id++, "assistant", "第" + turn + "轮助手：" + "答".repeat(perTurn / 2)));
        }
        return rows;
    }

    private static MessageRecord row(long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(id * 1000));
        return record;
    }
}
