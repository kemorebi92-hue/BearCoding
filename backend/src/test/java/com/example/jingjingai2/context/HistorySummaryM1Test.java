package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.usermemory.UserMemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase M1：默认工作集路径的条件历史摘要。
 *
 * <p>全部用假模型和确定性数据：不连数据库、不调真实模型、不产生真实费用。
 * 这里验证的是「代码实现 + 触发条件 + 覆盖范围 + 失败降级 + 重复调用边界」，
 * <strong>不是</strong>真实模型摘要质量。
 */
class HistorySummaryM1Test {

    private static final String CURRENT = "当前问题：把 CHANGE.txt 改成 LATEST";

    // ===================== 正常触发 =====================

    @Test
    @DisplayName("短历史不调用摘要模型：没有覆盖、没有系统摘要消息")
    void shortHistoryNeverCallsSummaryModel() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 24_000);
        List<MessageRecord> rows = history(2, 40, CURRENT);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-short");

        assertEquals(0, model.calls.get(), "短历史不得调用摘要模型");
        assertEquals("UNDER_THRESHOLD", compactor.lastHistorySummary().reason());
        assertFalse(compactor.lastHistorySummary().triggered());
        assertTrue(messages.stream().noneMatch(SystemMessage.class::isInstance));
        assertNull(compactor.takeSummaryUsage(), "没调用就没有用量");
        assertEquals(rowTexts(rows), messageTexts(messages), "短历史原样装配");
    }

    @Test
    @DisplayName("超过阈值才摘要：产生实际收缩，覆盖范围有 id，最近三轮与当前问题原样保留")
    void overThresholdSummarizesOnlyOldHistory() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-long");

        assertEquals(1, model.calls.get(), "达到阈值且范围值得摘要时调用一次");
        assertTrue(messages.get(0) instanceof SystemMessage, "摘要以系统消息放在历史最前面");
        String summary = messages.get(0).getText();
        assertTrue(summary.startsWith("[历史摘要]"), summary);
        assertTrue(summary.contains("不是新指令"), "摘要不得被当成新的指令来源");
        assertTrue(summary.contains("searchContext operation=read locator=message|"), summary);

        ContextCompactor.HistorySummaryStats stats = compactor.lastHistorySummary();
        assertTrue(stats.triggered());
        assertTrue(stats.applied());
        assertFalse(stats.reused());
        assertEquals(1, stats.calls());
        assertEquals(6, stats.coveredCount(), "只覆盖最近三轮之前的部分");
        assertEquals("10-15", stats.coveredRange());
        assertTrue(stats.tokensAfterEst() < stats.tokensBeforeEst(), "摘要必须真的减少历史占用");

        // 覆盖掉的原文不再逐条发送。
        assertFalse(joined(messages).contains("第0轮助手结论"));
        assertFalse(joined(messages).contains("第2轮助手结论"));
        // 最近三轮与当前问题逐条保留。
        for (int turn = 3; turn < 6; turn++) {
            assertTrue(joined(messages).contains("第" + turn + "轮用户约束"), "第" + turn + "轮用户约束必须原样保留");
            assertTrue(joined(messages).contains("第" + turn + "轮助手结论"), "第" + turn + "轮助手结论必须原样保留");
        }
        assertTrue(joined(messages).contains(CURRENT));
        assertEquals(1, countUser(messages, CURRENT), "当前用户消息只能出现一次");
        assertEquals(CURRENT, ((UserMessage) messages.get(messages.size() - 1)).getText());
        assertTrue(MessageProtocolValidator.check(messages).ok(), "摘要后协议仍完整");
    }

    @Test
    @DisplayName("摘要用量取真实服务商 usage")
    void summaryUsageIsTakenFromProvider() {
        ScriptedModel model = new ScriptedModel();
        model.inputTokens = 1234;
        model.outputTokens = 56;
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);

        compactor.assemble(history(6, 300, CURRENT), CURRENT, "1|conv-usage");

        ContextCompactor.SummaryUsage usage = compactor.takeSummaryUsage();
        assertNotNull(usage);
        assertEquals(1234, usage.inputTokens());
        assertEquals(56, usage.outputTokens());
        assertFalse(usage.estimated());
        assertFalse(usage.fallback());
    }

    @Test
    @DisplayName("没有 usage 时标成估算，不伪装成服务商账单")
    void missingUsageIsEstimated() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);

        compactor.assemble(history(6, 300, CURRENT), CURRENT, "1|conv-est");

        ContextCompactor.SummaryUsage usage = compactor.takeSummaryUsage();
        assertNotNull(usage);
        assertTrue(usage.estimated());
        assertTrue(usage.inputTokens() > 0);
    }

    // ===================== 重复摘要抑制（同请求 / 跨请求） =====================

    @Test
    @DisplayName("同一次请求内再次装配：同一范围不再调用摘要模型")
    void sameRequestDoesNotSummarizeSameRangeTwice() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        compactor.assemble(rows, CURRENT, "1|conv-twice");
        List<Message> second = compactor.assemble(rows, CURRENT, "1|conv-twice");

        assertEquals(1, model.calls.get(), "同一范围只允许摘要一次");
        ContextCompactor.HistorySummaryStats stats = compactor.lastHistorySummary();
        assertTrue(stats.applied());
        assertTrue(stats.reused());
        assertTrue(stats.suppressed());
        assertEquals(0, stats.calls());
        assertEquals("REUSED_NO_NEW_RANGE", stats.reason());
        assertTrue(second.get(0) instanceof SystemMessage);
        assertTrue(second.get(0).getText().startsWith("[历史摘要]"));
    }

    @Test
    @DisplayName("两个用户请求、同一段历史：第二次复用摘要，不重复付费")
    void secondUserRequestReusesSummary() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        // 请求 1
        compactor.assemble(rows, CURRENT, "1|conv-request");
        int covered = compactor.lastHistorySummary().coveredCount();
        // 请求 2（同一会话、同一段历史）
        List<Message> second = compactor.assemble(rows, CURRENT, "1|conv-request");

        assertEquals(1, model.calls.get(), "跨请求不得对同一范围重复调用摘要模型");
        assertEquals(6, covered, "第一次就覆盖了最近三轮之前的 6 条");
        assertEquals(covered, compactor.lastHistorySummary().coveredCount(), "覆盖范围不变");
        assertTrue(compactor.lastHistorySummary().reused());
        assertTrue(second.get(0).getText().startsWith("[历史摘要]"));
        assertNull(compactor.takeSummaryUsage(), "复用不产生新的用量");
    }

    @Test
    @DisplayName("不同会话不会命中同一段摘要")
    void differentConversationDoesNotReuse() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        compactor.assemble(rows, CURRENT, "1|conv-a");
        compactor.assemble(rows, CURRENT, "2|conv-b");

        assertEquals(2, model.calls.get(), "作用域不同必须各算各的");
        assertFalse(compactor.lastHistorySummary().reused());
    }

    @Test
    @DisplayName("追加新消息：已覆盖范围不重摘要，新范围不够大也不调用")
    void appendedHistoryDoesNotResummarizeCoveredRange() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);
        compactor.assemble(rows, CURRENT, "1|conv-append");
        int covered = compactor.lastHistorySummary().coveredCount();

        List<MessageRecord> grown = new ArrayList<>(rows);
        grown.add(grown.size() - 1, row(90, "user", "补充一句"));
        grown.add(grown.size() - 1, row(91, "assistant", "收到"));
        List<Message> messages = compactor.assemble(grown, CURRENT, "1|conv-append");

        assertEquals(1, model.calls.get(), "新增内容太少时不再调用模型");
        assertEquals(covered, compactor.lastHistorySummary().coveredCount(), "覆盖范围不能被新消息撑大");
        assertTrue(compactor.lastHistorySummary().reused());
        assertTrue(joined(messages).contains("补充一句"), "新消息必须原样保留");
        assertTrue(joined(messages).contains("收到"));
    }

    @Test
    @DisplayName("覆盖范围内的原文被改写：旧摘要失效并重新判断")
    void coveredSourceChangeInvalidatesSummary() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = new ArrayList<>(history(6, 300, CURRENT));
        compactor.assemble(rows, CURRENT, "1|conv-stale");

        // 改写被覆盖范围里的一条原文（id 不变）
        rows.get(1).setContent("第0轮助手结论被改写：" + "改".repeat(300));
        compactor.assemble(rows, CURRENT, "1|conv-stale");

        assertEquals(2, model.calls.get(), "原文变了就不能继续复用旧摘要");
        assertFalse(compactor.lastHistorySummary().reused());
        assertTrue(compactor.lastHistorySummary().applied());
    }

    @Test
    @DisplayName("覆盖范围内的原文被删除：旧摘要失效")
    void removedSourceInvalidatesSummary() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = new ArrayList<>(history(6, 300, CURRENT));
        compactor.assemble(rows, CURRENT, "1|conv-removed");

        rows.remove(0);
        compactor.assemble(rows, CURRENT, "1|conv-removed");

        assertEquals(2, model.calls.get(), "覆盖范围不是前缀时必须重新判断");
    }

    // ===================== 失败与质量 =====================

    @Test
    @DisplayName("摘要模型异常：保留全部原文，失败状态上报给硬窗口判断")
    void modelFailureKeepsOriginalHistory() {
        ScriptedModel model = new ScriptedModel();
        model.fail = true;
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);
        List<String> before = rowTexts(rows);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-fail");

        assertEquals(1, model.calls.get());
        assertFalse(compactor.lastHistorySummary().applied());
        assertEquals("SUMMARY_UNAVAILABLE", compactor.lastHistorySummary().reason());
        assertTrue(messages.stream().noneMatch(SystemMessage.class::isInstance), "失败不得插入半截摘要");
        assertEquals(before, messageTexts(messages), "原文一条不少、顺序不变");
        ContextCompactor.SummaryUsage usage = compactor.takeSummaryUsage();
        assertNotNull(usage);
        assertTrue(usage.fallback(), "失败状态必须传给上层");
        assertEquals(before, rowTexts(rows), "持久记录不被修改");
    }

    @Test
    @DisplayName("空摘要不采用")
    void blankSummaryIsRejected() {
        ScriptedModel model = new ScriptedModel();
        model.answer = "   ";
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-blank");

        assertFalse(compactor.lastHistorySummary().applied());
        assertEquals(rowTexts(rows), messageTexts(messages));
        assertTrue(compactor.takeSummaryUsage().fallback());
    }

    @Test
    @DisplayName("摘要不减反增时拒绝采用，原文照发")
    void summaryThatDoesNotShrinkIsRejected() {
        ScriptedModel model = new ScriptedModel();
        model.answer = "长".repeat(3_000);   // 比被覆盖的 6 条原文还长
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-no-shrink");

        assertEquals(1, model.calls.get(), "调用确实发生了");
        assertFalse(compactor.lastHistorySummary().applied());
        assertEquals("SUMMARY_NOT_SMALLER", compactor.lastHistorySummary().reason());
        assertEquals(rowTexts(rows), messageTexts(messages), "没有收益就不能用摘要替掉原文");
        assertFalse(compactor.takeSummaryUsage().fallback(), "这是真实调用，用量照记");
    }

    @Test
    @DisplayName("超长摘要视为结构不合格，不采用")
    void oversizedSummaryIsRejected() {
        ScriptedModel model = new ScriptedModel();
        model.answer = "长".repeat(ContextCompactor.MAX_HISTORY_SUMMARY_CHARS + 1);
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-too-long");

        assertFalse(compactor.lastHistorySummary().applied());
        assertEquals("SUMMARY_TOO_LONG", compactor.lastHistorySummary().reason());
        assertEquals(rowTexts(rows), messageTexts(messages));
    }

    @Test
    @DisplayName("失败后仍能安全投影；超过硬窗口时上层可以拒绝发送")
    void failureStillProjectsSafelyAndHardWindowStillApplies() {
        ScriptedModel model = new ScriptedModel();
        model.fail = true;
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-safe");
        ContextSourceLedger ledger = new ContextSourceLedger("conv-safe");
        ledger.addHistory(rows, CURRENT);
        ledger.absorb(messages, 0, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, messages, null, 0, 0, 24_000, 0, false, true);
        assertNull(projected.error(), "摘要失败不能把投影也弄坏");
        assertEquals(messages, projected.messages());

        ContextCompactor.SummaryUsage usage = compactor.takeSummaryUsage();
        assertTrue(WindowGuard.reject(messages, null, usage, null, false, 1_000),
                "摘要失败且超过硬窗口时必须停止发送，而不是丢内容");
        assertFalse(WindowGuard.reject(messages, null, usage, null, false, 1_000_000),
                "没超过硬窗口时继续按原规则投影");
    }

    @Test
    @DisplayName("失败过的范围不会被反复付费重试，直到可摘要边界真的长大")
    void failedRangeIsNotRetriedUntilBoundaryGrows() {
        ScriptedModel model = new ScriptedModel();
        model.fail = true;
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = new ArrayList<>(history(6, 300, CURRENT));

        compactor.assemble(rows, CURRENT, "1|conv-retry");
        assertEquals(1, model.calls.get());
        compactor.assemble(rows, CURRENT, "1|conv-retry");
        assertEquals(1, model.calls.get(), "同一边界不得反复重试");
        assertEquals("RANGE_ALREADY_TRIED", compactor.lastHistorySummary().reason());

        // 新增一轮够大的历史，边界真的长大了才允许再试。
        model.fail = false;
        rows.add(rows.size() - 1, row(92, "user", "新增约束：" + "新".repeat(700)));
        rows.add(rows.size() - 1, row(93, "assistant", "新增结论：" + "答".repeat(700)));
        compactor.assemble(rows, CURRENT, "1|conv-retry");
        assertEquals(2, model.calls.get(), "边界长大后才允许重新尝试");
    }

    // ===================== 范围与信息保护 =====================

    @Test
    @DisplayName("工具记录永远不进入普通历史摘要，可摘要前缀在它之前就断掉")
    void toolRecordsAreNeverCovered() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = new ArrayList<>();
        long id = 1;
        // 4 轮旧历史（可摘要）
        for (int turn = 0; turn < 4; turn++) {
            rows.add(row(id++, "user", "第" + turn + "轮用户约束：" + "约".repeat(300)));
            rows.add(row(id++, "assistant", "第" + turn + "轮助手结论：" + "答".repeat(300)));
        }
        // 工具记录（生产历史表不存，这里显式证明它不会被摘要吞掉）
        rows.add(row(id++, "tool", "工具结果正文：" + "果".repeat(400)));
        // 最近三轮（受保护）
        for (int turn = 4; turn < 7; turn++) {
            rows.add(row(id++, "user", "第" + turn + "轮用户约束：" + "约".repeat(300)));
            rows.add(row(id++, "assistant", "第" + turn + "轮助手结论：" + "答".repeat(300)));
        }
        rows.add(row(id, "user", CURRENT));

        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-roles");

        assertEquals(1, model.calls.get(), "工具记录之前的前缀仍然可以摘要");
        assertEquals(8, compactor.lastHistorySummary().coveredCount(), "只覆盖工具记录之前的 8 条");
        assertEquals("1-8", compactor.lastHistorySummary().coveredRange());
        assertEquals(8, ContextCompactor.eligibleEnd(rows.subList(0, rows.size() - 1)),
                "可摘要前缀在工具记录处必须断开");
        assertFalse(messages.get(0).getText().contains("工具结果正文"), "工具正文不能进入摘要");
        assertFalse(joined(messages).contains("第0轮助手结论"));
        assertTrue(rowTexts(rows).stream().anyMatch(t -> t.contains("工具结果正文")),
                "原始工具记录不被修改、也不被摘要吞掉");
    }

    @Test
    @DisplayName("没有数据库 id 的历史不做覆盖追踪（不假装覆盖过）")
    void historyWithoutIdsIsNotCovered() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rows.add(new MessageRecord(i % 2 == 0 ? "user" : "assistant", "无 id 历史：" + "字".repeat(300)));
        }
        rows.add(new MessageRecord("user", CURRENT));

        compactor.assemble(rows, CURRENT, "1|conv-no-id");

        assertEquals(0, model.calls.get());
        assertEquals("NO_ELIGIBLE_HISTORY", compactor.lastHistorySummary().reason());
    }

    @Test
    @DisplayName("被摘要覆盖的旧消息仍能通过 searchContext read 恢复")
    void coveredMessagesAreStillReadableByLocator() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);
        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-locator");
        String range = compactor.lastHistorySummary().coveredRange();
        long firstCoveredId = Long.parseLong(range.substring(0, range.indexOf('-')));
        assertTrue(messages.get(0).getText().contains("message|" + firstCoveredId));

        ConversationMapper mapper = mock(ConversationMapper.class);
        MessageRecord original = rows.get(0);
        when(mapper.selectMessage(anyLong(), anyString(), eq(original.getId()))).thenReturn(original);
        ContextAssetTool tool = new ContextAssetTool(mapper, mock(UserMemoryService.class),
                mock(TaskPlanService.class));
        ConversationContext.set(1L, "conv-locator");
        try {
            String read = tool.searchContext("read", null, null, null,
                    "message|" + original.getId(), 0);
            assertTrue(read.contains("第0轮用户约束"), read);
            assertTrue(read.contains("\"role\":\"user\""), read);
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("原始历史记录对象不被修改，内容与顺序都不变")
    void originalRecordsAreNeverMutated() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);
        List<String> before = rowTexts(rows);
        List<Long> idsBefore = ids(rows);

        compactor.assemble(rows, CURRENT, "1|conv-immutable");

        assertEquals(before, rowTexts(rows));
        assertEquals(idsBefore, ids(rows));
    }

    // ===================== 来源账本与覆盖关系 =====================

    @Test
    @DisplayName("来源账本保留被覆盖原文，但配对时跳过它们（摘要与原文不同时发送）")
    void ledgerKeepsCoveredOriginalsButSkipsThemWhenMatching() {
        ScriptedModel model = new ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(model.model, 2_000);
        List<MessageRecord> rows = history(6, 300, CURRENT);
        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-ledger");
        List<Long> coveredIds = compactor.lastHistorySummary().coveredIds();
        assertEquals(6, coveredIds.size());

        ContextSourceLedger ledger = new ContextSourceLedger("conv-ledger");
        ledger.addHistory(rows, CURRENT);
        ledger.markSummarized(coveredIds);
        ledger.absorb(messages, 0, null);

        assertEquals(6, ledger.summarizedCount());
        for (Long id : coveredIds) {
            assertNotNull(ledger.get("message:" + id), "被覆盖的原文仍登记在账本里");
            assertTrue(ledger.get("message:" + id).fullText().contains("第"),
                    "账本正文不改写，原文仍可追溯");
            assertFalse(ledger.get("message:" + id).sentToModel(), "被覆盖的原文没有发出去");
        }
        assertTrue(ledger.match(messages).stream().noneMatch(item -> ledger.summarized(item.databaseId())),
                "投影配对不得再命中被摘要覆盖的原文");
        assertTrue(ledger.match(messages).stream().anyMatch(item -> "system:history-summary".equals(item.id())),
                "摘要本身作为一条系统消息进入配对");
    }

    @Test
    @DisplayName("正文重复时不会把最近消息错配到已被摘要覆盖的旧消息上")
    void duplicateTextIsNotMatchedToSummarizedOriginal() {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-dup");
        List<MessageRecord> rows = List.of(
                row(1, "assistant", "完全相同的回复"),
                row(2, "assistant", "完全相同的回复"));
        ledger.addHistory(rows, "x");
        ledger.markSummarized(List.of(1L));

        List<ContextSourceLedger.Item> matched = ledger.match(
                List.of(new AssistantMessage("完全相同的回复")));

        assertEquals(1, matched.size());
        assertEquals(Long.valueOf(2), matched.get(0).databaseId(),
                "配对必须落在没被摘要覆盖的那一条上");
    }

    // ===================== helpers =====================

    /** 按剧本返回摘要的假模型：记录调用次数与收到的正文，可选失败或带 usage。 */
    static final class ScriptedModel {
        final OpenAiChatModel model = mock(OpenAiChatModel.class);
        final AtomicInteger calls = new AtomicInteger();
        final List<String> prompts = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String answer = "摘要：用户要求把 CHANGE.txt 改成 LATEST，已确认冻结标记不能动。";
        volatile boolean fail;
        volatile Integer inputTokens;
        volatile Integer outputTokens;

        ScriptedModel() {
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                calls.incrementAndGet();
                prompts.add(prompt.getInstructions().isEmpty() ? "" : prompt.getInstructions().get(0).getText());
                if (fail) throw new IllegalStateException("摘要模型不可用");
                Generation generation = new Generation(new AssistantMessage(answer));
                if (inputTokens == null) return new ChatResponse(List.of(generation));
                return new ChatResponse(List.of(generation), ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(inputTokens, outputTokens)).build());
            });
        }
    }

    /** 生成 turns 轮 user/assistant 历史 + 末条本轮用户消息（生产惯例）。 */
    static List<MessageRecord> history(int turns, int chars, String current) {
        List<MessageRecord> rows = new ArrayList<>();
        long id = 10;
        for (int turn = 0; turn < turns; turn++) {
            rows.add(row(id++, "user", "第" + turn + "轮用户约束：" + "约".repeat(chars)));
            rows.add(row(id++, "assistant", "第" + turn + "轮助手结论：" + "答".repeat(chars)));
        }
        rows.add(row(id, "user", current));
        return rows;
    }

    static MessageRecord row(long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(id * 1000));
        return record;
    }

    static List<String> rowTexts(List<MessageRecord> rows) {
        List<String> out = new ArrayList<>();
        for (MessageRecord row : rows) out.add(row.getContent());
        return out;
    }

    static List<String> messageTexts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message message : messages) out.add(message.getText() == null ? "" : message.getText());
        return out;
    }

    static List<Long> ids(List<MessageRecord> rows) {
        List<Long> out = new ArrayList<>();
        for (MessageRecord row : rows) out.add(row.getId());
        return out;
    }

    static String joined(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message message : messages) out.append(message.getText()).append('\n');
        return out.toString();
    }

    static int countUser(List<Message> messages, String text) {
        int n = 0;
        for (Message message : messages) {
            if (message instanceof UserMessage && text.equals(message.getText())) n++;
        }
        return n;
    }
}
