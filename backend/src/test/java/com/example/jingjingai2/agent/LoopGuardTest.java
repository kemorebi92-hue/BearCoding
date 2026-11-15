package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoopGuardTest {

    @Test
    @DisplayName("同一组参数连续第三次给提示，提示之后再重复才停")
    void hintThenStop() {
        LoopGuard.State state = new LoopGuard.State();
        String sig = LoopGuard.signature(List.of(
                new AssistantMessage.ToolCall("1", "function", "mcp__pw__browser_navigate", "{\"url\":\"https://example.com\"}")));

        LoopGuard.Decision first = LoopGuard.observe(state, sig);
        LoopGuard.apply(state, first);
        assertEquals(LoopGuard.Action.NONE, first.action);

        LoopGuard.Decision second = LoopGuard.observe(state, sig);
        LoopGuard.apply(state, second);
        assertEquals(LoopGuard.Action.NONE, second.action);

        LoopGuard.Decision third = LoopGuard.observe(state, sig);
        LoopGuard.apply(state, third);
        assertEquals(LoopGuard.Action.HINT, third.action);
        assertTrue(third.hinted);

        LoopGuard.Decision fourth = LoopGuard.observe(state, sig);
        LoopGuard.apply(state, fourth);
        assertEquals(LoopGuard.Action.STOP, fourth.action);
    }

    @Test
    @DisplayName("换一组参数就重新计数")
    void differentSignatureResets() {
        LoopGuard.State state = new LoopGuard.State();
        LoopGuard.apply(state, LoopGuard.observe(state, "a|1;;"));
        LoopGuard.apply(state, LoopGuard.observe(state, "a|1;;"));
        LoopGuard.Decision changed = LoopGuard.observe(state, "b|2;;");
        assertEquals(LoopGuard.Action.NONE, changed.action);
        assertEquals(0, changed.streak);
    }

    @Test
    @DisplayName("累计输入达到预算才拦住，0 表示关掉")
    void inputBudget() {
        assertFalse(LoopGuard.overInputBudget(1999, 2000));
        assertTrue(LoopGuard.overInputBudget(2000, 2000));
        assertFalse(LoopGuard.overInputBudget(9_000_000, 0));
        assertTrue(LoopGuard.inputBudgetNote(2000, 2000).contains("不要再调用工具"));
    }
}
