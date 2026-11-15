package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class McpEndpointGuardTest {

    @AfterEach
    void reset() {
        McpEndpointGuard.setResolverForTest(null);
    }

    @Test
    @DisplayName("默认只允许 https 公网；http 即使公网字面量也拒")
    void httpsOnlyByDefault() {
        assertNull(McpEndpointGuard.rejectReason("https://1.1.1.1"));
        assertNull(McpEndpointGuard.rejectReason("https://8.8.8.8"));
        String http = McpEndpointGuard.rejectReason("http://1.1.1.1");
        assertNotNull(http);
        assertTrue(http.contains("https"));
        assertNotNull(McpEndpointGuard.rejectReason("file:///etc/passwd"));
        assertNotNull(McpEndpointGuard.rejectReason("ftp://example.com"));
    }

    @Test
    @DisplayName("allowInsecureHttp 显式打开后仍拒绝私网")
    void insecureHttpStillBlocksPrivate() {
        McpEndpointGuard.Policy p = McpEndpointGuard.Policy.defaults().allowInsecureHttp(true);
        assertNull(McpEndpointGuard.rejectReason("http://1.1.1.1", p));
        assertNotNull(McpEndpointGuard.rejectReason("http://127.0.0.1", p));
        assertNotNull(McpEndpointGuard.rejectReason("http://10.0.0.1", p));
    }

    @Test
    @DisplayName("拒绝 userinfo、路径、query、fragment")
    void rejectsUserinfoAndPath() {
        assertNotNull(McpEndpointGuard.rejectReason("https://a@example.com"));
        assertNotNull(McpEndpointGuard.rejectReason("https://user:pass@example.com"));
        assertNotNull(McpEndpointGuard.rejectReason("https://example.com/mcp"));
        assertNotNull(McpEndpointGuard.rejectReason("https://example.com?x=1"));
        assertNotNull(McpEndpointGuard.rejectReason("https://example.com#frag"));
    }

    @Test
    @DisplayName("拒绝回环、私网、链路本地、元数据、伪装 IP")
    void blocksLocalPrivateMetadata() {
        assertNotNull(McpEndpointGuard.rejectReason("https://127.0.0.1"));
        assertNotNull(McpEndpointGuard.rejectReason("https://localhost"));
        assertNotNull(McpEndpointGuard.rejectReason("https://app.localhost"));
        assertNotNull(McpEndpointGuard.rejectReason("https://10.1.2.3"));
        assertNotNull(McpEndpointGuard.rejectReason("https://192.168.0.1"));
        assertNotNull(McpEndpointGuard.rejectReason("https://172.16.0.1"));
        assertNotNull(McpEndpointGuard.rejectReason("https://169.254.169.254"));
        assertNotNull(McpEndpointGuard.rejectReason("https://100.64.0.1"));
        assertNotNull(McpEndpointGuard.rejectReason("https://224.0.0.1"));
        assertNotNull(McpEndpointGuard.rejectReason("https://[::1]"));
        assertNotNull(McpEndpointGuard.rejectReason("https://[fc00::1]"));
        assertNotNull(McpEndpointGuard.rejectReason("https://[fe80::1]"));
        assertNotNull(McpEndpointGuard.rejectReason("https://[::ffff:127.0.0.1]"));
        assertNotNull(McpEndpointGuard.rejectReason("https://[fd00:ec2::254]"));
        assertNotNull(McpEndpointGuard.rejectReason("https://2130706433"));
        assertNotNull(McpEndpointGuard.rejectReason("https://0x7f000001"));
        assertNotNull(McpEndpointGuard.rejectReason("https://metadata.google.internal"));
    }

    @Test
    @DisplayName("主机名解析：全部公网放行；任一私网则拒；解析失败则拒")
    void dnsMixedAndFailure() throws Exception {
        InetAddress pub = InetAddress.getByAddress("mcp.example.com", new byte[]{1, 1, 1, 1});
        InetAddress priv = InetAddress.getByAddress("mcp.example.com", new byte[]{10, 0, 0, 1});

        McpEndpointGuard.setResolverForTest(host -> {
            if ("ok.example".equals(host)) return new InetAddress[]{pub};
            if ("mixed.example".equals(host)) return new InetAddress[]{pub, priv};
            if ("fail.example".equals(host)) throw new UnknownHostException(host);
            if ("empty.example".equals(host)) return new InetAddress[0];
            throw new UnknownHostException(host);
        });

        assertNull(McpEndpointGuard.rejectReason("https://ok.example"));
        String mixed = McpEndpointGuard.rejectReason("https://mixed.example");
        assertNotNull(mixed);
        assertTrue(mixed.contains("私有") || mixed.contains("网段"));
        assertEquals("无法解析主机", McpEndpointGuard.rejectReason("https://fail.example"));
        assertEquals("无法解析主机", McpEndpointGuard.rejectReason("https://empty.example"));
    }

    @Test
    @DisplayName("applySafeHttpClient 可调用且关闭自动重定向")
    void safeClientDisablesRedirects() {
        HttpClient client = McpEndpointGuard
                .applySafeHttpClient(HttpClient.newBuilder(), Duration.ofSeconds(5))
                .build();
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
    }
}
