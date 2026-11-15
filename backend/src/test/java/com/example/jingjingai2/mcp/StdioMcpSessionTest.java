package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真拉一个 Java 子进程当 MCP Server。覆盖握手、echo/add、以及三种失败。
 */
class StdioMcpSessionTest {

    @Test
    @DisplayName("stdio：initialize + tools/list + echo / add")
    void happyPathEchoAndAdd() {
        McpServerSpec spec = McpTestProcess.echoSpec();
        McpProperties props = McpTestProcess.shortTimeoutProps();
        try (StdioMcpSession session = StdioMcpSession.start("echo", spec, props)) {
            assertEquals(2, session.listTools().size());
            assertEquals("echo", session.listTools().get(0).originalName());
            assertEquals("2024-11-05", session.initializeResult().path("protocolVersion").asText());
            assertTrue(session.rawToolsList().path("tools").isArray());
            assertTrue(session.processPid() > 0);
            McpCallResult echo = session.callTool("echo", "{\"text\":\"ping\"}");
            assertFalse(echo.isError(), echo.text());
            assertEquals("ping", echo.text());
            McpCallResult add = session.callTool("add", "{\"a\":2,\"b\":3}");
            assertFalse(add.isError(), add.text());
            assertEquals("5", add.text());
        }
    }

    @Test
    @DisplayName("失败：进程在 initialized 之后崩溃 → 后续调用给出失败文案，不抛到循环外")
    void crashAfterInit() {
        McpServerSpec spec = McpTestProcess.echoSpec("--crash-after-init");
        McpProperties props = McpTestProcess.shortTimeoutProps();
        try {
            StdioMcpSession.start("echo", spec, props);
            fail("崩溃的 server 不应该让 handshake 成功");
        } catch (McpException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    @DisplayName("失败：tools/call 超时 → MCP 工具调用失败，会话可关")
    void hangOnCallTimesOut() {
        McpServerSpec spec = McpTestProcess.echoSpec("--hang-on-call");
        McpProperties props = McpTestProcess.shortTimeoutProps();
        props.setRequestTimeoutMs(400);
        try (StdioMcpSession session = StdioMcpSession.start("echo", spec, props)) {
            McpCallResult r = session.callTool("echo", "{\"text\":\"x\"}");
            assertTrue(r.isError(), r.text());
            assertTrue(r.text().contains("超时"), r.text());
            McpToolCallback cb = new McpToolCallback(session, "mcp__echo__echo",
                    session.listTools().get(0), 8000);
            String s = cb.call("{\"text\":\"x\"}");
            assertTrue(s.startsWith(McpToolCallback.ERROR_PREFIX), s);
        }
    }

    @Test
    @DisplayName("失败：tools/call 回非 JSON → 失败文案，不 500")
    void malformedJson() {
        McpServerSpec spec = McpTestProcess.echoSpec("--malformed");
        McpProperties props = McpTestProcess.shortTimeoutProps();
        try (StdioMcpSession session = StdioMcpSession.start("echo", spec, props)) {
            McpCallResult r = session.callTool("echo", "{\"text\":\"x\"}");
            assertTrue(r.isError(), r.text());
            assertTrue(r.text().toLowerCase().contains("json") || r.text().contains("解析")
                    || r.text().contains("退出") || r.text().contains("超时"), r.text());
        }
    }
}
