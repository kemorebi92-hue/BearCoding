package com.example.jingjingai2.mcp;

import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * stdio 与 HTTP 都先产出 {@link McpCallResult}，再经过这里变成模型文本并写下执行结论。
 * 不启动进程、不连数据库。
 */
class McpAccountingTest {

    @BeforeEach
    @AfterEach
    void clear() {
        ToolExecutionStatus.take();
    }

    @Test
    @DisplayName("结果未知：记 UNKNOWN，原文是 UNKNOWN_OUTCOME，不是成功")
    void unknownIsNotSuccess() {
        String text = McpAccounting.finish(McpCallResult.fail("写入超时"));
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, text);
        assertEquals(ToolExecutionStatus.Kind.UNKNOWN, ToolExecutionStatus.take());
        assertFalse(ToolExecutionStatus.succeeded(ToolExecutionStatus.Kind.UNKNOWN));
    }

    @Test
    @DisplayName("调用前没发出去：记 FAILED，带原来的错误前缀")
    void notSentIsFailedWithPrefix() {
        String text = McpAccounting.finish(McpCallResult.notSent("MCP 进程已退出"));
        assertEquals(McpToolCallback.ERROR_PREFIX + "MCP 进程已退出", text);
        assertEquals(ToolExecutionStatus.Kind.FAILED, ToolExecutionStatus.take());
        assertEquals(McpCallResult.Delivery.NOT_SENT, McpCallResult.notSent("x").delivery());
        assertEquals(McpCallResult.Delivery.UNKNOWN, McpCallResult.fail("x").delivery());
    }

    @Test
    @DisplayName("远端明确 isError 仍是完成的调用：返回错误前缀，但不写 FAILED")
    void completedToolErrorStaysAReturnValue() {
        String text = McpAccounting.finish(McpCallResult.failedAtTool("修改失败: 匹配到 0 处"));
        assertEquals(McpToolCallback.ERROR_PREFIX + "修改失败: 匹配到 0 处", text);
        assertNull(ToolExecutionStatus.take(), "完成的调用不写结论，由派发层记 EXECUTED");
        assertEquals(McpCallResult.Delivery.COMPLETED,
                McpCallResult.failedAtTool("修改失败: 匹配到 0 处").delivery());
    }

    @Test
    @DisplayName("普通成功文本原样返回，不写结论")
    void okTextIsUnchanged() {
        assertEquals("页面内容", McpAccounting.finish(McpCallResult.ok("页面内容")));
        assertNull(ToolExecutionStatus.take());
    }

    @Test
    @DisplayName("空结果按 FAILED 处理，不记成功")
    void nullResultIsFailed() {
        assertEquals(McpToolCallback.ERROR_PREFIX + "无结果", McpAccounting.finish(null));
        assertEquals(ToolExecutionStatus.Kind.FAILED, ToolExecutionStatus.take());
    }
}
