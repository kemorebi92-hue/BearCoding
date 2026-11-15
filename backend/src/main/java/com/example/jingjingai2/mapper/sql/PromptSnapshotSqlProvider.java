package com.example.jingjingai2.mapper.sql;

import com.example.jingjingai2.db.DbDialect;

/**
 * {@code PromptSnapshotMapper.insertIgnore} 的双数据库 SQL。
 * 两边语义一致：同一个 sha256 只保留第一次写入的那一份，重复插入静默忽略。
 */
public final class PromptSnapshotSqlProvider {

    private PromptSnapshotSqlProvider() {}

    private static final String MYSQL =
            "INSERT IGNORE INTO prompt_snapshot(sha256, kind, content) VALUES(#{sha256}, #{kind}, #{content})";

    private static final String SQLITE =
            "INSERT OR IGNORE INTO prompt_snapshot(sha256, kind, content) VALUES(#{sha256}, #{kind}, #{content})";

    public static String insertIgnore(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE : MYSQL;
    }
}
