package com.example.jingjingai2.mapper.sql;

import com.example.jingjingai2.db.DbDialect;

import java.util.List;
import java.util.Map;

/**
 * {@code ConversationMapper} 的双数据库 SQL。
 *
 * <p>MySQL 分支逐字保留原有注解 SQL；SQLite 分支只替换方言部分：
 * upsert 走 {@code ON CONFLICT}（冲突列与 schema-sqlite.sql 的唯一索引一一对应），
 * 元数据查询走 {@code pragma_table_info} / {@code pragma_index_list}，
 * 更新时间由应用层显式维护（SQLite 没有 {@code ON UPDATE CURRENT_TIMESTAMP}）。
 */
public final class ConversationSqlProvider {

    private ConversationSqlProvider() {}

    private static final String MYSQL_TOUCH =
            "INSERT INTO conversation(user_id, conversation_id, title, message_count, workspace_id, permission_mode) " +
                    "VALUES(#{userId}, #{conversationId}, #{title}, 1, #{workspaceId}, #{permissionMode}) " +
                    "ON DUPLICATE KEY UPDATE message_count = message_count + 1, " +
                    "                        updated_at = CURRENT_TIMESTAMP";

    private static final String SQLITE_TOUCH =
            "INSERT INTO conversation(user_id, conversation_id, title, message_count, workspace_id, permission_mode) " +
                    "VALUES(#{userId}, #{conversationId}, #{title}, 1, #{workspaceId}, #{permissionMode}) " +
                    "ON CONFLICT(user_id, conversation_id) DO UPDATE SET " +
                    "  message_count = message_count + 1, " +
                    "  updated_at = CURRENT_TIMESTAMP";

    private static final String MYSQL_BACKFILL =
            "INSERT INTO conversation(user_id, conversation_id, title, message_count, created_at, updated_at) " +
                    "SELECT m.user_id, m.conversation_id, " +
                    "       COALESCE((SELECT LEFT(x.content, 24) FROM conversation_message x " +
                    "                 WHERE x.user_id = m.user_id AND x.conversation_id = m.conversation_id " +
                    "                   AND x.role = 'user' ORDER BY x.id LIMIT 1), '历史会话'), " +
                    "       COUNT(*), MIN(m.created_at), MAX(m.created_at) " +
                    "FROM conversation_message m " +
                    "GROUP BY m.user_id, m.conversation_id " +
                    "ON DUPLICATE KEY UPDATE updated_at = GREATEST(conversation.updated_at, VALUES(updated_at))";

    /**
     * SQLite 版逐项等价：
     * <ul>
     *   <li>{@code LEFT(s, 24)} 与 {@code substr(s, 1, 24)} 都按<strong>字符</strong>截取，中文不会被切坏</li>
     *   <li>{@code GREATEST(a, b)} 与 {@code max(a, b)} 都是「任一为 NULL 则 NULL」</li>
     *   <li>{@code VALUES(col)} 与 {@code excluded.col} 都指「本次要插入的那一行」</li>
     *   <li>{@code WHERE 1} 是 SQLite 的硬要求：INSERT…SELECT 接 UPSERT 时没有 WHERE 会产生解析歧义</li>
     * </ul>
     */
    private static final String SQLITE_BACKFILL =
            "INSERT INTO conversation(user_id, conversation_id, title, message_count, created_at, updated_at) " +
                    "SELECT m.user_id, m.conversation_id, " +
                    "       COALESCE((SELECT substr(x.content, 1, 24) FROM conversation_message x " +
                    "                 WHERE x.user_id = m.user_id AND x.conversation_id = m.conversation_id " +
                    "                   AND x.role = 'user' ORDER BY x.id LIMIT 1), '历史会话'), " +
                    "       COUNT(*), MIN(m.created_at), MAX(m.created_at) " +
                    "FROM conversation_message m " +
                    "WHERE 1 " +
                    "GROUP BY m.user_id, m.conversation_id " +
                    "ON CONFLICT(user_id, conversation_id) DO UPDATE SET " +
                    "  updated_at = max(updated_at, excluded.updated_at)";

    private static final String MYSQL_UPDATE_PERMISSION_MODE =
            "UPDATE conversation SET permission_mode = #{mode} " +
                    "WHERE user_id = #{userId} AND conversation_id = #{conversationId}";

    private static final String SQLITE_UPDATE_PERMISSION_MODE =
            "UPDATE conversation SET permission_mode = #{mode}, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE user_id = #{userId} AND conversation_id = #{conversationId}";

    private static final String MYSQL_UPDATE_PLAN_JSON =
            "UPDATE conversation SET plan_json = #{planJson} " +
                    "WHERE user_id = #{userId} AND conversation_id = #{conversationId}";

    private static final String SQLITE_UPDATE_PLAN_JSON =
            "UPDATE conversation SET plan_json = #{planJson}, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE user_id = #{userId} AND conversation_id = #{conversationId}";

