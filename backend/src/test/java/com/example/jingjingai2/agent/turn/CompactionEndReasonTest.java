package com.example.jingjingai2.agent.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactionEndReasonTest {

    @Test
    void fallbackWinsOverIneffective() {
        assertEquals(AgentTurnRecord.END_COMPACTION_FALLBACK,
                AgentTurnRecorder.compactionEnd(false, true, "COMPACTION_INEFFECTIVE", false));
        assertEquals(AgentTurnRecord.END_COMPACTION_INEFFECTIVE,
                AgentTurnRecorder.compactionEnd(false, false, "COMPACTION_INEFFECTIVE", false));
        assertEquals(AgentTurnRecord.END_COMPACTION,
                AgentTurnRecorder.compactionEnd(false, false, null, false));
    }
}
