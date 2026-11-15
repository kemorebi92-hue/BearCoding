package com.example.jingjingai2.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 桌面实例的本地 API 启动令牌校验。
 *
 * <p>只保护“会读用户数据 / 操作模型、文件、命令、MCP”的 API 路径
 * （{@code /ai/**}、{@code /api/**}、{@code /user/**}）；Vue 静态页面与
 * 静态资源继续匿名加载，否则 Electron 连首屏都打不开。
 *
 * <p>令牌只从请求头 {@value DesktopAuthProperties#TOKEN_HEADER} 读取，
 * 不接受查询参数（避免进访问日志）、不写 Cookie、不回显。
 * 无令牌、错误令牌、空令牌一律 401，响应体不含令牌内容。
 *
 * <p>{@code app.auth.mode=local} 时本过滤器整体不生效（浏览器开发/验收环境）。
 * desktop 模式下令牌缺失会在 {@link DesktopAuthProperties#requireTokenBytes()} 抛异常，
 * 应用启动失败 —— fail-closed，不会静默退回无鉴权。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DesktopTokenFilter extends OncePerRequestFilter {

    private static final String REJECT_BODY =
            "{\"error\":\"未授权：缺少或无效的桌面启动令牌\"}";

    private final DesktopAuthProperties properties;
    /** desktop 模式下是启动令牌的字节；local 模式下为 null。 */
    private final byte[] expectedToken;

    public DesktopTokenFilter(DesktopAuthProperties properties) {
        this.properties = properties;
        this.expectedToken = properties.desktopMode() ? properties.requireTokenBytes() : null;
        if (this.expectedToken == null) {
            System.out.println("[Auth] app.auth.mode=local：本地 API 不做启动令牌校验（仅用于开发/验收环境）");
        } else {
            System.out.println("[Auth] 桌面鉴权已启用：本地 API 需要 " + DesktopAuthProperties.TOKEN_HEADER + " 请求头");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (expectedToken == null) return true;
        // 预检不执行任何业务逻辑，允许匿名通过；真正的请求仍会被下面拦住。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        return !isProtectedApiPath(requestPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(DesktopAuthProperties.TOKEN_HEADER);
        if (!matches(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(REJECT_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        if (provided == null || provided.isEmpty()) return false;
        byte[] candidate = provided.getBytes(StandardCharsets.UTF_8);
        // 常量时间比较；长度不同也走一次 digest 比较，避免明显的长度侧信道。
        return MessageDigest.isEqual(expectedToken, candidate);
    }

    /** 当前请求路径（不含 query，避免把参数写进日志/响应）。 */
    static String requestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String path;
        if (servletPath != null && !servletPath.isEmpty()) {
            path = servletPath;
        } else {
            String uri = request.getRequestURI();
            if (uri == null) return "";
            String context = request.getContextPath();
            path = context != null && !context.isEmpty() && uri.startsWith(context)
                    ? uri.substring(context.length()) : uri;
        }
        // 去掉矩阵参数（/ai;jsessionid=.../models）。Tomcat 路由时可能忽略它，
        // 过滤器若只看前缀就会被绕过。
        int semicolon = path.indexOf(';');
        if (semicolon >= 0) path = path.substring(0, semicolon);
        return path;
    }

    /** 受保护的本地 API 前缀。静态 SPA、favicon、js/css/fonts/img 不在其中。 */
    static boolean isProtectedApiPath(String path) {
        if (path == null || path.isEmpty()) return false;
        return path.equals("/ai") || path.startsWith("/ai/")
                || path.equals("/api") || path.startsWith("/api/")
                || path.equals("/user") || path.startsWith("/user/");
    }
}
