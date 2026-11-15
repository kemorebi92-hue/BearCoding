package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交回主 agent 的那段文本 —— 这是子代理模块**唯一**对外可见的东西。
 *
 * 它必须同时满足两件看起来矛盾的事：
 *  1. 主 agent 读完就知道"下一步该怎么办"（所以有停因枚举名 + 一句可执行的提示）；
 *  2. **一行子房间的工具原文都不许带出来**（否则隔离白做了，token 全省不下来）。
 *
 * 第 2 条在代码上由"没有承载工具正文的字段"保证，这里用"唯一两个自由文本入口都被硬截"
 * 把它钉在测试里。
 */
class SubagentResultTest {

    private static final SubagentSpec.Mode EXPLORE = SubagentSpec.Mode.EXPLORE;

    private static Set<String> files(String... names) {
        return new LinkedHashSet<>(java.util.List.of(names));
    }

    /** 六个停因，一个不多一个不少 —— 主 agent 的决策分支就该只有六条 */
    private static Map<SubagentResult.FailureReason, SubagentResult> oneOfEach() {
        Map<SubagentResult.FailureReason, SubagentResult> m = new LinkedHashMap<>();
        m.put(SubagentResult.FailureReason.SUCCESS,
                SubagentResult.success("看 plan 包", EXPLORE, "计划状态在 TaskPlanService", 5, 8, 11,
                        files("TaskPlanService.java", "PlanStep.java")));
        m.put(SubagentResult.FailureReason.ROUNDS_EXHAUSTED,
                SubagentResult.roundsExhausted("看 plan 包", EXPLORE, "只看到一半", 8, 8, 20,
                        files("TaskPlanService.java"), "轮数用尽时仍在调工具"));
        m.put(SubagentResult.FailureReason.TOOL_FAILURES,
                SubagentResult.toolFailures("看 plan 包", EXPLORE, "路径写错了", 3, 8, 4,
                        Set.of(), "读取失败: 没有这个文件"));
        m.put(SubagentResult.FailureReason.CANCELLED,
                SubagentResult.cancelled("看 plan 包", EXPLORE, 2, 8, 3, files("a.java")));
        m.put(SubagentResult.FailureReason.RETRY_LIMIT,
                SubagentResult.retryLimit("看 plan 包", EXPLORE, 3, 3));
        m.put(SubagentResult.FailureReason.REJECTED,
                SubagentResult.rejected("看 plan 包", EXPLORE, "已经有一个 implement 子代理在跑。"));
        return m;
    }

    // ==================== 停因 ====================

    @Test
    @DisplayName("六个停因各有自己的工厂入口，且恰好覆盖枚举的全部取值")
    void everyReasonHasExactlyOneFactory() {
        Map<SubagentResult.FailureReason, SubagentResult> m = oneOfEach();

        assertEquals(6, SubagentResult.FailureReason.values().length, "第一期就六个停因，加值要先改这里的断言");
        assertEquals(Set.of(SubagentResult.FailureReason.values()), m.keySet(),
                "有停因没被测到，就有分支没人守");
        m.forEach((reason, r) -> assertEquals(reason, r.failureReason(),
                "工厂 与 停因对不上：" + r));
    }

    @Test
    @DisplayName("ok / truncated / cancelled 三个布尔与停因自洽（不是三份独立状态，别拼错）")
    void booleansAgreeWithReason() {
        Map<SubagentResult.FailureReason, SubagentResult> m = oneOfEach();

        assertTrue(m.get(SubagentResult.FailureReason.SUCCESS).ok());
        m.forEach((reason, r) -> assertEquals(reason == SubagentResult.FailureReason.SUCCESS, r.ok()));

        assertTrue(m.get(SubagentResult.FailureReason.ROUNDS_EXHAUSTED).truncated());
        m.forEach((reason, r) -> assertEquals(reason == SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.truncated(),
                "截断只该由'轮数用尽'触发（模型自然收尾不算截断）：" + r));

        assertTrue(m.get(SubagentResult.FailureReason.CANCELLED).cancelled());
        m.forEach((reason, r) -> assertEquals(reason == SubagentResult.FailureReason.CANCELLED, r.cancelled()));
    }

