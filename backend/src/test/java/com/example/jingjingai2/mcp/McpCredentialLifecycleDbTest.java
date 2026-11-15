package com.example.jingjingai2.mcp;

import com.example.jingjingai2.controller.McpServerAdminController;
import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.entity.McpServerCredential;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpServerCredentialMapper;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.service.McpCredentialService;
import com.example.jingjingai2.service.McpServerProvisionService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实隔离库上的 {@code bearer → none} 事务验收。
 *
 * <p>只连 {@value #DB}，没有临时客户端配置就跳过。用真实的
 * {@link DriverManagerDataSource}、{@link DataSourceTransactionManager}、
 * {@code SqlSessionTemplate} 加 MyBatis Mapper，并通过
 * {@link TransactionInterceptor} 代理调用控制器，所以断言的是 InnoDB 里的最终状态，
 * 不是 Mock 调用次数或事务回调。
 *
 * <p>不启动 MCP 连接，不访问外网。EndpointGuard 的 DNS 在测试里被替换成本地常量，
 * 避免解析真实域名。
 */
class McpCredentialLifecycleDbTest {

    private static final String DB = "jingjing_ai_mcp_isolation_test";
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final long USER_A = 900001L;
    private static final long USER_B = 900002L;
    /** 只用于本测试的记录名前缀；清理也只按这个前缀加显式 id 删除。 */
    private static final String PREFIX = "s2c3d-tx";

    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager txManager;
    private McpServerConfigMapper configs;
    private McpServerCredentialMapper credentials;
    private McpUserPolicyMapper policies;
    private String suffix;
    private final List<Fixture> fixtures = new ArrayList<>();

    private record Fixture(long userId, long configId, String name) {}

    private static boolean mysqlDriverPresent() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path cnf = Path.of(System.getProperty("java.io.tmpdir"), "mcp-isolation-root.cnf");
        Assumptions.assumeTrue(Files.isRegularFile(cnf), "没有隔离库客户端配置，跳过");
        Assumptions.assumeTrue(mysqlDriverPresent(), "MySQL 驱动已退出 Windows 本地版，这条隔离库测试不再运行");
        Properties login = readClient(cnf);

        dataSource = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/" + DB
                        + "?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",
                login.getProperty("user"), login.getProperty("password"));
        try (Connection c = dataSource.getConnection()) {
            assertEquals(DB, c.getCatalog(), "只允许连接隔离库");
        }

        Configuration cfg = new Configuration(
                new Environment("isolation", new SpringManagedTransactionFactory(), dataSource));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(McpServerConfigMapper.class);
        cfg.addMapper(McpServerCredentialMapper.class);
        cfg.addMapper(McpUserPolicyMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        configs = template.getMapper(McpServerConfigMapper.class);
        credentials = template.getMapper(McpServerCredentialMapper.class);
        policies = template.getMapper(McpUserPolicyMapper.class);
        txManager = new DataSourceTransactionManager(dataSource);

        suffix = Long.toString(System.nanoTime(), 36);
        // 配置校验会解析主机名。测试用常量地址，不做真实 DNS。
        McpEndpointGuard.setResolverForTest(
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{1, 1, 1, 1})});
    }

    @AfterEach
    void tearDown() {
        McpEndpointGuard.setResolverForTest(null);
        if (configs == null || credentials == null) return;
        for (Fixture f : fixtures) {
            credentials.delete(f.userId, f.configId);
            configs.delete(f.userId, f.name);
        }
        fixtures.clear();
    }

    @Test
    @DisplayName("用例 A：凭证删除失败时，配置更新和凭证删除在真实事务里一起回滚")
    void credentialDeleteFailureRollsBackInRealDatabase() {
        String name = PREFIX + "-a-" + suffix;
        long configId = insertHttpConfig(name, USER_A, McpServerConfig.AUTH_BEARER);
        String cipher = insertCredential(USER_A, configId, "fake-token-a-v1");

        McpCredentialService service = new McpCredentialService(
                configs, failingOn(configId), new McpCredentialCrypto(KEY));
        McpServerAdminController proxy = proxiedController(service);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authType", McpServerConfig.AUTH_NONE);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> proxy.update(name, body, asUser(USER_A)));
        assertTrue(thrown.getMessage().contains("凭证删除失败"), thrown.getMessage());

        // 事务已经结束，下面的查询走独立连接，读的是数据库里的最终状态
        McpServerConfig after = configs.findByOwnerAndName(USER_A, name);
        assertNotNull(after);
        assertEquals(McpServerConfig.AUTH_BEARER, after.getAuthType(), "配置必须回滚为 bearer");
        McpServerCredential row = credentials.find(USER_A, configId);
        assertNotNull(row, "凭证记录必须还在");
        assertEquals(cipher, row.getCiphertext(), "密文必须未变");
    }

    @Test
    @DisplayName("用例 B：正常切换提交，其他用户的凭证不受影响")
    void successfulSwitchCommitsAndKeepsOtherUser() {
        String nameA = PREFIX + "-b-" + suffix;
        String nameB = PREFIX + "-b2-" + suffix;
        long idA = insertHttpConfig(nameA, USER_A, McpServerConfig.AUTH_BEARER);
        long idB = insertHttpConfig(nameB, USER_B, McpServerConfig.AUTH_BEARER);
        insertCredential(USER_A, idA, "fake-token-a-v1");
        String cipherB = insertCredential(USER_B, idB, "fake-token-b-v1");

        McpCredentialService service = new McpCredentialService(
                configs, credentials, new McpCredentialCrypto(KEY));
        McpServerAdminController proxy = proxiedController(service);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authType", McpServerConfig.AUTH_NONE);
        ResponseEntity<Map<String, Object>> res = proxy.update(nameA, body, asUser(USER_A));

        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        McpServerConfig after = configs.findByOwnerAndName(USER_A, nameA);
        assertNotNull(after);
        assertEquals(McpServerConfig.AUTH_NONE, after.getAuthType(), "配置必须提交为 none");
        assertNull(credentials.find(USER_A, idA), "本人凭证必须已删除");

        McpServerCredential other = credentials.find(USER_B, idB);
        assertNotNull(other, "其他用户的凭证必须保留");
        assertEquals(cipherB, other.getCiphertext(), "其他用户的密文必须未变");
        assertEquals(McpServerConfig.AUTH_BEARER,
                configs.findByOwnerAndName(USER_B, nameB).getAuthType(), "其他用户的配置不受影响");
    }

    // ---- 装配 ----

    /** 控制器挂在真实的 TransactionInterceptor 上，@Transactional 才会真的开事务。 */
    private McpServerAdminController proxiedController(McpCredentialService service) {
        McpClientManager manager = new McpClientManager(new McpProperties());
        McpServerProvisionService provision = new McpServerProvisionService(configs, manager);
        McpServerAdminController target = new McpServerAdminController(
                manager, configs, policies, new ApprovalService(1), provision, service);

        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.setTransactionManager(txManager);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return (McpServerAdminController) factory.getProxy();
    }

    /**
     * 对本测试配置 id 的 delete：先让真实 Mapper 删掉这一行，再抛异常。
     * 这样回滚必须同时撤销「配置更新」和「凭证删除」两笔真实写入，
     * 而不是在写库之前就失败。
     */
    private McpServerCredentialMapper failingOn(long failingConfigId) {
        return (McpServerCredentialMapper) Proxy.newProxyInstance(
                McpServerCredentialMapper.class.getClassLoader(),
                new Class<?>[]{McpServerCredentialMapper.class},
                (p, method, args) -> {
                    try {
                        Object result = method.invoke(credentials, args);
                        if ("delete".equals(method.getName()) && args != null && args.length == 2
                                && ((Number) args[1]).longValue() == failingConfigId) {
                            throw new IllegalStateException("测试注入：凭证删除失败");
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private long insertHttpConfig(String name, long owner, String authType) {
        McpServerConfig c = new McpServerConfig();
        c.setName(name);
        c.setCommand(null);
        c.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        c.setBaseUrl("https://example.com");
        c.setEndpointPath("/mcp");
        c.setAuthType(authType);
        c.setTrust("untrusted");
        c.setDisabledToolsJson("[]");
        c.setConnectTimeoutSeconds(0);
        c.setMaxResultChars(0);
        c.setEnabled(false);
        c.setCreatedBy(owner);
        assertEquals(1, configs.insert(c), "配置应插入一行");
        assertNotNull(c.getId());
        fixtures.add(new Fixture(owner, c.getId(), name));
        return c.getId();
    }

    private String insertCredential(long userId, long configId, String token) {
        String cipher = new McpCredentialCrypto(KEY).encrypt(token);
        McpServerCredential row = new McpServerCredential();
        row.setUserId(userId);
        row.setServerConfigId(configId);
        row.setCiphertext(cipher);
        assertEquals(1, credentials.insert(row), "凭证应插入一行");
        return cipher;
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
}
