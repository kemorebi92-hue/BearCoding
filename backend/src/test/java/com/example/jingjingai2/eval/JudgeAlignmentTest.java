package com.example.jingjingai2.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeAlignmentTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("读 ANSWER.txt 通过；读别的文件不通过；part 哈希变了不通过")
    void answerIsAllowedAndOtherFilesAreNot() throws Exception {
        Path workspace = tmp.resolve("t11");
        copyParts(workspace);
        Files.writeString(workspace.resolve("ANSWER.txt"),
                "CODE-PART-B: VIOLET-5521\nCODE-PART-E: AMBER-9033\n");
        List<Check> checks = Check.load(Path.of("docs/eval/tasks/T11.expect.json"));
        List<JudgeContext.TurnView> turns = List.of(
                new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 1),
                new JudgeContext.TurnView("DIGEST", "COMPACTION", "COMPACTION", 2));
        JudgeResult answer = ExpectationJudge.judge(workspace, checks, new JudgeContext(List.of(),
                List.of(new JudgeContext.ToolCallView("readFile", "{\"path\": \"ANSWER.txt\"}")), turns));
        assertEquals(JudgeResult.Outcome.PASS, answer.outcome());
        assertTrue(AllowedReads.outside(List.of(
                new JudgeContext.ToolCallView("readFile", "{\"path\": \"ANSWER.txt\"}"))).isEmpty());

        JudgeResult other = ExpectationJudge.judge(workspace, checks, new JudgeContext(List.of(),
                List.of(new JudgeContext.ToolCallView("readFile", "{\"path\": \"notes.txt\"}")), turns));
        assertEquals(JudgeResult.Outcome.FAIL, other.outcome());
        assertEquals(List.of("notes.txt"), AllowedReads.outside(List.of(
                new JudgeContext.ToolCallView("readFile", "{\"path\": \"notes.txt\"}"))));

        Files.writeString(workspace.resolve("part2.txt"), "改过\n");
        JudgeResult hashed = ExpectationJudge.judge(workspace, checks, new JudgeContext(List.of(),
                List.of(new JudgeContext.ToolCallView("readFile", "{\"path\": \"part2.txt\"}")), turns));
        assertEquals(JudgeResult.Outcome.FAIL, hashed.outcome());
    }

    @Test
    @DisplayName("压缩同一轮重读六个文件算 6 次；grep 和带 offset 的重读也算")
    void recoveryCountsSameTurnRereadAndGrepAndOffset() {
        List<RecoveryCounter.Use> reread = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            reread.add(new RecoveryCounter.Use(0, "readFile", "{\"path\": \"part" + i + ".txt\"}"));
        }
        for (int i = 1; i <= 6; i++) {
            reread.add(new RecoveryCounter.Use(1, "readFile", "{\"path\": \"part" + i + ".txt\"}"));
        }
        assertEquals(6, RecoveryCounter.count(reread, 1));

        List<RecoveryCounter.Use> grep = List.of(
                new RecoveryCounter.Use(0, "readFile", "{\"path\": \"part2.txt\"}"),
                new RecoveryCounter.Use(0, "readFile", "{\"path\": \"part5.txt\"}"),
                new RecoveryCounter.Use(1, "grepFile", "{\"path\": \"part2.txt\", \"pattern\": \"CODE-\"}"),
                new RecoveryCounter.Use(1, "grepFile", "{\"path\": \"part5.txt\", \"pattern\": \"CODE-\"}"));
        assertEquals(2, RecoveryCounter.count(grep, 1));

        List<RecoveryCounter.Use> offset = List.of(
                new RecoveryCounter.Use(1, "readFile", "{\"path\": \"part2.txt\"}"),
                new RecoveryCounter.Use(6, "readFile", "{\"path\": \"part2.txt\", \"offset\": 2, \"limit\": 1}"));
        assertEquals(1, RecoveryCounter.count(offset, 3));
    }

    private static void copyParts(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path from = Path.of("docs/eval/fixtures/T11");
        for (int i = 1; i <= 6; i++) {
            Files.copy(from.resolve("part" + i + ".txt"), workspace.resolve("part" + i + ".txt"));
        }
    }
}