    @Test
    @DisplayName("房间内部炸了 → 归到 TOOL_FAILURES（不新增第 7 个停因），但异常写进 detail")
    void crashedMapsToToolFailures() {
        SubagentResult r = SubagentResult.crashed("看 plan 包", EXPLORE, "NullPointerException: boom", 2, 8);

        assertEquals(SubagentResult.FailureReason.TOOL_FAILURES, r.failureReason());
        assertTrue(r.detail().contains("boom"), "异常信息要留在 detail 里，否则线上无从查起");
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("没进房间的两种停因：轮数/工具次数都是 0（不许编造执行统计）")
    void neverEnteredRoomHasZeroStats() {
        for (SubagentResult r : java.util.List.of(
                SubagentResult.retryLimit("g", EXPLORE, 3, 3),
                SubagentResult.rejected("g", EXPLORE, "功能未启用"))) {
            assertEquals(0, r.roundsUsed());
            assertEquals(0, r.maxRounds());
            assertEquals(0, r.toolCalls());
            assertTrue(r.filesRead().isEmpty());
        }
    }

    // ==================== 交给模型的文本 ====================

    @Test
    @DisplayName("成功时：一眼能扫到 模式/目标/停因/轮数/工具次数/已读文件，并附一句「别整份重读」")
    void successTextCarriesCoordinates() {
        String text = oneOfEach().get(SubagentResult.FailureReason.SUCCESS).toModelText(800);

        assertTrue(text.contains("[子代理 explore 完成]"), text);
        assertTrue(text.contains("目标：看 plan 包"));
        assertTrue(text.contains("原因：SUCCESS"));
        assertTrue(text.contains("轮数 5/8"));
        assertTrue(text.contains("工具 11 次"));
        assertTrue(text.contains("读了 2 处"), text);
        assertTrue(text.contains("【已读文件】"));
        assertTrue(text.contains("TaskPlanService.java"));
        // 第二轮改造点：成功时**也**留一句提示。省 token 的老理由是站不住的 ——
        // 少这一句，主 agent 就会"为了保险"把清单里的文件整份重读一遍，那才是真花 token。
        assertTrue(text.contains("【给主 agent 的提示】"), "成功时也要有一句「别整份重读」");
        assertTrue(text.contains("不要为了保险整份重读"), "实际：" + text);
        assertFalse(text.contains("卡点："));
    }

    @Test
    @DisplayName("每个失败停因都附一句**可执行**的提示，而不是只说一句「失败了」")
    void failureTextTellsTheMainAgentWhatToDoNext() {
        Map<SubagentResult.FailureReason, String> expected = Map.of(
                SubagentResult.FailureReason.ROUNDS_EXHAUSTED, "收窄",
                SubagentResult.FailureReason.TOOL_FAILURES, "不要原样再派",
                SubagentResult.FailureReason.CANCELLED, "不要再重试",
                SubagentResult.FailureReason.RETRY_LIMIT, "请自己做",
                SubagentResult.FailureReason.REJECTED, "请自己做");

        expected.forEach((reason, hint) -> {
            String text = oneOfEach().get(reason).toModelText(800);
            assertTrue(text.contains("【给主 agent 的提示】"), reason + " 缺提示段：" + text);
            assertTrue(text.contains(hint), reason + " 的提示里应含「" + hint + "」，实际：" + text);
        });
    }

    @Test
    @DisplayName("没进房间时标题是「未完成」且不出现轮数（0/0 会误导主 agent 以为跑过）")
    void neverEnteredRoomTextOmitsRounds() {
        String text = SubagentResult.retryLimit("看 plan 包", EXPLORE, 3, 3).toModelText(800);

        assertTrue(text.contains("未完成"), text);
        assertFalse(text.contains("轮数"), "根本没进房间，不该出现 轮数 0/0：" + text);
        assertTrue(text.contains("上限 3 次"));
    }

    @Test
    @DisplayName("摘要硬顶：子代理说了 3000 字，落到主 agent 上下文里的必须 ≤ 800")
    void summaryIsHardCapped() {
        String huge = "这一段是子房间里的长篇大论。".repeat(200);   // 2800 字
        SubagentResult r = SubagentResult.success("写报告", EXPLORE, huge, 3, 8, 6, Set.of());

        String text = r.toModelText(800);
        // 摘要段后面还跟着【已读文件】/提示段，所以只能量到**下一个段落标记**为止
        int from = text.indexOf("摘要：\n") + "摘要：\n".length();
        int to = text.indexOf("\n【", from);
        String summaryPart = text.substring(from, to < 0 ? text.length() : to);

        assertTrue(summaryPart.length() <= 800, "摘要段 " + summaryPart.length() + " 字，超了");
        assertTrue(summaryPart.endsWith("（摘要已截断）"), "截断了就要标出来：" + summaryPart);
        assertTrue(text.length() < huge.length(), "整段回给主 agent 的文本应当明显短于子代理的原话");
    }

    @Test
    @DisplayName("detail（卡点）也被硬截 300 字 —— 它是唯一另一个自由文本入口，不能从这里把工具原文偷渡出来")
    void detailIsHardCapped() {
        String hugeToolOutput = "文件正文第一行\n".repeat(3000);   // 24000 字
        SubagentResult r = SubagentResult.toolFailures("看 x", EXPLORE, "卡住了", 2, 8, 3, Set.of(), hugeToolOutput);

        String text = r.toModelText(800);
        assertTrue(text.length() < 2000,
                "detail 没被截住，工具原文就顺着它出房间了（实际 " + text.length() + " 字）");
        assertTrue(text.contains("卡点："));
    }

    @Test
    @DisplayName("PARTIAL + 可续跑：V1 把可复制的 spawnSubagent(resumeTaskId=..., goal=...) 放第一位")
    void partialV1PutsCopyableResumeFirst() {
        SubagentResult r = SubagentResult.roundsExhausted("调研 A 与 B", EXPLORE, "一半", 8, 8, 4,
                        files("A.java"), "")
                .withReport(new SubagentReport(SubagentReport.Status.PARTIAL, "一半", false,
                        List.of(), List.of("B 还没查"), "查 B"))
                .withResumeId("sa-001");

        String v1 = r.toModelText(800, true);
        assertTrue(v1.contains("spawnSubagent(resumeTaskId=\"sa-001\", goal=\"调研 A 与 B\")"),
                "必须是可复制的一行：实际 " + v1);
        assertTrue(v1.contains("【优先·续跑】"), "续跑要放第一位：实际 " + v1);
        assertTrue(v1.contains("整份重读台账文件是错的"));
        assertTrue(v1.contains("(a)") && v1.contains("(c)"));
        assertFalse(v1.contains("(b) 针对"), "V1 不再把续跑标成 (b)");

        String v0 = r.toModelText(800, false);
        assertTrue(v0.contains("(a)") && v0.contains("(b)") && v0.contains("(c)"));
        assertTrue(v0.contains("resumeTaskId=sa-001"));
        assertFalse(v0.contains("spawnSubagent(resumeTaskId="), "V0 不给可复制调用");
        assertEquals("spawnSubagent(resumeTaskId=\"sa-001\", goal=\"调研 A 与 B\")",
                r.copyableResumeCall());
    }

    @Test
    @DisplayName("maxSummaryChars<=0 → 不截断（配置成 0 表示「我不要摘要」时不该变成空串）")
    void zeroCapMeansPassthrough() {
        String s = "短结论";
        assertEquals(s, SubagentResult.trim(s, 0));
        assertEquals(s, SubagentResult.trim(s, -1));
        assertEquals("", SubagentResult.trim(null, 800));
    }

    // ==================== 短笔记 ====================

    @Test
    @DisplayName("noteSummary：有卡点就附在摘要后面，让下一个房间一眼看到上次死在哪")
    void noteSummaryCarriesTheStickingPoint() {
        SubagentResult r = SubagentResult.toolFailures("看 x", EXPLORE, "只查到一半", 3, 8, 4,
                Set.of(), "读取失败: xxx.java 不存在");

        String note = r.noteSummary(800);

        assertTrue(note.contains("只查到一半"));
        assertTrue(note.contains("卡点："));
        assertTrue(note.contains("xxx.java 不存在"));
    }

    @Test
    @DisplayName("noteSummary 同样受 maxSummaryChars 约束（笔记是要**注回新房间**的，不能无限长）")
    void noteSummaryIsCapped() {
        SubagentResult r = SubagentResult.success("看 x", EXPLORE, "很长".repeat(1000), 1, 8, 1, Set.of());

        assertTrue(r.noteSummary(200).length() <= 200 + 20, "笔记也要截：" + r.noteSummary(200).length());
    }

    // ==================== 防御 ====================

    @Test
    @DisplayName("filesRead 是防御性拷贝：外面改传进来的 set 不影响已交回的结果")
    void filesReadIsDefensivelyCopied() {
        Set<String> mutable = new LinkedHashSet<>(Set.of("a.java"));
        SubagentResult r = SubagentResult.success("g", EXPLORE, "结论", 1, 8, 1, mutable);

        mutable.add("b.java");

        assertEquals(java.util.List.of("a.java"), r.filesRead());
    }

    @Test
    @DisplayName("null 参数不抛异常（模型可能漏填 goal，主循环不该因此整轮崩掉）")
    void nullSafe() {
        SubagentResult r = SubagentResult.success(null, null, null, 0, 0, 0, null);

        assertEquals("", r.goal());
        assertEquals(EXPLORE, r.mode());
        assertEquals(SubagentResult.FailureReason.SUCCESS, r.failureReason());
        assertNotNull(r.toModelText(800));
        assertNotNull(r.noteSummary(800));
        assertNotNull(r.toString());
    }
}
