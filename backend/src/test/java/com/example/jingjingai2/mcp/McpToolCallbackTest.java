package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpToolCallbackTest {

    @Test
    @DisplayName("成功调用：把 session 返回的文本原样交给模型")
    void successReturnsText() {
        FakeMcpSession session = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("echo", "Echo", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")))
                .handler((name, args) -> McpCallResult.ok("hello"));
        McpToolCallback cb = new McpToolCallback(session, "mcp__echo__echo",
                session.listTools().get(0), 8000);
        assertEquals("mcp__echo__echo", cb.getToolDefinition().name());
        assertEquals("hello", cb.call("{\"text\":\"x\"}"));
        assertEquals(1, session.callCount());
    }

    @Test
    @DisplayName("session 抛异常：收成 MCP 工具调用失败，不往外抛")
    void exceptionBecomesErrorString() {
        FakeMcpSession session = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("boom", "Boom", "{}")))
                .throwOnCall(new McpException("进程已退出"));
        McpToolCallback cb = new McpToolCallback(session, "mcp__echo__boom",
                session.listTools().get(0), 8000);
        String r = cb.call("{}");
        assertTrue(r.startsWith(McpToolCallback.ERROR_PREFIX), r);
        assertTrue(r.contains("进程已退出"), r);
    }

    @Test
    @DisplayName("远端明确 isError（已完成）：带失败前缀")
    void isErrorPrefixed() {
        FakeMcpSession session = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("echo", "Echo", "{}")))
                .handler((n, a) -> McpCallResult.failedAtTool("boom"));
        McpToolCallback cb = new McpToolCallback(session, "mcp__echo__echo",
                session.listTools().get(0), 8000);
        assertEquals(McpToolCallback.ERROR_PREFIX + "boom", cb.call("{}"));
    }

    @Test
    @DisplayName("投递状态未知：给模型的是 UNKNOWN_OUTCOME，不谎称确定失败")
    void unknownDeliverySaysUnknown() {
        FakeMcpSession session = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("echo", "Echo", "{}")))
                .handler((n, a) -> McpCallResult.fail("boom"));
        McpToolCallback cb = new McpToolCallback(session, "mcp__echo__echo",
                session.listTools().get(0), 8000);
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, cb.call("{}"));
    }

    @Test
    @DisplayName("URL 策略拒绝：不调 session，返回地址不被允许，不抛")
    void urlRejectedBeforeCall() {
        FakeMcpSession session = new FakeMcpSession("pw", List.of(
                new McpRemoteTool("browser_navigate", "Nav",
                        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}")));
        McpServerSpec spec = new McpServerSpec();
        McpToolCallback cb = new McpToolCallback(session, "mcp__pw__browser_navigate",
                session.listTools().get(0), 8000, null, spec);
        String r = cb.call("{\"url\":\"http://169.254.169.254/\"}");
        assertTrue(r.startsWith(McpToolCallback.ERROR_PREFIX), r);
        assertTrue(r.contains("地址不被允许"), r);
        assertEquals(0, session.callCount());
        String blocked = cb.call("{\"url\":\"http://127.0.0.1:8080\"}");
        assertTrue(blocked.contains("地址不被允许"), blocked);
        assertEquals(0, session.callCount());
        String ok = cb.call("{\"url\":\"http://1.1.1.1/\"}");
        assertEquals("ok:browser_navigate:{\"url\":\"http://1.1.1.1/\"}", ok);
        assertEquals(1, session.callCount());
    }

    @Test
    @DisplayName("schema 被清洗：$ref 不会出现在下发给模型的 inputSchema 里")
    void schemaSanitized() {
        FakeMcpSession session = new FakeMcpSession("s", List.of(
                new McpRemoteTool("t", "d", "{\"$ref\":\"#/defs/X\",\"type\":\"object\"}")));
        ToolCallback cb = new McpToolCallback(session, "mcp__s__t", session.listTools().get(0), 100);
        String schema = cb.getToolDefinition().inputSchema();
        assertFalse(schema.contains("$ref"), schema);
        assertTrue(schema.contains("object"), schema);
    }
}
