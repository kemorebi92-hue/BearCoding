package com.example.jingjingai2.mcp;

import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 把一个远端 MCP 工具包装成主循环认识的 {@link ToolCallback}。
 *
 * <p>完成的调用（含远端明确的 {@code isError}）仍返回字符串。
 * 没发出去或结果未知时，除了返回原来的错误文字，还会把结构化结论写进
 * {@link ToolExecutionStatus}，避免被派发层记成执行成功。
 */
public final class McpToolCallback implements ToolCallback {

    public static final String ERROR_PREFIX = "MCP 工具调用失败: ";

    /** 请求可能已经发出去，但没拿到结果。不能据此断言执行了还是没执行。 */
    public static final String UNKNOWN_OUTCOME = "MCP 工具执行结果未知，不能判断是否已经执行。"
            + "不要用相同参数立即重试；先核实远端结果，核实不了就告诉用户结果未知。";

    private final McpSession session;
    private final String originalName;
    private final ToolDefinition def;
    private final int maxResultChars;
    private final McpClientManager manager;
    private final McpServerSpec spec;
    /**
     * 该回调所属的运行时槽位。执行时按这个对象路由，不按服务名全局查——
     * 服务被删除后重建会产生新的 Slot，旧回调因此不会落到同名的新服务上。
     */
    private final McpClientManager.Slot slot;

    public McpToolCallback(McpSession session, String exposedName, McpRemoteTool tool, int maxResultChars) {
        this(session, exposedName, tool, maxResultChars, null, null, null);
    }

    public McpToolCallback(McpSession session, String exposedName, McpRemoteTool tool, int maxResultChars,
                           McpClientManager manager, McpServerSpec spec) {
        this(session, exposedName, tool, maxResultChars, manager, spec, null);
    }

    public McpToolCallback(McpSession session, String exposedName, McpRemoteTool tool, int maxResultChars,
                           McpClientManager manager, McpServerSpec spec, McpClientManager.Slot slot) {
        this.session = session;
        this.originalName = tool.originalName();
        this.maxResultChars = maxResultChars;
        this.manager = manager;
        this.spec = spec;
        this.slot = slot;
        String desc = tool.description();
        if (desc == null || desc.isBlank()) {
            desc = "MCP 工具 " + exposedName;
        }
        this.def = ToolDefinition.builder()
                .name(exposedName)
                .description(desc)
                .inputSchema(McpSchemaSanitizer.sanitize(tool.inputSchemaJson()))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return def;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String arguments) {
        try {
            McpServerSpec.UrlPolicy policy = spec == null ? null : spec.getUrlPolicy();
            String why = McpUrlGuard.rejectReason(arguments, policy);
            if (why != null) {
                // 没发出去，也没到网关：不能记成执行成功。
                ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
                return ERROR_PREFIX + "地址不被允许（" + why + "）";
            }
            if (manager != null) {
                if (slot != null) {
                    return manager.invoke(slot, originalName, arguments, maxResultChars);
                }
                return manager.invoke(session == null ? null : session.serverName(),
                        originalName, arguments, maxResultChars);
            }
            return McpAccounting.finish(session.callTool(originalName, arguments));
        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) reason = e.getClass().getSimpleName();
            // 意外异常分不清请求有没有发出去，不能当成普通返回记成执行成功。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.UNKNOWN);
            return ERROR_PREFIX + reason;
        }
    }

    @Override
    public String call(String arguments, ToolContext context) {
        return call(arguments);
    }

    /** 测试/诊断用：server 侧原始工具名。 */
    public String originalName() {
        return originalName;
    }
}
