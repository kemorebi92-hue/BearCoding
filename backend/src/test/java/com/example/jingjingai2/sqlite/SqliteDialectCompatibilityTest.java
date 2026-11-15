package com.example.jingjingai2.sqlite;

import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.db.DbDialect;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import com.example.jingjingai2.mapper.PromptSnapshotMapper;
import com.example.jingjingai2.mapper.UserMemoryMapper;
import com.example.jingjingai2.mapper.WorkspaceMapper;
import com.example.jingjingai2.mapper.sql.ConversationSqlProvider;
import com.example.jingjingai2.mapper.sql.McpServerConfigSqlProvider;
import com.example.jingjingai2.mapper.sql.McpUserPolicySqlProvider;
import com.example.jingjingai2.mapper.sql.PromptSnapshotSqlProvider;
import com.example.jingjingai2.mapper.sql.UserMemorySqlProvider;
import com.example.jingjingai2.mapper.sql.WorkspaceSqlProvider;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.service.SchemaMigrator;
import com.example.jingjingai2.service.WorkspaceService;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Stage SQLite-2 定向测试：验证 Mapper SQL 与启动迁移逻辑在 SQLite 下的行为，
 * 以及 MySQL 分支的 SQL 仍被保留。
 *
 * <p>数据库是 JUnit 临时目录里的独立文件，不碰 ./data/jingjing.db，也不碰 MySQL。
 */
class SqliteDialectCompatibilityTest {

    @TempDir
    static Path tempDir;

