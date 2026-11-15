package com.example.jingjingai2.context;

import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.agent.turn.TurnState;
import com.example.jingjingai2.eval.TurnTokenTotals;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import com.example.jingjingai2.mapper.PromptSnapshotMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 假模型按剧本逐轮返回。工具结果做大，让压缩发生在循环中途。
 */
class ScriptedChatModelTest {

    /** 按剧本返回助手消息，并记下每次收到的列表。 */
    static final class ScriptedChatModel {
        private final List<AssistantMessage> script = new ArrayList<>();
        private final List<List<Message>> seen = new ArrayList<>();
        private int cursor;

        ScriptedChatModel then(AssistantMessage message) {
            script.add(message);
            return this;
        }

        AssistantMessage call(List<Message> messages) {
            seen.add(List.copyOf(messages));
            AssistantMessage next = script.get(Math.min(cursor, script.size() - 1));
            cursor++;
            return next;
        }
    }

    @Test
    @DisplayName("压缩发生在循环中途，工具消息整组保留，下一次调用拿到压缩后的列表")
    void compactsMidLoopWithoutSplittingToolGroups() {
        String fixed = "固".repeat(2000);
        String big = "果".repeat(6000);
        ScriptedChatModel model = new ScriptedChatModel()
                .then(toolCall("a1", "a2"))
                .then(toolCall("b1", "b2"))
                .then(new AssistantMessage("做完了", Map.of(), List.of()));
        List<AgentTurnRecord> rows = new ArrayList<>();
        AgentTurnRecorder recorder = recorder(rows);
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage(fixed), new UserMessage("开始")));
        RoundCompactor.Guard guard = new RoundCompactor.Guard();
        int compactedRound = -1;
        for (int round = 0; round < 6; round++) {
            RoundCompactor.Outcome outcome = RoundCompactor.apply(
                    messages, null, round, 20000, 3, 0.30, 1000, guard,
                    older -> new RoundCompactor.Summary("固定摘要", 12, 4, false, false));
            if (outcome.acted()) {
                recorder.recordCompaction("c", 1L, round, "scripted",
                        outcome.decision().value(), outcome.after(), outcome.compactedCount(),
                        new com.example.jingjingai2.context.ContextCompactor.SummaryUsage(
                                outcome.summary().text(), outcome.summary().inputTokens(),
                                outcome.summary().outputTokens(), false, false),
                        outcome.decision().reason());
            }
            AssistantMessage reply = model.call(messages);
            recorderLikeTurn(rows, round, reply.getToolCalls() == null || reply.getToolCalls().isEmpty()
                    ? AgentTurnRecord.END_NORMAL_STOP : AgentTurnRecord.END_TOOL_CALLING);
            if (outcome.compacted()) compactedRound = round;
            if (reply.getToolCalls() == null || reply.getToolCalls().isEmpty()) break;
            messages.add(reply);
            for (AssistantMessage.ToolCall call : reply.getToolCalls()) {
                messages.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(call.id(), call.name(), big))));
            }
        }
        assertTrue(compactedRound > 0, "应在循环中途压缩，实际轮 " + compactedRound);
        List<Message> seenAfter = model.seen.get(compactedRound);
        assertSameStructure(messages, seenAfter);
        assertGroupsIntact(seenAfter);
        assertTrue(rows.stream().anyMatch(r -> TurnState.PHASE_COMPACTION.equals(r.phase())
                && AgentTurnRecord.END_COMPACTION.equals(r.endReason())));
        List<Map<String, Object>> exported = new ArrayList<>();
        for (AgentTurnRecord row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("phase", row.phase());
            map.put("inputTokens", row.inputTokens());
            map.put("outputTokens", row.outputTokens());
            exported.add(map);
        }
        TurnTokenTotals.Result totals = TurnTokenTotals.of(exported);
        assertEquals(0, totals.totalTokens());
        assertEquals(16, totals.compactionTokens());
        assertEquals(16, totals.grandTotalTokens());
    }

    @Test
    @DisplayName("压完仍超阈值只记一次无效，工具消息组不被拆开")
    void ineffectiveDoesNotRetryOrSplit() {
        AssistantMessage call = toolCall("a1", "a2");
        String big = "果".repeat(4000);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("固".repeat(2000)));
        messages.add(call);
        messages.add(toolResult("a1", big));
        messages.add(toolResult("a2", big));
        RoundCompactor.Guard guard = new RoundCompactor.Guard();
        RoundCompactor.Outcome once = RoundCompactor.apply(messages, null, 2, 5000, 3, 0.30, 100, guard,
                older -> new RoundCompactor.Summary("长".repeat(8000), 1, 1, false, false));
        assertEquals("COMPACTION_INEFFECTIVE", once.decision().reason());
        assertGroupsIntact(messages);
        RoundCompactor.Outcome again = RoundCompactor.apply(messages, null, 6, 5000, 3, 0.30, 100, guard,
                older -> new RoundCompactor.Summary("不该再压", 1, 1, false, false));
        assertFalse(again.decision().trigger());
        assertEquals("COMPACTION_INEFFECTIVE", again.decision().reason());
        assertFalse(again.acted());
    }

    private static void assertSameStructure(List<Message> left, List<Message> right) {
        assertEquals(left.size(), right.size());
        for (int i = 0; i < left.size(); i++) {
            assertEquals(left.get(i).getClass(), right.get(i).getClass());
        }
    }

    private static void assertGroupsIntact(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) {
                assertTrue(i > 0, "工具结果不能出现在列表开头");
                Message previous = messages.get(i - 1);
                assertTrue(previous instanceof AssistantMessage || previous instanceof ToolResponseMessage,
                        "孤立的工具结果");
                continue;
            }
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
                continue;
            }
            int expected = assistant.getToolCalls().size();
            int got = 0;
            for (int j = i + 1; j < messages.size() && messages.get(j) instanceof ToolResponseMessage; j++) {
                got += ((ToolResponseMessage) messages.get(j)).getResponses().size();
            }
            assertEquals(expected, got, "工具调用和结果没有成组");
        }
    }

    private static AssistantMessage toolCall(String first, String second) {
        return new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(first, "function", "readFile", "{}"),
                new AssistantMessage.ToolCall(second, "function", "readFile", "{}")));
    }

    private static ToolResponseMessage toolResult(String id, String body) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, "readFile", body)));
    }

    private static AgentTurnRecorder recorder(List<AgentTurnRecord> rows) {
        AgentTurnMapper turns = new AgentTurnMapper() {
            @Override public void insert(AgentTurnRecord record) { rows.add(record); }
            @Override public Integer latestPendingTurnIndex(String conversationId) { return null; }
        };
        PromptSnapshotMapper snaps = (sha, kind, content) -> {};
        return new AgentTurnRecorder(turns, snaps);
    }

    /** 循环轮次本身不带 token，用来证明 totalTokens 不含摘要行。 */
    private static void recorderLikeTurn(List<AgentTurnRecord> rows, int round, String end) {
        rows.add(new AgentTurnRecord("c", 1L, round, TurnState.PHASE_MODEL_TURN, "scripted",
                null, null, null, TurnState.COMPACT_NONE,
                null, null, 0, null, null, null, null, null, null, null, end));
    }
}
