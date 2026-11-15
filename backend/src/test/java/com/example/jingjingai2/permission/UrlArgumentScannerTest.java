package com.example.jingjingai2.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工具参数里的 URL 扫描。重点：字符串里嵌的 JSON 不能被当成一个 URL。
 */
class UrlArgumentScannerTest {

    @Test
    @DisplayName("普通 URL 参数照旧检查")
    void plainUrlStillChecked() {
        assertNull(UrlArgumentScanner.reject("{\"url\":\"https://example.com/a\"}"));
        assertNotNull(UrlArgumentScanner.reject("{\"url\":\"http://169.254.169.254/latest\"}"));
    }

    @Test
    @DisplayName("proposalJson 里的合法地址放行，危险地址仍被拒")
    void embeddedProposalJsonIsNotTreatedAsUrl() {
        String ok = "{\"proposalJson\":\"{\\\"serviceName\\\":\\\"demo\\\",\\\"transport\\\":"
                + "\\\"streamable_http\\\",\\\"baseUrl\\\":\\\"https://1.1.1.1\\\","
                + "\\\"endpointPath\\\":\\\"/mcp\\\",\\\"authType\\\":\\\"none\\\"}\"}";
        assertNull(UrlArgumentScanner.reject(ok), "合法配置不应被当成非法 URL");

        String bad = "{\"proposalJson\":\"{\\\"serviceName\\\":\\\"demo\\\",\\\"transport\\\":"
                + "\\\"streamable_http\\\",\\\"baseUrl\\\":\\\"http://169.254.169.254\\\"}\"}";
        assertNotNull(UrlArgumentScanner.reject(bad), "嵌在 JSON 里的危险地址仍要被拒");
    }

    @Test
    @DisplayName("嵌的 JSON 里有普通句子也不误拦")
    void embeddedProseIgnored() {
        String prose = "{\"proposalJson\":\"{\\\"serviceName\\\":\\\"demo\\\",\\\"transport\\\":"
                + "\\\"stdio\\\",\\\"command\\\":\\\"npx\\\",\\\"args\\\":[\\\"-y\\\",\\\"pkg\\\"]}\"}";
        assertNull(UrlArgumentScanner.reject(prose));
    }
}
