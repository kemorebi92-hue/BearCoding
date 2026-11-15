package com.example.jingjingai2.mcp;

import com.example.jingjingai2.context.ContextBudget;
import com.example.jingjingai2.context.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三个固定任务在五档规则下的估算。不调用模型，数字来自同一套纯函数。
 * 任务 3 的工具规模和页面结果用上次实跑的量级：25 个工具定义约 1.5 万字符，
 * 一次页面结果 33158 字符。
 */
class LongTaskCostHarnessTest {

    @Test
    @DisplayName("三任务五档：延迟加载少发工具，大结果不再整段进上下文")
    void threeTasks() {
        ToolCallback[] playwright = catalog(25, 15346);
        long eagerTools = McpRoundSelector.estimateSchemaTokens(playwright);
        McpProperties auto = props();
        assertEquals(McpRoundSelector.Mode.DEFERRED, McpRoundSelector.resolveMode(auto, playwright));

        McpProperties percentOnly = props();
        percentOnly.setAutoEagerMaxTools(0);
        percentOnly.setContextWindowTokens(128_000);
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(percentOnly, playwright),
                "改之前：约 1 万 token 低于 128K 的 10%，25 个工具整包下发");

        String page = "H".repeat(400) + "M".repeat(33158 - 800) + "T".repeat(400);
        assertEquals(33158, page.length());
        String shown = McpOutputStore.previewWithNotice(page, 8000, 8000, "D:/mcp-output/conv/snap.txt");
        assertTrue(shown.startsWith("HHHH"));
        assertTrue(shown.endsWith("TTTT"));
        assertTrue(shown.length() <= 8000, "shown=" + shown.length());
        int shownTokens = TokenEstimator.estimate(shown);
        int fullTokens = TokenEstimator.estimate(page);

        // 任务 1：一句话问答，无工具。五档相同。
        Row t1 = new Row("T1 一句话", true, 1, "模型自然收尾", 800, 800);
        // 任务 2：写一个小文件，4 轮，只有原生工具。五档相同。
        Row t2 = new Row("T2 写文件", true, 4, "模型自然收尾", 3200, 9000);

        // 任务 3：打开页面。脚本：搜索（仅 deferred）→ 导航 → 快照 → 回答。
        // 改前 eager，快照全文留在后一轮。改后 deferred，快照只留预览。
        long answerBefore = 2_000 + eagerTools + fullTokens;
        long answerAfter = 2_000 + searchTokens(playwright) + shownTokens;
        Row before = new Row("T3 打开页面", true, 3, "模型自然收尾", answerBefore, answerBefore * 3);
        Row afterA = new Row("T3 打开页面", true, 4, "模型自然收尾",
                2_000 + searchTokens(playwright) + fullTokens,
                (2_000 + searchTokens(playwright)) * 4 + fullTokens);
        Row afterB = new Row("T3 打开页面", true, 4, "模型自然收尾", answerAfter,
                (2_000 + searchTokens(playwright)) * 4 + shownTokens);

        assertTrue(afterB.peak < before.peak);
        assertTrue(shownTokens < fullTokens / 2);

        System.out.println("eagerTools=" + eagerTools + " shownChars=" + shown.length()
                + " shownTokens=" + shownTokens + " fullTokens=" + fullTokens);
        System.out.println("T1 " + t1);
        System.out.println("T2 " + t2);
        System.out.println("baseline T3 " + before);
        System.out.println("A T3 " + afterA);
        System.out.println("B T3 " + afterB);
        assertFalse(ContextBudget.shouldCompact(shownTokens, searchTokens(playwright),
                1_000_000, 65, 24_000));
    }

    private static long searchTokens(ToolCallback[] catalog) {
        ToolCallback search = new McpToolSearchCallback(catalog, java.util.Map.of(), 5);
        ToolCallback one = catalog[0];
        return McpRoundSelector.estimateSchemaTokens(new ToolCallback[]{search, one});
    }

    private static McpProperties props() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setToolLoading("auto");
        p.setContextWindowTokens(1_000_000);
        p.setAutoThresholdPercent(10);
        p.setAutoEagerMaxTools(8);
        return p;
    }

    /** 把总字符数摊到 n 个工具的 description 上，让估算接近实跑的 15346 字符。 */
    private static ToolCallback[] catalog(int n, int totalChars) {
        int each = Math.max(20, totalChars / n);
        ToolCallback[] out = new ToolCallback[n];
        for (int i = 0; i < n; i++) {
            String desc = "d".repeat(each);
            out[i] = named("mcp__playwright__t" + i, desc);
        }
        return out;
    }

    private static ToolCallback named(String name, String desc) {
        org.springframework.ai.tool.definition.ToolDefinition def =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name).description(desc).inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return ""; }
        };
    }

    private record Row(String task, boolean done, int rounds, String reason, long peak, long sum) {
        @Override public String toString() {
            return task + " done=" + done + " rounds=" + rounds + " reason=" + reason
                    + " peak=" + peak + " sum=" + sum;
        }
    }
}
