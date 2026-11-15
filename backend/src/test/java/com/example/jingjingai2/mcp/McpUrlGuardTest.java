package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class McpUrlGuardTest {

    private static McpServerSpec.UrlPolicy def() {
        return new McpServerSpec.UrlPolicy();
    }

    private static McpServerSpec.UrlPolicy strict() {
        McpServerSpec.UrlPolicy p = new McpServerSpec.UrlPolicy();
        p.setMode("strict");
        return p;
    }

    @AfterEach
    void resetResolver() {
        McpUrlGuard.setResolverForTest(null);
    }

    @Test
    @DisplayName("default 与 strict 都拒绝 localhost / 回环 / 私有网段")
    void defaultBlocksLocalAndPrivate() {
        for (McpServerSpec.UrlPolicy p : new McpServerSpec.UrlPolicy[]{def(), strict()}) {
            assertNotNull(McpUrlGuard.checkUrl("http://127.0.0.1:8080", p));
            assertNotNull(McpUrlGuard.checkUrl("http://localhost:3000/app", p));
            assertNotNull(McpUrlGuard.checkUrl("http://app.localhost", p));
            assertNotNull(McpUrlGuard.checkUrl("http://10.0.0.5/", p));
            assertNotNull(McpUrlGuard.checkUrl("http://192.168.1.1/x", p));
            assertNotNull(McpUrlGuard.checkUrl("http://172.16.0.1", p));
            assertNotNull(McpUrlGuard.checkUrl("http://[::1]/", p));
            assertNotNull(McpUrlGuard.checkUrl("http://[fc00::1]/", p));
            assertNotNull(McpUrlGuard.checkUrl("http://100.64.0.1/", p));
            assertNotNull(McpUrlGuard.checkUrl("http://224.0.0.1/", p));
            assertNotNull(McpUrlGuard.checkUrl("http://[::ffff:127.0.0.1]/", p));
            assertNotNull(McpUrlGuard.checkUrl("http://0177.0.0.1/", p));
        }
    }

    @Test
    @DisplayName("公网字面 IP 不误拦")
    void publicLiteralIpAllowed() {
        assertNull(McpUrlGuard.checkUrl("http://1.1.1.1/", def()));
        assertNull(McpUrlGuard.checkUrl("https://8.8.8.8/dns", strict()));
    }

    @Test
    @DisplayName("strict：拦截 localhost 与私有网段")
    void strictBlocksPrivate() {
        assertNotNull(McpUrlGuard.checkUrl("http://127.0.0.1:8080", strict()));
        assertNotNull(McpUrlGuard.checkUrl("http://localhost", strict()));
        assertNotNull(McpUrlGuard.checkUrl("http://10.1.2.3", strict()));
        assertNotNull(McpUrlGuard.checkUrl("http://192.168.0.1", strict()));
        assertNotNull(McpUrlGuard.checkUrl("http://[::1]/", strict()));
    }

    @Test
    @DisplayName("两种 mode 都拒绝元数据、userinfo、数字伪装、非 http")
    void alwaysRejectDangerous() {
        for (McpServerSpec.UrlPolicy p : new McpServerSpec.UrlPolicy[]{def(), strict()}) {
            assertNotNull(McpUrlGuard.checkUrl("http://169.254.169.254/", p), "metadata");
            assertNotNull(McpUrlGuard.checkUrl("http://169.254.1.1/x", p), "link-local");
            assertNotNull(McpUrlGuard.checkUrl("http://[fd00:ec2::254]/", p), "aws imds v6");
            assertNotNull(McpUrlGuard.checkUrl("http://a@127.0.0.1", p), "userinfo");
            assertNotNull(McpUrlGuard.checkUrl("http://user:pass@example.com", p), "userinfo2");
            assertNotNull(McpUrlGuard.checkUrl("http://2130706433", p), "decimal ip");
            assertNotNull(McpUrlGuard.checkUrl("http://0x7f000001", p), "hex ip");
            assertNotNull(McpUrlGuard.checkUrl("file:///etc/passwd", p), "file");
            assertNotNull(McpUrlGuard.checkUrl("ftp://example.com", p), "ftp");
        }
    }

    @Test
    @DisplayName("大小写、尾随点：公网放行，回环仍拒绝")
    void casePortTrailingDot() {
        assertNotNull(McpUrlGuard.checkUrl("HTTP://127.0.0.1:8080", def()));
        assertNotNull(McpUrlGuard.checkUrl("http://127.0.0.1.:9", def()));
        assertNull(McpUrlGuard.checkUrl("HTTP://1.1.1.1:8080", def()));
        String why = McpUrlGuard.checkUrl("HTTP://169.254.169.254./latest", def());
        assertNotNull(why);
    }

    @Test
    @DisplayName("IPv6 链路本地一律拒绝")
    void ipv6LinkLocal() {
        assertNotNull(McpUrlGuard.checkUrl("http://[fe80::1]/", def()));
        assertNotNull(McpUrlGuard.checkUrl("http://[fe80::1]/", strict()));
    }

    @Test
    @DisplayName("arguments JSON 里按 arg-names 抽 url")
    void rejectFromArgumentsJson() {
        McpServerSpec.UrlPolicy p = def();
        assertNotNull(McpUrlGuard.rejectReason("{\"url\":\"http://169.254.169.254/\"}", p));
        assertNotNull(McpUrlGuard.rejectReason("{\"url\":\"http://127.0.0.1:8080\"}", p));
        assertNull(McpUrlGuard.rejectReason("{\"text\":\"no url here\"}", p));
    }

    @Test
    @DisplayName("DNS：解析到私有或元数据都拒绝；解析到公网放行")
    void dnsPolicy() throws Exception {
        InetAddress priv = InetAddress.getByAddress("x.example", new byte[]{10, 0, 0, 1});
        InetAddress meta = InetAddress.getByAddress("m.example", new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254});
        InetAddress pub = InetAddress.getByAddress("p.example", new byte[]{1, 1, 1, 1});
        McpUrlGuard.setResolverForTest(host -> {
            if (host.startsWith("priv.")) return new InetAddress[]{priv};
            if (host.startsWith("meta.")) return new InetAddress[]{meta};
            if (host.startsWith("pub.")) return new InetAddress[]{pub};
            throw new UnknownHostException(host);
        });
        assertNotNull(McpUrlGuard.checkUrl("http://priv.example/", def()));
        assertNotNull(McpUrlGuard.checkUrl("http://priv.example/", strict()));
        assertNotNull(McpUrlGuard.checkUrl("http://meta.example/", def()));
        assertNull(McpUrlGuard.checkUrl("http://pub.example/", def()));
    }
}
