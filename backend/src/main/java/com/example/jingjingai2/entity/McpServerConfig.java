package com.example.jingjingai2.entity;

/** mcp_server_config 一行。运行时连接状态不在这里。 */
public class McpServerConfig {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable_http";
    public static final String AUTH_NONE = "none";
    public static final String AUTH_BEARER = "bearer";

    private Long id;
    private String name;
    /** stdio 可执行文件名；streamable_http 时必须为空。 */
    private String command;
    private String argsJson;
    private String envJson;
    private String disabledToolsJson;
    private String trust;
    private Integer connectTimeoutSeconds;
    private Integer maxResultChars;
    private boolean enabled = true;
    private Long createdBy;
    /**
     * 传输类型：{@code stdio} / {@code streamable_http}。
     * 库外旧对象或反序列化缺省时按 {@link #normalizedTransport()} 视为 stdio。
     */
    private String transport;
    /** Streamable HTTP 的 base URI，例如 {@code https://mcp.example.com}。stdio 时为空。 */
    private String baseUrl;
    /** Streamable HTTP 的 path，默认 {@code /mcp}。stdio 时为空。 */
    private String endpointPath;
    /**
     * 认证方式：{@code none} / {@code bearer}。
     * 本阶段不落库任何密钥；{@code bearer} 仅作预留标记且当前保存会被拒绝。
     */
    private String authType;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }
    public String getEnvJson() { return envJson; }
    public void setEnvJson(String envJson) { this.envJson = envJson; }
    public String getDisabledToolsJson() { return disabledToolsJson; }
    public void setDisabledToolsJson(String disabledToolsJson) { this.disabledToolsJson = disabledToolsJson; }
    public String getTrust() { return trust; }
    public void setTrust(String trust) { this.trust = trust; }
    public Integer getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(Integer connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }
    public Integer getMaxResultChars() { return maxResultChars; }
    public void setMaxResultChars(Integer maxResultChars) { this.maxResultChars = maxResultChars; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    /** 缺省 / 空白 / null → stdio（兼容历史行与旧请求体）。 */
    public String normalizedTransport() {
        if (transport == null || transport.isBlank()) return TRANSPORT_STDIO;
        return transport.trim();
    }

    public boolean isStreamableHttp() {
        return TRANSPORT_STREAMABLE_HTTP.equals(normalizedTransport());
    }

    public String normalizedAuthType() {
        if (authType == null || authType.isBlank()) return AUTH_NONE;
        return authType.trim();
    }
}
