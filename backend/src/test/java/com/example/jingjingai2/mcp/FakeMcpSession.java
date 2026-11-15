package com.example.jingjingai2.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 测试替身：不拉进程，按注册表返回工具、按 handler 响应 call。
 */
public final class FakeMcpSession implements McpSession {

    private final String serverName;
    private final List<McpRemoteTool> tools;
    private BiFunction<String, String, McpCallResult> handler;
    private RuntimeException throwOnCall;
    private volatile boolean closed;
    private volatile boolean alive = true;
    private int callCount;
    private int closeCount;
    private boolean failOnClose;

    public FakeMcpSession(String serverName, List<McpRemoteTool> tools) {
        this.serverName = serverName;
        this.tools = tools == null ? new ArrayList<>() : tools;
        this.handler = (name, args) -> McpCallResult.ok("ok:" + name + ":" + args);
    }

    public FakeMcpSession handler(BiFunction<String, String, McpCallResult> handler) {
        this.handler = handler;
        return this;
    }

    public FakeMcpSession throwOnCall(RuntimeException e) {
        this.throwOnCall = e;
        return this;
    }

    public int callCount() { return callCount; }
    public int closeCount() { return closeCount; }
    public boolean isClosed() { return closed; }

    public FakeMcpSession failOnClose() {
        this.failOnClose = true;
        return this;
    }

    public FakeMcpSession die() {
        alive = false;
        return this;
    }

    @Override
    public boolean isAlive() {
        return alive && !closed;
    }

    @Override
    public String serverName() { return serverName; }

    @Override
    public List<McpRemoteTool> listTools() {
        return new ArrayList<>(tools);
    }

    @Override
    public McpCallResult callTool(String originalName, String argumentsJson) {
        callCount++;
        if (throwOnCall != null) throw throwOnCall;
        return handler.apply(originalName, argumentsJson);
    }

    @Override
    public void close() {
        closeCount++;
        if (failOnClose) throw new McpException("关闭失败");
        closed = true;
        alive = false;
    }
}
