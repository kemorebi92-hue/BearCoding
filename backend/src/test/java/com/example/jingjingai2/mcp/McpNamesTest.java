package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpNamesTest {

    @Test
    @DisplayName("常规：mcp__{server}__{tool}，长度 ≤64，字符合法")
    void regularName() {
        String n = McpNames.exposed("github", "create_issue");
        assertEquals("mcp__github__create_issue", n);
        assertTrue(McpNames.isMcp(n));
        assertTrue(McpNames.isLegal(n));
        assertTrue(n.length() <= McpNames.MAX_LEN);
        assertEquals("github", McpNames.serverOf(n));
        assertEquals("create_issue", McpNames.originalOf(n));
    }

    @Test
    @DisplayName("非法字符换成下划线")
    void sanitizesIllegalChars() {
        String n = McpNames.exposed("my server", "do/it!");
        assertEquals("mcp__my_server__do_it_", n);
        assertTrue(McpNames.isLegal(n));
    }

    @Test
    @DisplayName("超长：截断后带 6 位稳定哈希，同样输入同样输出")
    void truncatesWithStableHash() {
        String tool = "this_is_a_very_long_tool_name_that_will_definitely_exceed_the_limit";
        String a = McpNames.exposed("filesystem", tool);
        String b = McpNames.exposed("filesystem", tool);
        assertEquals(a, b, "哈希必须稳定");
        assertTrue(a.length() <= McpNames.MAX_LEN, a);
        assertTrue(McpNames.isLegal(a), a);
        assertTrue(a.startsWith("mcp__filesystem__"), a);
        assertTrue(a.contains("_"), "截断名应带哈希分隔");
        assertNotEquals("mcp__filesystem__" + tool, a);
    }

    @Test
    @DisplayName("两个不同的长名不应撞到同一个 exposed 名")
    void longNamesDoNotCollide() {
        String a = McpNames.exposed("s", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String b = McpNames.exposed("s", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertNotEquals(a, b);
        assertTrue(a.length() <= 64);
        assertTrue(b.length() <= 64);
    }

    @Test
    @DisplayName("空 / null 安全")
    void nullSafe() {
        assertEquals("mcp__unnamed__unnamed", McpNames.exposed(null, null));
        assertEquals("mcp__unnamed__unnamed", McpNames.exposed("  ", ""));
        assertFalse(McpNames.isMcp(null));
        assertFalse(McpNames.isMcp("readFile"));
    }
}
