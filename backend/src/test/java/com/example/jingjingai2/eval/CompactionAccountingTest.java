package com.example.jingjingai2.eval;

import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.agent.turn.TurnState;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import com.example.jingjingai2.mapper.PromptSnapshotMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompactionAccountingTest {

    @Test
    @DisplayName("摘要调用三种都落库，估算不是静默的 0")
    void compactionRowsRecordUsageKind() {
        List<AgentTurnRecord> rows = new ArrayList<>();
        AgentTurnMapper turns = new AgentTurnMapper() {
            @Override public void insert(AgentTurnRecord record) { rows.add(record); }
            @Override public Integer latestPendingTurnIndex(String conversationId) { return null; }
        };
        PromptSnapshotMapper snaps = (sha, kind, content) -> {};
        AgentTurnRecorder recorder = new AgentTurnRecorder(turns, snaps);

        recorder.recordCompaction("c", 1L, 2, "deepseek-v4-flash", 100, 40, 3,
                new ContextCompactor.SummaryUsage("摘要", 20, 5, false, false), null);
        recorder.recordCompaction("c", 1L, 3, "deepseek-v4-flash", 100, 40, 3,
                new ContextCompactor.SummaryUsage("摘要", 8, 2, true, false), null);
        recorder.recordCompaction("c", 1L, 4, "deepseek-v4-flash", 100, 90, 3,
                new ContextCompactor.SummaryUsage("", 7, 1, false, true), null);

        assertEquals(TurnState.PHASE_COMPACTION, rows.get(0).phase());
        assertEquals(AgentTurnRecord.END_COMPACTION, rows.get(0).endReason());
        assertEquals(20, rows.get(0).inputTokens());
        assertEquals(AgentTurnRecord.END_COMPACTION_ESTIMATED, rows.get(1).endReason());
        assertEquals(8, rows.get(1).inputTokens());
        assertEquals(AgentTurnRecord.END_COMPACTION_FALLBACK, rows.get(2).endReason());
        assertEquals(7, rows.get(2).inputTokens(), "失败但有真实用量时必须记账");
        assertEquals(1, rows.get(2).outputTokens());
        assertEquals(TurnState.COMPACT_NONE, rows.get(2).compactMode(), "失败不能记成压缩成功");
    }

    @Test
    @DisplayName("totalTokens 只含循环轮次，grand 才加上摘要")
    void exportKeepsLoopTotalAndAddsCompaction() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(turn("MODEL_TURN", 100, 10));
        rows.add(turn("COMPACTION", 20, 5));
        rows.add(turn("MODEL_TURN", 30, 4));
        TurnTokenTotals.Result totals = TurnTokenTotals.of(rows);
        assertEquals(144, totals.totalTokens());
        assertEquals(25, totals.compactionTokens());
        assertEquals(169, totals.grandTotalTokens());
    }

    private static Map<String, Object> turn(String phase, int in, int out) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        row.put("inputTokens", in);
        row.put("outputTokens", out);
        return row;
    }
}
