package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果未知的调用不自动重发。重发只留给两种能证明安全的情况：
 * 调用前会话就已经死了，或者这次结果明确说请求一个字节都没出去。
 * 不拉进程，不连库。
 */
class McpRetryBoundaryTest {

    private McpClientManager manager;
    private final AtomicInteger opens = new AtomicInteger();
    private final List<FakeMcpSession> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        opens.set(0);
        opened.clear();
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        manager = new McpClientManager(props) {
            @Override
            McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
                opens.incrementAndGet();
                FakeMcpSession session = new FakeMcpSession(serverName, List.of(
                        new McpRemoteTool("echo", "echo", "{\"type\":\"object\"}")));
                opened.add(session);
                return session;
            }
        };
        manager.start();
        assertFalse(manager.addServer(0L, "alpha", spec(), true).rejected());
        assertEquals(1, opens.get());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("调用前会话已死：重连后调用一次，不重复")
    void deadBeforeCallReconnectsOnce() {
        ToolCallback cb = callback();
        opened.get(0).die();
        String result = cb.call("{}");
        assertEquals(2, opens.get(), "调用前已死应重连");
        assertEquals(0, opened.get(0).callCount(), "死会话上不该调用");
        assertEquals(1, opened.get(1).callCount(), "新会话上只调用一次");
        assertTrue(result.startsWith("ok:echo"), result);
    }

    @Test
    @DisplayName("结果未知：不重发，并说明结果未知")
    void unknownOutcomeDoesNotRetry() {
        opened.get(0).handler((name, args) -> {
            opened.get(0).die();
            return McpCallResult.fail("等待 tools/call 超时 (30000ms)");
        });
        String result = callback().call("{}");
        assertEquals(1, opens.get(), "结果未知不该重连");
        assertEquals(1, opened.get(0).callCount(), "不该把同一次调用再发一遍");
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, result);
    }

    @Test
    @DisplayName("只有证明请求没发出去才重发一次")
    void notSentRetriesOnce() {
        opened.get(0).handler((name, args) ->
                McpCallResult.failed("连接尚未建立", McpCallResult.Delivery.NOT_SENT));
        String result = callback().call("{}");
        assertEquals(2, opens.get(), "NOT_SENT 应重连重发");
        assertEquals(1, opened.get(0).callCount());
        assertEquals(1, opened.get(1).callCount(), "新会话上只补一次");
        assertTrue(result.startsWith("ok:echo"), result);
    }

    @Test
    @DisplayName("工具明确返回的失败含「超时」也不重发")
    void toolErrorMentioningTimeoutDoesNotRetry() {
        opened.get(0).handler((name, args) -> McpCallResult.failedAtTool("上游超时，请稍后再试"));
        String result = callback().call("{}");
        assertEquals(1, opens.get());
        assertEquals(1, opened.get(0).callCount());
        assertTrue(result.startsWith(McpToolCallback.ERROR_PREFIX), result);
        assertTrue(result.contains("上游超时"), result);
    }

    @Test
    @DisplayName("传输异常（HTTP 连接超时这类）按结果未知处理，不重发")
    void transportExceptionDoesNotRetry() {
        opened.get(0).throwOnCall(new RuntimeException("HTTP connect timed out"));
        String result = callback().call("{}");
        assertEquals(1, opens.get(), "抛异常的失败不重发");
        assertEquals(1, opened.get(0).callCount());
        assertTrue(result.startsWith(McpToolCallback.ERROR_PREFIX), result);
        assertTrue(result.contains("HTTP connect timed out"), result);
    }

    private ToolCallback callback() {
        for (ToolCallback cb : manager.callbacks()) {
            if (cb != null && cb.getToolDefinition() != null
                    && "mcp__alpha__echo".equals(cb.getToolDefinition().name())) {
                return cb;
            }
        }
        throw new AssertionError("没有找到回调");
    }

    private static McpServerSpec spec() {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        return spec;
    }
}
