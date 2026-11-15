package com.example.jingjingai2.service;

import com.example.jingjingai2.db.DbDialect;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * schema.sql 的 mode=always 只会执行 CREATE TABLE IF NOT EXISTS，
 * 已存在的表不会补列。MySQL 8 没有 ADD COLUMN IF NOT EXISTS，
 * 所以这里先查 information_schema，缺了才 ALTER。失败不阻塞启动。
 */
@Component
public class SchemaMigrator {

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        // SQLite 的列和索引由 schema-sqlite.sql 一次性建好，不存在需要补列的旧库。
        // 这里显式跳过，而不是让 MySQL DDL 抛异常再被 catch 吞掉——后者会把「没执行」伪装成「迁移成功」。
        if (DbDialect.isSqlite()) {
            addSqliteColumn("ALTER TABLE user_model_config ADD COLUMN context_window_tokens INTEGER");
            return;
        }
        addColumnIfMissing("tool_call_log", "turn_index",
                "ALTER TABLE tool_call_log ADD COLUMN turn_index INT NULL");
        addIndexIfMissing("tool_call_log", "idx_tool_call_conv_turn",
                "ALTER TABLE tool_call_log ADD INDEX idx_tool_call_conv_turn (conversation_id, turn_index)");
        addColumnIfMissing("agent_turn", "system_dynamic_sha256",
                "ALTER TABLE agent_turn ADD COLUMN system_dynamic_sha256 CHAR(64) NULL");
        addColumnIfMissing("agent_turn", "est_tokens_injected",
                "ALTER TABLE agent_turn ADD COLUMN est_tokens_injected INT NULL");
        // 旧库的 mcp_server_config 是 CREATE TABLE IF NOT EXISTS 留下来的，没有远程 HTTP 列。
        // 缺列时 /ai/mcp/servers 会直接 500。已有行靠 DEFAULT 变成 stdio，不改原来的命令。
        addColumnIfMissing("mcp_server_config", "transport",
                "ALTER TABLE mcp_server_config ADD COLUMN transport VARCHAR(32) NOT NULL DEFAULT 'stdio'");
        addColumnIfMissing("mcp_server_config", "base_url",
                "ALTER TABLE mcp_server_config ADD COLUMN base_url VARCHAR(512) NULL");
        addColumnIfMissing("mcp_server_config", "endpoint_path",
                "ALTER TABLE mcp_server_config ADD COLUMN endpoint_path VARCHAR(256) NULL");
        addColumnIfMissing("mcp_server_config", "auth_type",
                "ALTER TABLE mcp_server_config ADD COLUMN auth_type VARCHAR(32) NOT NULL DEFAULT 'none'");
        allowNullCommand();
    }

    private void addSqliteColumn(String sql) {
        try {
            jdbc.execute(sql);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            if (message.contains("duplicate column")) return;
            Throwable cause = e.getCause();
            String nested = cause == null ? "" : String.valueOf(cause.getMessage()).toLowerCase();
            if (nested.contains("duplicate column")) return;
            throw e;
        }
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n == 0) {
                jdbc.execute(ddl);
                System.out.println("[Schema] 已为 " + table + " 补充列 " + column);
            }
        } catch (Exception e) {
            System.err.println("[Schema] " + table + "." + column + " 迁移跳过: " + e.getMessage());
        }
    }

    /** streamable_http 不需要命令。已有 stdio 行的 command 值不动。 */
    private void allowNullCommand() {
        try {
            String nullable = jdbc.queryForObject(
                    "SELECT IS_NULLABLE FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mcp_server_config' " +
                            "AND COLUMN_NAME = 'command'",
                    String.class);
            if (nullable != null && nullable.equalsIgnoreCase("NO")) {
                jdbc.execute("ALTER TABLE mcp_server_config MODIFY COLUMN command VARCHAR(64) NULL");
                System.out.println("[Schema] 已允许 mcp_server_config.command 为空");
            }
        } catch (Exception e) {
            System.err.println("[Schema] mcp_server_config.command 迁移跳过: " + e.getMessage());
        }
    }

    private void addIndexIfMissing(String table, String index, String ddl) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, index);
            if (n != null && n == 0) {
                jdbc.execute(ddl);
                System.out.println("[Schema] 已为 " + table + " 补充索引 " + index);
            }
        } catch (Exception e) {
            System.err.println("[Schema] " + table + " 索引 " + index + " 迁移跳过: " + e.getMessage());
        }
    }
}
