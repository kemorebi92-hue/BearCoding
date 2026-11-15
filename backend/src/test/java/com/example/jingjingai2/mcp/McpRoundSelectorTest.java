package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpRoundSelectorTest {

    @Test
    @DisplayName("eager：原样返回 fullBind（引用不变），不插入 mcpToolSearch")
    void eagerIdentity() {
        ToolCallback[] nativeCbs = {McpTestProcess.stub("readFile"), McpTestProcess.stub("writeFile")};
        ToolCallback[] mcp = {McpTestProcess.stub("mcp__echo__echo"), McpTestProcess.stub("mcp__echo__add")};
        ToolCallback[] full = McpToolBridge.concat(nativeCbs, mcp);
        ToolCallback search = new McpToolSearchCallback(mcp, java.util.Map.of(), 5);
        ToolCallback[] round = McpRoundSelector.select(full, search, List.of("mcp__echo__echo"),
                McpRoundSelector.Mode.EAGER);
        assertSame(full, round);
        for (ToolCallback cb : round) {
            assertNotEquals(McpToolSearchCallback.NAME, cb.getToolDefinition().name());
        }
    }

    @Test
    @DisplayName("deferred：原生在前，然后 search，再按 tracker 顺序追加已激活")
    void deferredOrder() {
        ToolCallback[] nativeCbs = {McpTestProcess.stub("readFile"), McpTestProcess.stub("createPlan")};
        ToolCallback[] mcp = {
                McpTestProcess.stub("mcp__tracker__list_issues"),
                McpTestProcess.stub("mcp__ops__get_weather"),
                McpTestProcess.stub("mcp__tracker__create_pr")
        };
        ToolCallback[] full = McpToolBridge.concat(nativeCbs, mcp);
        ToolCallback search = new McpToolSearchCallback(mcp, java.util.Map.of(), 5);
        ToolCallback[] round = McpRoundSelector.select(full, search,
                List.of("mcp__ops__get_weather", "mcp__tracker__create_pr"),
                McpRoundSelector.Mode.DEFERRED);
        assertEquals(List.of("readFile", "createPlan", "mcpToolSearch",
                        "mcp__ops__get_weather", "mcp__tracker__create_pr"),
                names(round));
        assertFalse(names(round).contains("mcp__tracker__list_issues"));
    }

    @Test
    @DisplayName("deferred 未激活：只有原生 + search")
    void deferredEmptyActivation() {
        ToolCallback[] full = McpToolBridge.concat(
                new ToolCallback[]{McpTestProcess.stub("readFile")},
                new ToolCallback[]{McpTestProcess.stub("mcp__echo__echo")});
        ToolCallback search = new McpToolSearchCallback(new ToolCallback[]{full[1]}, java.util.Map.of(), 5);
        ToolCallback[] round = McpRoundSelector.select(full, search, List.of(),
                McpRoundSelector.Mode.DEFERRED);
        assertEquals(List.of("readFile", "mcpToolSearch"), names(round));
    }

    @Test
    @DisplayName("auto：达到窗口百分比走 deferred，低于走 eager；窗口未知保守走 eager")
    void autoThreshold() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setToolLoading("auto");
        p.setAutoEagerMaxTools(0);
        p.setContextWindowTokens(20000);
        p.setAutoThresholdPercent(10);
        ToolCallback[] tiny = {named("mcp__echo__echo", "Echo", "{\"type\":\"object\"}")};
        long tinyTokens = McpRoundSelector.estimateSchemaTokens(tiny);
        assertTrue(tinyTokens < 2000, "小工具应低于窗口的 10%: " + tinyTokens);
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, tiny));

        ToolCallback[] fat = new ToolCallback[30];
        String fatDesc = "x".repeat(80);
        String fatSchema = "{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"type\":\"string\",\"description\":\"" + "y".repeat(40) + "\"},"
                + "\"b\":{\"type\":\"string\",\"description\":\"" + "z".repeat(40) + "\"}}}";
        for (int i = 0; i < 30; i++) fat[i] = named("mcp__t__t" + i, fatDesc, fatSchema);
        long fatTokens = McpRoundSelector.estimateSchemaTokens(fat);
        assertTrue(fatTokens >= 2000, "大目录应至少达到窗口的 10%: " + fatTokens);
        assertEquals(McpRoundSelector.Mode.DEFERRED, McpRoundSelector.resolveMode(p, fat));

        // 恰好等于阈值也走 deferred
        p.setContextWindowTokens((int) (fatTokens * 100 / 10));
        assertEquals(McpRoundSelector.Mode.DEFERRED, McpRoundSelector.resolveMode(p, fat));
        p.setContextWindowTokens((int) (fatTokens * 100 / 10) + 100);
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, fat));

        // 阈值可配置：同一目录，百分比调到估算占比之上就回到 eager
        int needed = (int) (fatTokens * 100 / 20000) + 5;
        p.setAutoThresholdPercent(needed);
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, fat));

        // 窗口未知：保守 eager
        p.setContextWindowTokens(0);
        p.setAutoThresholdPercent(10);
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, fat));
    }

    @Test
    @DisplayName("auto 的估算只看传入的目录：DENY 和未暴露的工具由调用方先滤掉")
    void autoCountsOnlyWhatIsPassed() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setToolLoading("auto");
        p.setAutoEagerMaxTools(0);
        p.setContextWindowTokens(20000);
        p.setAutoThresholdPercent(10);
        ToolCallback[] all = new ToolCallback[30];
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"description\":\""
                + "y".repeat(60) + "\"}}}";
        for (int i = 0; i < 30; i++) all[i] = named("mcp__t__t" + i, "x".repeat(80), schema);
        assertEquals(McpRoundSelector.Mode.DEFERRED, McpRoundSelector.resolveMode(p, all));
        // 滤掉 29 个之后只剩一个，估算降下来，判定变成 eager
        ToolCallback[] kept = {all[0]};
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, kept));
        assertTrue(McpRoundSelector.estimateSchemaTokens(kept) < McpRoundSelector.estimateSchemaTokens(all));
    }

    @Test
    @DisplayName("未知 tool-loading / MCP 关 → eager")
    void unknownAndOffAreEager() {
        McpProperties off = new McpProperties();
        off.setToolLoading("deferred");
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(off, new ToolCallback[0]));
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setToolLoading("whatever");
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, new ToolCallback[0]));
    }

    @Test
    @DisplayName("forRound：MCP 关原样返回；eager 不插 search")
    void forRoundOffAndEager() {
        ToolCallback[] nativeCbs = {McpTestProcess.stub("readFile")};
        McpProperties off = new McpProperties();
        McpClientManager m = new McpClientManager(off);
        assertSame(nativeCbs, McpRoundSelector.forRound(nativeCbs, m, List.of()));

        McpProperties eager = new McpProperties();
        eager.setEnabled(true);
        eager.setToolLoading("eager");
        ToolCallback[] full = McpToolBridge.concat(nativeCbs,
                new ToolCallback[]{McpTestProcess.stub("mcp__echo__echo")});
        McpClientManager fake = new McpClientManager(eager) {
            @Override public boolean isEnabled() { return true; }
            @Override public ToolCallback[] callbacks() {
                return new ToolCallback[]{McpTestProcess.stub("mcp__echo__echo")};
            }
        };
        ToolCallback[] round = McpRoundSelector.forRound(full, fake, List.of());
        assertSame(full, round);
    }

    @Test
    @DisplayName("forRound deferred：历史里的 ACTIVATED 会进入本轮清单")
    void forRoundDeferredUsesTracker() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setToolLoading("deferred");
        ToolCallback mcp = McpTestProcess.stub("mcp__ops__get_weather");
        ToolCallback[] full = McpToolBridge.concat(
                new ToolCallback[]{McpTestProcess.stub("readFile")},
                new ToolCallback[]{mcp});
        McpClientManager fake = new McpClientManager(p) {
            @Override public boolean isEnabled() { return true; }
            @Override public ToolCallback[] callbacks() { return new ToolCallback[]{mcp}; }
            @Override public java.util.Map<String, String[]> nameMap() {
                return java.util.Map.of("mcp__ops__get_weather", new String[]{"ops", "get_weather"});
            }
        };
        List<Message> msgs = List.of(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("1", "mcpToolSearch",
                        "ACTIVATED: mcp__ops__get_weather\n"))));
        ToolCallback[] round = McpRoundSelector.forRound(full, fake, msgs);
        assertEquals(List.of("readFile", "mcpToolSearch", "mcp__ops__get_weather"), names(round));
    }

    @Test
    @DisplayName("auto：工具个数超过上限就 deferred，哪怕远低于窗口的 10%")
    void autoDefersWhenToolCountExceedsCap() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setToolLoading("auto");
        p.setContextWindowTokens(1_000_000);
        p.setAutoThresholdPercent(10);
        p.setAutoEagerMaxTools(8);
        ToolCallback[] many = new ToolCallback[25];
        ToolCallback[] nativeCbs = {McpTestProcess.stub("readFile")};
        for (int i = 0; i < many.length; i++) {
            many[i] = McpTestProcess.stub("mcp__playwright__tool_" + i);
        }
        long tokens = McpRoundSelector.estimateSchemaTokens(many);
        assertTrue(tokens < 1_000_000L * 10 / 100, "小 schema 不应踩中 1M 窗口的 10%: " + tokens);
        assertEquals(McpRoundSelector.Mode.DEFERRED, McpRoundSelector.resolveMode(p, many));

        ToolCallback[] full = McpToolBridge.concat(nativeCbs, many);
        McpClientManager manager = new McpClientManager(p);
        ToolCallback[] round = McpRoundSelector.forRound(full, manager, List.of(), 0L);
        List<String> names = names(round);
        assertTrue(names.contains("readFile"));
        assertTrue(names.contains("mcpToolSearch"));
        assertFalse(names.contains("mcp__playwright__tool_0"));
        assertEquals(2, names.size());

        p.setAutoEagerMaxTools(0);
        assertEquals(McpRoundSelector.Mode.EAGER, McpRoundSelector.resolveMode(p, many));
    }

    @Test
    @DisplayName("总开关关掉但本轮已经带上很多 MCP 工具时，仍走 deferred")
    void connectedCatalogDefersWhenBootFlagOff() {
        McpProperties p = new McpProperties();
        assertFalse(p.isEnabled());
        p.setToolLoading("auto");
        p.setAutoEagerMaxTools(8);
        p.setContextWindowTokens(1_000_000);
        ToolCallback[] many = new ToolCallback[25];
        for (int i = 0; i < many.length; i++) {
            many[i] = McpTestProcess.stub("mcp__playwright__tool_" + i);
        }
        assertEquals(McpRoundSelector.Mode.DEFERRED, McpRoundSelector.resolveMode(p, many));
        ToolCallback[] full = McpToolBridge.concat(
                new ToolCallback[]{McpTestProcess.stub("readFile")}, many);
        ToolCallback[] round = McpRoundSelector.forRound(full, new McpClientManager(p), List.of(), 0L);
        List<String> got = names(round);
        assertTrue(got.contains("mcpToolSearch"), got.toString());
        assertFalse(got.contains("mcp__playwright__tool_0"), got.toString());
    }

    private static List<String> names(ToolCallback[] cbs) {
        List<String> n = new java.util.ArrayList<>();
        for (ToolCallback cb : cbs) n.add(cb.getToolDefinition().name());
        return n;
    }

    private static ToolCallback named(String name, String desc, String schema) {
        org.springframework.ai.tool.definition.ToolDefinition def =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name).description(desc).inputSchema(schema).build();
        return new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return ""; }
        };
    }
}
