package com.example.jingjingai2.context;

import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.agent.turn.TurnState;
import com.example.jingjingai2.controller.ChatController;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import com.example.jingjingai2.mapper.PromptSnapshotMapper;
import com.example.jingjingai2.utils.PlanInjectionMessage;
import com.example.jingjingai2.utils.UsageTracker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0A、0B、0C 的组合回归。不启动 ChatController 的完整对话入口：
 * 那个构造器要三十多个协作对象，现有测试也只反射调用单个私有方法。
 */
class LongSessionRegressionTest {

    @Test
    void planIdentitySurvivesInsertsDigestAndSummary() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("规则"));
        messages.add(new UserMessage("做多步任务"));
        messages.add(new PlanInjectionMessage("plan-a", "计划A"));

        assertTrue(PlanInjectionMessage.remove(messages, "plan-a"));
        messages.add(new PlanInjectionMessage("plan-b", "计划B"));
        assertEquals(1, planCount(messages));
        assertEquals("规则", ((SystemMessage) messages.get(0)).getText());

        messages.add(1, new SystemMessage("后插入的标记"));
        assertTrue(PlanInjectionMessage.remove(messages, "plan-b"));
        messages.add(new PlanInjectionMessage("plan-c", "计划C"));
        assertEquals(-1, PlanInjectionMessage.indexOf(messages, "plan-b"));
        assertTrue(PlanInjectionMessage.indexOf(messages, "plan-c") >= 0);

        List<Message> digested = compactionFixture("plan-c");
        RoundCompactor.apply(digested, new org.springframework.ai.tool.ToolCallback[0], 0,
                40, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("不该用", 1, 1, false, false), "digest");
        assertTrue(digested.stream().anyMatch(m -> textStarts(m, "[压缩索引] ")));
        assertTrue(PlanInjectionMessage.indexOf(digested, "plan-c") >= 0);
        assertTrue(PlanInjectionMessage.remove(digested, "plan-c"));
        assertFalse(digested.stream().anyMatch(m -> "计划C".equals(m.getText())));

        List<Message> summarized = compactionFixture("plan-c");
        int sizeBefore = summarized.size();
        RoundCompactor.apply(summarized, new org.springframework.ai.tool.ToolCallback[0], 0,
                80, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("摘要", 0, 0, false, false), "summary");
        assertTrue(summarized.stream().anyMatch(m -> textStarts(m, "[历史摘要]")));
        assertFalse(PlanInjectionMessage.remove(summarized, "plan-c"));
        assertTrue(summarized.stream().anyMatch(m -> m instanceof UserMessage && "还在".equals(m.getText())));
        summarized.add(new PlanInjectionMessage("plan-d", "计划D"));
        assertEquals(1, planCount(summarized));
        assertTrue(summarized.size() >= sizeBefore - 2);
    }

    @Test
    void toolGroupsStayValidThroughCompactionAndASplitIsRefusedWithoutMutation() throws Exception {
        List<Message> paired = toolSession();
        assertTrue(MessageProtocolValidator.check(paired).ok());

        RoundCompactor.apply(paired, new org.springframework.ai.tool.ToolCallback[0], 0,
                30, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("摘要", 1, 1, false, false), "digest");
        MessageProtocolValidator.Result digested = MessageProtocolValidator.check(paired);
        assertTrue(digested.ok(), digested.summary());

        List<Message> summarized = toolSession();
        RoundCompactor.apply(summarized, new org.springframework.ai.tool.ToolCallback[0], 0,
                40, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("摘要", 1, 1, false, false), "summary");
        assertTrue(MessageProtocolValidator.check(summarized).ok());

        List<Message> broken = new ArrayList<>(toolSession());
        Message removed = broken.remove(broken.size() - 1);
        List<Message> snapshot = new ArrayList<>(broken);
        assertFalse(MessageProtocolValidator.check(broken).ok());
        assertTrue(controllerRefuses(broken));
        assertEquals(snapshot, broken);
        assertTrue(removed instanceof ToolResponseMessage);

        broken.add(removed);
        assertTrue(MessageProtocolValidator.check(broken).ok());
        assertFalse(controllerRefuses(broken));
    }

    @Test
    void summaryIsBookedOnceAndDoesNotPolluteTheNextMainTurn() {
        List<AgentTurnRecord> rows = new ArrayList<>();
        AgentTurnRecorder recorder = new AgentTurnRecorder(new AgentTurnMapper() {
            @Override public void insert(AgentTurnRecord record) { rows.add(record); }
            @Override public Integer latestPendingTurnIndex(String conversationId) { return null; }
        }, (sha, kind, content) -> {});
        UsageTracker tracker = new UsageTracker();

        ContextCompactor.SummaryUsage first = ContextCompactor.interpretUsage("摘要提示", "摘要", 100, 8, null);
        ContextCompactor.SummaryUsage second = ContextCompactor.interpretUsage("摘要提示", "摘要", 100, 8, 0);
        book(recorder, tracker, first, 0);
        book(recorder, tracker, second, 1);
        assertEquals(2, tracker.getResponsesWithUsage());
        assertEquals(200, tracker.getPromptTokens());
        assertTrue(tracker.cacheFieldSeen());
        assertEquals(0, tracker.getCachedPromptTokens());
        assertEquals(2, rows.stream().filter(r -> TurnState.PHASE_COMPACTION.equals(r.phase())).count());

        long promptBefore = tracker.getPromptTokens();
        int responsesBefore = tracker.getResponsesWithUsage();
        tracker.accept(responseOf(100, 9));
        assertEquals(responsesBefore + 1, tracker.getResponsesWithUsage());
        assertEquals(promptBefore + 100, tracker.getPromptTokens());
        int mainInput = (int) (tracker.getPromptTokens() - promptBefore);
        rows.add(new AgentTurnRecord("c", 1L, 2, TurnState.PHASE_MODEL_TURN, "m",
                null, null, null, TurnState.COMPACT_NONE, null, null, 0, mainInput, 9,
                null, null, null, null, null, AgentTurnRecord.END_NORMAL_STOP));
        long compaction = rows.stream().filter(r -> TurnState.PHASE_COMPACTION.equals(r.phase()))
                .mapToLong(r -> r.inputTokens() + r.outputTokens()).sum();
        long modelTurn = rows.stream().filter(r -> TurnState.PHASE_MODEL_TURN.equals(r.phase()))
                .mapToLong(r -> r.inputTokens() + r.outputTokens()).sum();
        assertEquals(216, compaction);
        assertEquals(109, modelTurn);

        ContextCompactor.SummaryUsage unknown = ContextCompactor.interpretUsage("提示", "摘要", null, null);
        assertTrue(unknown.estimated());
        assertNull(unknown.cachedPromptTokens());
        int responses = tracker.getResponsesWithUsage();
        tracker.acceptMeasured(0, 0, null);
        assertEquals(responses, tracker.getResponsesWithUsage());
    }

    @Test
    void longSessionKeepsPlanToolPairsAndStopsBeforeABrokenRequest() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("规则"));
        messages.add(new UserMessage("做多步任务"));
        messages.add(new PlanInjectionMessage("plan-1", "计划一"));
        messages.add(call("call_1", "readFile"));
        messages.add(response("call_1", "文件一"));
        assertTrue(PlanInjectionMessage.remove(messages, "plan-1"));
        messages.add(new PlanInjectionMessage("plan-2", "计划二"));
        messages.add(1, new SystemMessage("历史标记"));
        messages.add(call("call_2", "grepFile"));
        messages.add(response("call_2", "计".repeat(500)));

        RoundCompactor.apply(messages, new org.springframework.ai.tool.ToolCallback[0], 0,
                40, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("不该用", 1, 1, false, false), "digest");
        assertTrue(MessageProtocolValidator.check(messages).ok());
        assertEquals(1, planCount(messages));

        int modelCalls = 0;
        if (!controllerRefuses(messages)) modelCalls++;
        assertEquals(1, modelCalls);

        messages.add(call("call_3", "readFile"));
        List<Message> beforeRefuse = new ArrayList<>(messages);
        assertTrue(controllerRefuses(messages));
        assertEquals(beforeRefuse, messages);
        assertEquals(1, modelCalls);

        messages.add(response("call_3", "补上的结果"));
        if (!controllerRefuses(messages)) modelCalls++;
        assertEquals(2, modelCalls);
        assertTrue(messages.stream().anyMatch(m -> m instanceof UserMessage && "做多步任务".equals(m.getText())));
    }

    private static void book(AgentTurnRecorder recorder, UsageTracker tracker,
                              ContextCompactor.SummaryUsage usage, int turn) {
        recorder.recordCompaction("c", 1L, turn, "deepseek", 0, 0, 1, usage, null, "SUMMARY");
        if (!usage.estimated() && !usage.fallback()) {
            tracker.acceptMeasured(usage.inputTokens(), usage.outputTokens(), usage.cachedPromptTokens());
        }
    }

    private static boolean controllerRefuses(List<Message> messages) throws Exception {
        Method method = ChatController.class.getDeclaredMethod("refuseBrokenProtocol", List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, messages);
    }

    private static List<Message> compactionFixture(String planId) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("规则"));
        messages.add(new UserMessage("还在"));
        messages.add(new PlanInjectionMessage(planId, "计".repeat(800)));
        return messages;
    }

    private static List<Message> toolSession() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("规则"));
        messages.add(new UserMessage("还在"));
        messages.add(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"),
                new AssistantMessage.ToolCall("call_2", "function", "grepFile", "{}"))));
        messages.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call_2", "grepFile", "计".repeat(400)),
                new ToolResponseMessage.ToolResponse("call_1", "readFile", "文件"))));
        return messages;
    }

    private static AssistantMessage call(String id, String name) {
        return new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(id, "function", name, "{}")));
    }

    private static ToolResponseMessage response(String id, String data) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, "tool", data)));
    }

    private static ChatResponse responseOf(int prompt, int completion) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, completion, prompt + completion)).build());
    }

    private static boolean textStarts(Message message, String prefix) {
        return message.getText() != null && message.getText().startsWith(prefix);
    }

    private static long planCount(List<Message> messages) {
        return messages.stream().filter(m -> m instanceof PlanInjectionMessage).count();
    }
}
