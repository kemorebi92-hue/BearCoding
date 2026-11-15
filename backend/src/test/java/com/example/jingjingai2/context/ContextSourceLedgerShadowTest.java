package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSourceLedgerShadowTest {

    @Test
    @DisplayName("相同正文的历史仍用数据库 id 区分，压缩不改账本")
    void historyIdentitySurvivesCompaction() {
        MessageRecord first = row(11L, "user", "同样的正文");
        MessageRecord second = row(12L, "assistant", "同样的正文");
        ContextSourceLedger ledger = new ContextSourceLedger("c1");
        ledger.addHistory(List.of(first, second), "当前问题");

        assertEquals("message:11", ledger.get("message:11").id());
        assertEquals("message:12", ledger.get("message:12").id());
        assertNotEquals(ledger.get("message:11").id(), ledger.get("message:12").id());
        assertEquals("同样的正文", ledger.get("message:11").fullText());

        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("同样的正文"), new AssistantMessage("同样的正文"), new UserMessage("当前问题")));
        messages.set(0, new SystemMessage("[历史摘要] 被压缩"));
        assertEquals("同样的正文", ledger.get("message:11").fullText(), "生产消息被改写后账本原文不变");
        assertFalse(ledger.get("message:11").sentToModel());
    }

    @Test
    @DisplayName("工具结果按 toolCallId 区分版本和可恢复性")
    void toolIdentityVersionAndRecovery() {
        ContextSourceLedger ledger = new ContextSourceLedger("c1");
        List<Message> messages = List.of(
                assistantCall("call-old", "readFile", "{\"path\":\"notes/spec.txt\"}"),
                toolResult("call-old", "readFile", "旧正文"),
                assistantCall("call-new", "readFile", "{\"path\":\"notes/spec.txt\"}"),
                toolResult("call-new", "readFile", "新正文"));
        ledger.absorb(messages, 1, null);
        ledger.markSent(messages, 1);

        ContextSourceLedger.Item oldItem = ledger.get("tool:call-old");
        ContextSourceLedger.Item newItem = ledger.get("tool:call-new");
        assertNotEquals(oldItem.version(), newItem.version(), "同一路径不同内容必须是不同版本");
        assertEquals(ContextSourceLedger.Recoverability.NOT_RECOVERABLE, oldItem.recoverability());
        assertTrue(oldItem.sentToModel());
        assertEquals(1, oldItem.lastSentRound());

        String signed = "预览" + ToolArtifactRef.marker("D:/snap/out.txt", 8, "executeCommand", "a".repeat(64));
        ledger.absorb(List.of(toolResult("call-snap", "executeCommand", signed)), 2, null);
        assertEquals(ContextSourceLedger.Recoverability.RECOVERABLE, ledger.get("tool:call-snap").recoverability());
        assertEquals("a".repeat(64), ledger.get("tool:call-snap").contentHash());
    }

    @Test
    @DisplayName("影子模式不改消息和工具，异常也退回原文")
    void shadowDoesNotChangePrompt() {
        ContextSourceLedger ledger = new ContextSourceLedger("c1");
        MessageRecord old = row(7L, "user", "y".repeat(4000));
        MessageRecord recent = row(8L, "assistant", "最近回复");
        ledger.addHistory(List.of(old, recent), "当前");
        List<Message> actual = new ArrayList<>(List.of(
                new SystemMessage("系统"), new UserMessage("y".repeat(4000)),
                new AssistantMessage("最近回复"), new UserMessage("当前")));
        ledger.absorb(actual, 0, null);
        List<Message> snapshot = List.copyOf(actual);
        ContextProjectionPolicy.Result result = ContextProjectionPolicy.project(
                ledger, actual, null, 0, 100, 1, 0, true, false);
        assertNull(result.error());
        assertEquals(snapshot, result.messages());
        assertEquals(snapshot, actual);
        assertTrue(result.decisions().stream().anyMatch(d -> "USER_MESSAGE".equals(d.reason())),
                "超预算的用户消息仍然保留");

        ContextProjectionPolicy.Result off = ContextProjectionPolicy.project(
                ledger, actual, null, 0, 100, 1, 0, false, false);
        assertTrue(off.decisions().isEmpty(), "两个开关都关闭时不做分类");

        ContextProjectionPolicy.Result broken = ContextProjectionPolicy.project(
                null, actual, null, 0, 100, 1, 0, true, false);
        assertEquals(actual, broken.messages());
        assertNotNull(broken.error());
    }

    @Test
    @DisplayName("非流式和流式使用同一投影口径，最终交付也有记录")
    void channelsAndDeliveryShareProjection() throws Exception {
        List<Message> actual = List.of(new SystemMessage("系统"), new UserMessage("当前"),
                toolResult("call-1", "readFile", "x".repeat(5000)));
        ContextProjectionPolicy.Result sync = classify(actual, false);
        ContextProjectionPolicy.Result stream = classify(actual, true);
        assertEquals(sync.decisions(), stream.decisions());
        assertEquals(sync.stats(), stream.stats());

        Path output = Files.createTempFile("context-observation", ".jsonl");
        ContextObservationLog log = new ContextObservationLog(output);
        log.add(Map.of("delivery", true, "round", 4, "actualPrefixHash", "abc"));
        assertEquals(1, log.records().size());
        assertTrue(Files.readString(output).contains("\"delivery\":true"));
        assertFalse(Files.readString(output).contains("系统"), "观测文件不保存 Prompt 明文");
        Files.deleteIfExists(output);
    }

    @Test
    @DisplayName("前缀哈希、首次变化和分类估算能够解释")
    void prefixAndStatsAreExplainable() {
        List<Message> first = List.of(new SystemMessage("系统"), new UserMessage("当前"));
        List<Message> appended = new ArrayList<>(first);
        appended.add(new AssistantMessage("追加"));
        ContextProjectionPolicy.PrefixStability stable = ContextProjectionPolicy.prefix(first, appended, List.of(), "tools", "tools");
        assertEquals(2, stable.actualFirstChange(), "只在尾部追加时，变化位置就是追加点");
        assertTrue(stable.actualTailAppendOnly());
        assertFalse(stable.toolSchemaChanged());

        List<Message> rewritten = List.of(new SystemMessage("系统被改"), new UserMessage("当前"), new AssistantMessage("追加"));
        ContextProjectionPolicy.PrefixStability changed = ContextProjectionPolicy.prefix(first, rewritten, List.of(), "tools", "tools-2");
        assertEquals(0, changed.actualFirstChange());
        assertTrue(changed.actualRewroteEarlier());
        assertTrue(changed.toolSchemaChanged());

        ContextSourceLedger ledger = new ContextSourceLedger("c1");
        ledger.addHistory(List.of(
                row(1L, "user", "h".repeat(8000)),
                row(2L, "assistant", "a".repeat(8000)),
                row(3L, "user", "第二问"),
                row(4L, "user", "第三问"),
                row(5L, "user", "第四问")), "当前");
        List<Message> actual = List.of(new SystemMessage("系统"), new UserMessage("h".repeat(8000)),
                new AssistantMessage("a".repeat(8000)), new UserMessage("第二问"),
                new UserMessage("第三问"), new UserMessage("第四问"), new UserMessage("当前"));
        ledger.absorb(actual, 0, null);
        ContextProjectionPolicy.Result result = ContextProjectionPolicy.project(ledger, actual, null, 0, 10, 1, 0, true, false);
        ContextProjectionPolicy.Stats stats = result.stats();
        int categories = stats.fixedTokensEst() + stats.toolSchemaTokensEst() + stats.historyTokensEst()
                + stats.activeTaskTokensEst() + stats.userMemoryTokensEst() + stats.workspaceMemoryTokensEst()
                + stats.planTokensEst() + stats.toolResultTokensEst();
        assertEquals(stats.totalPromptTokensEst(), categories, "分类之和必须解释总估算");
        int decisions = stats.shadowKeepTokensEst() + stats.shadowRetireTokensEst() + stats.shadowDiscardTokensEst();
        assertEquals(stats.shadowKeepTokensEst() + stats.shadowRetireTokensEst() + stats.shadowDiscardTokensEst(), decisions);
        assertTrue(stats.shadowRetireTokensEst() > 0);
    }

    @Test
    @DisplayName("协议校验在影子投影前后不变")
    void protocolResultStaysSame() {
        List<Message> messages = List.of(
                assistantCall("call-1", "readFile", "{\"path\":\"a.txt\"}"),
                toolResult("call-1", "readFile", "正文"));
        MessageProtocolValidator.Result before = MessageProtocolValidator.check(messages);
        ContextSourceLedger ledger = new ContextSourceLedger("c1");
        ledger.absorb(messages, 0, planWithDoneStep());
        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, messages, planWithDoneStep(), 1, 0, 100, 0, true, false);
        assertEquals(before.ok(), MessageProtocolValidator.check(projected.messages()).ok());
        assertTrue(before.ok());
    }

    private static ContextProjectionPolicy.Result classify(List<Message> actual, boolean streaming) {
        ContextSourceLedger ledger = new ContextSourceLedger(streaming ? "stream" : "sync");
        ledger.absorb(actual, 0, null);
        ledger.markSent(actual, 0);
        return ContextProjectionPolicy.project(ledger, actual, null, streaming ? 2 : 2, 0, 24000, 0, true, false);
    }

    private static TaskPlan planWithDoneStep() {
        TaskPlan plan = new TaskPlan();
        PlanStep step = new PlanStep("s1", "读取 spec.txt", "读完");
        step.setStatus(PlanStep.DONE);
        plan.setSteps(List.of(step));
        return plan;
    }

    private static MessageRecord row(Long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(0));
        return record;
    }

    private static AssistantMessage assistantCall(String id, String name, String arguments) {
        return new AssistantMessage("", Map.of(), List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)));
    }

    private static ToolResponseMessage toolResult(String id, String name, String body) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, name, body)));
    }
}
