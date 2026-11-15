package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 短笔记 + 重派计数：**会话级、纯内存、结论一到就释放**。
 *
 * 最要紧的一条是 {@link SubagentMemory#normalize}：模型每次措辞都略有不同，
 * 不做归一化，"同一个目标"就会被当成新目标，重派闸门形同虚设。
 */
class SubagentMemoryTest {

    private static final String CONV = "conv-1";

    private final SubagentMemory memory = new SubagentMemory();

    private static SubagentResult ok(String goal) {
        return SubagentResult.success(goal, SubagentSpec.Mode.EXPLORE, "结论", 2, 8, 5, java.util.Set.of());
    }

    @Test
    @DisplayName("normalize：去空白、压空格、转小写、去尾部标点（所以大小写/标点不同的同一个目标能对上）")
    void normalizeCollapsesVariants() {
        String a = SubagentMemory.normalize("调研 Plan 包。");
        assertEquals(SubagentMemory.normalize("  调研   plan 包"), a);
        assertEquals(SubagentMemory.normalize("调研 Plan 包！"), a);
        assertEquals(SubagentMemory.normalize("调研 Plan 包；"), a);
        assertNotEquals(a, SubagentMemory.normalize("调研 plan 包的其他东西"));
    }

    @Test
    @DisplayName("normalize 对 null / 空串安全")
    void normalizeHandlesNullAndEmpty() {
        assertEquals("", SubagentMemory.normalize(null));
        assertEquals("", SubagentMemory.normalize("   "));
        assertEquals("", SubagentMemory.normalize("。。。"));
    }

    @Test
    @DisplayName("派发计数：按会话 + 规范化 goal 累加，未派过返回 0")
    void countsAttemptsPerGoalAndConversation() {
        assertEquals(0, memory.attemptsOf(CONV, "g"));
        assertEquals(1, memory.recordAttempt(CONV, "g"));
        assertEquals(2, memory.recordAttempt(CONV, "g"));
        assertEquals(2, memory.attemptsOf(CONV, "g"));
        assertEquals(0, memory.attemptsOf(CONV, "别的目标"), "不同目标各算各的");
        assertEquals(0, memory.attemptsOf("别的会话", "g"), "不同会话各算各的");
    }

    @Test
    @DisplayName("笔记：同 key 覆盖，可读回")
    void remembersAndReadsBackNotes() {
        assertNull(memory.noteOf(CONV, "g"), "还没派过 → 没有笔记");
        memory.remember(CONV, "g", LastRunNote.of(ok("g"), 800));
        assertNotNull(memory.noteOf(CONV, "g"));
        assertEquals(1, memory.noteCount(CONV));

        memory.remember(CONV, "g", LastRunNote.of(
                SubagentResult.roundsExhausted("g", SubagentSpec.Mode.EXPLORE, "卡在 X",
                        8, 8, 3, java.util.Set.of(), "轮数用尽"), 800));
        assertEquals(1, memory.noteCount(CONV), "同 key 覆盖，不该堆两条");
        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED,
                memory.noteOf(CONV, "g").failureReason());
    }

    @Test
    @DisplayName("释放：笔记与计数一起清掉（新任务 / 结论出来时调用）")
    void releaseClearsNotesAndCounts() {
        memory.remember(CONV, "g", LastRunNote.of(ok("g"), 800));
        memory.recordAttempt(CONV, "g");
        assertTrue(memory.conversations().contains(CONV));

        memory.releaseConversation(CONV);

        assertEquals(0, memory.noteCount(CONV));
        assertEquals(0, memory.attemptsOf(CONV, "g"), "计数也要清 —— 否则下一轮新任务直接撞上限");
        assertFalse(memory.conversations().contains(CONV));
        assertNull(memory.noteOf(CONV, "g"));
    }

    @Test
    @DisplayName("释放只影响指定会话，不误伤别人")
    void releaseIsScopedToConversation() {
        memory.remember("c1", "g", LastRunNote.of(ok("g"), 800));
        memory.remember("c2", "g", LastRunNote.of(ok("g"), 800));

        memory.releaseConversation("c1");

        assertEquals(0, memory.noteCount("c1"));
        assertEquals(1, memory.noteCount("c2"));
    }

    @Test
    @DisplayName("null 参数不抛异常（会话 id 缺失时不该把整轮对话搞崩）")
    void nullSafe() {
        assertNull(memory.noteOf(null, "g"));
        assertNull(memory.noteOf(CONV, null));
        assertEquals(0, memory.attemptsOf(null, null));
        assertEquals(0, memory.recordAttempt(null, null));
        memory.remember(null, null, null);
        memory.releaseConversation(null);
        assertEquals(0, memory.noteCount(null));
    }
}
