package com.example.jingjingai2.mcp;

/**
 * 只读：一个 MCP server 当前状态，给下一期前端用。本期不暴露 HTTP 接口。
 */
public final class McpServerStatus {

    private final String name;
    private final String state;
    private final String lastError;
    private final int toolCount;
    private final int offeredCount;

    public McpServerStatus(String name, String state, String lastError, int toolCount, int offeredCount) {
        this.name = name;
        this.state = state;
        this.lastError = lastError;
        this.toolCount = toolCount;
        this.offeredCount = offeredCount;
    }

    public String getName() { return name; }
    public String getState() { return state; }
    public String getLastError() { return lastError; }
    public int getToolCount() { return toolCount; }
    public int getOfferedCount() { return offeredCount; }
}
