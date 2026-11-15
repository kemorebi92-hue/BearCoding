package com.example.jingjingai2.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用已经保存的导出重放判定器，对照事后重算。不改那些记录。 */
class HistoricalReplayTest {

    private static final Map<String, Integer> RECOVERY = Map.ofEntries(
            Map.entry("stage-3b-t11-2026-10-18/T11-summary-1", 4),
            Map.entry("stage-3b-t11-2026-10-18/T11-summary-2", 6),
            Map.entry("stage-3b-t11-2026-10-18/T11-summary-3", 1),
            Map.entry("stage-3b-t11-2026-10-18/T11-digest-1", 6),
            Map.entry("stage-3b-t11-2026-10-18/T11-digest-2", 0),
            Map.entry("stage-3b-t11-2026-10-18/T11-digest-3", 0),
            Map.entry("stage-3b-t11-2026-10-18/T11-digest-4", 1),
            Map.entry("stage-3b-t11-2026-10-18/T11-digest-5", 2),
            Map.entry("stage-3c-tiered-2026-10-18/T11-tiered-1", 0),
            Map.entry("stage-3c-tiered-2026-10-18/T11-tiered-2", 2));

    @TempDir Path tmp;

    @Test
    @DisplayName("历史 T11 记录的恢复次数和约束与重算一致")
    void replayMatchesTheRecompute() throws Exception {
        Path firstExport = Path.of("docs/eval/results", RECOVERY.keySet().iterator().next() + "-export.json");
        Assumptions.assumeTrue(Files.isRegularFile(firstExport), "历史跑数结果不进入 V3");
        ObjectMapper mapper = new ObjectMapper();
        List<Check> checks = Check.load(Path.of("docs/eval/tasks/T11.expect.json"));
        for (Map.Entry<String, Integer> expected : RECOVERY.entrySet()) {
            Path exportFile = Path.of("docs/eval/results", expected.getKey() + "-export.json");
            JsonNode export = mapper.readTree(exportFile.toFile());
            List<RecoveryCounter.Use> uses = new ArrayList<>();
            Integer first = null;
            for (JsonNode turn : export.get("turnRows")) {
                if (!"COMPACTION".equals(turn.path("phase").asText())) continue;
                int index = turn.path("turnIndex").asInt();
                if (first == null || index < first) first = index;
            }
            for (JsonNode row : export.get("logRows")) {
                uses.add(new RecoveryCounter.Use(
                        row.path("turnIndex").isMissingNode() || row.get("turnIndex").isNull()
                                ? null : row.get("turnIndex").asInt(),
                        row.path("toolName").asText(null),
                        row.path("arguments").asText(null)));
            }
            assertEquals(expected.getValue(), RecoveryCounter.count(uses, first), expected.getKey());

            Path workspace = tmp.resolve(expected.getKey().replace('/', '-'));
            copyParts(workspace);
            Files.writeString(workspace.resolve("ANSWER.txt"),
                    "CODE-PART-B: VIOLET-5521\nCODE-PART-E: AMBER-9033\n");
            List<JudgeContext.ToolCallView> calls = new ArrayList<>();
            for (JsonNode row : export.get("logRows")) {
                calls.add(new JudgeContext.ToolCallView(
                        row.path("toolName").asText(null), row.path("arguments").asText(null)));
            }
            List<JudgeContext.TurnView> turns = new ArrayList<>();
            for (JsonNode turn : export.get("turnRows")) {
                turns.add(new JudgeContext.TurnView(
                        turn.path("compactMode").asText(null),
                        turn.path("endReason").asText(null),
                        turn.path("phase").asText(null),
                        turn.path("turnIndex").isMissingNode() || turn.get("turnIndex").isNull()
                                ? null : turn.get("turnIndex").asInt()));
            }
            JudgeResult judged = ExpectationJudge.judge(workspace, checks,
                    new JudgeContext(List.of(), calls, turns));
            assertTrue(AllowedReads.outside(calls).isEmpty(), expected.getKey());
            boolean compacted = turns.stream().anyMatch(t ->
                    "COMPACTION".equals(t.phase()) && t.turnIndex() != null && t.turnIndex() > 0);
            if (compacted) {
                assertEquals(JudgeResult.Outcome.PASS, judged.outcome(), expected.getKey());
            } else {
                assertEquals(JudgeResult.Outcome.INVALID, judged.outcome(), expected.getKey());
            }
        }
    }

    private static void copyParts(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path from = Path.of("docs/eval/fixtures/T11");
        for (int i = 1; i <= 6; i++) {
            Files.copy(from.resolve("part" + i + ".txt"), workspace.resolve("part" + i + ".txt"));
        }
    }
}
