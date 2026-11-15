package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.*;

class McpToolBridgeTest {

    @Test
    @DisplayName("MCP 空数组：返回原来的原生数组（引用不变）")
    void emptyMcpReturnsSameArray() {
        ToolCallback[] nativeCbs = {McpTestProcess.stub("readFile"), McpTestProcess.stub("writeFile")};
        assertSame(nativeCbs, McpToolBridge.concat(nativeCbs, new ToolCallback[0]));
        assertSame(nativeCbs, McpToolBridge.concat(nativeCbs, (McpClientManager) null));
    }

    @Test
    @DisplayName("MCP 接在原生工具后面")
    void mcpAppendedLast() {
        ToolCallback[] nativeCbs = {McpTestProcess.stub("readFile")};
        ToolCallback[] mcp = {McpTestProcess.stub("mcp__echo__echo")};
        ToolCallback[] all = McpToolBridge.concat(nativeCbs, mcp);
        assertEquals(2, all.length);
        assertEquals("readFile", all[0].getToolDefinition().name());
        assertEquals("mcp__echo__echo", all[1].getToolDefinition().name());
    }

    @Test
    @DisplayName("原生 null + MCP 空 → 空数组，不抛")
    void nullNative() {
        ToolCallback[] all = McpToolBridge.concat(null, new ToolCallback[0]);
        assertNotNull(all);
        assertEquals(0, all.length);
    }
}
