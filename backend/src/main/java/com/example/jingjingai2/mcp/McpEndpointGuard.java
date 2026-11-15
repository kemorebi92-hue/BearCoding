package com.example.jingjingai2.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * 远程 MCP <strong>endpoint</strong>（base URL）的 SSRF 边界检查。
 *
 * <p>与 {@link McpUrlGuard} 分离：后者只拦<strong>工具参数</strong>里的 URL；
 * 本类拦的是用户配置的远程 MCP 服务地址。不要互相替换。
 *
 * <p>本检查在「解析主机 → 分类地址」层面拒绝明显危险目标。JDK
 * {@link HttpClient} 在真正连接时会再次 DNS，存在重绑定时间窗；且默认可能跟随重定向。
 * 未来 {@code HttpMcpSession} 必须通过 {@link #applySafeHttpClient(HttpClient.Builder, Duration)}
 * 关闭自动重定向并设置连接超时。完整出站隔离仍需部署层网络策略。
 */
public final class McpEndpointGuard {

    /** 可替换的 DNS，单测注入。 */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static volatile Resolver resolver = InetAddress::getAllByName;

    private McpEndpointGuard() {}

    public static void setResolverForTest(Resolver r) {
        resolver = r == null ? InetAddress::getAllByName : r;
    }

    /**
     * 默认策略：仅 https；拒绝回环/私网/链路本地/元数据等。
     * @return null 放行；否则是拒绝原因（可给用户看，不含密钥）
     */
    public static String rejectReason(String baseUrl) {
        return rejectReason(baseUrl, Policy.defaults());
    }

    public static String rejectReason(String baseUrl, Policy policy) {
        if (policy == null) policy = Policy.defaults();
        if (baseUrl == null || baseUrl.isBlank()) return "远程 baseUrl 不能为空";
        String raw = baseUrl.trim();
        if (!raw.equals(baseUrl)) return "baseUrl 首尾不能有空白";

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (Exception e) {
            return "baseUrl 无法解析";
        }
        if (!uri.isAbsolute()) return "baseUrl 必须是绝对地址";

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) return "缺少协议";
        scheme = scheme.toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            // ok
        } else if ("http".equals(scheme)) {
            if (!policy.allowInsecureHttp) {
                return "远程 endpoint 默认只允许 https";
            }
        } else {
            return "协议不被允许：" + scheme;
        }

        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
            return "含用户信息（userinfo）";
        }
        String authority = uri.getRawAuthority();
        if (authority != null && authority.contains("@")) {
            return "含用户信息（userinfo）";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "baseUrl 不能含 query 或 fragment";
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return "baseUrl 不要带路径；路径请放在 endpointPath";
        }

        int port = uri.getPort();
        if (port == 0 || port < -1) return "端口不合法";
        // -1 = default; explicit 0 is invalid. Block uncommon dangerous locals already via IP.

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            host = extractHost(uri);
        }
        if (host == null || host.isBlank()) return "缺少主机名";
        host = McpUrlGuard.normalizeHost(host);

        if (McpUrlGuard.isMetadataHostname(host)) return "云厂商元数据主机";
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            return "回环地址";
        }
        if (host.endsWith(".local") || host.endsWith(".internal")) {
            return "内网主机名";
        }

        McpUrlGuard.HostKind kind = McpUrlGuard.classify(host);
        if (kind == McpUrlGuard.HostKind.DISGUISED) return "十进制/十六进制形式的 IP 伪装";

        if (kind == McpUrlGuard.HostKind.IPV4) {
            byte[] ip = McpUrlGuard.parseDottedIPv4(host);
            String why = McpUrlGuard.ipv4Reason(ip, true);
            if (why != null) return why;
            if (!policy.allowInsecureHttp && "http".equals(scheme)) {
                return "远程 endpoint 默认只允许 https";
            }
            return null;
        }
        if (kind == McpUrlGuard.HostKind.IPV6) {
            byte[] ip = McpUrlGuard.parseIPv6(host);
            if (ip == null) return "无法解析 IPv6";
            String why = McpUrlGuard.ipv6Reason(ip, true);
            if (why != null) return why;
            return null;
        }

        // 主机名：解析全部地址，任一不允许则拒绝（混合解析结果）
        Resolver dns = resolver;
        if (dns == null) return "无法解析主机";
        InetAddress[] addrs;
        try {
            addrs = dns.resolve(host);
        } catch (UnknownHostException e) {
            return "无法解析主机";
        } catch (Exception e) {
            return "无法解析主机";
        }
        if (addrs == null || addrs.length == 0) return "无法解析主机";
        for (InetAddress a : addrs) {
            if (a == null) continue;
            byte[] b = a.getAddress();
            String why = b.length == 4
                    ? McpUrlGuard.ipv4Reason(b, true)
                    : McpUrlGuard.ipv6Reason(b, true);
            if (why != null) return why;
        }
        return null;
    }

    /**
     * 供未来 HttpMcpSession 使用：关闭自动重定向并设置连接超时。
     * 不能消除 DNS 重绑定时间窗；只降低重定向绕过风险。
     */
    public static HttpClient.Builder applySafeHttpClient(HttpClient.Builder builder, Duration connectTimeout) {
        if (builder == null) throw new IllegalArgumentException("builder");
        Duration t = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        return builder
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(t);
    }

    private static String extractHost(URI uri) {
        String auth = uri.getAuthority();
        if (auth == null) auth = uri.getRawAuthority();
        if (auth == null) return null;
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.startsWith("[")) {
            int end = auth.indexOf(']');
            if (end > 0) return auth.substring(1, end);
        }
        int colon = auth.indexOf(':');
        if (colon >= 0) auth = auth.substring(0, colon);
        return auth;
    }

    /** endpoint 策略。默认最严：仅 https，不因开发便利放行 http/loopback。 */
    public static final class Policy {
        /** 为 true 时允许 http://（仍拒绝私网等）。默认 false。 */
        public boolean allowInsecureHttp = false;

        public static Policy defaults() {
            return new Policy();
        }

        public Policy allowInsecureHttp(boolean v) {
            this.allowInsecureHttp = v;
            return this;
        }
    }
}
