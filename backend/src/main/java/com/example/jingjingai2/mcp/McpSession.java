package com.example.jingjingai2.mcp;

import java.util.List;

/**
 * 一个 MCP Server 连接（stdio 子进程，或测试用的假实现）。
 *
 * <p>第一期只覆盖 initialize / tools/list / tools/call。不实现 sampling、elicitation、
 * resources、prompts。关闭时必须把子进程干掉，避免僵尸。
 */
public interface McpSession extends AutoCloseable {

    String serverName();

    List<McpRemoteTool> listTools();

    McpCallResult callTool(String originalName, String argumentsJson);

    /**
     * 同 {@link #callTool}，但不做结果截断。超限落盘需要完整内容。
     * 默认实现退回 {@code callTool}（假实现够用了）。
     */
    default McpCallResult callToolRaw(String originalName, String argumentsJson) {
        return callTool(originalName, argumentsJson);
    }

    /** 进程还在、会话未关。假实现默认 true。 */
    default boolean isAlive() {
        return true;
    }

    @Override
    void close();
}
