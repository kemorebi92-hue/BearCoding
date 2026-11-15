package com.example.jingjingai2.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 桌面启动令牌：正确通过、错误/缺失/空都拒绝，静态页面不受影响，SSE 路径同样受保护。
 * 全部离线，不启动 Tomcat。
 */
class DesktopTokenFilterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("local 模式完全不校验（浏览器开发/验收环境不受影响）")
    void localModeBypasses() throws Exception {
        DesktopTokenFilter filter = new DesktopTokenFilter(props(DesktopAuthProperties.MODE_LOCAL, ""));
        MockHttpServletRequest request = get("/ai/models");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "local 模式必须放行到后续过滤器/控制器");
    }

    @Test
    @DisplayName("desktop：正确令牌通过")
    void correctTokenPasses() throws Exception {
        DesktopTokenFilter filter = new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, TOKEN));
        MockHttpServletRequest request = get("/ai/models");
        request.addHeader(DesktopAuthProperties.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("desktop：没有令牌 / 空令牌 / 错误令牌一律 401，且响应不含令牌")
    void missingEmptyAndWrongTokensRejected() throws Exception {
        DesktopTokenFilter filter = new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, TOKEN));

        assertRejected(filter, null);
        assertRejected(filter, "");
        assertRejected(filter, "   ");
        assertRejected(filter, TOKEN.substring(0, TOKEN.length() - 1));
        assertRejected(filter, "f".repeat(TOKEN.length()));
    }

    @Test
    @DisplayName("desktop：所有本地 API 前缀都受保护，静态资源/SPA 不受影响")
    void protectsAllApiPrefixes() throws Exception {
        DesktopTokenFilter filter = new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, TOKEN));

        for (String path : new String[]{
                "/ai/chat", "/ai/stream", "/ai/models", "/ai/models/status",
                "/ai/workspace/file", "/ai/mcp/servers", "/ai/approvals/1/decision",
                "/api/anything", "/user/profile",
                // 矩阵参数不能成为绕过前缀检查的口子
                "/ai;jsessionid=abc/models", "/ai/models;jsessionid=abc"}) {
            assertRejected(filter, path, null);
        }
        for (String path : new String[]{"/", "/chat", "/models", "/mcp",
                "/js/app.384c163a.js", "/css/app.1e72d626.css", "/favicon.ico",
                "/img/image.0bfffcda.png"}) {
            assertPasses(filter, path);
        }
    }

    @Test
    @DisplayName("desktop：OPTIONS 预检不执行业务，允许匿名通过")
    void optionsBypasses() throws Exception {
        DesktopTokenFilter filter = new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, TOKEN));
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/ai/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("desktop 但令牌缺失/空白/过短：启动即失败，不退回无鉴权")
    void failClosedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, "")));
        assertThrows(IllegalStateException.class,
                () -> new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, "   ")));
        assertThrows(IllegalStateException.class,
                () -> new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, "short-token")));
    }

    @Test
    @DisplayName("令牌不会出现在启动日志里")
    void tokenNeverLogged() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            new DesktopTokenFilter(props(DesktopAuthProperties.MODE_DESKTOP, TOKEN));
        } finally {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains(TOKEN), "启动日志不能包含令牌内容");
        assertTrue(out.contains(DesktopAuthProperties.TOKEN_HEADER));
    }

    @Test
    @DisplayName("子进程环境会摘掉启动令牌，其它变量保留")
    void childEnvironmentScrubbed() {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(DesktopAuthProperties.ENV_TOKEN, TOKEN);
        env.put("PATH", "C:\\Windows");

        DesktopTokenSecrets.scrub(env);

        assertFalse(env.containsKey(DesktopAuthProperties.ENV_TOKEN));
        assertEquals("C:\\Windows", env.get("PATH"));
    }

    private static void assertRejected(DesktopTokenFilter filter, String token) throws Exception {
        assertRejected(filter, "/ai/models", token);
    }

    private static void assertRejected(DesktopTokenFilter filter, String path, String token) throws Exception {
        MockHttpServletRequest request = get(path);
        if (token != null) request.addHeader(DesktopAuthProperties.TOKEN_HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus(), path);
        assertNull(chain.getRequest(), "被拒绝的请求不能进入控制器: " + path);
        String body = response.getContentAsString();
        assertTrue(body.contains("未授权"), body);
        if (token != null && !token.isBlank()) {
            assertFalse(body.contains(token), "401 响应不能回显令牌");
        }
    }

    private static void assertPasses(DesktopTokenFilter filter, String path) throws Exception {
        MockHttpServletRequest request = get(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus(), path);
        assertNotNull(chain.getRequest(), "静态页面/资源必须能匿名加载: " + path);
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static DesktopAuthProperties props(String mode, String token) {
        DesktopAuthProperties properties = new DesktopAuthProperties();
        properties.setMode(mode);
        properties.setLocalToken(token);
        return properties;
    }
}
