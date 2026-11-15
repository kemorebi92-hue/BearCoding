package com.example.jingjingai2.mapper.sql;

import com.example.jingjingai2.db.DbDialect;

/** {@code UserMemoryMapper.upsert} 的双数据库 SQL。冲突列对应 {@code uk_user_cat_key}。 */
public final class UserMemorySqlProvider {

    private UserMemorySqlProvider() {}

    private static final String MYSQL =
            "INSERT INTO user_memory(user_id, category, mem_key, content, source, conversation_id) " +
                    "VALUES(#{userId}, #{category}, #{memKey}, #{content}, #{source}, #{conversationId}) " +
                    "ON DUPLICATE KEY UPDATE content = #{content}, source = #{source}, " +
                    "                        conversation_id = #{conversationId}, " +
                    "                        updated_at = CURRENT_TIMESTAMP";

    private static final String SQLITE =
            "INSERT INTO user_memory(user_id, category, mem_key, content, source, conversation_id) " +
                    "VALUES(#{userId}, #{category}, #{memKey}, #{content}, #{source}, #{conversationId}) " +
                    "ON CONFLICT(user_id, category, mem_key) DO UPDATE SET " +
                    "  content = excluded.content, " +
                    "  source = excluded.source, " +
                    "  conversation_id = excluded.conversation_id, " +
                    "  updated_at = CURRENT_TIMESTAMP";

    public static String upsert(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE : MYSQL;
    }
}
