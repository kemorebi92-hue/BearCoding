package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McpServerTemplateCatalog} 单测：模板目录的基本契约。
 * env 值来自 {@link System#getenv}，跨平台不硬断言具体键存在；只断言非秘密 + 命令/参数固定。
 */
class McpServerTemplateCatalogTest {

    @Test
    @DisplayName("目录里有 playwright 模板，固定命令 npx + 已验证的包名，allowedParams 为空")
    void hasPlaywrightTemplate() {
        McpServerTemplateCatalog catalog = new McpServerTemplateCatalog();

        McpServerTemplate pw = catalog.findById("playwright");
        assertNotNull(pw);
        assertEquals("playwright", pw.id());
        assertEquals("npx", pw.command(), "命令固定为 npx，模型改不了");
        assertTrue(pw.args().contains("@playwright/mcp@0.0.83"), "用项目已验证的包版本");
        assertTrue(pw.args().contains("--browser") && pargsContains(pw.args(), "msedge"));
        assertEquals("untrusted", pw.trust());
        assertTrue(pw.allowedParams() == null || pw.allowedParams().isEmpty(),
                "第一版 playwright 模板不接受任何用户参数");
        assertEquals(60, pw.connectTimeoutSeconds());
        assertEquals(6000, pw.maxResultChars());
    }

    @Test
    @DisplayName("未知模板 ID 返回 null；空/null ID 也返回 null")
    void unknownTemplateReturnsNull() {
        McpServerTemplateCatalog catalog = new McpServerTemplateCatalog();
        assertNull(catalog.findById("nope"));
        assertNull(catalog.findById(""));
        assertNull(catalog.findById(null));
    }

    @Test
    @DisplayName("模板 env 键不含敏感名（token/secret/key/password 等），值非空")
    void templateEnvKeysAreNotSensitive() {
        McpServerTemplate pw = new McpServerTemplateCatalog().findById("playwright");
        assertNotNull(pw.env());
        for (String key : pw.env().keySet()) {
            assertFalse(com.example.jingjingai2.mcp.McpClientManager.isSensitiveEnvKey(key),
                    "env 键不得是敏感名，实际：" + key);
            String value = pw.env().get(key);
            assertNotNull(value);
            assertFalse(value.isEmpty());
        }
    }

    @Test
    @DisplayName("all() 返回不可变且与 findById 一致")
    void allMatchesFindById() {
        McpServerTemplateCatalog catalog = new McpServerTemplateCatalog();
        List<McpServerTemplate> all = catalog.all();
        for (McpServerTemplate t : all) {
            assertSame(t, catalog.findById(t.id()));
        }
    }

    private static boolean pargsContains(List<String> args, String s) {
        return args != null && args.contains(s);
    }
}
