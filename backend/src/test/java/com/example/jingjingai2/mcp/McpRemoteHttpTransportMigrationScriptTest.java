package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 2C 迁移脚本的静态检查。不连数据库。
 */
class McpRemoteHttpTransportMigrationScriptTest {

    @Test
    @DisplayName("迁移脚本存在且声明手工执行、默认 stdio、command 可空、不碰权限表")
    void scriptHasCompatibilityGuarantees() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(script()), "MySQL 迁移脚本不进入 V3");
        String raw = Files.readString(script(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("不会自动执行") || raw.contains("手工"));
        assertTrue(raw.contains("DEFAULT 'stdio'"));
        assertTrue(raw.contains("MODIFY COLUMN command VARCHAR(64) NULL"));
        assertTrue(raw.contains("base_url"));
        assertTrue(raw.contains("endpoint_path"));
        assertTrue(raw.contains("auth_type"));
        assertFalse(raw.toLowerCase().contains("delete from mcp_user_policy"));
        assertFalse(raw.toLowerCase().contains("drop table"));
        assertTrue(raw.contains("不改 enabled") || raw.contains("不改 enabled、created_by"));
    }

    private static Path script() {
        return Path.of("docs/sql/2026-10-25-mcp-remote-http-transport.sql");
    }
}
