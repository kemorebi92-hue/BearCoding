package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Host 侧在 {@code tools/call} 之前检查模型填的 URL。
 *
 * <p>拦的是<strong>模型作为工具参数给出的地址</strong>，不是页面里的跳转/点击。
 * DNS 解析与使用之间有 rebinding 窗口，这是已知限制。
 *
 * <p>{@code mode=default}（本地单用户默认）：允许 localhost 与 RFC1918，
 * 仍拒绝非 http(s)、云元数据、userinfo、十进制/十六进制 IP 伪装。
 * {@code mode=strict}：再加上私有网段与回环。
 */
public final class McpUrlGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static volatile Resolver resolver = InetAddress::getAllByName;

    private McpUrlGuard() {}

    public static void setResolverForTest(Resolver r) {
        resolver = r == null ? InetAddress::getAllByName : r;
    }

    /**
     * @return null = 放行；否则是「地址不被允许（原因）」里的原因
     */
    public static String rejectReason(String argumentsJson, McpServerSpec.UrlPolicy policy) {
        if (policy == null) return null;
        List<String> names = policy.getArgNames();
        if (names == null || names.isEmpty()) return null;
        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            return null;
        }
        if (args == null || !args.isObject()) return null;
        for (String arg : names) {
            if (arg == null || arg.isBlank()) continue;
            JsonNode v = args.get(arg);
            if (v == null || !v.isTextual()) continue;
            String why = checkUrl(v.asText(), policy, resolver);
            if (why != null) return why;
        }
        return null;
    }

    public static String checkUrl(String raw, McpServerSpec.UrlPolicy policy) {
        return checkUrl(raw, policy, resolver);
    }

    static String checkUrl(String raw, McpServerSpec.UrlPolicy policy, Resolver dns) {
        if (raw == null || raw.isBlank()) return "URL 为空";
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            return "无法解析 URL";
        }
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) return "缺少协议";
        List<String> allowedSchemes = policy == null ? List.of("http", "https") : policy.effectiveSchemes();
        boolean schemeOk = false;
        for (String s : allowedSchemes) {
            if (s != null && s.equalsIgnoreCase(scheme)) {
                schemeOk = true;
                break;
            }
        }
        if (!schemeOk) return "协议不被允许：" + scheme;

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            return "含用户信息（userinfo）";
        }
        // userinfo 有时被包在 authority 里但 getUserInfo 为空：http://a@host
        String authority = uri.getRawAuthority();
        if (authority != null && authority.contains("@")) {
            return "含用户信息（userinfo）";
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // URI 对十进制 IP 可能把 host 放进 authority
            host = extractHost(uri);
        }
        if (host == null || host.isBlank()) return "缺少主机名";
        host = normalizeHost(host);

        if (isMetadataHostname(host)) return "云厂商元数据主机";

        List<String> allow = policy == null ? List.of() : policy.getAllowedHosts();
        if (allow != null && !allow.isEmpty()) {
            if (!hostAllowed(host, allow)) return "主机不在允许名单：" + host;
        }

        HostKind kind = classify(host);
        if (kind == HostKind.DISGUISED) return "十进制/十六进制形式的 IP 伪装";

        boolean strict = policy != null && policy.isStrict();

        if (kind == HostKind.IPV4) {
            byte[] ip = parseDottedIPv4(host);
            String why = ipv4Reason(ip, strict);
            if (why != null) return why;
            return null;
        }
        if (kind == HostKind.IPV6) {
            byte[] ip = parseIPv6(host);
            if (ip == null) return "无法解析 IPv6";
            String why = ipv6Reason(ip, strict);
            if (why != null) return why;
            return null;
        }

        // 主机名：localhost 一族直接拒绝，不走 DNS。尾随点已在 normalizeHost 去掉。
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            return "回环地址";
        }
        if (strict && (host.endsWith(".local") || host.endsWith(".internal"))) {
            return "内网主机名";
        }
        if (dns == null) return null;
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
            String why = b.length == 4 ? ipv4Reason(b, strict) : ipv6Reason(b, strict);
            if (why != null) return why;
        }
        return null;
    }

    enum HostKind { HOSTNAME, IPV4, IPV6, DISGUISED }

    static HostKind classify(String host) {
        if (host == null || host.isBlank()) return HostKind.HOSTNAME;
        String h = stripBrackets(host);
        if (h.indexOf(':') >= 0) return HostKind.IPV6;
        if (looksDisguised(h)) return HostKind.DISGUISED;
        if (isCanonicalDottedIPv4(h)) return HostKind.IPV4;
        if (h.indexOf('.') >= 0 && allNumericDots(h)) return HostKind.DISGUISED;
        return HostKind.HOSTNAME;
    }

    static boolean looksDisguised(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("0x")) return true;
        if (h.chars().allMatch(c -> (c >= '0' && c <= '9'))) {
            // 纯数字且不是单 octet：dword 形式 2130706433
            return host.length() > 3;
        }
        if (h.indexOf('.') < 0 && h.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return host.length() > 3;
        }
        return false;
    }

    static boolean isCanonicalDottedIPv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            if (p.length() > 1 && p.charAt(0) == '0') return false; // 八进制伪装
            int v = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return false;
        }
        return true;
    }

    private static boolean allNumericDots(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c == '.' || (c >= '0' && c <= '9') || c == 'x' || c == 'X'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return host.indexOf('.') >= 0;
    }

    static String normalizeHost(String host) {
        String h = stripBrackets(host).toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
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

    static boolean hostAllowed(String host, List<String> allow) {
        String h = normalizeHost(host);
        for (String a : allow) {
            if (a == null || a.isBlank()) continue;
            String n = normalizeHost(a);
            if (h.equals(n)) return true;
            if (h.endsWith("." + n)) return true;
        }
        return false;
    }

    static boolean isMetadataHostname(String host) {
        String h = normalizeHost(host);
        return "metadata.google.internal".equals(h)
                || "metadata.internal".equals(h)
                || "metadata".equals(h)
                || h.endsWith(".metadata.google.internal");
    }

    /**
     * 内网、回环、链路本地、CGNAT、组播一律拒绝。{@code strict} 不再放行这些地址，
     * 参数留着是为了不改已有调用点。
     */
    static String ipv4Reason(byte[] ip, boolean strict) {
        if (ip == null || ip.length != 4) return "无法解析 IPv4";
        int a = ip[0] & 0xff, b = ip[1] & 0xff;
        if (a == 0) return "未指定地址";
        if (a == 127) return "回环地址";
        if (a == 10) return "私有网段";
        if (a == 192 && b == 168) return "私有网段";
        if (a == 172 && b >= 16 && b <= 31) return "私有网段";
        if (a == 169 && b == 254) return "链路本地/云元数据地址";
        if (a == 100 && b >= 64 && b <= 127) return "运营商级 NAT 地址";
        if (a >= 224) return "组播或保留地址";
        return null;
    }

    static String ipv6Reason(byte[] ip, boolean strict) {
        if (ip == null || ip.length != 16) return "无法解析 IPv6";
        if (isIpv6MappedV4(ip)) {
            byte[] v4 = new byte[]{ip[12], ip[13], ip[14], ip[15]};
            return ipv4Reason(v4, true);
        }
        if (isLoopbackV6(ip)) return "回环地址";
        if (isUnspecifiedV6(ip)) return "未指定地址";
        int b0 = ip[0] & 0xff, b1 = ip[1] & 0xff;
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return "链路本地地址";
        if (isAwsImdsV6(ip)) return "云厂商元数据地址";
        if ((b0 & 0xfe) == 0xfc) return "私有网段";
        return null;
    }

    private static boolean isLoopbackV6(byte[] ip) {
        for (int i = 0; i < 15; i++) if (ip[i] != 0) return false;
        return ip[15] == 1;
    }

    private static boolean isUnspecifiedV6(byte[] ip) {
        for (byte b : ip) if (b != 0) return false;
        return true;
    }

    private static boolean isIpv6MappedV4(byte[] ip) {
        for (int i = 0; i < 10; i++) if (ip[i] != 0) return false;
        return (ip[10] & 0xff) == 0xff && (ip[11] & 0xff) == 0xff;
    }

    private static boolean isAwsImdsV6(byte[] ip) {
        // fd00:0ec2:0000:0000:0000:0000:0000:0254
        // fd00:ec2::254 → last hextet is hex 0x0254, not decimal 254
        return (ip[0] & 0xff) == 0xfd && ip[1] == 0
                && (ip[2] & 0xff) == 0x0e && (ip[3] & 0xff) == 0xc2
                && ip[4] == 0 && ip[5] == 0 && ip[6] == 0 && ip[7] == 0
                && ip[8] == 0 && ip[9] == 0 && ip[10] == 0 && ip[11] == 0
                && ip[12] == 0 && ip[13] == 0
                && (ip[14] & 0xff) == 0x02 && (ip[15] & 0xff) == 0x54;
    }

    static byte[] parseDottedIPv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    static byte[] parseIPv6(String host) {
        try {
            String h = stripBrackets(host);
            // 压缩形式交给 InetAddress（字面量，不走 DNS）
            if (isCanonicalDottedIPv4(h) || looksDisguised(h)) return null;
            InetAddress a = InetAddress.getByName(h);
            return a.getAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
