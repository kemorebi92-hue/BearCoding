package com.example.jingjingai2.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一份 MCP Server 配置（{@code app.mcp.servers.<name>}）。
 *
 * <p>{@code command} + {@code args} 用 {@link ProcessBuilder} 的参数数组启动，
 * 不用 {@code cmd /c} 拼字符串。
 *
 * <p>暴露规则：<strong>默认暴露该 server 发现的所有工具</strong>，用 {@code disabledTools} 排除个别工具。
 * {@code enabledTools} 已废弃，写了也会被忽略（只为兼容旧 YAML 保留字段）。
 *
 * <p>注意：暴露（能不能被模型看到）与授权（能不能被执行）是两层。被暴露不等于被允许执行，
 * 执行仍要过统一权限网关（默认 ASK）和 URL 安全守卫。
 */
public class McpServerSpec {

    /** 远程 HTTP 没有单独配置超时时的下限。公网服务冷启动可能远慢于本地进程。 */
    public static final long HTTP_MIN_INIT_TIMEOUT_MS = 90_000L;

    /** 可执行文件（Windows 上 {@code npx} 会在启动时解析成 {@code npx.cmd}） */
    private String command;

    private List<String> args = new ArrayList<>();

    /** 额外环境变量，覆盖在「最小 PATH」之上，不继承父进程全量 env */
    private Map<String, String> env = new LinkedHashMap<>();

    /**
     * 【已废弃，不再生效】旧的部署层暴露白名单。
     *
     * <p>保留字段只为兼容旧 YAML：配了不会启动失败、不会报错，但<strong>不再收窄暴露范围</strong>。
     * 现在默认暴露该 server 发现的所有工具；要排除个别工具请用 {@link #disabledTools}。
     * 启动时会为仍配置了本字段的 server 打一次废弃警告（见 {@code McpClientManager#warnBootConfig}）。
     *
     * @deprecated 不参与暴露判定，请改用 {@link #disabledTools}
     */
    @Deprecated
    private List<String> enabledTools = new ArrayList<>();

    /**
     * 从该 server 发现的所有工具里摘掉这些<strong>原始</strong>工具名（server 侧 name，不是 mcp__ 前缀）。
     * 被摘掉的工具不下发、面板灰显、不能改，也不进搜索目录。
     */
    private List<String> disabledTools = new ArrayList<>();

    /**
     * 给模型看的一句话介绍（deferred 提示词列 server 不列工具时用）。空 = 只写 server 名。
     */
    private String description = "";

    /**
     * 该 server 的 initialize 超时（秒）。≤0 用全局 {@code app.mcp.initialize-timeout-ms}。
     * Playwright 冷启动实测约 24s，需要大于默认 15s。
     */
    private int connectTimeoutSeconds = 0;

    /**
     * 该 server 工具结果字符硬顶。≤0 用全局 {@code app.mcp.max-result-chars}。
     */
    private int maxResultChars = 0;

    /**
     * 页面类工具（原始名或暴露名）。新的同类结果出现时，更早的换成省略标记。
     * 空 = 不修剪。
     */
    private List<String> historyPruneTools = new ArrayList<>();

    /**
     * 短于这个长度的结果不参与修剪。≤0 用 {@link McpHistoryPruner#DEFAULT_MIN_CHARS}（500）。
     */
    private int historyPruneMinChars = 0;

    /**
     * {@code trusted} 或 {@code untrusted}。默认 untrusted：结果加包装前缀，会话记污点。
     */
    private String trust = "untrusted";

    /** 模型填的 URL 参数检查。null 时仍用默认策略（arg-names=[url]，mode=default）。 */
    private UrlPolicy urlPolicy = new UrlPolicy();

    /**
     * 传输类型。缺省 / null / 空白按 stdio（兼容旧 YAML）。
     * 当前运行时仍只连接 stdio；streamable_http 字段供配置落库与未来 HttpMcpSession 使用。
     */
    private String transport;

    /** Streamable HTTP base URI；stdio 时不用。 */
    private String baseUrl;

    /** Streamable HTTP path，默认 /mcp；stdio 时不用。 */
    private String endpointPath;

    /** none / bearer；本阶段不携带密钥。 */
    private String authType;
    /** 个人 HTTP 服务的配置主键。YAML 服务没有这一项，也不能挂用户凭证。 */
    private Long serverConfigId;

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public Long getServerConfigId() { return serverConfigId; }
    public void setServerConfigId(Long serverConfigId) { this.serverConfigId = serverConfigId; }

    public String normalizedTransport() {
        if (transport == null || transport.isBlank()) {
            return com.example.jingjingai2.entity.McpServerConfig.TRANSPORT_STDIO;
        }
        return transport.trim();
    }

