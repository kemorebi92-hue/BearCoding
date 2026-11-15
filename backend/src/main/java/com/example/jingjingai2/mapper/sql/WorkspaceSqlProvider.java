package com.example.jingjingai2.mapper.sql;

import com.example.jingjingai2.db.DbDialect;

/** {@code WorkspaceMapper.countWorkspaceTable} 的双数据库 SQL。 */
public final class WorkspaceSqlProvider {

    private WorkspaceSqlProvider() {}

    private static final String MYSQL =
            "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workspace'";

    private static final String SQLITE =
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'workspace'";

    public static String countWorkspaceTable(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE : MYSQL;
    }
}
