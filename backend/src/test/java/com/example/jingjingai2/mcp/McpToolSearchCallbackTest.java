package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolSearchCallbackTest {

    @Test
    @DisplayName("tokenize：英文、下划线、汉字；忽略标点")
    void tokenize() {
        assertEquals(List.of("list", "issues"), McpToolSearchCallback.tokenize("list issues"));
        // 连续汉字是一个 token；标点丢掉。查询动词不会被悄悄丢掉。
        assertEquals(List.of("查一下天气"), McpToolSearchCallback.tokenize("查一下天气"));
        assertEquals(List.of("天气"), McpToolSearchCallback.tokenize("天气？"));
        assertEquals(List.of("create_pr"), McpToolSearchCallback.tokenize("create_pr"));
        assertTrue(McpToolSearchCallback.tokenize("   ").isEmpty());
    }

    @Test
    @DisplayName("打分：名字×3 + server×2 + 说明×1")
    void scoring() {
        List<String> tok = List.of("issue");
        assertEquals(3, McpToolSearchCallback.score("mcp__tracker__list_issues", "tracker", "other", tok));
        assertEquals(2, McpToolSearchCallback.score("mcp__ops__x", "issue-bot", "other", tok));
        assertEquals(1, McpToolSearchCallback.score("mcp__ops__x", "ops", "open issue list", tok));
        // name 含 issue(+3) + description 含 issue(+1)；server=tracker 不含
        assertEquals(4, McpToolSearchCallback.score("mcp__tracker__list_issues", "tracker", "list issues", tok));
        assertEquals(0, McpToolSearchCallback.score("mcp__ops__weather", "ops", "forecast", tok));
    }

    @Test
    @DisplayName("关键词搜索：第一行 ACTIVATED，只返回 catalog 里的工具，条数封顶")
    void keywordSearchActivates() {
        McpToolSearchCallback cb = callback(3);
        String out = cb.call("{\"query\":\"issue\"}");
        assertTrue(out.startsWith("ACTIVATED:"));
        String first = out.split("\n", 2)[0];
        assertTrue(first.contains("mcp__tracker__list_issues"));
        assertTrue(first.contains("mcp__tracker__create_issue"));
        assertFalse(first.contains("mcp__ops__get_weather"));
        assertEquals(2, first.substring("ACTIVATED:".length()).trim().split(",\\s*").length);
        assertEquals(McpToolSearchCallback.NAME, cb.getToolDefinition().name());
    }

    @Test
    @DisplayName("select: 精确加载暴露名或原始名")
    void selectExact() {
        McpToolSearchCallback cb = callback(8);
        String out = cb.call("{\"query\":\"select:mcp__ops__get_weather, create_pr\"}");
        String first = out.split("\n", 2)[0];
        assertTrue(first.contains("mcp__ops__get_weather"));
        assertTrue(first.contains("mcp__tracker__create_pr"));
        assertFalse(first.contains("list_issues"));
        String missing = cb.call("select:mcp__ghost__nope");
        assertTrue(missing.startsWith("ACTIVATED:"));
        assertTrue(missing.contains("未找到"));
        assertFalse(missing.contains("mcp__ghost"));
    }

    @Test
    @DisplayName("不在 catalog 的工具搜不到（enabled/disabled 已过滤）")
    void onlyCatalog() {
        McpToolSearchCallback cb = callback(8);
        String out = cb.call("{\"query\":\"select:mcp__tracker__merge_pr\"}");
        assertTrue(out.contains("未找到"));
    }

    private static McpToolSearchCallback callback(int max) {
        ToolCallback[] catalog = {
                McpTestProcess.stub("mcp__tracker__list_issues"),
                named("mcp__tracker__create_issue", "Open a new issue"),
                named("mcp__tracker__create_pr", "Open a pull request"),
                named("mcp__ops__get_weather", "Weather for a city")
        };
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("mcp__tracker__list_issues", new String[]{"tracker", "list_issues"});
        map.put("mcp__tracker__create_issue", new String[]{"tracker", "create_issue"});
        map.put("mcp__tracker__create_pr", new String[]{"tracker", "create_pr"});
        map.put("mcp__ops__get_weather", new String[]{"ops", "get_weather"});
        return new McpToolSearchCallback(catalog, map, max);
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
}
