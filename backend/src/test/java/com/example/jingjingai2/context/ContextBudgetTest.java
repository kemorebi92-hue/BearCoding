package com.example.jingjingai2.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetTest {

    @Test
    @DisplayName("有窗口时只看总输入是否超过 65%，历史单独超过预算也不压")
    void windowPercentNotHistoryAlone() {
        assertFalse(ContextBudget.shouldCompact(30_000, 5_000, 1_000_000, 65, 24_000));
        assertTrue(ContextBudget.shouldCompact(600_000, 60_000, 1_000_000, 65, 24_000));
        assertFalse(ContextBudget.shouldCompact(650_000, 0, 1_000_000, 65, 24_000));
    }

    @Test
    @DisplayName("窗口未知时退回历史预算")
    void unknownWindowUsesHistoryBudget() {
        assertFalse(ContextBudget.shouldCompact(24_000, 0, 0, 65, 24_000));
        assertTrue(ContextBudget.shouldCompact(24_001, 0, 0, 65, 24_000));
    }

    @Test
    @DisplayName("历史和固定开销分开：系统提示与工具结果不混在一起")
    void splitsHistoryAndFixed() {
        List<Message> messages = List.of(
                new SystemMessage("系统"),
                new UserMessage("问题"),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("1", "mcp__pw__browser_snapshot", "x".repeat(400)))));
        long history = ContextBudget.historyTokens(messages);
        long fixed = ContextBudget.fixedTokens(messages, null);
        assertTrue(history > 0);
        assertTrue(fixed > 0);
        assertTrue(fixed < history);
    }

    @Test
    @DisplayName("没有可靠副本时不摘旧工具结果")
    void compactorKeepsUnrecoverableToolResults() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("开始"));
        messages.add(tool("1", "a".repeat(5000)));
        messages.add(tool("2", "b".repeat(5000)));
        messages.add(tool("3", "c".repeat(200)));
        ToolHistoryCompactor.Result result = ToolHistoryCompactor.compact(messages, 100);
        assertEquals(0, result.replaced());
        assertFalse(result.complete());
        String first = ((ToolResponseMessage) messages.get(1)).getResponses().get(0).responseData();
        assertEquals("a".repeat(5000), first);
    }

    private static ToolResponseMessage tool(String id, String data) {
        return new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(id, "mcp__pw__browser_snapshot", data)));
    }
}
