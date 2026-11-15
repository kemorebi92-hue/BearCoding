package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 观测台：**冗余读提示** + **成本汇总**。
 *
 * 这两件事都是本模块要回答"派子代理到底值不值"的直接依据：
 *  - 冗余读提示：子代理读过的文件，主 agent 又整份读了一遍 —— 这是"白派"残留的**可观测信号**，
 *    所以既要在工具结果前给一行提醒（软），又要计 {@code redundantReadCount}（硬数字）。
 *  - 成本汇总：子代理侧的真实 token 与加权成本，和主 agent 那一行并排看就是净账。
 */
class SubagentTelemetryTest {

    private static final String CONV = "conv-tel";

    private final SubagentProperties props = new SubagentProperties();
    private final SubagentTelemetry telemetry = new SubagentTelemetry();

    private static SubagentResult child(String id, String path) {
        return SubagentResult.success("调研 " + id, SubagentSpec.Mode.EXPLORE, "结论", 3, 8, 4,
                        Set.of(path))
                .withRunInfo(true, false, 900);
    }

    // ==================== 冗余读提示（软提醒，不拦截） ====================

    @Test
    @DisplayName("子代理读过的文件，主 agent 再整份读 → 前面加一行提醒，并且计数 +1")
    void wholeFileReadGetsHint() {
        telemetry.recordChildRun(CONV, "sa-001", child("A", "src/main/java/A.java"), null);

        String hint = telemetry.hintForWholeFileRead(CONV, "src/main/java/A.java", false);

        assertNotNull(hint, "整份读已被子代理读过的文件时必须给提示");
        assertTrue(hint.contains("已被子代理 sa-001 读过"), "实际：" + hint);
        assertTrue(hint.contains("不必重读"), "要明确说清不用重读，否则只是一句废话：实际 " + hint);
        assertEquals(1, telemetry.redundantReadCount(CONV));
        assertEquals(1, telemetry.mainFullReadCount(CONV), "整文件读要计入 mainFullReadCount");
    }

    @Test
    @DisplayName("带 offset/limit 的局部读不给提示（那是在核对细节，提示只会变噪音）")
    void rangedReadGetsNoHint() {
        telemetry.recordChildRun(CONV, "sa-001", child("A", "src/main/java/A.java"), null);

        assertNull(telemetry.hintForWholeFileRead(CONV, "src/main/java/A.java", true));
        assertEquals(0, telemetry.redundantReadCount(CONV), "没提示就不该计数");
        assertEquals(0, telemetry.mainFullReadCount(CONV), "带行范围的局部读两边都不计");
    }

    @Test
    @DisplayName("没被子代理读过的文件不给提示（提示必须是真的，不能见谁都提醒）")
    void unknownPathGetsNoHint() {
        telemetry.recordChildRun(CONV, "sa-001", child("A", "src/main/java/A.java"), null);

        assertNull(telemetry.hintForWholeFileRead(CONV, "src/main/java/B.java", false));
        assertNull(telemetry.hintForWholeFileRead(CONV, null, false));
        assertEquals(0, telemetry.redundantReadCount(CONV));
        assertEquals(1, telemetry.mainFullReadCount(CONV),
                "没台账可对的整文件读也要计 mainFullReadCount（对照组比较用）");
    }

    @Test
    @DisplayName("相对路径 vs 绝对路径 vs 反斜杠：归一化 + 后缀匹配都要能命中（否则提示形同虚设）")
    void pathFormsAreNormalized() {
        telemetry.recordChildRun(CONV, "sa-001",
                child("A", "D:\\java\\practice\\jingjingAI2\\src\\main\\java\\A.java"), null);

        assertNotNull(telemetry.hintForWholeFileRead(CONV, "src/main/java/A.java", false),
                "子代理记的是绝对路径，主 agent 写的是相对路径 —— 必须命中");
        assertNotNull(telemetry.hintForWholeFileRead(CONV,
                "d:/java/practice/jingjingAI2/src/main/java/A.java", false), "大小写/斜杠不同也要命中");
        assertNotNull(telemetry.hintForWholeFileRead(CONV, "./src/main/java/A.java", false),
                "开头的 ./ 要去掉");
    }

    @Test
    @DisplayName("同一个文件被多个子代理读过 → 谁先读算谁的（提示里只报一个 id，不刷屏）")
    void firstReaderWins() {
        telemetry.recordChildRun(CONV, "sa-001", child("A", "A.java"), null);
        telemetry.recordChildRun(CONV, "sa-002", child("B", "A.java"), null);

        assertEquals("sa-001", telemetry.readMap(CONV).get("a.java"));
    }

