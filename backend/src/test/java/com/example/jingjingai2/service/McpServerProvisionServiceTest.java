package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpServerConfigValidator;
import com.example.jingjingai2.mcp.McpServerSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McpServerProvisionService} 单元测试：覆盖阶段 1A 的核心安全行为——
 * 保存成功但不启动、归属可信、强制禁用、校验与重名规则、不碰 mcp_user_policy。
 *
 * <p>不连数据库、不拉 Spring 上下文、不起真实 MCP 子进程：
 * Mapper 用内存假实现，Manager 子类只记录 findServer/addServer 是否被调用。
 * 能证明到「业务层没有调用 addServer / connectSlot」，但不能证明 OS 层面绝对不拉进程
 * （那是 McpClientManager 内部行为，由 McpUserIsolationTest/ExplicitStopReconnectTest 覆盖）。
 */
class McpServerProvisionServiceTest {

    // ==================== A. 保存成功但不启动 ====================

    @Test
    @DisplayName("合法个人服务保存成功：enabled 被强制写成 false，createdBy 用可信 owner，不调 addServer")
    void saveDisabledPersistsDisabledAndDoesNotConnect() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();

        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);
        McpServerConfig cfg = config("echo", "npx");

        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, cfg);

        assertTrue(r.saved(), "应保存成功");
        assertEquals("echo", r.name());
        assertNotNull(mapper.inserted, "配置已落库");
        assertEquals(false, mapper.inserted.isEnabled(),
                "enabled 不能依赖请求体恰好传 false——必须由业务层强制写 false");
        assertEquals(7L, mapper.inserted.getCreatedBy(), "归属用调用方提供的可信 userId");
        assertEquals(0, manager.addServerCalls,
                "保存路径不得触发 McpClientManager.addServer（不建槽位、不连进程）");
    }

    @Test
    @DisplayName("请求体里 enabled=true / createdBy=999 都被业务层覆盖：不采信模型可控字段")
    void saveDisabledIgnoresBodyOwnedAndEnabled() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerConfig cfg = config("echo", "npx");
        cfg.setEnabled(true);          // 模型/请求体想启用
        cfg.setCreatedBy(999L);        // 模型/请求体想记到别人头上

        svc.saveDisabled(7L, cfg);

        assertEquals(false, mapper.inserted.isEnabled(), "enabled 由业务层强制 false");
        assertEquals(7L, mapper.inserted.getCreatedBy(),
                "createdBy 由可信 owner 覆盖，不采信请求体");
    }

    @Test
    @DisplayName("trust 为 null 或空白时补成 untrusted，显式的 trusted / untrusted 保持原值")
    void trustDefaultsToUntrustedUnlessExplicit() {
        assertEquals("untrusted", savedTrust(null), "null 应补成 untrusted");
        assertEquals("untrusted", savedTrust("   "), "空白应补成 untrusted");
        assertEquals("untrusted", savedTrust("untrusted"), "显式 untrusted 不变");
        assertEquals("trusted", savedTrust("trusted"), "显式 trusted 不变");
    }

    @Test
    @DisplayName("非法的非空 trust 仍被拒绝，不落库、不连")
    void invalidTrustRejectedWithoutInsert() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerConfig cfg = config("echo", "npx");
        cfg.setTrust("maybe");
        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, cfg);

        assertFalse(r.saved());
        assertEquals("trust 只能是 trusted 或 untrusted", r.error());
        assertNull(mapper.inserted, "非法 trust 不得落库");
        assertEquals(0, manager.addServerCalls);
    }

    /** 走一次保存并返回实际落库的 trust。 */
    private static String savedTrust(String trust) {
        RecordingMapper mapper = new RecordingMapper();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, new SpyManager());
        McpServerConfig cfg = config("echo", "npx");
        cfg.setTrust(trust);
        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, cfg);
        assertTrue(r.saved(), r.error());
        return mapper.inserted.getTrust();
    }

    // ==================== C. 校验与重名规则 ====================

    @Test
    @DisplayName("非法配置（命令带路径）被拒，不落库、不连")
    void invalidConfigRejectedWithoutInsert() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerConfig cfg = config("echo", "./evil.sh");   // 路径，校验器拒绝
        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, cfg);

        assertFalse(r.saved());
        assertNotNull(r.error());
        assertNull(mapper.inserted, "校验失败不得落库");
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("同一用户的个人服务重名被拒，不落库")
    void ownDuplicateNameRejected() {
        RecordingMapper mapper = new RecordingMapper();
        // 库里已有 user 7 的 echo
        McpServerConfig existing = config("echo", "npx");
        existing.setCreatedBy(7L);
        mapper.stored.add(existing);
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, config("echo", "npx"));

        assertFalse(r.saved());
        assertEquals("服务已存在", r.error());
        assertNull(mapper.inserted, "重名不得落库");
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("与 ownerId=0 运行时槽位（部署/YAML/共享）同名被拒")
    void deploymentSlotNameConflictRejected() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        manager.deploymentSlotName = "playwright";   // ownerId=0 槽位已占
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, config("playwright", "npx"));

        assertFalse(r.saved());
        assertEquals("与系统或部署服务重名，请换一个名字", r.error());
        assertNull(mapper.inserted);
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("与 DB 共享行（created_by=0）同名被拒，不落库")
    void sharedRowNameConflictRejected() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig shared = config("playwright", "npx");
        shared.setCreatedBy(0L);              // 历史/系统共享行
        mapper.stored.add(shared);
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, config("playwright", "npx"));

        assertFalse(r.saved());
        assertEquals("与系统或部署服务重名，请换一个名字", r.error());
        assertNull(mapper.inserted);
    }

    @Test
    @DisplayName("DB 唯一约束在 insert 时抛异常不会被当成成功：异常原样传播")
    void insertDuplicateKeyPropagatesNotReportedAsSuccess() {
        RecordingMapper mapper = new RecordingMapper() {
            @Override public int insert(McpServerConfig config) {
                throw new RuntimeException("Duplicate entry for key 'uk_owner_name'");
            }
        };
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        // findByOwnerAndName 返回 null（应用层预检通过），但 insert 触发 DB 约束。
        assertThrows(RuntimeException.class,
                () -> svc.saveDisabled(7L, config("echo", "npx")),
                "DB 约束冲突应抛出，不能被吞成 saved=true");
    }

    // ==================== D. 用户隔离与权限边界 ====================

    @Test
    @DisplayName("不同 owner 各自保存同名服务互不影响：用户 7 和用户 9 都能保存 echo")
    void differentOwnersCanSaveSameName() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerProvisionService.SaveResult r7 = svc.saveDisabled(7L, config("echo", "npx"));
        assertTrue(r7.saved());
        assertEquals(7L, mapper.inserted.getCreatedBy());

        // 第二次：不同 owner，同名，应允许（uk_owner_name 是 (created_by, name)）
        RecordingMapper mapper2 = new RecordingMapper();
        McpServerProvisionService svc2 = new McpServerProvisionService(mapper2, manager);
        McpServerProvisionService.SaveResult r9 = svc2.saveDisabled(9L, config("echo", "npx"));
        assertTrue(r9.saved());
        assertEquals(9L, mapper2.inserted.getCreatedBy());
    }

    @Test
    @DisplayName("Service 不持有 mcp_user_policy 依赖：结构上无法写入工具授权")
    void serviceCannotGrantToolPermissions() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        svc.saveDisabled(7L, config("echo", "npx"));

        // 没有注入 McpUserPolicyMapper，也没有 ApprovalService：添加路径碰不到授权表。
        // 新服务的工具默认 ASK（由 PermissionPolicy.mcp__* 与 McpPermissionExplainer 保证）。
        assertNotNull(mapper.inserted);
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("null 配置直接失败，不触碰 mapper/manager")
    void nullConfigRejected() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, null);

        assertFalse(r.saved());
        assertEquals("配置不能为空", r.error());
        assertNull(mapper.inserted);
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("streamable_http 无认证配置可 saveDisabled：强制禁用、不连、command 置空")
    void saveDisabledHttpWithoutAuth() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);

        McpServerConfig cfg = new McpServerConfig();
        cfg.setName("remote-echo");
        cfg.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        cfg.setBaseUrl("https://1.1.1.1");
        cfg.setEnabled(true);
        cfg.setCreatedBy(999L);

        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, cfg);
        assertTrue(r.saved(), r.error());
        assertFalse(mapper.inserted.isEnabled());
        assertEquals(7L, mapper.inserted.getCreatedBy());
        assertEquals(McpServerConfig.TRANSPORT_STREAMABLE_HTTP, mapper.inserted.getTransport());
        assertNull(mapper.inserted.getCommand());
        assertEquals("/mcp", mapper.inserted.getEndpointPath());
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("streamable_http + bearer 可以保存认证方式，配置行里没有令牌，也不连接")
    void saveDisabledHttpBearerRejected() {
        RecordingMapper mapper = new RecordingMapper();
        SpyManager manager = new SpyManager();
        McpServerProvisionService svc = new McpServerProvisionService(mapper, manager);
        McpServerConfig cfg = new McpServerConfig();
        cfg.setName("remote-echo");
        cfg.setTransport(McpServerConfig.TRANSPORT_STREAMABLE_HTTP);
        cfg.setBaseUrl("https://1.1.1.1");
        cfg.setAuthType(McpServerConfig.AUTH_BEARER);
        McpServerProvisionService.SaveResult r = svc.saveDisabled(7L, cfg);
        assertTrue(r.saved(), r.error());
        assertEquals("bearer", mapper.inserted.getAuthType());
        assertFalse(mapper.inserted.isEnabled());
        assertNull(mapper.inserted.getEnvJson());
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("normalizeForCreate 对已规范化的 HTTP/stdio 再执行一次，连接字段不变")
    void normalizeForCreateIsIdempotent() {
        McpServerConfig http = new McpServerConfig();
        http.setName("remote-echo");
        http.setTransport(" streamable_http ");
        http.setBaseUrl(" https://1.1.1.1 ");
        http.setEndpointPath(" /mcp ");
        http.setCommand("npx");
        http.setArgsJson("[\"-y\"]");
        http.setEnvJson("{\"K\":\"v\"}");
        http.setAuthType(" none ");
        McpServerConfigValidator.normalizeForCreate(http);
        assertEquals("streamable_http", http.getTransport());
        assertEquals("https://1.1.1.1", http.getBaseUrl());
        assertEquals("/mcp", http.getEndpointPath());
        assertEquals("none", http.getAuthType());
        assertNull(http.getCommand());
        assertNull(http.getArgsJson());
        assertNull(http.getEnvJson());
        McpServerConfigValidator.normalizeForCreate(http);
        assertEquals("streamable_http", http.getTransport());
        assertEquals("https://1.1.1.1", http.getBaseUrl());
        assertEquals("/mcp", http.getEndpointPath());
        assertEquals("none", http.getAuthType());
        assertNull(http.getCommand());
        assertNull(http.getArgsJson());
        assertNull(http.getEnvJson());

        McpServerConfig stdio = config("echo", "npx");
        stdio.setArgsJson("[\"-y\",\"pkg\"]");
        stdio.setTrust("untrusted");
        McpServerConfigValidator.normalizeForCreate(stdio);
        McpServerConfigValidator.normalizeForCreate(stdio);
        assertEquals("stdio", stdio.getTransport());
        assertEquals("npx", stdio.getCommand());
        assertEquals("[\"-y\",\"pkg\"]", stdio.getArgsJson());
        assertEquals("none", stdio.getAuthType());
        assertEquals("untrusted", stdio.getTrust());
    }

    // ==================== helpers ====================

    private static McpServerConfig config(String name, String command) {
        McpServerConfig c = new McpServerConfig();
        c.setName(name);
        c.setCommand(command);
        return c;
    }

    /** 内存 Mapper：记录 insert 的最后一行；支持按 owner+name 查、列出共享行。 */
    static class RecordingMapper implements McpServerConfigMapper {
        McpServerConfig inserted;
        final List<McpServerConfig> stored = new ArrayList<>();

        @Override public int insert(McpServerConfig config) { inserted = config; stored.add(config); return 1; }
        @Override public int update(McpServerConfig config) { return 1; }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) { return 1; }
        @Override public int delete(long ownerId, String name) { return 1; }
        @Override public McpServerConfig findByName(String name) { return null; }
        @Override public McpServerConfig findById(long id) {
            for (McpServerConfig c : stored) {
                if (c != null && c.getId() != null && c.getId() == id) return c;
            }
            return null;
        }
        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) {
            for (McpServerConfig c : stored) {
                if (c != null && c.getCreatedBy() != null && c.getCreatedBy() == ownerId
                        && name.equals(c.getName())) return c;
            }
            return null;
        }
        @Override public McpServerConfig findSharedByName(String name) {
            for (McpServerConfig c : stored) {
                if (c != null && name.equals(c.getName())
                        && (c.getCreatedBy() == null || c.getCreatedBy() == 0L)) return c;
            }
            return null;
        }
        @Override public List<McpServerConfig> findAll() { return stored; }
        @Override public List<McpServerConfig> findAllByOwner(long ownerId) { return stored; }
    }

    /** Manager 子类：只记录 findServer(0L,…) 与 addServer 是否被调用，不拉进程。 */
    static class SpyManager extends McpClientManager {
        String deploymentSlotName;   // 非 null 表示 ownerId=0 槽位占了这个名字
        int addServerCalls;
        int findServerCalls;

        SpyManager() { super(new McpProperties()); }

        @Override
        public String findServer(long userId, String name) {
            findServerCalls++;
            if (userId == 0L && deploymentSlotName != null && deploymentSlotName.equals(name)) {
                return name;
            }
            return null;
        }

        @Override
        public McpServerChange addServer(long ownerId, String name, McpServerSpec spec, boolean fromDatabase) {
            addServerCalls++;
            return new McpServerChange();
        }
    }
}
