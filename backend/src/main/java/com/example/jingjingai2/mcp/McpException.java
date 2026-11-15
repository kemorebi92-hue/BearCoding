package com.example.jingjingai2.mcp;

/**
 * MCP 协议/传输层失败。不要让它穿透 Agent 循环 —— {@link McpToolCallback} 会收成
 * {@code MCP 工具调用失败: ...} 字符串。
 */
public class McpException extends RuntimeException {
    public McpException(String message) { super(message); }
    public McpException(String message, Throwable cause) { super(message, cause); }
}
