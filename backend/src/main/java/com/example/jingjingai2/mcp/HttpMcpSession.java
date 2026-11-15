package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 远程 Streamable HTTP MCP 会话（官方 SDK）。
 *
 * <p>{@code authType=none} 不发送 Authorization。{@code bearer} 的令牌由调用方在
 * {@link McpEndpointGuard} 通过之后解密并传入，经 {@code customizeRequest} 加到每次请求。
 * 自动重定向保持关闭。DNS 重绑定仍需部署层控制。
 */
public final class HttpMcpSession implements McpSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final McpSyncClient client;
    private final HttpClientStreamableHttpTransport transport;
    private final long requestTimeoutMs;
    private final int maxResultChars;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile List<McpRemoteTool> tools = List.of();
    private volatile String negotiatedProtocol = "";

    private HttpMcpSession(String serverName, McpSyncClient client, HttpClientStreamableHttpTransport transport,
                           long requestTimeoutMs, int maxResultChars) {
        this.serverName = serverName;
        this.client = client;
        this.transport = transport;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxResultChars = maxResultChars;
    }

    public static HttpMcpSession start(String serverName, McpServerSpec spec, McpProperties props) {
        return start(serverName, spec, props, null);
    }

    /**
     * @param bearerToken 仅在 {@code authType=bearer} 时使用。调用方必须先过 Guard，
     *                    再从当前用户自己的凭证里解密。本方法不查库。
     */
    public static HttpMcpSession start(String serverName, McpServerSpec spec, McpProperties props,
                                        String bearerToken) {
        if (spec == null || !spec.isStreamableHttp()) {
            throw new McpException("HttpMcpSession 只接受 streamable_http 配置");
        }
        String auth = authOf(spec);
        if (!McpServerConfig.AUTH_NONE.equals(auth) && !McpServerConfig.AUTH_BEARER.equals(auth)) {
            throw new McpException("远程 HTTP 不支持的 authType=" + auth);
        }
        String baseUrl = spec.getBaseUrl() == null ? "" : spec.getBaseUrl().trim();
        String guard = McpEndpointGuard.rejectReason(baseUrl);
        if (guard != null) {
            throw new McpException("远程 endpoint 不被允许（" + guard + "）");
        }
        if (McpServerConfig.AUTH_BEARER.equals(auth)) {
            if (bearerToken == null || bearerToken.isBlank()) {
                throw new McpException("Bearer 凭证不可用，拒绝连接");
            }
            return connectInternal(serverName, spec, props, baseUrl, bearerToken);
        }
        return connectInternal(serverName, spec, props, baseUrl, null);
    }

    /**
     * 测试专用：跳过 {@link McpEndpointGuard}（以便 loopback 假服务器）。
     * none 不发送 Authorization。bearer 必须带上调用方给出的假令牌。
     */
    static HttpMcpSession connectForTest(String serverName, McpServerSpec spec, McpProperties props) {
        return connectForTest(serverName, spec, props, null);
    }

    static HttpMcpSession connectForTest(String serverName, McpServerSpec spec, McpProperties props,
                                          String bearerToken) {
        if (spec == null || !spec.isStreamableHttp()) {
            throw new McpException("HttpMcpSession 只接受 streamable_http 配置");
        }
        String auth = authOf(spec);
        if (McpServerConfig.AUTH_BEARER.equals(auth)) {
            if (bearerToken == null || bearerToken.isBlank()) {
                throw new McpException("Bearer 凭证不可用，拒绝连接");
            }
        } else if (!McpServerConfig.AUTH_NONE.equals(auth)) {
            throw new McpException("远程 HTTP 不支持的 authType=" + auth);
        } else {
            bearerToken = null;
        }
        String baseUrl = spec.getBaseUrl() == null ? "" : spec.getBaseUrl().trim();
        return connectInternal(serverName, spec, props, baseUrl, bearerToken);
    }

    private static String authOf(McpServerSpec spec) {
        String auth = spec.getAuthType();
        if (auth == null || auth.isBlank()) return McpServerConfig.AUTH_NONE;
        return auth.trim();
    }

    private static HttpMcpSession connectInternal(String serverName, McpServerSpec spec, McpProperties props,
                                                    String baseUrl, String bearerToken) {
        String path = spec.getEndpointPath();
        if (path == null || path.isBlank()) path = McpServerConfigValidator.DEFAULT_ENDPOINT_PATH;
        path = path.trim();
        String pathErr = McpServerConfigValidator.checkEndpointPath(path);
        if (pathErr != null) throw new McpException(pathErr);

        long initTimeout = spec.initializeTimeoutMs(props);
        long requestTimeout = props == null ? 30_000L : props.getRequestTimeoutMs();
        int maxChars = spec.effectiveMaxResultChars(props);
        Duration connectTimeout = Duration.ofMillis(Math.max(1_000L, Math.min(initTimeout, 60_000L)));

        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(path)
                .connectTimeout(connectTimeout)
                .customizeClient(b -> McpEndpointGuard.applySafeHttpClient(b, connectTimeout))
                .supportedProtocolVersions(List.of(
                        ProtocolVersions.MCP_2024_11_05,
                        ProtocolVersions.MCP_2025_03_26,
                        ProtocolVersions.MCP_2025_06_18,
                        ProtocolVersions.MCP_2025_11_25));
        if (bearerToken != null) {
            builder.customizeRequest(request -> request.header("Authorization", "Bearer " + bearerToken));
        }
        HttpClientStreamableHttpTransport transport = builder.build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(Math.max(1_000L, requestTimeout)))
                .initializationTimeout(Duration.ofMillis(Math.max(1_000L, initTimeout)))
                .build();

        HttpMcpSession session = new HttpMcpSession(serverName, client, transport, requestTimeout, maxChars);
        try {
            session.handshake();
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    private void handshake() {
        McpSchema.InitializeResult init = client.initialize();
        if (init == null) throw new McpException("MCP initialize 返回空");
        negotiatedProtocol = init.protocolVersion() == null ? "" : init.protocolVersion();
        System.out.println("[MCP] server=" + serverName + " HTTP 协议 " + negotiatedProtocol
                + " serverInfo=" + (init.serverInfo() == null ? "?" : init.serverInfo().name()));
        McpSchema.ListToolsResult listed = client.listTools();
        this.tools = parseTools(listed);
        System.out.println("[MCP] server=" + serverName + " HTTP 已连接，工具 " + tools.size() + " 个");
    }

    String negotiatedProtocol() {
        return negotiatedProtocol;
    }

    private List<McpRemoteTool> parseTools(McpSchema.ListToolsResult listed) {
        List<McpRemoteTool> out = new ArrayList<>();
        if (listed == null || listed.tools() == null) return List.of();
        for (McpSchema.Tool t : listed.tools()) {
            if (t == null || t.name() == null || t.name().isBlank()) continue;
            String schemaJson;
            try {
                schemaJson = t.inputSchema() == null
                        ? McpSchemaSanitizer.FALLBACK
                        : MAPPER.writeValueAsString(t.inputSchema());
            } catch (Exception e) {
                schemaJson = McpSchemaSanitizer.FALLBACK;
            }
            out.add(new McpRemoteTool(t.name(), t.description() == null ? "" : t.description(),
                    McpSchemaSanitizer.sanitize(schemaJson), McpToolAnnotations.none()));
        }
        return List.copyOf(out);
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public List<McpRemoteTool> listTools() {
        return tools;
    }

    @Override
    public boolean isAlive() {
        return !closed.get() && client != null && client.isInitialized();
    }

    @Override
    public McpCallResult callTool(String originalName, String argumentsJson) {
        McpCallResult raw = callToolRaw(originalName, argumentsJson);
        String text = McpContentMapper.truncate(raw.text(), maxResultChars);
        return new McpCallResult(raw.isError(), text, raw.delivery());
    }

    @Override
    public McpCallResult callToolRaw(String originalName, String argumentsJson) {
        if (closed.get()) return McpCallResult.notSent("MCP 会话已关闭");
        if (!isAlive()) return McpCallResult.notSent("MCP HTTP 会话不可用");
        try {
            Map<String, Object> args = parseArgs(argumentsJson);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(originalName, args));
            boolean isError = result != null && Boolean.TRUE.equals(result.isError());
            String text = contentToText(result);
            if (isError) return McpCallResult.failedAtTool(text.isBlank() ? "isError=true" : text);
            return McpCallResult.ok(text);
        } catch (RuntimeException e) {
            // SDK 把连接失败和响应超时都包成同一次 sendAsync 的异常，异常类型证明不了
            // 请求有没有真正发出去，所以一律按结果未知处理，不自动重发。
            String m = safeMessage(e);
            return McpCallResult.fail(m);
        }
    }

    private static Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(argumentsJson, Map.class);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String contentToText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content part : result.content()) {
            if (part instanceof McpSchema.TextContent text) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text.text() == null ? "" : text.text());
            } else if (part instanceof McpSchema.EmbeddedResource er
                    && er.resource() instanceof McpSchema.TextResourceContents trc) {
                // 文本资源：正文在 resource.text，不是二进制。blob 资源仍落到下面的占位分支。
                if (sb.length() > 0) sb.append('\n');
                sb.append(trc.text() == null ? "" : trc.text());
            } else {
                if (sb.length() > 0) sb.append('\n');
                sb.append(McpContentMapper.BINARY_PLACEHOLDER);
            }
        }
        return sb.toString();
    }

    private static String safeMessage(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        // 避免把可能的 Authorization 头或超长内部细节打进模型可见结果
        String lower = m.toLowerCase();
        if (lower.contains("authorization") || lower.contains("bearer ")) {
            return "MCP HTTP 调用失败（认证相关错误已省略细节）";
        }
        if (m.length() > 400) return m.substring(0, 400) + "…";
        return m;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Exception ignored) {
                    try { client.close(); } catch (Exception ignored2) {}
                }
            }
        } finally {
            if (transport != null) {
                try {
                    transport.closeGracefully().block(Duration.ofSeconds(5));
                } catch (Exception ignored) {
                    try { transport.close(); } catch (Exception ignored2) {}
                }
            }
        }
    }
}
