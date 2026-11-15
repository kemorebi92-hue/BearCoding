package com.example.jingjingai2.mapper.sql;

import com.example.jingjingai2.db.DbDialect;

/**
 * {@code McpServerConfigMapper.update} 的双数据库 SQL。
 * SQLite 没有 {@code ON UPDATE CURRENT_TIMESTAMP}，这里显式写 {@code updated_at}。
 */
public final class McpServerConfigSqlProvider {

    private McpServerConfigSqlProvider() {}

    private static final String MYSQL =
            "UPDATE mcp_server_config SET command=#{command}, args_json=#{argsJson}, env_json=#{envJson}, " +
                    "disabled_tools_json=#{disabledToolsJson}, trust=#{trust}, " +
                    "connect_timeout_seconds=#{connectTimeoutSeconds}, max_result_chars=#{maxResultChars}, " +
                    "transport=#{transport}, base_url=#{baseUrl}, endpoint_path=#{endpointPath}, auth_type=#{authType} " +
                    "WHERE name=#{name} AND created_by=#{createdBy}";

    private static final String SQLITE =
            "UPDATE mcp_server_config SET command=#{command}, args_json=#{argsJson}, env_json=#{envJson}, " +
                    "disabled_tools_json=#{disabledToolsJson}, trust=#{trust}, " +
                    "connect_timeout_seconds=#{connectTimeoutSeconds}, max_result_chars=#{maxResultChars}, " +
                    "transport=#{transport}, base_url=#{baseUrl}, endpoint_path=#{endpointPath}, auth_type=#{authType}, " +
                    "updated_at=CURRENT_TIMESTAMP " +
                    "WHERE name=#{name} AND created_by=#{createdBy}";

    public static String update(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE : MYSQL;
    }
}
