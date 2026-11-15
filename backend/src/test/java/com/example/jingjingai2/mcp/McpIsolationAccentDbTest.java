package com.example.jingjingai2.mcp;

import com.example.jingjingai2.controller.McpServerAdminController;
import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.entity.McpUserPolicy;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.mcp.McpCredentialCrypto;
import com.example.jingjingai2.service.McpCredentialService;
import com.example.jingjingai2.service.McpServerProvisionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只连隔离库 jingjing_ai_mcp_isolation_test。没有临时客户端配置就跳过，
 * 普通 {@code mvn test} 不会去碰数据库。口令只从仓库外的配置读取。
 */
class McpIsolationAccentDbTest {

    private static final String DB = "jingjing_ai_mcp_isolation_test";
    private static final String MARK = "iso-accent";
    private static final String TOOL = "iso_accent";
    private static final String STORED = "bébé-iso";
    private static final String REQUEST = "bebe-iso";

    @Test
    void deleteAgreesWithDatabaseAccentRules() throws Exception {
        Path cnf = Path.of(System.getProperty("java.io.tmpdir"), "mcp-isolation-root.cnf");
        Assumptions.assumeTrue(Files.isRegularFile(cnf), "没有隔离库客户端配置，跳过");
        Assumptions.assumeTrue(mysqlDriverPresent(), "MySQL 驱动已退出 Windows 本地版，重音规则不再对 MySQL 验收");
        Properties login = readClient(cnf);
        String url = "jdbc:mysql://127.0.0.1:3306/" + DB
                + "?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection(
                url, login.getProperty("user"), login.getProperty("password"))) {
            assertEquals(DB, connection.getCatalog());
            JdbcConfigs configs = new JdbcConfigs(connection);
            JdbcPolicies policies = new JdbcPolicies(connection);
            UnusedCredentials credentials = new UnusedCredentials();
            try {
                assertTrue(McpClientManager.namesMatch(STORED, REQUEST));
                insertShared(connection);
                McpServerAdminController controller = controller(configs, policies, false, credentials);

                ResponseEntity<Map<String, Object>> denied = controller.delete(REQUEST, asUser(7L));

                assertEquals(400, denied.getStatusCode().value());
                assertEquals("系统共享服务不能删除", denied.getBody().get("message"));
                assertEquals(0, policies.deleteCalls);
                assertEquals(0, configs.deleteCalls);
                assertEquals(0, configs.findAllCalls);
                assertEquals(0, credentials.deleteCalls, "拒绝删除时不应清理凭证");
                assertEquals(2, countPolicies(connection));

                deleteConfigs(connection);
                controller = controller(configs, policies, true, credentials);
                ResponseEntity<Map<String, Object>> yaml = controller.delete(REQUEST, asUser(7L));
                assertEquals(400, yaml.getStatusCode().value());
                assertEquals("部署配置里的服务不能删除", yaml.getBody().get("message"));
                assertEquals(0, policies.deleteCalls);
                assertEquals(0, credentials.deleteCalls, "部署服务拒绝删除时不应清理凭证");
                assertEquals(2, countPolicies(connection));

                insertPersonal(connection);
                configs.deleteCalls = 0;
                policies.deleteCalls = 0;
                controller = controller(configs, policies, false, credentials);
                ResponseEntity<Map<String, Object>> removed = controller.delete(REQUEST, asUser(7L));

                assertEquals(200, removed.getStatusCode().value());
                assertFalse(String.valueOf(removed.getBody().get("action")).contains("残留"));
                assertEquals(1, policies.deleteCalls);
                assertEquals(1, configs.deleteCalls);
                assertEquals(0, configs.findAllCalls);
                // 个人服务删除会按配置主键清一次凭证，桩返回 0（本测试没有凭证行）
                assertEquals(1, credentials.deleteCalls, "个人服务删除应清理一次凭证");
                assertEquals(7L, credentials.lastUserId);
                assertTrue(credentials.lastServerConfigId > 0, "按配置主键清理，不是按服务名");
                assertNull(configs.findByOwnerAndName(7L, REQUEST));
                assertNotNull(policies.find(9L, STORED, TOOL));
                assertNull(policies.find(7L, STORED, TOOL));
            } finally {
                deleteConfigs(connection);
                deletePolicies(connection);
            }
        }
    }

    private static McpServerAdminController controller(JdbcConfigs configs, JdbcPolicies policies,
                                                       boolean yaml, UnusedCredentials credentials) {
        McpProperties props = new McpProperties();
        if (yaml) props.getServers().put(STORED, new McpServerSpec());
        McpClientManager manager = new McpClientManager(props);
        return new McpServerAdminController(
                manager, configs, policies, new ApprovalService(1),
                new McpServerProvisionService(configs, manager),
                new McpCredentialService(configs, credentials, new McpCredentialCrypto(null)));
    }

    private static void insertShared(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mcp_server_config (name, command, created_by) VALUES (?, ?, 0)")) {
            ps.setString(1, STORED);
            ps.setString(2, MARK);
            ps.executeUpdate();
        }
        insertPolicy(connection, 7L, "DENY");
        insertPolicy(connection, 9L, "ALLOW");
    }

    private static void insertPersonal(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mcp_server_config (name, command, created_by) VALUES (?, ?, 7)")) {
            ps.setString(1, STORED);
            ps.setString(2, MARK);
            ps.executeUpdate();
        }
    }

    private static void insertPolicy(Connection connection, long userId, String effect) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mcp_user_policy (user_id, server_name, tool_name, effect) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, STORED);
            ps.setString(3, TOOL);
            ps.setString(4, effect);
            ps.executeUpdate();
        }
    }

    private static int countPolicies(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM mcp_user_policy WHERE tool_name = ?")) {
            ps.setString(1, TOOL);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static boolean mysqlDriverPresent() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void deleteConfigs(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM mcp_server_config WHERE command = ?")) {
            ps.setString(1, MARK);
            ps.executeUpdate();
        }
    }

    private static void deletePolicies(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM mcp_user_policy WHERE tool_name = ?")) {
            ps.setString(1, TOOL);
            ps.executeUpdate();
        }
    }

    private static Properties readClient(Path cnf) throws Exception {
        Properties parsed = new Properties();
        for (String line : Files.readAllLines(cnf, StandardCharsets.US_ASCII)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#") && !line.startsWith("[")) {
                parsed.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return parsed;
    }

    private static HttpServletRequest asUser(Long id) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) {
                        return args != null && "userId".equals(args[0]) ? id : null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    /**
     * 本测试不连凭证库。{@code delete} 按当前接口契约返回 0（没有可删的凭证行）并记下调用参数，
     * 因为删除个人服务本来就会走这一步；其余方法一律失败，误调时测试会立刻暴露。
     */
    private static final class UnusedCredentials implements com.example.jingjingai2.mapper.McpServerCredentialMapper {
        int deleteCalls;
        long lastUserId;
        long lastServerConfigId;

        @Override public int insert(com.example.jingjingai2.entity.McpServerCredential row) {
            throw new UnsupportedOperationException();
        }
        @Override public int updateCiphertext(long userId, long serverConfigId, String ciphertext) {
            throw new UnsupportedOperationException();
        }
        @Override public com.example.jingjingai2.entity.McpServerCredential find(long userId, long serverConfigId) {
            throw new UnsupportedOperationException();
        }
        @Override public int count(long userId, long serverConfigId) { throw new UnsupportedOperationException(); }
        @Override public int delete(long userId, long serverConfigId) {
            deleteCalls++;
            lastUserId = userId;
            lastServerConfigId = serverConfigId;
            return 0;
        }
    }

    /** 只实现删除会碰到的 SQL，语句与 Mapper 注解一致。findAll 被调用就失败。 */
    private static final class JdbcConfigs implements McpServerConfigMapper {
        private final Connection connection;
        int deleteCalls;
        int findAllCalls;

        JdbcConfigs(Connection connection) { this.connection = connection; }

        @Override public int insert(McpServerConfig config) { throw new UnsupportedOperationException(); }
        @Override public int update(McpServerConfig config) { throw new UnsupportedOperationException(); }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) {
            throw new UnsupportedOperationException();
        }
        @Override public McpServerConfig findByName(String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findById(long id) {
            String sql = "SELECT id, name, created_by FROM mcp_server_config WHERE id=?";
            try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public List<McpServerConfig> findAll() { findAllCalls++; throw new IllegalStateException("findAll"); }
        @Override public List<McpServerConfig> findAllByOwner(long ownerId) {
            throw new UnsupportedOperationException();
        }

        @Override public int delete(long ownerId, String name) {
            deleteCalls++;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM mcp_server_config WHERE name=? AND created_by=?")) {
                ps.setString(1, name);
                ps.setLong(2, ownerId);
                return ps.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) {
            String sql = "SELECT id, name, created_by FROM mcp_server_config "
                    + "WHERE name=? AND created_by=? LIMIT 1";
            return one(sql, name, ownerId);
        }

        @Override public McpServerConfig findSharedByName(String name) {
            String sql = "SELECT id, name, created_by FROM mcp_server_config "
                    + "WHERE name = ? AND (created_by = 0 OR created_by IS NULL) LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private McpServerConfig one(String sql, String name, long ownerId) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setLong(2, ownerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static McpServerConfig row(ResultSet rs) throws Exception {
            McpServerConfig config = new McpServerConfig();
            config.setId(rs.getLong("id"));
            config.setName(rs.getString("name"));
            long owner = rs.getLong("created_by");
            config.setCreatedBy(rs.wasNull() ? null : owner);
            return config;
        }
    }

    private static final class JdbcPolicies implements McpUserPolicyMapper {
        private final Connection connection;
        int deleteCalls;

        JdbcPolicies(Connection connection) { this.connection = connection; }

        @Override public int upsert(long userId, String server, String tool, String effect, String definitionHash) {
            throw new UnsupportedOperationException();
        }
        @Override public int deleteOne(long userId, String server, String tool) {
            throw new UnsupportedOperationException();
        }
        @Override public int deleteTools(long userId, String server) { throw new UnsupportedOperationException(); }

        @Override public McpUserPolicy find(long userId, String server, String tool) {
            String sql = "SELECT user_id, server_name, tool_name, effect FROM mcp_user_policy "
                    + "WHERE user_id = ? AND server_name = ? AND tool_name = ? LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setString(2, server);
                ps.setString(3, tool);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    McpUserPolicy policy = new McpUserPolicy();
                    policy.setUserId(rs.getLong("user_id"));
                    policy.setServerName(rs.getString("server_name"));
                    policy.setToolName(rs.getString("tool_name"));
                    policy.setEffect(rs.getString("effect"));
                    return policy;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public int deleteByUserAndServer(long userId, String server) {
            deleteCalls++;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM mcp_user_policy WHERE user_id = ? AND server_name = ?")) {
                ps.setLong(1, userId);
                ps.setString(2, server);
                return ps.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
