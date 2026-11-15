package com.example.jingjingai2.mcp;

import com.example.jingjingai2.tool.ToolExecutionStatus;

/**
 * 把已经算好的 {@link McpCallResult} 变成「给模型的字符串」，同时把执行结论写进
 * {@link ToolExecutionStatus}。stdio 和 HTTP 共用这一处，不看文案前缀。
 *
 * <p>返回给模型的文本保持原样（错误前缀或 {@code UNKNOWN_OUTCOME}）；
 * 结论只给记账层看。{@code ToolDispatcher} 在记 {@code EXECUTED} 之前会先看这里有没有结论。
 */
public final class McpAccounting {

    private McpAccounting() {}

    public static String finish(McpCallResult result) {
        if (result == null) {
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
            return McpToolCallback.ERROR_PREFIX + "无结果";
        }
        if (result.delivery() == McpCallResult.Delivery.UNKNOWN) {
            // 可能已经发出。固定用这句，不把 success=false 说成「确定没执行」。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.UNKNOWN);
            return McpToolCallback.UNKNOWN_OUTCOME;
        }
        if (result.delivery() == McpCallResult.Delivery.NOT_SENT) {
            // 调用开始前就能证明没发出去：不是权限拒绝，也不是成功。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
            return McpToolCallback.ERROR_PREFIX + result.text();
        }
        String text = result.text() == null ? "" : result.text();
        if (result.isError()) {
            // 远端明确的 isError：交互已经完成，前缀只是给模型看的，不是传输失败。
            return McpToolCallback.ERROR_PREFIX + text;
        }
        return text;
    }
}