    private static final String MYSQL_COUNT_PLAN_COLUMN =
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation' AND COLUMN_NAME = 'plan_json'";

    private static final String SQLITE_COUNT_PLAN_COLUMN =
            "SELECT COUNT(*) FROM pragma_table_info('conversation') WHERE name = 'plan_json'";

    private static final String MYSQL_COUNT_WORKSPACE_ID_COLUMN =
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation' AND COLUMN_NAME = 'workspace_id'";

    private static final String SQLITE_COUNT_WORKSPACE_ID_COLUMN =
            "SELECT COUNT(*) FROM pragma_table_info('conversation') WHERE name = 'workspace_id'";

    private static final String MYSQL_COUNT_WORKSPACE_ID_INDEX =
            "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation' AND INDEX_NAME = 'idx_conv_workspace'";

    private static final String SQLITE_COUNT_WORKSPACE_ID_INDEX =
            "SELECT COUNT(*) FROM pragma_index_list('conversation') WHERE name = 'idx_conv_workspace'";

    private static final String MYSQL_COUNT_PERMISSION_MODE_COLUMN =
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation' AND COLUMN_NAME = 'permission_mode'";

    private static final String SQLITE_COUNT_PERMISSION_MODE_COLUMN =
            "SELECT COUNT(*) FROM pragma_table_info('conversation') WHERE name = 'permission_mode'";

    public static String touch(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_TOUCH : MYSQL_TOUCH;
    }

    public static String backfill(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_BACKFILL : MYSQL_BACKFILL;
    }

    public static String updatePermissionMode(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_UPDATE_PERMISSION_MODE : MYSQL_UPDATE_PERMISSION_MODE;
    }

    public static String updatePlanJson(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_UPDATE_PLAN_JSON : MYSQL_UPDATE_PLAN_JSON;
    }

    public static String countPlanColumn(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_COUNT_PLAN_COLUMN : MYSQL_COUNT_PLAN_COLUMN;
    }

    public static String countWorkspaceIdColumn(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_COUNT_WORKSPACE_ID_COLUMN : MYSQL_COUNT_WORKSPACE_ID_COLUMN;
    }

    public static String countWorkspaceIdIndex(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_COUNT_WORKSPACE_ID_INDEX : MYSQL_COUNT_WORKSPACE_ID_INDEX;
    }

    public static String countPermissionModeColumn(Object ignored) {
        return DbDialect.isSqlite() ? SQLITE_COUNT_PERMISSION_MODE_COLUMN : MYSQL_COUNT_PERMISSION_MODE_COLUMN;
    }

    /** 多关键词降序分页。ORDER BY 写死，用户输入只出现在 {@code #{}} 占位符里。 */
    public static String searchMessagesAnyDesc(Map<String, Object> params) {
        return searchMessagesAny(params, false);
    }

    /** 多关键词升序分页。与降序是两条独立 SQL，不用 {@code ${}} 拼接排序方向。 */
    public static String searchMessagesAnyAsc(Map<String, Object> params) {
        return searchMessagesAny(params, true);
    }

    /**
     * 一次匹配全部关键词：OR、一行一条消息、统一排序，再 LIMIT/OFFSET。
     * 摘要落在查询顺序里第一个实际命中的关键词上。单个关键词时与旧 SQL 的起点算法一致。
     */
    private static String searchMessagesAny(Map<String, Object> params, boolean ascending) {
        int n = listSize(params == null ? null : params.get("needles"));
        if (n <= 0 || listSize(params.get("patterns")) != n) {
            return "SELECT id, role, created_at AS timestamp, '' AS snippet "
                    + "FROM conversation_message WHERE 1 = 0";
        }
        String pos = firstHitPosition(n);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, role, created_at AS timestamp, ");
        sql.append("SUBSTR(content, CASE WHEN (").append(pos).append(") - #{before} > 1 THEN (")
                .append(pos).append(") - #{before} ELSE 1 END, #{window}) AS snippet ");
        sql.append("FROM conversation_message ");
        sql.append("WHERE user_id = #{userId} AND conversation_id = #{conversationId} AND (");
        for (int i = 0; i < n; i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("content LIKE #{patterns[").append(i).append("]} ESCAPE '\\'");
        }
        sql.append(") ORDER BY id ").append(ascending ? "ASC" : "DESC");
        sql.append(" LIMIT #{limit} OFFSET #{offset}");
        return sql.toString();
    }

    /** 查询顺序中第一个真正出现在正文里的关键词位置。线性 CASE，MySQL 和 SQLite 都能跑。 */
    private static String firstHitPosition(int n) {
        StringBuilder pos = new StringBuilder("CASE");
        for (int i = 0; i < n; i++) {
            String instr = "INSTR(content, #{needles[" + i + "]})";
            pos.append(" WHEN ").append(instr).append(" > 0 THEN ").append(instr);
        }
        pos.append(" ELSE 1 END");
        return pos.toString();
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }
}