    public boolean isStreamableHttp() {
        return com.example.jingjingai2.entity.McpServerConfig.TRANSPORT_STREAMABLE_HTTP
                .equals(normalizedTransport());
    }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args == null ? new ArrayList<>() : args; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env == null ? new LinkedHashMap<>() : env; }

    public List<String> getEnabledTools() { return enabledTools; }
    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools == null ? new ArrayList<>() : enabledTools;
    }

    public List<String> getDisabledTools() { return disabledTools; }
    public void setDisabledTools(List<String> disabledTools) {
        this.disabledTools = disabledTools == null ? new ArrayList<>() : disabledTools;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getMaxResultChars() { return maxResultChars; }
    public void setMaxResultChars(int maxResultChars) { this.maxResultChars = maxResultChars; }

    public List<String> getHistoryPruneTools() { return historyPruneTools; }
    public void setHistoryPruneTools(List<String> historyPruneTools) {
        this.historyPruneTools = historyPruneTools == null ? new ArrayList<>() : historyPruneTools;
    }

    public int getHistoryPruneMinChars() { return historyPruneMinChars; }
    public void setHistoryPruneMinChars(int historyPruneMinChars) {
        this.historyPruneMinChars = historyPruneMinChars;
    }

    public int effectiveHistoryPruneMinChars() {
        return historyPruneMinChars > 0 ? historyPruneMinChars : McpHistoryPruner.DEFAULT_MIN_CHARS;
    }

    public String getTrust() { return trust; }
    public void setTrust(String trust) {
        this.trust = (trust == null || trust.isBlank()) ? "untrusted" : trust.trim();
    }

    public boolean isUntrusted() {
        return !"trusted".equalsIgnoreCase(trust);
    }

    public UrlPolicy getUrlPolicy() { return urlPolicy; }
    public void setUrlPolicy(UrlPolicy urlPolicy) {
        this.urlPolicy = urlPolicy == null ? new UrlPolicy() : urlPolicy;
    }

    /**
     * initialize 超时毫秒：server 级秒数优先，否则全局。
     *
     * <p>远程 HTTP 冷启动明显更慢（Postman Minimal 实测约 39 秒），全局默认 15 秒不够；
     * 没有单独配置时，HTTP 至少等 {@link #HTTP_MIN_INIT_TIMEOUT_MS}。stdio 仍用全局值，
     * 免得本地进程启动慢把普通服务的失败检测一起拖长。
     */
    public long initializeTimeoutMs(McpProperties props) {
        if (connectTimeoutSeconds > 0) return connectTimeoutSeconds * 1000L;
        long global = props == null ? 15_000L : props.getInitializeTimeoutMs();
        return isStreamableHttp() ? Math.max(global, HTTP_MIN_INIT_TIMEOUT_MS) : global;
    }

    /** 结果字符硬顶：server 级优先，否则全局。 */
    public int effectiveMaxResultChars(McpProperties props) {
        if (maxResultChars > 0) return maxResultChars;
        return props == null ? 8000 : props.getMaxResultChars();
    }

    /**
     * 会不会拉起另一个进程或换一套工具。相同则连接时不拆掉已经活着的会话。
     * 不比较介绍文字和 URL 策略。
     */
    public boolean sameLaunchConfig(McpServerSpec other) {
        if (other == null) return false;
        return Objects.equals(command, other.command)
                && Objects.equals(args, other.args)
                && Objects.equals(env, other.env)
                && Objects.equals(disabledTools, other.disabledTools)
                && Objects.equals(trust, other.trust)
                && connectTimeoutSeconds == other.connectTimeoutSeconds
                && maxResultChars == other.maxResultChars
                && Objects.equals(normalizedTransport(), other.normalizedTransport())
                && Objects.equals(baseUrl, other.baseUrl)
                && Objects.equals(endpointPath, other.endpointPath)
                && Objects.equals(authType, other.authType)
                && Objects.equals(serverConfigId, other.serverConfigId);
    }

    /**
     * Host 侧 URL 策略。{@code mode=default} 允许 localhost / RFC1918，
     * 仍拒绝元数据、userinfo、数字伪装 IP、非 http(s)。{@code strict} 再拦私有网段。
     */
    public static class UrlPolicy {
        private String mode = "default";
        private List<String> argNames = new ArrayList<>(List.of("url"));
        private List<String> schemes = new ArrayList<>(List.of("http", "https"));
        private List<String> allowedHosts = new ArrayList<>();

        public String getMode() { return mode; }
        public void setMode(String mode) {
            this.mode = (mode == null || mode.isBlank()) ? "default" : mode.trim();
        }

        public boolean isStrict() {
            return "strict".equalsIgnoreCase(mode);
        }

        public List<String> getArgNames() { return argNames; }
        public void setArgNames(List<String> argNames) {
            this.argNames = argNames == null || argNames.isEmpty()
                    ? new ArrayList<>(List.of("url")) : argNames;
        }

        public List<String> getSchemes() { return schemes; }
        public void setSchemes(List<String> schemes) {
            this.schemes = schemes;
        }

        public List<String> effectiveSchemes() {
            if (schemes == null || schemes.isEmpty()) return List.of("http", "https");
            return schemes;
        }

        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
        }
    }
}