    private static String jdbcUrl;
    private static SqlSessionFactory sessionFactory;
    private static PooledDataSource pooledDataSource;

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("dialect-test.db").toAbsolutePath();
        DbDialect.overrideForTest(true);
        applySchema();
        sessionFactory = buildSessionFactory(jdbcUrl);
    }

    @AfterAll
    static void tearDown() {
        DbDialect.overrideForTest(null);
        if (pooledDataSource != null) pooledDataSource.forceCloseAll();
    }

    // ===================== 1 & 2：三个 upsert =====================

    @Test
    @DisplayName("touch：首次插入 message_count=1，重复执行 +1 且不改 title")
    void touchInsertThenUpdate() {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ConversationMapper m = s.getMapper(ConversationMapper.class);
            assertEquals(1, m.touch(1L, "c-touch", "原标题", null, "ASK"));
            assertEquals(1, rowInt("SELECT message_count FROM conversation WHERE conversation_id='c-touch'"));
            assertEquals("原标题", rowStr("SELECT title FROM conversation WHERE conversation_id='c-touch'"));

            assertEquals(1, m.touch(1L, "c-touch", "新标题不该覆盖", null, "ASK"));
            assertEquals(2, rowInt("SELECT message_count FROM conversation WHERE conversation_id='c-touch'"));
            assertEquals("原标题", rowStr("SELECT title FROM conversation WHERE conversation_id='c-touch'"));
            assertEquals("ASK", rowStr("SELECT permission_mode FROM conversation WHERE conversation_id='c-touch'"));
        }
    }

    @Test
    @DisplayName("mcp_user_policy.upsert：首次插入，重复执行覆盖 effect 与 definition_hash")
    void policyUpsert() {
        try (SqlSession s = sessionFactory.openSession(true)) {
            McpUserPolicyMapper m = s.getMapper(McpUserPolicyMapper.class);
            m.upsert(1L, "srv", "tool", "ASK", "hash-1");
            assertEquals("ASK", rowStr(
                    "SELECT effect FROM mcp_user_policy WHERE user_id=1 AND server_name='srv' AND tool_name='tool'"));
            assertEquals("hash-1", rowStr(
                    "SELECT definition_hash FROM mcp_user_policy WHERE user_id=1 AND server_name='srv' AND tool_name='tool'"));
            assertEquals(1, rowInt("SELECT COUNT(*) FROM mcp_user_policy WHERE user_id=1 AND server_name='srv'"));

            m.upsert(1L, "srv", "tool", "ALLOW", "hash-2");
            assertEquals("ALLOW", rowStr(
                    "SELECT effect FROM mcp_user_policy WHERE user_id=1 AND server_name='srv' AND tool_name='tool'"));
            assertEquals("hash-2", rowStr(
                    "SELECT definition_hash FROM mcp_user_policy WHERE user_id=1 AND server_name='srv' AND tool_name='tool'"));
            assertEquals(1, rowInt("SELECT COUNT(*) FROM mcp_user_policy WHERE user_id=1 AND server_name='srv'"));
        }
    }

    @Test
    @DisplayName("user_memory.upsert：首次插入，重复执行覆盖 content 与 source")
    void memoryUpsert() {
        try (SqlSession s = sessionFactory.openSession(true)) {
            UserMemoryMapper m = s.getMapper(UserMemoryMapper.class);
            m.upsert(1L, "profile", "nick", "第一版", "explicit", null);
            assertEquals("第一版", rowStr(
                    "SELECT content FROM user_memory WHERE user_id=1 AND category='profile' AND mem_key='nick'"));

            m.upsert(1L, "profile", "nick", "第二版", "auto", "conv-x");
            assertEquals("第二版", rowStr(
                    "SELECT content FROM user_memory WHERE user_id=1 AND category='profile' AND mem_key='nick'"));
            assertEquals("auto", rowStr(
                    "SELECT source FROM user_memory WHERE user_id=1 AND category='profile' AND mem_key='nick'"));
            assertEquals("conv-x", rowStr(
                    "SELECT conversation_id FROM user_memory WHERE user_id=1 AND category='profile' AND mem_key='nick'"));
            assertEquals(1, rowInt("SELECT COUNT(*) FROM user_memory WHERE user_id=1 AND category='profile'"));
        }
    }

    // ===================== 3：PromptSnapshot 重复不覆盖 =====================

    @Test
    @DisplayName("prompt_snapshot：重复 sha256 不覆盖已有内容")
    void promptSnapshotKeepsFirst() {
        try (SqlSession s = sessionFactory.openSession(true)) {
            PromptSnapshotMapper m = s.getMapper(PromptSnapshotMapper.class);
            String sha = "b".repeat(64);
            m.insertIgnore(sha, "SYSTEM", "原始内容");
            m.insertIgnore(sha, "SYSTEM", "后来内容");
            assertEquals("原始内容", rowStr("SELECT content FROM prompt_snapshot WHERE sha256='" + sha + "'"));
            assertEquals(1, rowInt("SELECT COUNT(*) FROM prompt_snapshot WHERE sha256='" + sha + "'"));
        }
    }

    // ===================== 4 & 5：backfill =====================

    @Test
    @DisplayName("backfill：补齐缺失的会话记录，标题按前 24 字符截取，message_count 正确")
    void backfillCreatesMissingConversations() {
        insertMessage(1L, "c-backfill", "user",
                "这是一条超过二十四个字符的很长很长的用户消息内容用于验证截断行为", "2026-01-01 10:00:00");
        insertMessage(1L, "c-backfill", "assistant", "回复", "2026-01-01 10:01:00");

        try (SqlSession s = sessionFactory.openSession(true)) {
            int n = s.getMapper(ConversationMapper.class).backfillFromMessages();
            assertTrue(n >= 1, "backfill 应至少插入 1 行");
        }

        String title = rowStr("SELECT title FROM conversation WHERE conversation_id='c-backfill'");
        assertEquals(24, title.length(), "标题应截取前 24 个字符: " + title);
        assertEquals("这是一条超过二十四个字符的很长很长的用户消息内容用于验证截断行为".substring(0, 24), title);
        assertEquals(2, rowInt("SELECT message_count FROM conversation WHERE conversation_id='c-backfill'"));
        assertEquals("2026-01-01 10:00:00",
                rowStr("SELECT created_at FROM conversation WHERE conversation_id='c-backfill'"));
        assertEquals("2026-01-01 10:01:00",
                rowStr("SELECT updated_at FROM conversation WHERE conversation_id='c-backfill'"));
    }

    @Test
    @DisplayName("backfill：没有用户消息时标题回退为「历史会话」")
    void backfillFallbackTitle() {
        insertMessage(1L, "c-backfill-nouser", "assistant", "只有助手消息", "2026-01-02 09:00:00");
        try (SqlSession s = sessionFactory.openSession(true)) {
            s.getMapper(ConversationMapper.class).backfillFromMessages();
        }
        assertEquals("历史会话", rowStr("SELECT title FROM conversation WHERE conversation_id='c-backfill-nouser'"));
    }

    @Test
    @DisplayName("backfill：created_at 为 NULL 时 GREATEST/max 保持 NULL 语义")
    void backfillNullTimestamps() {
        insertMessageWithNullTime(1L, "c-backfill-null", "user", "内容");
        try (SqlSession s = sessionFactory.openSession(true)) {
            s.getMapper(ConversationMapper.class).backfillFromMessages();
        }
        assertEquals(1, rowInt("SELECT COUNT(*) FROM conversation WHERE conversation_id='c-backfill-null'"));
        assertNull(rowStr("SELECT updated_at FROM conversation WHERE conversation_id='c-backfill-null'"),
                "MAX(NULL) 应为 NULL，与 MySQL GREATEST 一致");
    }

    @Test
    @DisplayName("backfill：已存在会话时 updated_at 取两者较大值")
    void backfillKeepsLaterUpdatedAt() {
        insertMessage(1L, "c-backfill-existing", "user", "新消息", "2026-03-01 08:00:00");
        exec("INSERT INTO conversation(user_id, conversation_id, title, message_count, created_at, updated_at) " +
                "VALUES(1, 'c-backfill-existing', '已有标题', 1, '2026-01-01 00:00:00', '2099-01-01 00:00:00')");

        try (SqlSession s = sessionFactory.openSession(true)) {
            s.getMapper(ConversationMapper.class).backfillFromMessages();
        }
        assertEquals("2099-01-01 00:00:00",
                rowStr("SELECT updated_at FROM conversation WHERE conversation_id='c-backfill-existing'"),
                "已有 updated_at 更晚时应保留");
        assertEquals("已有标题", rowStr("SELECT title FROM conversation WHERE conversation_id='c-backfill-existing'"),
                "冲突时不应覆盖 title");
    }

    // ===================== 6：六处元数据查询 =====================

    @Test
    @DisplayName("元数据查询：5 个 Mapper 方法在 SQLite 下返回正确结果")
    void metadataQueries() {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ConversationMapper c = s.getMapper(ConversationMapper.class);
            assertEquals(1, c.countPlanColumn());
            assertEquals(1, c.countWorkspaceIdColumn());
            assertEquals(1, c.countWorkspaceIdIndex());
            assertEquals(1, c.countPermissionModeColumn());
            assertEquals(1, s.getMapper(WorkspaceMapper.class).countWorkspaceTable());
        }
    }

    // ===================== 7 & 8：启动迁移隔离 =====================

    @Test
    @DisplayName("SchemaMigrator：SQLite 只补模型窗口列，不跑 MySQL 的 information_schema")
    void schemaMigratorSkippedOnSqlite() {
        DbDialect.overrideForTest(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new SchemaMigrator(jdbc).migrate();
        verify(jdbc).execute(contains("context_window_tokens"));
        verify(jdbc, never()).queryForObject(contains("information_schema"), eq(Integer.class), any(), any());
    }

    @Test
    @DisplayName("SchemaMigrator：MySQL 下仍执行原补列逻辑")
    void schemaMigratorRunsOnMySql() {
        DbDialect.overrideForTest(false);
        try {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
            when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("YES");
            new SchemaMigrator(jdbc).migrate();
            verify(jdbc, atLeastOnce()).queryForObject(contains("information_schema.COLUMNS"),
                    eq(Integer.class), any(), any());
        } finally {
            DbDialect.overrideForTest(true);
        }
    }

    @Test
    @DisplayName("ConversationService：SQLite 下跳过补列 DDL，但业务回填照常执行")
    void conversationServiceSkipsDdlButRunsBackfillOnSqlite() {
        DbDialect.overrideForTest(true);
        ConversationMapper mapper = mock(ConversationMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(mapper.backfillFromMessages()).thenReturn(0);
        when(workspaceService.migrateBindings()).thenReturn(0);

        new ConversationService(mapper, workspaceService).migrateAndBackfill();

        verify(mapper).backfillFromMessages();
        verify(mapper, never()).countPlanColumn();
        verify(mapper, never()).addPlanColumn();
        verify(mapper, never()).countWorkspaceIdColumn();
        verify(mapper, never()).addWorkspaceIdColumn();
        verify(mapper, never()).countPermissionModeColumn();
        verify(mapper, never()).addPermissionModeColumn();
        verify(workspaceService).migrateBindings();
    }

    @Test
    @DisplayName("ConversationService：MySQL 下补列与回填都执行")
    void conversationServiceRunsAllOnMySql() {
        DbDialect.overrideForTest(false);
        try {
            ConversationMapper mapper = mock(ConversationMapper.class);
            WorkspaceService workspaceService = mock(WorkspaceService.class);
            when(mapper.countPlanColumn()).thenReturn(1);
            when(mapper.countWorkspaceIdColumn()).thenReturn(1);
            when(mapper.countWorkspaceIdIndex()).thenReturn(1);
            when(mapper.countPermissionModeColumn()).thenReturn(1);
            when(mapper.backfillFromMessages()).thenReturn(0);
            when(workspaceService.migrateBindings()).thenReturn(0);

            new ConversationService(mapper, workspaceService).migrateAndBackfill();

            verify(mapper).countPlanColumn();
            verify(mapper).backfillFromMessages();
            verify(mapper).countWorkspaceIdColumn();
            verify(mapper).countPermissionModeColumn();
        } finally {
            DbDialect.overrideForTest(true);
        }
    }

    // ===================== 9：updated_at 三条路径 =====================

    @Test
    @DisplayName("updated_at：touch / updatePermissionMode / updatePlanJson 都会刷新")
    void conversationUpdatedAtRefreshed() {
        exec("INSERT INTO conversation(user_id, conversation_id, title, message_count, created_at, updated_at) " +
                "VALUES(1, 'c-updated', 't', 1, '2000-01-01 00:00:00', '2000-01-01 00:00:00')");
        try (SqlSession s = sessionFactory.openSession(true)) {
            ConversationMapper m = s.getMapper(ConversationMapper.class);
            m.updatePermissionMode(1L, "c-updated", "FULL_ACCESS");
            assertNotEquals("2000-01-01 00:00:00",
                    rowStr("SELECT updated_at FROM conversation WHERE conversation_id='c-updated'"),
                    "updatePermissionMode 应刷新 updated_at");

            exec("UPDATE conversation SET updated_at='2000-01-01 00:00:00' WHERE conversation_id='c-updated'");
            m.updatePlanJson(1L, "c-updated", "{\"steps\":[]}");
            assertNotEquals("2000-01-01 00:00:00",
                    rowStr("SELECT updated_at FROM conversation WHERE conversation_id='c-updated'"),
                    "updatePlanJson 应刷新 updated_at");

            exec("UPDATE conversation SET updated_at='2000-01-01 00:00:00' WHERE conversation_id='c-updated'");
            m.touch(1L, "c-updated", "t", null, "ASK");
            assertNotEquals("2000-01-01 00:00:00",
                    rowStr("SELECT updated_at FROM conversation WHERE conversation_id='c-updated'"),
                    "touch 应刷新 updated_at");
        }
    }

    @Test
    @DisplayName("updated_at：mcp_server_config.update 会刷新")
    void mcpServerConfigUpdatedAtRefreshed() {
        exec("INSERT INTO mcp_server_config(name, created_by, updated_at) " +
                "VALUES('srv-updated', 1, '2000-01-01 00:00:00')");
        try (SqlSession s = sessionFactory.openSession(true)) {
            McpServerConfigMapper m = s.getMapper(McpServerConfigMapper.class);
            com.example.jingjingai2.entity.McpServerConfig cfg =
                    new com.example.jingjingai2.entity.McpServerConfig();
            cfg.setName("srv-updated");
            cfg.setCreatedBy(1L);
            cfg.setTrust("trusted");
            // update SQL 会整行覆盖这些列，必须给非空值（与真实调用方一致）
            cfg.setConnectTimeoutSeconds(0);
            cfg.setMaxResultChars(0);
            cfg.setTransport("stdio");
            cfg.setAuthType("none");
            m.update(cfg);
        }
        assertNotEquals("2000-01-01 00:00:00",
                rowStr("SELECT updated_at FROM mcp_server_config WHERE name='srv-updated'"),
                "McpServerConfigMapper.update 应刷新 updated_at");
    }

    // ===================== 10：服务名大小写 =====================

    @Test
    @DisplayName("服务名：SQLite 下查找大小写不敏感，唯一索引也大小写不敏感")
    void serverNameCaseInsensitive() {
        exec("INSERT INTO mcp_server_config(name, created_by) VALUES('GitHub', 1)");
        try (SqlSession s = sessionFactory.openSession(true)) {
            McpServerConfigMapper m = s.getMapper(McpServerConfigMapper.class);
            assertNotNull(m.findByOwnerAndName(1L, "github"), "小写应能查到 GitHub");
            assertNotNull(m.findByName("GITHUB"), "大写应能查到 GitHub");
        }
        assertThrows(Exception.class,
                () -> exec("INSERT INTO mcp_server_config(name, created_by) VALUES('github', 1)"),
                "同一 owner 下大小写不同的同名服务应被唯一索引拒绝");
    }

    // ===================== 11：MySQL 分支保留 =====================

    @Test
    @DisplayName("MySQL 分支：方言切回 MySQL 时仍返回原 SQL")
    void mysqlBranchPreserved() {
        DbDialect.overrideForTest(false);
        try {
            assertTrue(ConversationSqlProvider.touch(null).contains("ON DUPLICATE KEY UPDATE"));
            assertTrue(ConversationSqlProvider.backfill(null).contains("GREATEST("));
            assertTrue(ConversationSqlProvider.backfill(null).contains("LEFT(x.content, 24)"));
            assertTrue(ConversationSqlProvider.backfill(null).contains("VALUES(updated_at)"));
            assertTrue(ConversationSqlProvider.updatePermissionMode(null).contains("permission_mode = #{mode}"));
            assertFalse(ConversationSqlProvider.updatePermissionMode(null).contains("updated_at"));
            assertTrue(ConversationSqlProvider.countPlanColumn(null).contains("information_schema.COLUMNS"));
            assertTrue(ConversationSqlProvider.countWorkspaceIdIndex(null).contains("information_schema.STATISTICS"));
            assertTrue(WorkspaceSqlProvider.countWorkspaceTable(null).contains("information_schema.TABLES"));
            assertTrue(McpUserPolicySqlProvider.upsert(null).contains("ON DUPLICATE KEY UPDATE"));
            assertTrue(UserMemorySqlProvider.upsert(null).contains("ON DUPLICATE KEY UPDATE"));
            assertTrue(PromptSnapshotSqlProvider.insertIgnore(null).startsWith("INSERT IGNORE"));
            assertFalse(McpServerConfigSqlProvider.update(null).contains("updated_at=CURRENT_TIMESTAMP"));
        } finally {
            DbDialect.overrideForTest(true);
        }
    }

    @Test
    @DisplayName("消息检索：关键词命中旧消息，读取必须同时对上用户和会话")
    void messageSearchAndReadAreScoped() {
        for (int i = 0; i < 61; i++) {
            insertMessage(1L, "c-search", "user", "普通消息 " + i, "2020-01-01 00:00:" + String.format("%02d", i % 60));
        }
        insertMessage(1L, "c-search", "user", "很早的预算讨论", "2019-01-01 00:00:00");
        insertMessage(2L, "c-search", "user", "别人的预算讨论", "2019-01-02 00:00:00");
        insertMessage(1L, "c-other", "user", "另一个会话的预算讨论", "2019-01-03 00:00:00");
        insertMessage(1L, "c-search", "user", "百分号 100%_off 原样", "2019-02-01 00:00:00");

        try (SqlSession s = sessionFactory.openSession(true)) {
            ConversationMapper m = s.getMapper(ConversationMapper.class);

            List<MessageHit> hits = m.searchMessages(1L, "c-search", "%预算%", "预算", 30, 80, 20);
            assertEquals(1, hits.size(), "只命中本用户本会话");
            assertTrue(hits.get(0).getSnippet().contains("预算"));
            Long id = hits.get(0).getId();
            assertNotNull(id);

            MessageRecord body = m.selectMessage(1L, "c-search", id);
            assertNotNull(body);
            assertEquals("很早的预算讨论", body.getContent());
            assertNull(m.selectMessage(2L, "c-search", id), "别的用户不能凭 id 读取");
            assertNull(m.selectMessage(1L, "c-other", id), "别的会话不能凭 id 读取");

            assertTrue(m.searchMessages(1L, "c-search", "%不存在%", "不存在", 30, 80, 20).isEmpty());
            assertEquals(1, m.searchMessages(1L, "c-search", "%100\\%\\_off%", "100%_off", 30, 80, 20).size(),
                    "百分号和下划线按字面匹配");

            // 关键词在正文很后面时，摘要应落在命中处附近，而不是正文开头
            String far = "a".repeat(400) + "深埋事实" + "b".repeat(40);
            insertMessage(1L, "c-search", "user", far, "2019-03-01 00:00:00");
            List<MessageHit> farHits = m.searchMessages(1L, "c-search", "%深埋事实%", "深埋事实", 30, 80, 20);
            assertEquals(1, farHits.size());
            assertTrue(farHits.get(0).getSnippet().contains("深埋事实"), farHits.get(0).getSnippet());
            int headRun = 0;
            while (headRun < farHits.get(0).getSnippet().length()
                    && farHits.get(0).getSnippet().charAt(headRun) == 'a') headRun++;
            assertTrue(headRun < 200, "摘要开头的 a 只有 " + headRun + " 个，说明没有从正文开头截");
            assertTrue(farHits.get(0).getSnippet().length() <= 80);

            List<MessageRecord> history = m.getHistory(1L, "c-search", 5);
            assertEquals(5, history.size());
            assertNotNull(history.get(0).getId(), "历史行必须带数据库 id");
            assertNotEquals(history.get(0).getId(), history.get(1).getId(),
                    "正文即使相同，身份也必须不同");
            MessageRecord same = m.selectMessage(1L, "c-search", history.get(0).getId());
            assertNotNull(same);
            assertEquals(history.get(0).getId(), same.getId());
        }
    }

    @Test
    @DisplayName("搜索 SQL 不用多参数 MAX，两种库都走 CASE")
    void searchSqlDoesNotUseScalarMax() throws Exception {
        String sql = ConversationMapper.class
                .getMethod("searchMessages", Long.class, String.class, String.class, String.class,
                        int.class, int.class, int.class)
                .getAnnotation(Select.class)
                .value()[0];
        String compact = sql.replaceAll("\\s+", " ").toUpperCase();
        assertFalse(compact.contains("MAX("), sql);
        assertTrue(compact.contains("CASE WHEN INSTR(CONTENT, #{NEEDLE}) - #{BEFORE} > 1"), sql);
        assertTrue(compact.contains("ELSE 1 END"), sql);

        String history = ConversationMapper.class
                .getMethod("getHistory", Long.class, String.class, int.class)
                .getAnnotation(Select.class)
                .value()[0]
                .replaceAll("\\s+", " ")
                .toUpperCase();
        assertTrue(history.startsWith("SELECT ID, ROLE, CONTENT,"), history);
    }

    @Test
    @DisplayName("SQLite 分支：upsert 使用 ON CONFLICT，冲突列与唯一索引一致")
    void sqliteBranchUsesOnConflict() {
        DbDialect.overrideForTest(true);
        assertTrue(ConversationSqlProvider.touch(null)
                .contains("ON CONFLICT(user_id, conversation_id) DO UPDATE"));
        assertTrue(McpUserPolicySqlProvider.upsert(null)
                .contains("ON CONFLICT(user_id, server_name, tool_name) DO UPDATE"));
        assertTrue(UserMemorySqlProvider.upsert(null)
                .contains("ON CONFLICT(user_id, category, mem_key) DO UPDATE"));
        assertTrue(PromptSnapshotSqlProvider.insertIgnore(null).startsWith("INSERT OR IGNORE"));
        assertTrue(ConversationSqlProvider.backfill(null).contains("ON CONFLICT(user_id, conversation_id)"));
        assertTrue(ConversationSqlProvider.backfill(null).contains("WHERE 1"));
        assertTrue(ConversationSqlProvider.countPlanColumn(null).contains("pragma_table_info"));
        assertTrue(ConversationSqlProvider.countWorkspaceIdIndex(null).contains("pragma_index_list"));
        assertTrue(WorkspaceSqlProvider.countWorkspaceTable(null).contains("sqlite_master"));
    }

    // ===================== helpers =====================

    private static void applySchema() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema-sqlite.sql"));
        }
    }

    private static void insertMessage(Long userId, String conversationId, String role,
                                      String content, String createdAt) {
        exec("INSERT INTO conversation_message(user_id, conversation_id, role, content, created_at) " +
                "VALUES(?,?,?,?,?)", userId, conversationId, role, content, createdAt);
    }

    private static void insertMessageWithNullTime(Long userId, String conversationId,
                                                  String role, String content) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO conversation_message(user_id, conversation_id, role, content, created_at) " +
                             "VALUES(?,?,?,?,?)")) {
            ps.setLong(1, userId);
            ps.setString(2, conversationId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setNull(5, Types.VARCHAR);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exec(String sql, Object... args) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    private static String rowStr(String sql) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    private static int rowInt(String sql) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    private static SqlSessionFactory buildSessionFactory(String url) {
        pooledDataSource = new PooledDataSource("org.sqlite.JDBC", url, "", "");
        Environment env = new Environment("sqlite-dialect-test", new JdbcTransactionFactory(), pooledDataSource);
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(ConversationMapper.class);
        cfg.addMapper(McpUserPolicyMapper.class);
        cfg.addMapper(UserMemoryMapper.class);
        cfg.addMapper(PromptSnapshotMapper.class);
        cfg.addMapper(WorkspaceMapper.class);
        cfg.addMapper(McpServerConfigMapper.class);
        return new SqlSessionFactoryBuilder().build(cfg);
    }
}
