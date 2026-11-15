package com.example.jingjingai2.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 客户端开关（前缀 {@code app.mcp.*}）。
 *
 * <p>默认 {@code enabled=false}：关掉时工具不下发、提示词整段不出现、不拉起任何子进程。
 * 本项目是 MCP <strong>Host</strong>（连别人的 Server），不是 Server。
 */
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    /**
     * 总开关。关闭时行为必须与加 MCP 之前 bit 级一致
     * （工具数、工具 JSON、SYSTEM_PROMPT、子房间白名单）。
     */
    private boolean enabled = false;

    /** 发给 Server 的 protocolVersion。第一期只谈 2024-11-05。 */
    private String protocolVersion = "2024-11-05";

    /** initialize 握手超时 */
    private long initializeTimeoutMs = 15_000;

    /** tools/list、tools/call 超时 */
    private long requestTimeoutMs = 30_000;

    /** 工具结果写入 messages 前的字符硬顶 */
    private int maxResultChars = 8000;

    /**
     * 本轮 MCP 工具怎么下发：{@code auto}（默认，已暴露工具定义的估算 token
     * 达到上下文窗口的 {@link #autoThresholdPercent}% 才 deferred）/
     * {@code eager}（全部下发）/ {@code deferred}（只下发 mcpToolSearch + 已激活）。
     */
    private String toolLoading = "auto";

    /**
     * auto 的旧阈值（绝对 token 数）。保留是为了不让已有配置报错，判定不再用它。
     */
    private int autoThresholdTokens = 1500;

    /**
     * auto：已暴露 MCP 工具定义的估算 token ≥ 上下文窗口的这个百分比才 deferred。默认 10。
     */
    private int autoThresholdPercent = 10;

    /**
     * auto 的个数闸。已暴露的 MCP 工具多过这个数就 deferred，不再等百分比。
     * ≤0 表示只看百分比。默认 8，和 {@link #maxActiveTools} 对齐：
     * 一次塞给模型的 mcp__ 本来就不该比「已激活上限」还多。
     */
    private int autoEagerMaxTools = 8;

    /**
     * 当前模型的上下文窗口（token）。项目没有从模型 API 读取窗口的途径，
     * 这里显式配置；≤0 表示未知，auto 退回 eager。默认按 DeepSeek 的 128K 窗口。
     */
    private int contextWindowTokens = 128_000;

    /** deferred 下同时激活的 mcp__ 上限。超出时先保留每条 ACTIVATED 行的第一个名字，其余名额按最近出现的补齐。默认 8。 */
    private int maxActiveTools = 8;

    /** mcpToolSearch 一次最多返回几条，硬顶 8。默认 5。 */
    private int searchMaxResults = 5;

    /**
     * 会话里出现不可信 MCP 结果之后：
     * {@code ask}（默认，危险内置工具改为逐次审批，不从名单里摘掉）/
     * {@code strip-dangerous}（旧行为：摘掉写类工具并用固定句子拒绝）/
     * {@code off}（不因污点改变权限）。
     */
    private String untrustedContentPolicy = "ask";

    /**
     * server 名 → 启动规格。Map 的 key 就是 {@code mcp__{key}__{tool}} 里的 server 段。
     */
    private Map<String, McpServerSpec> servers = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }

    public long getInitializeTimeoutMs() { return initializeTimeoutMs; }
    public void setInitializeTimeoutMs(long initializeTimeoutMs) { this.initializeTimeoutMs = initializeTimeoutMs; }

    public long getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }

    public int getMaxResultChars() { return maxResultChars; }
    public void setMaxResultChars(int maxResultChars) { this.maxResultChars = maxResultChars; }

    public String getToolLoading() { return toolLoading; }
    public void setToolLoading(String toolLoading) {
        this.toolLoading = toolLoading == null || toolLoading.isBlank() ? "eager" : toolLoading;
    }

    public int getAutoThresholdTokens() { return autoThresholdTokens; }
    public void setAutoThresholdTokens(int autoThresholdTokens) { this.autoThresholdTokens = autoThresholdTokens; }

    public int getAutoThresholdPercent() { return autoThresholdPercent; }
    public void setAutoThresholdPercent(int autoThresholdPercent) { this.autoThresholdPercent = autoThresholdPercent; }

    public int getAutoEagerMaxTools() { return autoEagerMaxTools; }
    public void setAutoEagerMaxTools(int autoEagerMaxTools) { this.autoEagerMaxTools = autoEagerMaxTools; }

    public int getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public int getMaxActiveTools() { return maxActiveTools; }
    public void setMaxActiveTools(int maxActiveTools) { this.maxActiveTools = maxActiveTools; }

    public int getSearchMaxResults() { return searchMaxResults; }
    public void setSearchMaxResults(int searchMaxResults) { this.searchMaxResults = searchMaxResults; }

    /** 非法或 ≤0 时回落到 8。 */
    public int effectiveMaxActiveTools() {
        return maxActiveTools < 1 ? 8 : maxActiveTools;
    }

    /** 夹到 [1, 8]，默认 5。 */
    public int effectiveSearchMaxResults() {
        int n = searchMaxResults < 1 ? 5 : searchMaxResults;
        return Math.min(n, 8);
    }

    public String getUntrustedContentPolicy() { return untrustedContentPolicy; }
    public void setUntrustedContentPolicy(String untrustedContentPolicy) {
        this.untrustedContentPolicy = untrustedContentPolicy == null || untrustedContentPolicy.isBlank()
                ? "ask" : untrustedContentPolicy.trim();
    }

    /**
     * 真正生效的策略。{@code ask} 不再降级。非法取值回落到 ask。
     */
    public String effectiveUntrustedPolicy() {
        String p = untrustedContentPolicy == null ? "ask"
                : untrustedContentPolicy.trim().toLowerCase();
        if ("ask".equals(p) || "off".equals(p) || "strip-dangerous".equals(p)) return p;
        return "ask";
    }

    public boolean isAskPolicyConfigured() {
        return untrustedContentPolicy != null && "ask".equalsIgnoreCase(untrustedContentPolicy.trim());
    }

    public Map<String, McpServerSpec> getServers() { return servers; }
    public void setServers(Map<String, McpServerSpec> servers) {
        this.servers = servers == null ? new LinkedHashMap<>() : servers;
    }
}
