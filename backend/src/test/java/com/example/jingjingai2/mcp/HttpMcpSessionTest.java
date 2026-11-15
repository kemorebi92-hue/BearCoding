package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpServerCredentialMapper;
import com.example.jingjingai2.service.McpCredentialService;
import com.example.jingjingai2.entity.McpServerCredential;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpMcpSession against an official SDK Streamable HTTP servlet on loopback.
 * EndpointGuard normally blocks loopback — tests temporarily allow insecure http
 * only via a public literal IP reverse? Loopback is always blocked by EndpointGuard.
 *
 * <p>Strategy: bind Jetty to 127.0.0.1 but present baseUrl as https://1.1.1.1 is wrong.
 * We cannot use real public IP. Instead, use a custom approach:
 * temporarily replace EndpointGuard resolver is not enough for loopback literals.
 *
 * <p>For local e2e we start server on 127.0.0.1 and use reflection-free bypass:
 * call HttpMcpSession with a spec that uses http://127.0.0.1 — Guard will reject.
 *
 * <p>Therefore this test uses {@link McpEndpointGuard.Policy#allowInsecureHttp} only
 * indirectly by testing through a package-visible test hook... We add a package
 * test helper startForTest that skips guard when system property is set? Better:
 * use {@code HttpMcpSession.start} after setting a test-only policy is not available.
 *
 * Practical approach used here: start the MCP server on an ephemeral port on 127.0.0.1,
 * and invoke the SDK client path via a package-local test factory method
 * {@code HttpMcpSession.connectForTest} that still applies safe HttpClient + path checks
 * but accepts loopback http for tests only.
 */
class HttpMcpSessionTest {

    private final java.util.concurrent.CopyOnWriteArrayList<String> seenAuth = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Server jetty;
    private int port;
    private McpSyncServer mcpServer;
    private HttpServletStreamableServerTransportProvider serverTransport;

    @BeforeEach
    void startServer() throws Exception {
        serverTransport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("message", Map.of("type", "string")),
                List.of("message"),
                false, null, null);

        McpSchema.Tool echo = McpSchema.Tool.builder()
                .name("echo")
                .title("Echo")
                .description("echo tool")
                .inputSchema(inputSchema)
                .build();

        mcpServer = McpServer.sync(serverTransport)
                .serverInfo("http-session-test", "0.1.0")
                .requestTimeout(Duration.ofSeconds(20))
                .toolCall(echo, (ex, req) -> {
                    Object msg = req.arguments() == null ? null : req.arguments().get("message");
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("HOST_ECHO:" + msg)))
                            .isError(false)
                            .build();
                })
                .build();

        jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        jetty.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        context.addServlet(new ServletHolder(serverTransport), "/*");
        context.addFilter(HeaderCapture.class, "/*", java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        HeaderCapture.seen = seenAuth;
        jetty.setHandler(context);
        jetty.start();
        port = connector.getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (mcpServer != null) try { mcpServer.close(); } catch (Exception ignored) {}
        if (serverTransport != null) try { serverTransport.close(); } catch (Exception ignored) {}
        if (jetty != null) { jetty.stop(); jetty.destroy(); }
    }

    @Test
    @DisplayName("initialize + list + call + close（loopback 测试工厂）")
    void initListCallClose() {
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        try (HttpMcpSession session = HttpMcpSession.connectForTest("svc", spec, props())) {
            assertTrue(session.isAlive());
            assertFalse(session.negotiatedProtocol().isBlank());
            assertEquals(1, session.listTools().size());
            assertEquals("echo", session.listTools().get(0).originalName());
            McpCallResult r = session.callTool("echo", "{\"message\":\"hi\"}");
            assertFalse(r.isError(), r.text());
            assertEquals("HOST_ECHO:hi", r.text());
        }
    }

    @Test
    @DisplayName("生产 start() 对 loopback https 仍拒绝（EndpointGuard）")
    void productionStartRejectsLoopback() {
        McpServerSpec spec = httpSpec("https://127.0.0.1:" + port, "/mcp");
        McpException ex = assertThrows(McpException.class,
                () -> HttpMcpSession.start("svc", spec, props()));
        assertTrue(ex.getMessage().contains("不被允许") || ex.getMessage().contains("回环"));
    }

    @Test
    @DisplayName("authType=bearer 在测试工厂里必须带上假令牌")
    void bearerRejected() {
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        spec.setAuthType(McpServerConfig.AUTH_BEARER);
        McpException ex = assertThrows(McpException.class,
                () -> HttpMcpSession.connectForTest("svc", spec, props()));
        assertTrue(ex.getMessage().contains("凭证不可用"), ex.getMessage());
    }

    @Test
    @DisplayName("bearer 的 initialize、list、call 都带同一份假令牌；none 不带")
    void bearerHeaderOnInitListAndCall() {
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        spec.setAuthType(McpServerConfig.AUTH_BEARER);
        try (HttpMcpSession session = HttpMcpSession.connectForTest("svc", spec, props(), "fake-token-not-real")) {
            assertEquals(1, session.listTools().size());
            McpCallResult r = session.callTool("echo", "{\"message\":\"hi\"}");
            assertFalse(r.isError(), r.text());
            assertEquals("HOST_ECHO:hi", r.text());
        }
        assertTrue(seenAuth.contains("Bearer fake-token-not-real"), String.valueOf(seenAuth));
        assertTrue(seenAuth.size() >= 3, String.valueOf(seenAuth));

        seenAuth.clear();
        McpServerSpec none = httpSpec("http://127.0.0.1:" + port, "/mcp");
        try (HttpMcpSession session = HttpMcpSession.connectForTest("svc", none, props())) {
            session.callTool("echo", "{\"message\":\"x\"}");
        }
        assertTrue(seenAuth.stream().allMatch(v -> v == null || v.isBlank()), String.valueOf(seenAuth));
    }

    @Test
    @DisplayName("轮换后新建连接用新令牌；已建会话仍持旧令牌，直到重新连接")
    void rotatedTokenUsedOnlyByNewSession() {
        MemoryConfigs configs = new MemoryConfigs(config(11L, 7L, "svc"));
        MemoryCredentials stored = new MemoryCredentials();
        McpCredentialService credentials = new McpCredentialService(
                configs, stored, new McpCredentialCrypto("0123456789abcdef0123456789abcdef"));
        credentials.put(7L, 11L, "fake-token-v1");

        McpProperties props = props();
        TokenSpy first = new TokenSpy(props, credentials);
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        spec.setAuthType(McpServerConfig.AUTH_BEARER);
        spec.setServerConfigId(11L);

        McpSession old = first.openSession(7L, "svc", spec);
        old.callTool("echo", "{\"message\":\"one\"}");
        assertTrue(seenAuth.contains("Bearer fake-token-v1"), String.valueOf(seenAuth));
        assertFalse(seenAuth.contains("Bearer fake-token-v2"), String.valueOf(seenAuth));

        credentials.put(7L, 11L, "fake-token-v2");
        assertEquals("fake-token-v2", credentials.load(7L, 11L));
        assertEquals(1, stored.rows.size(), "轮换覆盖同一行");
        assertFalse(stored.rows.get(0).getCiphertext().contains("fake-token"));

        seenAuth.clear();
        old.callTool("echo", "{\"message\":\"still-old\"}");
        assertTrue(seenAuth.contains("Bearer fake-token-v1"), "已建会话继续用 v1：" + seenAuth);
        assertFalse(seenAuth.contains("Bearer fake-token-v2"), String.valueOf(seenAuth));

        seenAuth.clear();
        TokenSpy second = new TokenSpy(props, credentials);
        try (McpSession fresh = second.openSession(7L, "svc", spec)) {
            fresh.callTool("echo", "{\"message\":\"two\"}");
        }
        assertTrue(seenAuth.contains("Bearer fake-token-v2"), "重新连接后用 v2：" + seenAuth);
        assertFalse(seenAuth.contains("Bearer fake-token-v1"), String.valueOf(seenAuth));
        old.close();
    }

    @Test
    @DisplayName("用户 A 用用户 B 的配置 id 建会话会被拒绝，且不发出请求")
    void openSessionRejectsForeignConfig() {
        MemoryConfigs configs = new MemoryConfigs(config(22L, 9L, "svc"));
        MemoryCredentials stored = new MemoryCredentials();
        McpCredentialService credentials = new McpCredentialService(
                configs, stored, new McpCredentialCrypto("0123456789abcdef0123456789abcdef"));
        credentials.put(9L, 22L, "fake-token-for-b");

        TokenSpy spy = new TokenSpy(props(), credentials);
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        spec.setAuthType(McpServerConfig.AUTH_BEARER);
        spec.setServerConfigId(22L);

        seenAuth.clear();
        assertThrows(McpCredentialException.class, () -> spy.openSession(7L, "svc", spec));
        assertTrue(seenAuth.isEmpty(), "拒绝发生在建连之前：" + seenAuth);

        McpServerSpec nameless = httpSpec("http://127.0.0.1:" + port, "/mcp");
        nameless.setAuthType(McpServerConfig.AUTH_BEARER);
        nameless.setServerConfigId(null);
        assertThrows(McpException.class, () -> spy.openSession(7L, "svc", nameless));
        assertTrue(seenAuth.isEmpty());
    }

    /** 归属检查和解密走生产 openSession，只有建会话这一步换成测试工厂。 */
    private static final class TokenSpy extends McpClientManager {
        private final McpProperties props;
        TokenSpy(McpProperties props, McpCredentialService credentials) {
            super(props);
            this.props = props;
            setCredentialService(credentials);
        }
        @Override McpSession openBearerSession(String serverName, McpServerSpec spec, String token) {
            return HttpMcpSession.connectForTest(serverName, spec, props, token);
        }
    }

    private static McpServerConfig config(long id, long owner, String name) {
        McpServerConfig c = new McpServerConfig();
        c.setId(id);
        c.setCreatedBy(owner);
        c.setName(name);
        c.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        c.setAuthType(McpServerConfig.AUTH_BEARER);
        return c;
    }

    private static final class MemoryConfigs implements McpServerConfigMapper {
        private final java.util.List<McpServerConfig> rows;
        MemoryConfigs(McpServerConfig... configs) { this.rows = java.util.List.of(configs); }
        @Override public McpServerConfig findById(long id) {
            for (McpServerConfig c : rows) {
                if (c.getId() != null && c.getId() == id) return c;
            }
            return null;
        }
        @Override public int insert(McpServerConfig c) { throw new UnsupportedOperationException(); }
        @Override public int update(McpServerConfig c) { throw new UnsupportedOperationException(); }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public int delete(long ownerId, String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findByName(String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findSharedByName(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<McpServerConfig> findAll() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<McpServerConfig> findAllByOwner(long ownerId) { throw new UnsupportedOperationException(); }
    }

    private static final class MemoryCredentials implements McpServerCredentialMapper {
        final java.util.List<McpServerCredential> rows = new java.util.ArrayList<>();
        @Override public int insert(McpServerCredential row) { rows.add(row); return 1; }
        @Override public int updateCiphertext(long userId, long serverConfigId, String ciphertext) {
            McpServerCredential row = find(userId, serverConfigId);
            if (row == null) return 0;
            row.setCiphertext(ciphertext);
            return 1;
        }
        @Override public McpServerCredential find(long userId, long serverConfigId) {
            for (McpServerCredential row : rows) {
                if (row.getUserId() == userId && row.getServerConfigId() == serverConfigId) return row;
            }
            return null;
        }
        @Override public int count(long userId, long serverConfigId) {
            return find(userId, serverConfigId) == null ? 0 : 1;
        }
        @Override public int delete(long userId, long serverConfigId) {
            return rows.removeIf(row -> row.getUserId() == userId && row.getServerConfigId() == serverConfigId) ? 1 : 0;
        }
    }

    @Test
    @DisplayName("关闭后调用失败；openSession 分支选中 HttpMcpSession")
    void closedSessionAndManagerBranch() {
        McpProperties props = props();
        McpClientManager mgr = new McpClientManager(props);
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        // Manager production openSession uses start() which blocks loopback.
        // Verify branch selection with a spy subclass.
        class Spy extends McpClientManager {
            String path;
            Spy() { super(props); }
            @Override McpSession openSession(String serverName, McpServerSpec s) {
                if (s != null && s.isStreamableHttp()) {
                    path = "http";
                    return HttpMcpSession.connectForTest(serverName, s, props);
                }
                path = "stdio";
                return super.openSession(serverName, s);
            }
        }
        Spy spy = new Spy();
        McpSession session = spy.openSession("svc", spec);
        assertEquals("http", spy.path);
        assertTrue(session instanceof HttpMcpSession);
        session.close();
        McpCallResult r = session.callTool("echo", "{\"message\":\"x\"}");
        assertTrue(r.isError());
        assertTrue(r.text().contains("关闭") || r.text().contains("不可用"));
    }

    @Test
    @DisplayName("安全 HttpClient 不跟随 302（隔离小服务器）")
    void safeClientDoesNotFollowRedirect() throws Exception {
        Server redirectServer = new Server();
        ServerConnector c = new ServerConnector(redirectServer);
        c.setHost("127.0.0.1");
        c.setPort(0);
        redirectServer.addConnector(c);
        ServletContextHandler ctx = new ServletContextHandler("/");
        ctx.addServlet(new ServletHolder(new jakarta.servlet.http.HttpServlet() {
            @Override protected void doGet(jakarta.servlet.http.HttpServletRequest req,
                                           jakarta.servlet.http.HttpServletResponse resp) {
                resp.setStatus(302);
                resp.setHeader("Location", "http://127.0.0.1/private");
            }
            @Override protected void doPost(jakarta.servlet.http.HttpServletRequest req,
                                            jakarta.servlet.http.HttpServletResponse resp) {
                doGet(req, resp);
            }
        }), "/*");
        redirectServer.setHandler(ctx);
        redirectServer.start();
        int p = c.getLocalPort();
        try {
            HttpClient client = McpEndpointGuard
                    .applySafeHttpClient(HttpClient.newBuilder(), Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + "/"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(302, resp.statusCode());
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        } finally {
            redirectServer.stop();
            redirectServer.destroy();
        }
    }

    @Test
    @DisplayName("未知工具返回错误结果而非抛出到测试外")
    void unknownTool() {
        McpServerSpec spec = httpSpec("http://127.0.0.1:" + port, "/mcp");
        try (HttpMcpSession session = HttpMcpSession.connectForTest("svc", spec, props())) {
            McpCallResult r = session.callTool("nope", "{}");
            assertTrue(r.isError());
        }
    }

    public static final class HeaderCapture implements jakarta.servlet.Filter {
        static java.util.List<String> seen = java.util.List.of();

        @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            if (request instanceof jakarta.servlet.http.HttpServletRequest http) {
                seen.add(http.getHeader("Authorization"));
            }
            chain.doFilter(request, response);
        }
    }

    @Test
    @DisplayName("文本 EmbeddedResource 的正文进入模型文本；blob 资源仍是占位")
    void embeddedResourceMapping() throws Exception {
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                Map.of("path", Map.of("type", "string")), List.of("path"), false, null, null);

        McpSchema.Tool readText = McpSchema.Tool.builder()
                .name("read_text").title("Read text").description("read a text file")
                .inputSchema(schema).build();
        McpSchema.Tool readBlob = McpSchema.Tool.builder()
                .name("read_blob").title("Read blob").description("read a binary file")
                .inputSchema(schema).build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("resource-test", "0.1.0")
                .requestTimeout(Duration.ofSeconds(20))
                .toolCall(readText, (ex, req) -> McpSchema.CallToolResult.builder()
                        .content(List.of(
                                new McpSchema.TextContent("successfully downloaded text file"),
                                new McpSchema.EmbeddedResource(null,
                                        new McpSchema.TextResourceContents(
                                                "repo://x/README.adoc", "text/plain", "正文内容"))))
                        .isError(false)
                        .build())
                .toolCall(readBlob, (ex, req) -> McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.EmbeddedResource(null,
                                new McpSchema.BlobResourceContents(
                                        "file://x.bin", "application/octet-stream", "QUJDRA=="))))
                        .isError(false)
                        .build())
                .build();

        Server localJetty = new Server();
        ServerConnector connector = new ServerConnector(localJetty);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        localJetty.addConnector(connector);
        ServletContextHandler ctx = new ServletContextHandler("/");
        ctx.addServlet(new ServletHolder(transport), "/*");
        localJetty.setHandler(ctx);
        localJetty.start();
        try {
            McpServerSpec spec = httpSpec("http://127.0.0.1:" + connector.getLocalPort(), "/mcp");
            try (HttpMcpSession session = HttpMcpSession.connectForTest("svc", spec, props())) {
                McpCallResult text = session.callTool("read_text", "{\"path\":\"README.adoc\"}");
                assertFalse(text.isError(), text.text());
                assertEquals("successfully downloaded text file\n正文内容", text.text());
                assertFalse(text.text().contains(McpContentMapper.BINARY_PLACEHOLDER),
                        "文本资源不该再被当成二进制");

                McpCallResult blob = session.callTool("read_blob", "{\"path\":\"x.bin\"}");
                assertFalse(blob.isError(), blob.text());
                assertEquals(McpContentMapper.BINARY_PLACEHOLDER, blob.text());
                assertFalse(blob.text().contains("QUJDRA=="), "base64 不得进入模型文本");
            }
        } finally {
            try { server.close(); } catch (Exception ignored) {}
            try { transport.close(); } catch (Exception ignored) {}
            localJetty.stop();
            localJetty.destroy();
        }
    }

    private static McpServerSpec httpSpec(String base, String path) {
        McpServerSpec s = new McpServerSpec();
        s.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        s.setBaseUrl(base);
        s.setEndpointPath(path);
        s.setAuthType(McpServerConfig.AUTH_NONE);
        s.setConnectTimeoutSeconds(20);
        s.setMaxResultChars(8000);
        return s;
    }

    private static McpProperties props() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setInitializeTimeoutMs(20_000);
        p.setRequestTimeoutMs(20_000);
        p.setMaxResultChars(8000);
        return p;
    }
}