    @Test
    @DisplayName("新用户请求开始 → 观测数据翻篇（上一轮的提示不能跨请求生效）")
    void beginUserRequestClearsEverything() {
        telemetry.recordChildRun(CONV, "sa-001", child("A", "A.java"), null);
        telemetry.hintForWholeFileRead(CONV, "A.java", false);

        telemetry.beginUserRequest(CONV);

        assertNull(telemetry.hintForWholeFileRead(CONV, "A.java", false));
        assertEquals(0, telemetry.redundantReadCount(CONV));
        assertEquals(1, telemetry.mainFullReadCount(CONV), "翻篇后的整文件读算新请求的，不计上一轮台账");
        assertEquals(0, telemetry.childRuns(CONV));
        assertNull(telemetry.hintForWholeFileRead(null, "A.java", false), "没有会话 id 时不能 NPE");
    }

    // ==================== 成本汇总 ====================

    @Test
    @DisplayName("汇总行：子代理 runs / partial / token 三档 / 固定开销 / mainFullReadCount / redundantReadCount / 价格免责")
    void summaryLineReportsEverything() {
        SubagentUsage u1 = new SubagentUsage();
        u1.add(10_000, 8_000, 500);
        telemetry.recordChildRun(CONV, "sa-001", child("A", "A.java"), u1);
        SubagentUsage u2 = new SubagentUsage();
        u2.add(20_000, 16_000, 1_000);
        telemetry.recordChildRun(CONV, "sa-002", child("B", "B.java"), u2);
        telemetry.hintForWholeFileRead(CONV, "A.java", false);

        String line = telemetry.summaryLine(CONV, 50_000L, 2_000L, props);

        assertTrue(line.startsWith("[SubagentCost]"), "实际：" + line);
        assertTrue(line.contains("runs=2"));
        assertTrue(line.contains("prompt=30000"), "子代理侧输入要累加：实际 " + line);
        assertTrue(line.contains("命中缓存 24000"));
        assertTrue(line.contains("completion=1500"));
        assertTrue(line.contains("固定开销估算=1800"), "两个房间各 900：实际 " + line);
        assertTrue(line.contains("主 agent prompt=50000"), "要和主 agent 那一行并排看");
        assertTrue(line.contains("redundantReadCount=1"));
        assertTrue(line.contains("mainFullReadCount=1"), "实际：" + line);
        assertTrue(line.contains("handoffDemotionCount=0"), "实际：" + line);
        assertTrue(line.contains("价格为配置项"), "价格是配置项，必须带免责说明");
    }

    @Test
    @DisplayName("完工单降级会计入 handoffDemotionCount")
    void handoffDemotionIsCounted() {
        telemetry.noteHandoffDemotion(CONV);
        assertEquals(1, telemetry.handoffDemotionCount(CONV));
        assertTrue(telemetry.summaryLine(CONV, 0L, 0L, props).contains("handoffDemotionCount=1"));
    }

    @Test
    @DisplayName("价格表算法：缓存命中的输入按缓存价算（命中价通常只有未命中的 1/4）")
    void costUsesCacheHitPrice() {
        props.setPriceInputPerMillion(2.0);
        props.setPriceCachedInputPerMillion(0.5);
        props.setPriceOutputPerMillion(8.0);

        SubagentUsage.Cost cost = SubagentUsage.costOf(1_000_000, 400_000, 100_000, props);

        assertEquals(400_000, cost.cachedPromptTokens, "缓存命中数要被夹在总输入之内");
        assertEquals(600_000, cost.promptTokens - cost.cachedPromptTokens);
        // 600k 未命中 × 2 + 400k 命中 × 0.5 + 100k 输出 × 8 = 1.2 + 0.2 + 0.8 = 2.2
        assertEquals(2.2, cost.yuan, 1e-9);
    }

    @Test
    @DisplayName("没有会话时汇总行也不能炸（观测代码不许影响主流程）")
    void summaryLineIsNullSafe() {
        String line = telemetry.summaryLine("不存在的会话", null, null, props);

        assertTrue(line.contains("runs=0"));
        assertTrue(line.contains("redundantReadCount=0"));
        assertTrue(line.contains("mainFullReadCount=0"));
        assertEquals(0, telemetry.childFixedOverheadTokens("不存在的会话"));
        assertTrue(telemetry.childUsage("不存在的会话") instanceof SubagentUsage);
    }

    @Test
    @DisplayName("partial 计数只在 PARTIAL 时涨（COMPLETE / NONE 都不算）")
    void partialCounting() {
        SubagentResult complete = SubagentResult.success("g", SubagentSpec.Mode.EXPLORE, "ok", 1, 8, 1, Set.of())
                .withReport(new SubagentReport(SubagentReport.Status.COMPLETE, "ok", false,
                        List.of(), List.of(), ""));
        SubagentResult partial = SubagentResult.roundsExhausted("g2", SubagentSpec.Mode.EXPLORE, "一半", 8, 8, 4,
                        Set.of(), "")
                .withReport(new SubagentReport(SubagentReport.Status.PARTIAL, "一半", false,
                        List.of(), List.of("还差 X"), ""));

        telemetry.recordChildRun(CONV, "sa-1", complete, null);
        telemetry.recordChildRun(CONV, "sa-2", partial, null);

        assertEquals(2, telemetry.childRuns(CONV));
        assertEquals(1, telemetry.partialRuns(CONV));
    }

}
