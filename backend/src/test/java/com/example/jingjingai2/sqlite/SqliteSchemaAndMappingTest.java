package com.example.jingjingai2.sqlite;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import com.example.jingjingai2.config.SqliteDataDirectoryConfig;
import com.example.jingjingai2.entity.ModelConfig;
import com.example.jingjingai2.entity.User;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mapper.ModelConfigMapper;
import com.example.jingjingai2.mapper.UserMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage SQLite-1 定向测试：验证 SQLite 驱动、schema-sqlite.sql、MyBatis 基础读写、
 * 主键回填与日期映射。
 *
 * <p>数据库是 JUnit 临时目录里的独立文件，不碰 ./data/jingjing.db，也不碰 MySQL。
 * 只使用现有 Mapper 中<strong>标准 SQL</strong> 的方法；MySQL 专有语句
 * （touch / upsert / INSERT IGNORE / information_schema）留给 SQLite-2。
 */
class SqliteSchemaAndMappingTest {

    private static final String CONVERSATION_ID = "conv-sqlite-test";

    @TempDir
    static Path tempDir;

    private static String jdbcUrl;
    private static SqlSessionFactory sessionFactory;
    private static PooledDataSource pooledDataSource;

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("jingjing-test.db").toAbsolutePath();
        applySchema();
        sessionFactory = buildSessionFactory(jdbcUrl);
    }

    @AfterAll
    static void tearDown() {
        // 连接池持有文件句柄，Windows 下不关掉会导致 JUnit 临时目录删不掉
        if (pooledDataSource != null) pooledDataSource.forceCloseAll();
    }

    // ===================== 驱动与 Schema =====================

    @Test
    @DisplayName("SQLite JDBC 驱动可以连接")
    void driverConnects() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            assertTrue(c.isValid(5));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sqlite_version()")) {
                assertTrue(rs.next());
                assertFalse(rs.getString(1).isBlank());
            }
        }
    }

    @Test
    @DisplayName("schema-sqlite.sql 创建全部 12 张表")
    void schemaCreatesTwelveTables() throws Exception {
        assertEquals(EXPECTED_TABLES, tableNames());
    }

    @Test
    @DisplayName("schema 重复执行不报错（幂等）")
    void schemaIsIdempotent() throws Exception {
        assertDoesNotThrow(SqliteSchemaAndMappingTest::applySchema);
        assertDoesNotThrow(SqliteSchemaAndMappingTest::applySchema);
        assertEquals(EXPECTED_TABLES, tableNames());
    }

    @Test
    @DisplayName("唯一索引与普通索引全部存在")
    void indexesExist() throws Exception {
        List<String> indexes = indexNames();
        for (String expected : List.of(
                "uk_users_username", "uk_user_conv", "uk_user_bound_path", "uk_user_cat_key",
                "uk_user_server_tool", "uk_owner_name", "uk_user_server",
                "idx_conv", "idx_user_time", "idx_tool", "idx_tool_call_conv_turn",
                "idx_user", "idx_user_updated", "idx_conv_workspace", "idx_ws_user",
                "idx_user_cat", "idx_agent_turn_conv")) {
            assertTrue(indexes.contains(expected), "缺少索引: " + expected + "，实际=" + indexes);
        }
    }

    @Test
    @DisplayName("prompt_snapshot.sha256 仍是单列主键")
    void promptSnapshotPrimaryKey() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR IGNORE INTO prompt_snapshot(sha256, kind, content) VALUES(?,?,?)")) {
            ps.setString(1, "a".repeat(64));
            ps.setString(2, "SYSTEM");
            ps.setString(3, "x");
            assertEquals(1, ps.executeUpdate());
            assertEquals(0, ps.executeUpdate(), "重复哈希应被 OR IGNORE 忽略");
        }
    }

    // ===================== MyBatis 基础读写 =====================

    @Test
    @DisplayName("users：MyBatis 插入并回填自增主键")
    void userInsertBackfillsGeneratedKey() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User u = new User();
            u.setUsername("sqlite-user");
            u.setPassword("hashed");
            mapper.insert(u);
            assertNotNull(u.getId(), "useGeneratedKeys 未回填主键");
            assertTrue(u.getId() > 0);
            assertEquals(u.getId(), mapper.findByUsername("sqlite-user").getId());
        }
    }

    @Test
    @DisplayName("conversation_message：MyBatis 插入并查询，java.util.Date 映射")
    void conversationMessageInsertAndQuery() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ConversationMapper mapper = session.getMapper(ConversationMapper.class);
            mapper.insert(1L, CONVERSATION_ID, "user", "你好，SQLite");
            mapper.insert(1L, CONVERSATION_ID, "assistant", "收到");

            List<MessageRecord> history = mapper.getHistory(1L, CONVERSATION_ID, 10);
            assertEquals(2, history.size());
            // getHistory 是倒序，调用方再 reverse
            MessageRecord first = history.get(history.size() - 1);
            assertEquals("user", first.getRole());
            assertEquals("你好，SQLite", first.getContent());
            assertNotNull(first.getTimestamp(), "created_at 未映射到 java.util.Date");
            assertTrue(first.getTimestamp() instanceof Date);
        }
    }

    @Test
    @DisplayName("conversation：JDBC 插入 + MyBatis 查询，Date 映射")
    void conversationInsertAndQuery() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO conversation(user_id, conversation_id, title, message_count, workspace_id, permission_mode) " +
                             "VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, 1L);
            ps.setString(2, CONVERSATION_ID);
            ps.setString(3, "测试会话");
            ps.setInt(4, 2);
            ps.setNull(5, java.sql.Types.INTEGER);
            ps.setString(6, "ASK");
            assertEquals(1, ps.executeUpdate());
        }
        try (SqlSession session = sessionFactory.openSession(true)) {
            ConversationMapper mapper = session.getMapper(ConversationMapper.class);
            ConversationSummary one = mapper.selectOne(1L, CONVERSATION_ID);
            assertNotNull(one);
            assertEquals("测试会话", one.getTitle());
            assertEquals(2, one.getMessageCount());
            assertEquals("ASK", one.getPermissionMode());
            assertNotNull(one.getCreatedAt(), "created_at 未映射");
            assertNotNull(one.getUpdatedAt(), "updated_at 未映射");
        }
    }

    @Test
    @DisplayName("agent_turn：MyBatis 插入并查询")
    void agentTurnInsertAndQuery() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentTurnMapper mapper = session.getMapper(AgentTurnMapper.class);
            mapper.insert(new AgentTurnRecord(
                    CONVERSATION_ID, 1L, 0, "MODEL_TURN", "grok-4.7",
                    "a".repeat(64), null, "b".repeat(64), "none",
                    10, 10, 0, 100, 20,
                    5, null, 3, 2, 0,
                    AgentTurnRecord.END_APPROVAL_PENDING));
            Integer pending = mapper.latestPendingTurnIndex(CONVERSATION_ID);
            assertEquals(0, pending);
        }
    }

    @Test
    @DisplayName("中文与长文本可以正常存取")
    void chineseAndLongText() {
        String longText = "中文测试内容。".repeat(800); // 约 5600 字符
        try (SqlSession session = sessionFactory.openSession(true)) {
            ConversationMapper mapper = session.getMapper(ConversationMapper.class);
            mapper.insert(1L, "conv-long", "user", longText);
            List<MessageRecord> history = mapper.getHistory(1L, "conv-long", 10);
            assertEquals(1, history.size());
            assertEquals(longText, history.get(0).getContent());
        }
    }

    @Test
    @DisplayName("LocalDateTime 映射：记录实际行为（SQLite 驱动可能不支持 getObject(..., LocalDateTime.class)）")
    void localDateTimeMappingBehavior() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ModelConfigMapper mapper = session.getMapper(ModelConfigMapper.class);
            ModelConfig cfg = new ModelConfig();
            cfg.setUserId(1L);
            cfg.setProvider("sqlite-test");
            cfg.setBaseUrl("http://localhost");
            cfg.setApiKey("encrypted");
            cfg.setModelName("m");
            cfg.setDefaultModel(true);
            mapper.insert(cfg);
            assertNotNull(cfg.getId(), "user_model_config 主键未回填");

            ModelConfig loaded = mapper.findByUserId(1L).get(0);
            LocalDateTime createdAt = loaded.getCreatedAt();
            assertNotNull(createdAt, "created_at 未映射为 LocalDateTime（需在 SQLite-2 处理）");
        }
    }

    // ===================== 目录创建逻辑 =====================

    @Test
    @DisplayName("SqliteDataDirectoryConfig：解析 URL 并创建父目录")
    void dataDirectoryCreated() throws Exception {
        Path nested = tempDir.resolve("nested/deeper");
        assertFalse(Files.exists(nested));
        Path created = SqliteDataDirectoryConfig.ensureParentDirectory(
                "jdbc:sqlite:" + nested.resolve("app.db").toAbsolutePath());
        assertEquals(nested.toAbsolutePath().normalize(), created);
        assertTrue(Files.isDirectory(created));
    }

    @Test
    @DisplayName("SqliteDataDirectoryConfig：内存库与非法 URL 不创建目录")
    void dataDirectoryIgnoresMemoryAndNonSqlite() {
        assertNull(SqliteDataDirectoryConfig.parentDirectory("jdbc:sqlite::memory:"));
        assertNull(SqliteDataDirectoryConfig.parentDirectory("jdbc:mysql://localhost:3306/db"));
        assertNull(SqliteDataDirectoryConfig.parentDirectory(null));
    }

    // ===================== helpers =====================

    private static final List<String> EXPECTED_TABLES = List.of(
            "agent_turn", "conversation", "conversation_message", "mcp_server_config",
            "mcp_server_credential", "mcp_user_policy", "prompt_snapshot", "tool_call_log",
            "user_memory", "user_model_config", "users", "workspace");

    private static void applySchema() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema-sqlite.sql"));
        }
    }

    private static List<String> tableNames() throws Exception {
        return names("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
    }

    private static List<String> indexNames() throws Exception {
        return names("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name");
    }

    private static List<String> names(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static SqlSessionFactory buildSessionFactory(String url) {
        pooledDataSource = new PooledDataSource("org.sqlite.JDBC", url, "", "");
        Environment env = new Environment("sqlite-test", new JdbcTransactionFactory(), pooledDataSource);
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(UserMapper.class);
        cfg.addMapper(ConversationMapper.class);
        cfg.addMapper(AgentTurnMapper.class);
        cfg.addMapper(ModelConfigMapper.class);
        return new SqlSessionFactoryBuilder().build(cfg);
    }
}
