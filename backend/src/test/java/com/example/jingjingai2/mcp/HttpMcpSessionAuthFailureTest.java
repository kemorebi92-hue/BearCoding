package com.example.jingjingai2.mcp;

import com.example.jingjingai2.controller.McpServerAdminController;
import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.entity.McpServerCredential;
import com.example.jingjingai2.entity.McpUserPolicy;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpServerCredentialMapper;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.service.McpCredentialService;
import com.example.jingjingai2.service.McpServerProvisionService;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP 401 认证失败与恢复。全部走本机 Jetty 假服务器，不连公网、不做真实 DNS。
 *
 * <p>假服务器在 Servlet 过滤器里按 Authorization 判定：期望的令牌放行，其余一律 401；
 * 也可以只拒绝 POST，用来模拟「握手成功、调用工具时认证失效」。
 *
 * <p>生产代码零修改：控制器、会话和凭证服务都是现成的。
 */
class HttpMcpSessionAuthFailureTest {

    private static final String TOKEN_V1 = "fake-token-v1";
    private static final String TOKEN_V2 = "fake-token-v2";
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final long OWNER = 7L;
    private static final long CONFIG_ID = 11L;
    private static final long CONFIG_ID2 = 12L;
    private static final String SERVER = "svc";
    private static final String SERVER2 = "svc2";

    private Server jetty;
    private int port;
    private McpSyncServer mcpServer;
    private HttpServletStreamableServerTransportProvider serverTransport;

    @BeforeEach
    void startServer() throws Exception {
        AuthGate.reset();
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
                .serverInfo("auth-failure-test", "0.1.0")
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
        context.addFilter(AuthGate.class, "/*",
                java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        jetty.setHandler(context);
        jetty.start();
        port = connector.getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (mcpServer != null) try { mcpServer.close(); } catch (Exception ignored) {}
        if (serverTransport != null) try { serverTransport.close(); } catch (Exception ignored) {}
        if (jetty != null) { jetty.stop(); jetty.destroy(); }
        AuthGate.reset();
    }

    // ---------------- T1 ----------------

    @Test
    @DisplayName("T1：initialize 收到 401 时连接失败，异常链里能确认是认证错误")
    void t1InitializeReturns401() {
        AuthGate.acceptedToken = TOKEN_V2;   // 只接受 v2，本次用 v1
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> HttpMcpSession.connectForTest(SERVER, httpSpec(), props(), TOKEN_V1));

        assertTrue(hasCause(thrown, "McpHttpClientTransportAuthorizationException"),
                "异常链里应有认证异常，实际：" + chain(thrown));
        System.out.println("[AUTH-FAILURE-T1] chain=" + chain(thrown));
        assertTrue(AuthGate.rejected.get() >= 1, "假服务器应至少拒绝一次");
        assertTrue(AuthGate.seen.contains("Bearer " + TOKEN_V1), "请求应带 v1");
        assertFalse(chain(thrown).contains(TOKEN_V1), "异常链不得包含令牌：" + chain(thrown));
    }

    // ---------------- T2 ----------------

    @Test
    @DisplayName("T2：401 后槽位 failed、工具数为 0、回调为空")
    void t2FailedSlotHasNoTools() {
        AuthGate.acceptedToken = TOKEN_V2;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);

