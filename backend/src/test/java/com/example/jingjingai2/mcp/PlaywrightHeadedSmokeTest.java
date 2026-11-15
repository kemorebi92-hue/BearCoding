package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动烟测：本机有头 Edge 能打开哔哩哔哩，且不会再拼出 undefined\Program Files。
 * 默认不跑。大会员正片播不了不是这个测试的失败条件。
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "JINGJING_PLAYWRIGHT_LIVE", matches = "1")
class PlaywrightHeadedSmokeTest {

    @Test
    void headedEdgeOpensBilibili() {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("npx");
        spec.setArgs(List.of("-y", "@playwright/mcp@0.0.83", "--isolated", "--browser", "msedge"));
        spec.setConnectTimeoutSeconds(60);
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : List.of("USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH",
                "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) env.put(key, value);
        }
        spec.setEnv(env);
        McpProperties props = new McpProperties();
        props.setInitializeTimeoutMs(60_000L);
        props.setRequestTimeoutMs(60_000L);
        StdioMcpSession session = StdioMcpSession.start("playwright-headed", spec, props);
        try {
            McpCallResult page = session.callTool("browser_navigate",
                    "{\"url\":\"https://search.bilibili.com/all?keyword=%E5%A4%8F%E7%9B%AE%E5%8F%8B%E4%BA%BA%E5%B8%90\"}");
            String text = page.text() == null ? "" : page.text();
            System.out.println(text.substring(0, Math.min(text.length(), 500)));
            assertFalse(page.isError(), text);
            assertFalse(text.contains("undefined\\Program Files"), text);
            assertFalse(text.contains("is not found"), text);
            assertTrue(text.contains("bilibili.com"), text);
        } finally {
            session.close();
        }
    }
}
