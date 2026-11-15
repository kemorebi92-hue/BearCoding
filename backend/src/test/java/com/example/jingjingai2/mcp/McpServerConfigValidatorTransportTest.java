package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigValidatorTransportTest {

    @Test
    @DisplayName("缺省 transport 按 stdio：仍要求合法 command")
    void blankTransportTreatedAsStdio() {
        McpServerConfig c = stdio("echo", "npx");
        c.setTransport(null);
        assertNull(McpServerConfigValidator.check(c));
        assertEquals(McpServerConfig.TRANSPORT_STDIO, c.normalizedTransport());
        McpServerSpec spec = McpServerConfigValidator.toSpec(c);
        assertEquals(McpServerConfig.TRANSPORT_STDIO, spec.normalizedTransport());
        assertEquals("npx", spec.getCommand());
    }

    @Test
    @DisplayName("非法 transport 拒绝，不静默映射")
    void illegalTransportRejected() {
        McpServerConfig c = stdio("echo", "npx");
        c.setTransport("websocket");
        assertEquals("transport 只能是 stdio 或 streamable_http", McpServerConfigValidator.check(c));
    }

    @Test
    @DisplayName("stdio 白名单未放宽：路径命令仍拒")
    void stdioCommandWhitelistUnchanged() {
        McpServerConfig c = stdio("echo", "./evil");
        assertNotNull(McpServerConfigValidator.check(c));
        assertTrue(McpServerConfigValidator.check(c).contains("命令"));
    }

    @Test
    @DisplayName("streamable_http 缺少 baseUrl 被拒")
    void httpRequiresBaseUrl() {
        McpServerConfig c = http("remote-a", null);
        assertEquals("远程 baseUrl 不能为空", McpServerConfigValidator.check(c));
    }

    @Test
    @DisplayName("streamable_http 合法无认证配置通过，toSpec 带默认 /mcp")
    void httpOkWithoutAuth() {
        // 使用公网字面量 IP，避免单测依赖真实 DNS。
        McpServerConfig c = http("remote-a", "https://1.1.1.1");
        assertNull(McpServerConfigValidator.check(c));
        McpServerSpec spec = McpServerConfigValidator.toSpec(c);
        assertTrue(spec.isStreamableHttp());
        assertEquals("https://1.1.1.1", spec.getBaseUrl());
        assertEquals("/mcp", spec.getEndpointPath());
        assertNull(spec.getCommand());
    }

    @Test
    @DisplayName("streamable_http 的私网/http endpoint 被 EndpointGuard 拒绝")
    void httpEndpointGuardIntegrated() {
        McpServerConfig loop = http("remote-a", "https://127.0.0.1");
        assertNotNull(McpServerConfigValidator.check(loop));
        assertTrue(McpServerConfigValidator.check(loop).contains("不被允许"));
        McpServerConfig plainHttp = http("remote-b", "http://1.1.1.1");
        assertNotNull(McpServerConfigValidator.check(plainHttp));
        assertTrue(McpServerConfigValidator.check(plainHttp).contains("https")
                || McpServerConfigValidator.check(plainHttp).contains("不被允许"));
    }

    @Test
    @DisplayName("streamable_http 带 command/args/env 被拒")
    void httpRejectsStdioFields() {
        McpServerConfig c = http("remote-a", "https://1.1.1.1");
        c.setCommand("npx");
        assertEquals("streamable_http 配置不能填写 command", McpServerConfigValidator.check(c));
        c.setCommand(null);
        c.setArgsJson("[\"-y\"]");
        assertEquals("streamable_http 配置不能填写 args", McpServerConfigValidator.check(c));
    }

    @Test
    @DisplayName("baseUrl 含路径/userinfo/非 http(s) 被拒")
    void baseUrlFormatChecks() {
        assertTrue(McpServerConfigValidator.checkBaseUrl("ftp://x").contains("http"));
        assertTrue(McpServerConfigValidator.checkBaseUrl("https://a@host").contains("用户信息")
                || McpServerConfigValidator.checkBaseUrl("https://u:p@host").contains("用户信息"));
        assertTrue(McpServerConfigValidator.checkBaseUrl("https://host/mcp").contains("路径"));
        assertNull(McpServerConfigValidator.checkBaseUrl("http://127.0.0.1:8080"));
    }

    @Test
    @DisplayName("authType=bearer 可以保存认证方式，但配置对象里没有令牌")
    void bearerNotPersistableYet() {
        McpServerConfig c = http("remote-a", "https://1.1.1.1");
        c.setAuthType(McpServerConfig.AUTH_BEARER);
        assertNull(McpServerConfigValidator.check(c));
        McpServerSpec spec = McpServerConfigValidator.toSpec(c);
        assertEquals("bearer", spec.getAuthType());
        assertFalse(String.valueOf(spec.getEnv()).toLowerCase().contains("token"));
    }

    @Test
    @DisplayName("运行时：none 和 bearer 都不再被运行时门拒绝")
    void runtimeNotReadyForHttp() {
        McpServerConfig c = http("remote-a", "https://1.1.1.1");
        assertNull(McpServerConfigValidator.check(c));
        assertNull(McpServerConfigValidator.checkRuntimeSupported(c));
        c.setAuthType(McpServerConfig.AUTH_BEARER);
        assertNull(McpServerConfigValidator.checkRuntimeSupported(c));
        McpServerConfig stdio = stdio("echo", "npx");
        assertNull(McpServerConfigValidator.checkRuntimeSupported(stdio));
    }

    @Test
    @DisplayName("远程 HTTP 没单独配超时时至少等 90 秒，stdio 仍用全局值")
    void httpDefaultInitTimeoutHasFloor() {
        McpProperties props = new McpProperties();
        McpServerSpec http = new McpServerSpec();
        http.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        assertEquals(McpServerSpec.HTTP_MIN_INIT_TIMEOUT_MS, http.initializeTimeoutMs(props),
                "公网冷启动慢，HTTP 默认不能沿用 15 秒");

        McpServerSpec stdio = new McpServerSpec();
        stdio.setTransport(McpServerConfig.TRANSPORT_STDIO);
        assertEquals(props.getInitializeTimeoutMs(), stdio.initializeTimeoutMs(props),
                "stdio 仍用全局值，不跟着变长");

        http.setConnectTimeoutSeconds(120);
        assertEquals(120_000L, http.initializeTimeoutMs(props), "单独配置优先于下限");
    }

    private static McpServerConfig stdio(String name, String command) {
        McpServerConfig c = new McpServerConfig();
        c.setName(name);
        c.setCommand(command);
        c.setTransport(McpServerConfig.TRANSPORT_STDIO);
        c.setTrust("untrusted");
        c.setAuthType(McpServerConfig.AUTH_NONE);
        return c;
    }

    private static McpServerConfig http(String name, String baseUrl) {
        McpServerConfig c = new McpServerConfig();
        c.setName(name);
        c.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        c.setBaseUrl(baseUrl);
        c.setTrust("untrusted");
        c.setAuthType(McpServerConfig.AUTH_NONE);
        return c;
    }
}
