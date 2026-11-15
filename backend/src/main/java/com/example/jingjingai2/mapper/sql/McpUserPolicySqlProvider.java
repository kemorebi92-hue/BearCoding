package com.example.jingjingai2.mapper.sql;

import com.example.jingjingai2.db.DbDialect;

/** {@code McpUserPolicyMapper.upsert} 的双数据库 SQL。冲突列对应 {@code uk_user_server_tool}。 */
public final class McpUserPolicySqlProvider {

    private McpUserPolicySqlProvider() {}

    private static final String MYSQL =
            "INSERT INTO mcp_user_policy(user_id, server_name, tool_name, effect, definition_hash) " +
                    "VALUES(#{userId}, #{server}, #{tool}, #{effect}, #{definitionHash}) " +
                    "ON DUPLICATE KEY UPDATE effect = #{effect}, definition_hash = #{definitionHash}, " +
                    "updated_at = CURRENT_TIMESTAMP";

    private static final String SQLITE =
            "INSERT INTO mcp_user_policy(user_id, server_name, tool_name, effect, definition_hash) " +
                    "VALUES(#{userId}, #{server}, #{tool}, #{effect}, #{definitionHash}) " +
                    "ON CONFLICT(user_id, server_name, tool_name) DO UPDATE SET " +
                    "  effect = excluded.effect, " +
                    "  definition_hash = excluded.definition_hash, " +
                    "  updated_at = CURRENT_TIMESTAMP";

    public static String upsert(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE : MYSQL;
    }
}
