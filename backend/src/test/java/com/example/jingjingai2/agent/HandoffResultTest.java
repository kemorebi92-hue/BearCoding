package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffResultTest {

    private static String full(String status, String verification) {
        return "STATUS: " + status + "\n"
                + "SUMMARY: 改完了\n"
                + "CHANGES: Foo.java — 加了方法\n"
                + "VERIFICATION: " + verification + "\n"
                + "DISCOVERIES: None\n"
                + "RISKS: None\n"
                + "REMAINING: None";
    }

    @Test
    @DisplayName("七段齐全 → SUCCEEDED，render 保留原标题")
    void parseHappyPath() {
        HandoffResult h = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"));
        assertFalse(h.parseFailed());
        assertEquals(HandoffResult.Status.SUCCEEDED, h.status());
        assertEquals("改完了", h.summary());
        assertTrue(h.render().startsWith("STATUS: SUCCEEDED"));
        assertTrue(h.render().contains("VERIFICATION: bash test.sh / passed: true"));
    }

    @Test
    @DisplayName("缺段 / 乱格式 → PARTIAL，不抛异常，原文进 REMAINING")
    void missingOrGarbageDegrades() {
        HandoffResult missing = HandoffResult.parse("STATUS: SUCCEEDED\nSUMMARY: 只有两段");
        assertEquals(HandoffResult.Status.PARTIAL, missing.status());
        assertTrue(missing.parseFailed());
        assertTrue(missing.summary().contains("缺段"));
        assertTrue(missing.remaining().contains("STATUS: SUCCEEDED"));

        HandoffResult garbage = HandoffResult.parse("我做完了，你看一下吧");
        assertEquals(HandoffResult.Status.PARTIAL, garbage.status());
        assertTrue(garbage.parseFailed());
        assertTrue(garbage.remaining().contains("我做完了"));

        HandoffResult empty = HandoffResult.parse("   ");
        assertEquals(HandoffResult.Status.PARTIAL, empty.status());
        assertDoesNotThrow(() -> HandoffResult.parse(null));
    }

    @Test
    @DisplayName("超长裁剪保留 STATUS / VERIFICATION / CHANGES，不整段从头截")
    void trimKeepsVerification() {
        String fat = "STATUS: PARTIAL\n"
                + "SUMMARY: " + "概要".repeat(200) + "\n"
                + "CHANGES: Foo.java — 改了构造器\n"
                + "VERIFICATION: javac -encoding UTF-8 -d target/classes src/Foo.java / passed: false\n"
                + "DISCOVERIES: " + "范围外发现很多字".repeat(80) + "\n"
                + "RISKS: " + "风险很多字".repeat(80) + "\n"
                + "REMAINING: 还差测试";
        HandoffResult h = HandoffResult.parse(fat).trim(400);
        String out = h.render();
        assertTrue(out.contains("STATUS: PARTIAL"), out);
        assertTrue(out.contains("VERIFICATION:"), out);
        assertTrue(out.contains("javac -encoding UTF-8"), out);
        assertTrue(out.contains("CHANGES:"), out);
        assertTrue(out.contains("Foo.java"), out);
        assertFalse(out.startsWith("STATUS: PARTIAL\nSUMMARY: 概要概要") && out.length() == 400,
                "不能整段 substring(0, max)：" + out.substring(0, Math.min(80, out.length())));
        assertTrue(out.contains("（已裁剪）"), out);
        assertTrue(out.length() < fat.length());
    }

    @Test
    @DisplayName("SUCCEEDED 且命令在执行记录里 → 保持；不在记录里 → 降 PARTIAL")
    void verifyAgainstCommandLog() {
        HandoffResult ok = HandoffResult.parse(full("SUCCEEDED",
                        "javac -encoding UTF-8 -d target/classes src/Foo.java / passed: true"))
                .verifyAgainst(List.of("javac -encoding UTF-8 -d target/classes src/Foo.java"));
        assertEquals(HandoffResult.Status.SUCCEEDED, ok.status());

        HandoffResult miss = HandoffResult.parse(full("SUCCEEDED",
                        "mvn test / passed: true"))
                .verifyAgainst(List.of("javac -encoding UTF-8 Foo.java"));
        assertEquals(HandoffResult.Status.PARTIAL, miss.status());
        assertTrue(miss.remaining().contains(HandoffResult.VERIFY_NOT_FOUND), miss.remaining());

        HandoffResult none = HandoffResult.parse(full("SUCCEEDED", "passed: true"))
                .verifyAgainst(List.of());
        assertEquals(HandoffResult.Status.PARTIAL, none.status());
        assertTrue(none.remaining().contains(HandoffResult.VERIFY_NOT_FOUND)
                || none.summary().contains(HandoffResult.VERIFY_NOT_FOUND), none.render());
    }

    @Test
    @DisplayName("PARTIAL/FAILED 不做命令核对")
    void nonSuccessSkipsVerify() {
        HandoffResult p = HandoffResult.parse(full("PARTIAL", "没跑测试"))
                .verifyAgainst(List.of());
        assertEquals(HandoffResult.Status.PARTIAL, p.status());
        assertFalse(p.remaining().contains(HandoffResult.VERIFY_NOT_FOUND));

        HandoffResult f = HandoffResult.parse(full("FAILED", "编译都没跑"))
                .verifyAgainst(List.of());
        assertEquals(HandoffResult.Status.FAILED, f.status());
    }

    @Test
    @DisplayName("空白折叠 + 双向包含：bash test.sh 对 test.sh 不算假降级")
    void mutualContainsDoesNotFalseDemote() {
        HandoffResult.resetDemotionCount();
        HandoffResult h = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"))
                .verifyAgainst(List.of("test.sh"));
        assertEquals(HandoffResult.Status.SUCCEEDED, h.status(), h.render());
        assertFalse(h.demoted());

        HandoffResult reverse = HandoffResult.parse(full("SUCCEEDED", "test.sh / passed: true"))
                .verifyAgainst(List.of("bash test.sh"));
        assertEquals(HandoffResult.Status.SUCCEEDED, reverse.status(), reverse.render());
    }

    @Test
    @DisplayName("声称跑过但执行记录为空 → 降级；计数 +1")
    void neverRanDemotesAndCounts() {
        HandoffResult.resetDemotionCount();
        HandoffResult h = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"))
                .verifyAgainst(List.of());
        assertEquals(HandoffResult.Status.PARTIAL, h.status());
        assertTrue(h.demoted());
        assertEquals(1, HandoffResult.demotionCount());
        assertFalse(HandoffResult.looksLikeVerifiedSuccess(h.render()));
    }

    @Test
    @DisplayName("退出码非 0 却写 passed:true → 降级；拿不到退出码则跳过对照")
    void exitMismatchDemotesButMissingExitSkips() {
        HandoffResult.ExecutedCommand failed = new HandoffResult.ExecutedCommand("bash test.sh", 1);
        HandoffResult mismatch = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"))
                .verifyRecords(List.of(failed));
        assertEquals(HandoffResult.Status.PARTIAL, mismatch.status());
        assertTrue(mismatch.demoted());
        assertTrue(mismatch.remaining().contains(HandoffResult.VERIFY_EXIT_MISMATCH)
                || mismatch.summary().contains(HandoffResult.VERIFY_EXIT_MISMATCH), mismatch.render());

        HandoffResult.ExecutedCommand unknown = new HandoffResult.ExecutedCommand("bash test.sh", null);
        HandoffResult skip = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"))
                .verifyRecords(List.of(unknown));
        assertEquals(HandoffResult.Status.SUCCEEDED, skip.status(), "没退出码就不能凭空降级");

        HandoffResult.ExecutedCommand ok = new HandoffResult.ExecutedCommand("bash test.sh", 0);
        HandoffResult keep = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"))
                .verifyRecords(List.of(ok));
        assertEquals(HandoffResult.Status.SUCCEEDED, keep.status());
    }

    @Test
    @DisplayName("续跑工具集不符 → SUCCEEDED 降 PARTIAL，报告里必须有那句原话")
    void noteToolsetMismatchDemotesSucceeded() {
        HandoffResult.resetDemotionCount();
        HandoffResult h = HandoffResult.parse(full("SUCCEEDED", "bash test.sh / passed: true"))
                .noteToolsetMismatch();
        assertEquals(HandoffResult.Status.PARTIAL, h.status());
        assertTrue(h.demoted());
        assertTrue(h.remaining().contains(HandoffResult.TOOLSET_MISMATCH)
                || h.summary().contains(HandoffResult.TOOLSET_MISMATCH), h.render());
        assertFalse(HandoffResult.looksLikeVerifiedSuccess(h.render()));
        assertEquals(1, HandoffResult.demotionCount());
    }

    @Test
    @DisplayName("formatExecuted：空列表写 n=0；有命令时带退出码，不参与核对")
    void formatExecutedIsReadable() {
        assertEquals("executedCommands n=0", HandoffResult.formatExecuted(List.of()));
        assertEquals("executedCommands n=0", HandoffResult.formatExecuted(null));
        String line = HandoffResult.formatExecuted(List.of(
                new HandoffResult.ExecutedCommand("javac Foo.java", 0),
                new HandoffResult.ExecutedCommand("java OldMain", 1),
                new HandoffResult.ExecutedCommand("echo hi", null)));
        assertTrue(line.startsWith("executedCommands n=3"), line);
        assertTrue(line.contains("[exit=0] javac Foo.java"), line);
        assertTrue(line.contains("[exit=1] java OldMain"), line);
        assertTrue(line.contains("[exit=?] echo hi"), line);
    }

    @Test
    @DisplayName("parseExitCode：只认结果里已有的 (exit=N) 或 success:true")
    void parseExitCodeFromExistingResult() {
        assertEquals(1, HandoffResult.parseExitCode("执行失败 (exit=1):\nboom"));
        assertEquals(0, HandoffResult.parseExitCode("执行成功 (exit=0):\ncompiled ok"));
        assertEquals(0, HandoffResult.parseExitCode("[TC]{\"tool\":\"executeCommand\",\"success\":true}[/TC]"));
        assertNull(HandoffResult.parseExitCode("compiled ok"));
        assertNull(HandoffResult.parseExitCode(null));
    }

    @Test
    @DisplayName("looksLikeVerifiedSuccess 只认行首 STATUS: SUCCEEDED")
    void looksLikeVerifiedSuccess() {
        assertTrue(HandoffResult.looksLikeVerifiedSuccess("STATUS: SUCCEEDED\nSUMMARY: ok"));
        assertFalse(HandoffResult.looksLikeVerifiedSuccess("STATUS: PARTIAL\nSUMMARY: STATUS: SUCCEEDED"));
        assertFalse(HandoffResult.looksLikeVerifiedSuccess(""));
        assertFalse(HandoffResult.looksLikeVerifiedSuccess(null));
    }
}
