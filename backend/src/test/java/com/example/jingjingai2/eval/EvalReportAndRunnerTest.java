package com.example.jingjingai2.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvalReportAndRunnerTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("假数据：判定未通过的记录先归类，再生成一页纸")
    void dryRunUsesFakeRecords() {
        Check missed = new Check("fileContains", "Hello.java", "Hello World", null, null, null);
        JudgeResult failed = new JudgeResult(false, List.of(new JudgeResult.Item(missed, false, "未含指定文本")));
        FailureKind kind = FailureClassifier.classify(new EvalTrace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(),
                failed));
        assertEquals(FailureKind.UNCLASSIFIED, kind);

        List<RunRecord> fake = List.of(
                row("T01", 1, true, null, 2, 100),
                row("T01", 2, true, null, 2, 110),
                row("T01", 3, false, kind, 2, 90),
                row("T02", 1, false, FailureKind.COMPILE_UNFIXED, 4, 200),
                row("T02", 2, false, FailureKind.COMPILE_UNFIXED, 4, 210),
                row("T02", 3, true, null, 3, 180));
        String report = EvalReport.render(fake);
        System.out.println("【假数据，不是真实模型跑出来的】");
        System.out.println(report);
        assertTrue(report.contains("| T01 | 2/3 | 300 | 2.0 |"));
        assertTrue(report.contains("UNCLASSIFIED×1"));
        assertTrue(report.contains("| T02 | 1/3 | 590 | 3.7 |"));
        assertTrue(report.contains("COMPILE_UNFIXED×2"));
        assertTrue(report.contains("UNCLASSIFIED：T01#3"));
    }

    @Test
    @DisplayName("已完成的任务×次数不重跑，并且可以只跑指定任务")
    void resumesAndFiltersTasks() throws Exception {
        Path dir = tmp.resolve("results");
        Files.createDirectories(dir);
        EvalRunner.RunSettings settings = new EvalRunner.RunSettings(
                18081, "jingjingai_eval_tmp", "fake-model", "abc1234", 8000, "off");
        AtomicInteger calls = new AtomicInteger();
        EvalRunner.AttemptRunner fake = (taskId, attempt, s) -> {
            calls.incrementAndGet();
            assertEquals("off", s.evalForceMode());
            assertEquals(18081, s.port());
            assertFalse(s.databaseName().isBlank());
            return row(taskId, attempt, true, null, 1, 10);
        };
        EvalRunner.run(List.of("T01"), 1, dir, fake, settings);
        assertEquals(1, calls.get());
        List<RunRecord> second = EvalRunner.run(List.of("T01", "T02"), 2, dir, fake, settings);
        assertEquals(4, calls.get(), "应只补 T01#2、T02#1、T02#2");
        assertEquals(4, second.size());
        String json = Files.readString(dir.resolve("runs.json"));
        assertFalse(json.toLowerCase().contains("password"));
    }

    private static RunRecord row(String task, int attempt, boolean passed, FailureKind kind, int rounds, long tokens) {
        return new RunRecord(task, attempt, "fake-model", "abc1234", 8000, "off",
                passed, kind, rounds, rounds, tokens);
    }
}
