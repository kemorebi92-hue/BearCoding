package com.example.jingjingai2.eval;

import com.example.jingjingai2.context.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 十个任务的期望：各用一个手工改出来的成功工作区和失败工作区跑判定器。
 * 不调用模型。
 */
class TaskExpectationTest {

    private static final Path FIXTURES = Path.of("docs/eval/fixtures");
    private static final Path TASKS = Path.of("docs/eval/tasks");

    @TempDir Path tmp;

    @Test
    @DisplayName("T01 到 T10：成功工作区通过，失败工作区不通过")
    void eachTaskHasAPassingAndAFailingWorkspace() throws Exception {
        for (int n = 1; n <= 10; n++) {
            String id = String.format("T%02d", n);
            List<Check> checks = Check.load(TASKS.resolve(id + ".expect.json"));
            Path ok = tmp.resolve(id + "-ok");
            Path bad = tmp.resolve(id + "-bad");
            copy(FIXTURES.resolve(id), ok);
            copy(FIXTURES.resolve(id), bad);
            JudgeContext okPlan = JudgeContext.empty();
            JudgeContext badPlan = JudgeContext.empty();
            switch (id) {
                case "T01" -> replace(ok.resolve("Hello.java"), "Helo", "Hello");
                case "T02" -> Files.writeString(ok.resolve("src/App.java"), """
                        public class App {
                            public static void main(String[] args) {
                                System.out.println("ok");
                            }
                        }
                        """);
                case "T03" -> {
                    replace(ok.resolve("a/A.java"), "OLD", "NEW");
                    replace(ok.resolve("b/B.java"), "OLD", "NEW");
                }
                case "T04" -> {
                    replace(ok.resolve("CHANGE.txt"), "before", "LATEST");
                    replace(bad.resolve("CHANGE.txt"), "before", "LATEST");
                    Files.writeString(bad.resolve("KEEP.txt"), "CHANGED\n");
                    okPlan = new JudgeContext(List.of(), List.of(), List.of(
                            new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 0),
                            new JudgeContext.TurnView("SUMMARY", "COMPACTION", "COMPACTION", 1)));
                    badPlan = okPlan;
                }
                case "T05" -> {
                    Files.writeString(ok.resolve("step3.txt"), "STEP3-DONE\n");
                    okPlan = plan("done");
                    badPlan = plan("pending");
                }
                case "T06" -> {
                    okPlan = new JudgeContext(List.of(),
                            List.of(new JudgeContext.ToolCallView("readFile")), List.of(), "2");
                    badPlan = new JudgeContext(List.of(),
                            List.of(new JudgeContext.ToolCallView("createPlan")), List.of(), "2");
                }
                case "T07" -> replace(ok.resolve("Dup.java"),
                        "// UNIQUE_B\n        return 1;", "// UNIQUE_B\n        return 2;");
                case "T08" -> replace(ok.resolve("src/hidden/Real.java"), "TOKEN_42", "TOKEN_99");
                case "T09" -> replace(ok.resolve("only.txt"), "red", "blue");
                case "T10" -> replace(ok.resolve("lib/App.java"), "FIXME", "FIXED");
                default -> fail(id);
            }
            JudgeResult pass = ExpectationJudge.judge(ok, checks, okPlan);
            JudgeResult fail = ExpectationJudge.judge(bad, checks, badPlan);
            assertTrue(pass.passed(), id + " 成功工作区应通过: " + detail(pass));
            assertFalse(fail.passed(), id + " 失败工作区应不通过");
        }
    }

    @Test
    @DisplayName("T11：读完第 4 份才过 20000，读范围外的文件不通过")
    void t11CrossesThresholdBetweenThirdAndFourthFile() throws Exception {
        int overhead = 9270;
        int one = TokenEstimator.estimate(Files.readString(FIXTURES.resolve("T11/part1.txt")));
        assertTrue(overhead + 3 * one < 20000, "3 份还不该压: " + (overhead + 3 * one));
        assertTrue(overhead + 4 * one > 20000, "4 份应该压: " + (overhead + 4 * one));

        Path workspace = tmp.resolve("t11");
        copy(FIXTURES.resolve("T11"), workspace);
        Files.writeString(workspace.resolve("ANSWER.txt"), "CODE-PART-B: VIOLET-5521\nCODE-PART-E: AMBER-9033\n");
        List<Check> checks = Check.load(TASKS.resolve("T11.expect.json"));
        List<JudgeContext.TurnView> turns = List.of(
                new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 1),
                new JudgeContext.TurnView("DIGEST", "COMPACTION", "COMPACTION", 4));
        List<JudgeContext.ToolCallView> reads = List.of(
                new JudgeContext.ToolCallView("readFile", "{\"path\": \"part1.txt\"}"));
        JudgeResult ok = ExpectationJudge.judge(workspace, checks,
                new JudgeContext(List.of(), reads, turns));
        assertEquals(JudgeResult.Outcome.PASS, ok.outcome(), detail(ok));

        List<JudgeContext.ToolCallView> outside = List.of(
                new JudgeContext.ToolCallView("readFile", "{\"path\": \"notes.txt\"}"));
        JudgeResult leaked = ExpectationJudge.judge(workspace, checks,
                new JudgeContext(List.of(), outside, turns));
        assertEquals(JudgeResult.Outcome.FAIL, leaked.outcome());
    }

    @Test
    @DisplayName("T04：压缩发生且约束守住是通过；守不住是不通过；没压缩是无效")
    void t04ThreeOutcomes() throws Exception {
        List<Check> checks = Check.load(TASKS.resolve("T04.expect.json"));
        Path base = tmp.resolve("t04-base");
        copy(FIXTURES.resolve("T04"), base);
        replace(base.resolve("CHANGE.txt"), "before", "LATEST");

        List<JudgeContext.TurnView> summary = List.of(
                new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 0),
                new JudgeContext.TurnView("SUMMARY", "COMPACTION", "COMPACTION", 1));
        JudgeResult pass = ExpectationJudge.judge(base, checks, new JudgeContext(List.of(), List.of(), summary));
        assertEquals(JudgeResult.Outcome.PASS, pass.outcome());

        Path broken = tmp.resolve("t04-broken");
        copy(base, broken);
        Files.writeString(broken.resolve("KEEP.txt"), "CHANGED\n");
        JudgeResult fail = ExpectationJudge.judge(broken, checks, new JudgeContext(List.of(), List.of(), summary));
        assertEquals(JudgeResult.Outcome.FAIL, fail.outcome());

        JudgeResult invalid = ExpectationJudge.judge(base, checks,
                new JudgeContext(List.of(), List.of(), List.of(
                        new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 0))));
        assertEquals(JudgeResult.Outcome.INVALID, invalid.outcome());
        assertEquals("NO_REAL_SUMMARY", T04Validity.invalidReason(invalidTurns()));
        assertFalse(invalid.passed());

        List<JudgeContext.TurnView> fallback = List.of(
                new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 0),
                new JudgeContext.TurnView("SUMMARY", "COMPACTION_FALLBACK", "COMPACTION", 1));
        JudgeResult fallbackResult = ExpectationJudge.judge(base, checks,
                new JudgeContext(List.of(), List.of(), fallback));
        assertEquals(JudgeResult.Outcome.INVALID, fallbackResult.outcome());
        assertEquals("FALLBACK_COMPACTION", T04Validity.invalidReason(fallback));

        JudgeResult noModel = ExpectationJudge.judge(base, checks,
                new JudgeContext(List.of(), List.of(), List.of(
                        new JudgeContext.TurnView("SUMMARY", "COMPACTION", "COMPACTION", 0))));
        assertEquals(JudgeResult.Outcome.INVALID, noModel.outcome());
        assertEquals("NO_MODEL_ROUNDS", T04Validity.invalidReason(List.of(
                new JudgeContext.TurnView("SUMMARY", "COMPACTION", "COMPACTION", 0))));

        List<JudgeContext.TurnView> digest = List.of(
                new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 1),
                new JudgeContext.TurnView("DIGEST", "COMPACTION", "COMPACTION", 1));
        Path kept = tmp.resolve("t04-kept");
        copy(FIXTURES.resolve("T04"), kept);
        JudgeResult constraintOnly = ExpectationJudge.judge(kept, checks,
                new JudgeContext(List.of(), List.of(), digest));
        assertEquals(JudgeResult.Outcome.PASS, constraintOnly.outcome(),
                "KEEP 没被改就算通过，CHANGE 没写成 LATEST 不影响通过");
    }

    private static List<JudgeContext.TurnView> invalidTurns() {
        return List.of(new JudgeContext.TurnView("NONE", "TOOL_CALLING", "MODEL_TURN", 0));
    }

    @Test
    @DisplayName("T06：回复里没有 2 不能靠没调 createPlan 通过")
    void replyWithoutTwoDoesNotPass() throws Exception {
        List<Check> checks = Check.load(TASKS.resolve("T06.expect.json"));
        Path workspace = tmp.resolve("t06");
        copy(FIXTURES.resolve("T06"), workspace);
        JudgeResult empty = ExpectationJudge.judge(workspace, checks,
                new JudgeContext(List.of(), List.of(), List.of(), ""));
        assertEquals(JudgeResult.Outcome.FAIL, empty.outcome());
        assertTrue(failedTypes(empty).contains("replyContains"));
        assertFalse(failedTypes(empty).contains("toolNotCalled"));

        JudgeResult wrong = ExpectationJudge.judge(workspace, checks,
                new JudgeContext(List.of(), List.of(), List.of(), "1+1等于一"));
        assertEquals(JudgeResult.Outcome.FAIL, wrong.outcome());
        assertTrue(failedTypes(wrong).contains("replyContains"));

        JudgeResult ok = ExpectationJudge.judge(workspace, checks,
                new JudgeContext(List.of(), List.of(), List.of(), "1+1等于2"));
        assertEquals(JudgeResult.Outcome.PASS, ok.outcome(), detail(ok));
    }

    @Test
    @DisplayName("每个任务至少有一条必须做成某件事的期望")
    void everyTaskHasAPositiveCheck() throws Exception {
        Set<String> positive = Set.of(
                "fileContains", "commandOutputContains", "planStepDone",
                "toolCalledAtLeast", "compactionHappened", "replyContains");
        List<String> missing = new java.util.ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            String id = String.format("T%02d", n);
            boolean any = Check.load(TASKS.resolve(id + ".expect.json")).stream()
                    .anyMatch(check -> positive.contains(check.type()));
            if (!any) missing.add(id);
        }
        assertTrue(missing.isEmpty(), "只有负向条件: " + missing);
    }

    private static List<String> failedTypes(JudgeResult result) {
        List<String> types = new java.util.ArrayList<>();
        for (JudgeResult.Item item : result.items()) {
            if (!item.passed()) types.add(item.check().type());
        }
        return types;
    }

    private static JudgeContext plan(String s3) {
        return new JudgeContext(List.of(
                new JudgeContext.PlanStepView("s1", "done"),
                new JudgeContext.PlanStepView("s2", "done"),
                new JudgeContext.PlanStepView("s3", s3)));
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path dest = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void replace(Path file, String from, String to) throws IOException {
        String body = Files.readString(file);
        if (!body.contains(from)) fail("找不到要替换的文本: " + file);
        Files.writeString(file, body.replace(from, to));
    }

    private static String detail(JudgeResult result) {
        StringBuilder sb = new StringBuilder();
        for (JudgeResult.Item item : result.items()) {
            if (!item.passed()) sb.append(item.check().type()).append(' ').append(item.detail()).append("; ");
        }
        return sb.toString();
    }
}