        List<McpServerStatus> statuses = manager.statuses(OWNER);
        assertEquals(1, statuses.size());
        McpServerStatus status = statuses.get(0);
        assertEquals("failed", status.getState());
        assertEquals(0, status.getOfferedCount(), "失败后不应暴露工具");
        assertEquals(0, status.getToolCount(), "失败后工具数应为 0");
        assertNotNull(status.getLastError());
        assertEquals(0, manager.callbacks(OWNER).length, "回调必须为空");
        assertTrue(manager.nameMap(OWNER).isEmpty(), "名字映射必须为空");
        assertEquals(0, manager.listedTools(OWNER, SERVER).size(), "首次连接失败不应有已发现工具");
    }

    @Test
    @DisplayName("T2b：掉线后重连 401 时，回调与已发现工具都清空")
    void t2bReconnectFailureClearsDiscoveredTools() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        assertEquals("connected", manager.statuses(OWNER).get(0).getState());
        assertEquals(1, manager.callbacks(OWNER).length, "前置：首次连接应装上 echo");
        assertEquals(1, manager.listedTools(OWNER, SERVER).size(), "前置：应发现 1 个工具");

        // 模拟连接自己掉线（不是用户主动断开），再让服务器只接受 v2：重连必然 401
        manager.lastSession.close();
        AuthGate.acceptedToken = TOKEN_V2;
        manager.connectServer(OWNER, SERVER);

        assertEquals("failed", manager.statuses(OWNER).get(0).getState());
        assertEquals(0, manager.callbacks(OWNER).length, "重连失败后回调必须清空");
        assertTrue(manager.nameMap(OWNER).isEmpty());
        assertEquals(0, manager.listedTools(OWNER, SERVER).size(),
                "重连失败后已发现工具必须清空，管理页不能再显示旧工具");
    }

    // ---------------- T3 ----------------

    @Test
    @DisplayName("T3：异常、lastError、管理接口响应和日志都不含令牌")
    void t3NoTokenInErrorsOrLogs() {
        AuthGate.acceptedToken = TOKEN_V2;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        MemoryConfigs configs = new MemoryConfigs(config());
        McpCredentialService credentials = new McpCredentialService(configs, stored, crypto());
        TokenSpy manager = new TokenSpy(props(), credentials);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        String lastError;
        try {
            System.setErr(new PrintStream(err, true, "UTF-8"));
            manager.addServer(OWNER, SERVER, httpSpec(), true);
            lastError = manager.statuses(OWNER).get(0).getLastError();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            System.setErr(original);
        }

        assertNotNull(lastError);
        assertFalse(lastError.contains(TOKEN_V1), "lastError 不得含令牌：" + lastError);
        assertFalse(lastError.contains("v1."), "lastError 不得含密文：" + lastError);

        String logs = err.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(logs.contains(SERVER), "前置：日志里应有这次失败，实际：" + logs);
        assertFalse(logs.contains(TOKEN_V1), "日志不得含令牌：" + logs);

        // 管理接口的真实响应体
        McpServerAdminController controller = controller(manager, configs, credentials);
        ResponseEntity<Map<String, Object>> list = controller.list(asUser(OWNER));
        assertEquals(200, list.getStatusCode().value());
        String body = String.valueOf(list.getBody());
        assertTrue(body.contains(SERVER), "前置：列表里应有这个服务，实际：" + body);
        assertFalse(body.contains(TOKEN_V1), "管理接口响应不得含令牌：" + body);
        assertFalse(body.contains("v1."), "管理接口响应不得含密文：" + body);
    }

    // ---------------- T4 ----------------

    @Test
    @DisplayName("T4：换成 v2 后重连成功，新请求只用 v2")
    void t4ReconnectAfterCredentialFix() {
        AuthGate.acceptedToken = TOKEN_V2;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        assertEquals("failed", manager.statuses(OWNER).get(0).getState(), "前置：v1 应失败");

        stored.put(OWNER, CONFIG_ID, TOKEN_V2);
        AuthGate.seen.clear();
        manager.connectServer(OWNER, SERVER);

        McpServerStatus status = manager.statuses(OWNER).get(0);
        assertEquals("connected", status.getState(), "换凭证后应连上");
        assertEquals(1, status.getOfferedCount());
        assertEquals(1, manager.callbacks(OWNER).length);
        assertTrue(manager.nameMap(OWNER).keySet().stream().anyMatch(k -> k.contains("echo")),
                "应装上 echo：" + manager.nameMap(OWNER).keySet());
        assertTrue(AuthGate.seen.contains("Bearer " + TOKEN_V2), "重连应带 v2：" + AuthGate.seen);
        assertFalse(AuthGate.seen.contains("Bearer " + TOKEN_V1),
                "重连不得再用 v1：" + AuthGate.seen);
    }

    // ---------------- E3.6：失败清理与重连语义 ----------------

    @Test
    @DisplayName("E3.6-A：一个服务重连失败，不影响另一个正常服务的工具")
    void e36aReconnectFailureIsIsolated() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        stored.put(OWNER, CONFIG_ID2, TOKEN_V1);
        TokenSpy manager = new TokenSpy(props(), new McpCredentialService(
                new MemoryConfigs(config(CONFIG_ID, SERVER), config(CONFIG_ID2, SERVER2)),
                stored, crypto()));

        manager.addServer(OWNER, SERVER, httpSpec(CONFIG_ID), true);
        manager.addServer(OWNER, SERVER2, httpSpec(CONFIG_ID2), true);
        assertEquals(2, manager.callbacks(OWNER).length, "前置：两个服务各 1 个工具");

        // 新连接一律失败，但只重连第一个
        AuthGate.acceptedToken = TOKEN_V2;
        manager.reconnectServer(OWNER, SERVER);

        assertEquals("failed", stateOf(manager, SERVER));
        assertEquals(0, manager.listedTools(OWNER, SERVER).size());
        assertEquals("connected", stateOf(manager, SERVER2), "另一个服务不受影响");
        assertEquals(1, manager.callbacks(OWNER).length, "只剩另一个服务的工具");
        assertEquals(1, manager.listedTools(OWNER, SERVER2).size());
    }

    @Test
    @DisplayName("E3.6-B1：reconnect 重建会话，connect 对存活会话保持幂等")
    void e36b1ReconnectRebuildsConnectIsIdempotent() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        assertEquals(1, manager.opens, "前置：首次连接建了 1 个会话");

        manager.connectServer(OWNER, SERVER);
        assertEquals(1, manager.opens, "普通 connect 对存活会话不得重建");
        assertEquals("connected", stateOf(manager, SERVER));

        manager.reconnectServer(OWNER, SERVER);
        assertEquals(2, manager.opens, "reconnect 必须真的重建会话");
        assertEquals("connected", stateOf(manager, SERVER));
        assertEquals(1, manager.callbacks(OWNER).length);
        assertEquals(1, manager.listedTools(OWNER, SERVER).size());
    }

    @Test
    @DisplayName("E3.6-B2：换凭证后 reconnect 用新令牌，不再用旧的")
    void e36b2ReconnectUsesNewCredential() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        assertEquals("connected", stateOf(manager, SERVER));

        stored.put(OWNER, CONFIG_ID, TOKEN_V2);
        AuthGate.acceptedToken = TOKEN_V2;
        AuthGate.postAuth.clear();
        manager.reconnectServer(OWNER, SERVER);

        assertEquals("connected", stateOf(manager, SERVER), "换凭证后重连应成功");
        assertTrue(AuthGate.postAuth.contains("Bearer " + TOKEN_V2),
                "重连的 POST 应带新令牌：" + AuthGate.postAuth);
        // 只看向服务器发起的 POST。关闭旧会话时 SDK 会发一个带旧令牌的收尾请求，
        // 那是断开动作，不是新建连接。
        assertFalse(AuthGate.postAuth.contains("Bearer " + TOKEN_V1),
                "重连的 POST 不得再用旧令牌：" + AuthGate.postAuth);
    }

    @Test
    @DisplayName("E3.6-B3：reconnect 失败后不残留旧会话回调或旧工具")
    void e36b3ReconnectFailureLeavesNothing() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        assertEquals(1, manager.callbacks(OWNER).length);

        AuthGate.acceptedToken = TOKEN_V2;   // 旧令牌被拒
        manager.reconnectServer(OWNER, SERVER);

        assertEquals("failed", stateOf(manager, SERVER));
        assertEquals(0, manager.callbacks(OWNER).length);
        assertTrue(manager.nameMap(OWNER).isEmpty());
        assertEquals(0, manager.listedTools(OWNER, SERVER).size());
    }

    @Test
    @DisplayName("E3.6-B4：连续两次 reconnect 只留下一个会话和一份工具")
    void e36b4RepeatedReconnectLeavesSingleSession() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        manager.reconnectServer(OWNER, SERVER);
        manager.reconnectServer(OWNER, SERVER);

        assertEquals(3, manager.opens, "每次 reconnect 各建一次会话");
        assertEquals("connected", stateOf(manager, SERVER));
        assertEquals(1, manager.callbacks(OWNER).length, "不得出现重复工具");
        assertEquals(1, manager.listedTools(OWNER, SERVER).size());
    }

    // ---------------- T5 ----------------

    @Test
    @DisplayName("T5：会话中途 tools/call 收到 401 时 callToolRaw 的真实返回")
    void t5MidSession401OnCallToolRaw() {
        AuthGate.acceptedToken = TOKEN_V1;
        HttpMcpSession session = HttpMcpSession.connectForTest(SERVER, httpSpec(), props(), TOKEN_V1);
        try {
            assertTrue(session.isAlive(), "前置：握手成功");
            AuthGate.rejectPost = true;

            McpCallResult result = session.callToolRaw("echo", "{\"message\":\"x\"}");

            assertTrue(result.isError(), "中途 401 应返回失败结果");
            assertEquals(McpCallResult.Delivery.UNKNOWN, result.delivery(),
                    "请求可能已发出，按结果未知处理");
            System.out.println("[AUTH-FAILURE-T5] text=" + result.text()
                    + " delivery=" + result.delivery() + " alive=" + session.isAlive());
            assertFalse(result.text().contains(TOKEN_V1), "结果不得含令牌：" + result.text());
            // 记录实际行为：会话不会因为一次调用失败而被关闭
            assertTrue(session.isAlive(), "实际：调用失败后会话仍被判定为存活");
            assertTrue(AuthGate.rejected.get() >= 1);
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("T5b：中途 401 时 manager.invoke 返回结果未知，槽位仍为 connected")
    void t5bMidSession401ThroughManager() {
        AuthGate.acceptedToken = TOKEN_V1;
        MemoryCredentials stored = new MemoryCredentials();
        stored.put(OWNER, CONFIG_ID, TOKEN_V1);
        TokenSpy manager = manager(stored);

        manager.addServer(OWNER, SERVER, httpSpec(), true);
        assertEquals("connected", manager.statuses(OWNER).get(0).getState());
        var callbacks = manager.callbacks(OWNER);
        assertEquals(1, callbacks.length);

        AuthGate.rejectPost = true;
        String first = callbacks[0].call("{\"message\":\"x\"}");
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, first,
                "实际返回：" + first);

        assertEquals("connected", manager.statuses(OWNER).get(0).getState(),
                "实际：槽位没有被标记为失败");
        assertEquals(1, manager.callbacks(OWNER).length, "实际：工具仍在回调列表里");

        String second = manager.callbacks(OWNER)[0].call("{\"message\":\"y\"}");
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, second,
                "再次调用仍是结果未知：" + second);
    }

    // ---------------- 装配 ----------------

    private McpServerSpec httpSpec() {
        return httpSpec(CONFIG_ID);
    }

    private McpServerSpec httpSpec(long configId) {
        McpServerSpec spec = new McpServerSpec();
        spec.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        spec.setBaseUrl("http://127.0.0.1:" + port);
        spec.setEndpointPath("/mcp");
        spec.setAuthType(McpServerConfig.AUTH_BEARER);
        spec.setServerConfigId(configId);
        spec.setConnectTimeoutSeconds(20);
        spec.setMaxResultChars(8000);
        return spec;
    }

    private static String stateOf(McpClientManager manager, String name) {
        for (McpServerStatus s : manager.statuses(OWNER)) {
            if (name.equals(s.getName())) return s.getState();
        }
        throw new AssertionError("没有这个服务的状态：" + name);
    }

    private static McpProperties props() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setInitializeTimeoutMs(20_000);
        p.setRequestTimeoutMs(20_000);
        p.setMaxResultChars(8000);
        return p;
    }

    private TokenSpy manager(MemoryCredentials stored) {
        McpCredentialService credentials =
                new McpCredentialService(new MemoryConfigs(config()), stored, crypto());
        return new TokenSpy(props(), credentials);
    }

    private static McpCredentialCrypto crypto() {
        return new McpCredentialCrypto(KEY);
    }

    private static McpServerConfig config() {
        return config(CONFIG_ID, SERVER);
    }

    private static McpServerConfig config(long id, String name) {
        McpServerConfig c = new McpServerConfig();
        c.setId(id);
        c.setName(name);
        c.setCreatedBy(OWNER);
        c.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        c.setBaseUrl("http://127.0.0.1");
        c.setEndpointPath("/mcp");
        c.setAuthType(McpServerConfig.AUTH_BEARER);
        c.setTrust("untrusted");
        c.setDisabledToolsJson("[]");
        c.setConnectTimeoutSeconds(20);
        c.setMaxResultChars(8000);
        c.setEnabled(true);
        return c;
    }

    private static McpServerAdminController controller(McpClientManager manager, McpServerConfigMapper configs,
                                                        McpCredentialService credentials) {
        McpServerProvisionService provision = new McpServerProvisionService(configs, manager);
        return new McpServerAdminController(manager, configs, new UnusedPolicies(),
                new ApprovalService(1), provision, credentials);
    }

    private static HttpServletRequest asUser(Long id) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) {
                        return args != null && "userId".equals(args[0]) ? id : null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    private static boolean hasCause(Throwable t, String simpleName) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getClass().getSimpleName().equals(simpleName)) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }

    /** 按 Authorization 判定；也可只拒绝 POST，模拟调用阶段认证失效。 */
    public static final class AuthGate implements Filter {
        static volatile String acceptedToken;
        static volatile boolean rejectPost;
        static final List<String> seen = new CopyOnWriteArrayList<>();
        /** 只记 POST（新建连接和调用），不记关闭旧会话时的收尾请求。 */
        static final List<String> postAuth = new CopyOnWriteArrayList<>();
        static final AtomicInteger rejected = new AtomicInteger();

        static void reset() {
            acceptedToken = null;
            rejectPost = false;
            seen.clear();
            postAuth.clear();
            rejected.set(0);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            HttpServletRequest http = (HttpServletRequest) request;
            String auth = http.getHeader("Authorization");
            seen.add(auth);
            if ("POST".equalsIgnoreCase(http.getMethod())) postAuth.add(auth);
            boolean reject = false;
            if (acceptedToken != null && !("Bearer " + acceptedToken).equals(auth)) reject = true;
            if (rejectPost && "POST".equalsIgnoreCase(http.getMethod())) reject = true;
            if (reject) {
                rejected.incrementAndGet();
                ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /** 归属检查和解密走生产 openSession，只有建会话换成测试工厂连本机服务器。 */
    private static final class TokenSpy extends McpClientManager {
        private final McpProperties props;
        /** 测试用：记下最近一次建出来的会话，模拟连接自己掉线。 */
        McpSession lastSession;
        /** 测试用：真正建了几次会话，用来区分 connect 的幂等和 reconnect 的重建。 */
        int opens;
        TokenSpy(McpProperties props, McpCredentialService credentials) {
            super(props);
            this.props = props;
            setCredentialService(credentials);
        }
        @Override McpSession openBearerSession(String serverName, McpServerSpec spec, String token) {
            McpSession session = HttpMcpSession.connectForTest(serverName, spec, props, token);
            opens++;
            lastSession = session;
            return session;
        }
    }

    private static final class MemoryConfigs implements McpServerConfigMapper {
        private final List<McpServerConfig> rows;
        MemoryConfigs(McpServerConfig... configs) { this.rows = List.of(configs); }
        @Override public McpServerConfig findById(long id) {
            for (McpServerConfig c : rows) {
                if (c.getId() != null && c.getId() == id) return c;
            }
            return null;
        }
        @Override public List<McpServerConfig> findAll() { return new ArrayList<>(rows); }
        @Override public int insert(McpServerConfig c) { throw new UnsupportedOperationException(); }
        @Override public int update(McpServerConfig c) { throw new UnsupportedOperationException(); }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) {
            throw new UnsupportedOperationException();
        }
        @Override public int delete(long ownerId, String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findByName(String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) {
            throw new UnsupportedOperationException();
        }
        @Override public McpServerConfig findSharedByName(String name) { throw new UnsupportedOperationException(); }
        @Override public List<McpServerConfig> findAllByOwner(long ownerId) { throw new UnsupportedOperationException(); }
    }

    private static final class MemoryCredentials implements McpServerCredentialMapper {
        final Map<String, McpServerCredential> rows = new LinkedHashMap<>();
        void put(long userId, long configId, String token) {
            McpServerCredential row = new McpServerCredential();
            row.setUserId(userId);
            row.setServerConfigId(configId);
            row.setCiphertext(crypto().encrypt(token));
            rows.put(userId + ":" + configId, row);
        }
        @Override public int insert(McpServerCredential row) {
            rows.put(row.getUserId() + ":" + row.getServerConfigId(), row);
            return 1;
        }
        @Override public int updateCiphertext(long userId, long serverConfigId, String ciphertext) {
            McpServerCredential row = find(userId, serverConfigId);
            if (row == null) return 0;
            row.setCiphertext(ciphertext);
            return 1;
        }
        @Override public McpServerCredential find(long userId, long serverConfigId) {
            return rows.get(userId + ":" + serverConfigId);
        }
        @Override public int count(long userId, long serverConfigId) {
            return find(userId, serverConfigId) == null ? 0 : 1;
        }
        @Override public int delete(long userId, long serverConfigId) {
            return rows.remove(userId + ":" + serverConfigId) == null ? 0 : 1;
        }
    }

    private static final class UnusedPolicies implements McpUserPolicyMapper {
        @Override public int upsert(long userId, String server, String tool, String effect, String hash) {
            throw new UnsupportedOperationException();
        }
        @Override public McpUserPolicy find(long userId, String server, String tool) {
            throw new UnsupportedOperationException();
        }
        @Override public int deleteOne(long userId, String server, String tool) {
            throw new UnsupportedOperationException();
        }
        @Override public int deleteTools(long userId, String server) { throw new UnsupportedOperationException(); }
        @Override public int deleteByUserAndServer(long userId, String server) {
            throw new UnsupportedOperationException();
        }
    }
}
