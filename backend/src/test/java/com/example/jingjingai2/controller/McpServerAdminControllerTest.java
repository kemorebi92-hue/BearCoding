package com.example.jingjingai2.controller;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.entity.McpUserPolicy;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.mcp.McpServerStatus;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.McpPermissionExplainer;
import com.example.jingjingai2.permission.McpUserPolicyDbStore;
import com.example.jingjingai2.entity.McpServerCredential;
import com.example.jingjingai2.mapper.McpServerCredentialMapper;
import com.example.jingjingai2.mcp.McpCredentialCrypto;
import com.example.jingjingai2.service.McpCredentialService;
import com.example.jingjingai2.service.McpServerProvisionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 编辑服务时，请求里没带的字段沿用原值。不连数据库，Mapper 只是记录。 */
class McpServerAdminControllerTest {

    /** 删除服务会跨用户清授权，用内存表模拟这张没有外键的表。 */
    private final FakePolicyMapper policies = new FakePolicyMapper();
    /** 内存里的「本会话允许」：只记录被清了哪个服务。 */
    private final SpyApprovals approvals = new SpyApprovals();

    private final MemoryCredentials credentialSink = new MemoryCredentials();

    private McpServerAdminController newController(McpClientManager manager, McpServerConfigMapper configs) {
        credentialSink.rows.clear();
        McpCredentialService credentials = new McpCredentialService(
                configs, credentialSink, new McpCredentialCrypto("0123456789abcdef0123456789abcdef"));
        return transactional(new McpServerAdminController(manager, configs, policies, approvals,
                new McpServerProvisionService(configs, manager), credentials));
    }

    /** 让方法上的 @Transactional 真的开事务并在异常时回滚。不连数据库。 */
    private static McpServerAdminController transactional(McpServerAdminController target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.setTransactionManager(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        });
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return (McpServerAdminController) factory.getProxy();
    }

    @Test
    @DisplayName("编辑时省略 env 保留原值，显式传空对象则清空")
    void omittedEnvIsKept() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig existing = new McpServerConfig();
        existing.setName("echo");
        existing.setCommand("npx");
        existing.setArgsJson("[\"-y\",\"pkg\"]");
        existing.setEnvJson("{\"API_KEY\":\"secret-value\"}");
        existing.setTrust("untrusted");
        existing.setEnabled(true);
        existing.setCreatedBy(7L);
        mapper.row = existing;

        McpClientManager manager = new McpClientManager(new McpProperties());
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "uvx");
        ResponseEntity<Map<String, Object>> res = controller.update("echo", body, authed());
        assertEquals(200, res.getStatusCode().value());
        assertEquals("uvx", mapper.updated.getCommand());
        assertEquals("{\"API_KEY\":\"secret-value\"}", mapper.updated.getEnvJson(), "没传 env 时原值保留");
        assertEquals("[\"-y\",\"pkg\"]", mapper.updated.getArgsJson(), "没传 args 时原值保留");
        assertFalse(String.valueOf(res.getBody()).contains("secret-value"), "响应不含密钥");

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("command", "uvx");
        clear.put("env", Map.of());
        controller.update("echo", clear, authed());
        assertEquals("{}", mapper.updated.getEnvJson(), "显式传空对象才清空");
    }

    @Test
    @DisplayName("stdio 改为 HTTP：显式清空 command/args/env 后，禁用配置可更新且不连接")
    void stdioSwitchesToHttpWhenOtherFieldsCleared() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(false);
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transport", "streamable_http");
        body.put("baseUrl", "https://1.1.1.1");
        body.put("endpointPath", "/mcp");
        body.put("authType", "none");
        body.put("command", "");
        body.put("args", List.of());
        body.put("env", Map.of());
        ResponseEntity<Map<String, Object>> res = controller.update("playwright", body, authed());
        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("streamable_http", mapper.updated.getTransport());
        assertEquals("https://1.1.1.1", mapper.updated.getBaseUrl());
        assertNull(mapper.updated.getCommand());
        assertEquals(0, manager.addCalls);
        assertEquals(0, manager.applySpecCalls);
        assertFalse(manager.connectCalled);
    }

    /** 一个已能运行的个人远程 HTTP 服务（bearer）。 */
    private static McpServerConfig httpRow(boolean enabled) {
        McpServerConfig row = new McpServerConfig();
        row.setName("gh");
        row.setTransport("streamable_http");
        row.setBaseUrl("https://1.1.1.1");
        row.setEndpointPath("/mcp");
        row.setAuthType("bearer");
        row.setTrust("untrusted");
        row.setEnabled(enabled);
        row.setCreatedBy(7L);
        return row;
    }

    @Test
    @DisplayName("已启用的 HTTP 服务可以更新配置，并在同一 Slot 上重连")
    void enabledHttpServiceCanBeUpdated() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig existing = httpRow(true);
        existing.setId(42L);
        mapper.row = existing;
        StubManager manager = new StubManager();
        manager.slot = "gh";
        manager.slotOwner = 7L;
        manager.slotState = "connected";
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connectTimeoutSeconds", 30);
        ResponseEntity<Map<String, Object>> res = controller.update("gh", body, authed());

        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertNotNull(mapper.updated, "配置必须真的落库");
        assertEquals("streamable_http", mapper.updated.getTransport());
        assertEquals("https://1.1.1.1", mapper.updated.getBaseUrl());
        assertEquals("/mcp", mapper.updated.getEndpointPath());
        assertEquals("bearer", mapper.updated.getAuthType());
        assertEquals(30, mapper.updated.getConnectTimeoutSeconds());
        assertEquals(42L, mapper.updated.getId().longValue(), "更新不能换掉配置主键");
        assertEquals(1, manager.applySpecCalls, "已启用的服务要在同一 Slot 上换规格并重连");
        assertEquals(42L, manager.lastSpec.getServerConfigId().longValue(),
                "凭证按这个 ID 读取，更新后必须还是原值");
    }

    @Test
    @DisplayName("HTTP 服务更新后，已关联的凭证仍按原服务 ID 读得到")
    void httpUpdateKeepsCredentialLookupKey() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig existing = httpRow(true);
        existing.setId(42L);
        mapper.row = existing;
        StubManager manager = new StubManager();
        manager.slot = "gh";
        manager.slotOwner = 7L;
        manager.slotState = "connected";
        McpServerAdminController controller = newController(manager, mapper);

        McpServerCredential credential = new McpServerCredential();
        credential.setUserId(7L);
        credential.setServerConfigId(42L);
        credential.setCiphertext("v1.stub");
        credentialSink.rows.add(credential);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connectTimeoutSeconds", 30);
        assertEquals(200, controller.update("gh", body, authed()).getStatusCode().value());

        ResponseEntity<Map<String, Object>> status = controller.credentialStatus("gh", authed());
        assertEquals(200, status.getStatusCode().value());
        assertEquals(Boolean.TRUE, status.getBody().get("configured"),
                "主键没变，凭证才读得到");
        assertEquals("bearer", status.getBody().get("authType"));
    }

    @Test
    @DisplayName("已启用的 HTTP 服务，非法配置仍被拒绝且不落库")
    void enabledHttpServiceStillRejectsInvalidConfig() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = httpRow(true);
        StubManager manager = new StubManager();
        manager.slot = "gh";
        manager.slotOwner = 7L;
        manager.slotState = "connected";
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseUrl", "https://1.1.1.1/with-path");
        ResponseEntity<Map<String, Object>> res = controller.update("gh", body, authed());

        assertEquals(400, res.getStatusCode().value());
        assertNull(mapper.updated, "校验失败不能落库");
        assertEquals(0, manager.applySpecCalls, "校验失败不能动运行时");
    }

    @Test
    @DisplayName("别人的 HTTP 服务仍然不能修改")
    void otherUsersHttpServiceCannotBeUpdated() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig existing = httpRow(true);
        existing.setCreatedBy(9L);
        mapper.row = existing;
        StubManager manager = new StubManager();
        manager.slot = "gh";
        manager.slotOwner = 9L;
        manager.slotState = "connected";
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connectTimeoutSeconds", 30);
        ResponseEntity<Map<String, Object>> res = controller.update("gh", body, authed());

        assertNotEquals(200, res.getStatusCode().value());
        assertNull(mapper.updated);
        assertEquals(0, manager.applySpecCalls);
    }

    @Test
    @DisplayName("已启用的 stdio 服务更新行为不变")
    void enabledStdioServiceStillReplaces() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig existing = fullRow(true);
        existing.setId(11L);
        mapper.row = existing;
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        manager.slotOwner = 7L;
        manager.slotState = "connected";
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "uvx");
        ResponseEntity<Map<String, Object>> res = controller.update("playwright", body, authed());

        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("uvx", mapper.updated.getCommand());
        assertEquals("[\"-y\",\"@playwright/mcp@latest\"]", mapper.updated.getArgsJson(), "没传 args 时原值保留");
        assertEquals(1, manager.applySpecCalls);
    }

    @Test
    @DisplayName("只改 transport、不清空 command：校验失败，不调用 update")
    void transportChangeWithoutClearingCommandDoesNotUpdate() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(false);
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transport", "streamable_http");
        body.put("baseUrl", "https://1.1.1.1");
        body.put("endpointPath", "/mcp");
        body.put("authType", "none");
        body.put("args", List.of());
        body.put("env", Map.of());
        ResponseEntity<Map<String, Object>> res = controller.update("playwright", body, authed());
        assertEquals(400, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertNull(mapper.updated);
        assertEquals(0, manager.addCalls);
    }

    @Test
    @DisplayName("HTTP 改为 stdio：显式清空 URL 并提交命令后可以更新")
    void httpSwitchesToStdioWhenUrlsCleared() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = httpRow();
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transport", "stdio");
        body.put("command", "npx");
        body.put("args", List.of("-y", "pkg"));
        body.put("baseUrl", "");
        body.put("endpointPath", "");
        body.put("authType", "none");
        ResponseEntity<Map<String, Object>> res = controller.update("remote-a", body, authed());
        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals("stdio", mapper.updated.getTransport());
        assertEquals("npx", mapper.updated.getCommand());
        assertNull(mapper.updated.getBaseUrl());
        assertEquals(0, manager.addCalls);
        assertEquals(0, manager.applySpecCalls);
    }

    @Test
    @DisplayName("HTTP 改为 stdio 但未清空 baseUrl：校验失败，不调用 update")
    void httpToStdioWithoutClearingBaseUrlDoesNotUpdate() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = httpRow();
        McpServerAdminController controller = newController(new StubManager(), mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transport", "stdio");
        body.put("command", "npx");
        body.put("args", List.of("-y", "pkg"));
        ResponseEntity<Map<String, Object>> res = controller.update("remote-a", body, authed());
        assertEquals(400, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertNull(mapper.updated);
        assertEquals("https://1.1.1.1", mapper.row.getBaseUrl());
    }

    @Test
    @DisplayName("只改超时：未提交的传输和 URL 保持原值，禁用 HTTP 不连接")
    void partialTimeoutKeepsHttpFieldsAndDoesNotConnect() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = httpRow();
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connectTimeoutSeconds", 45);
        ResponseEntity<Map<String, Object>> res = controller.update("remote-a", body, authed());
        assertEquals(200, res.getStatusCode().value(), String.valueOf(res.getBody()));
        assertEquals(45, mapper.updated.getConnectTimeoutSeconds());
        assertEquals("streamable_http", mapper.updated.getTransport());
        assertEquals("https://1.1.1.1", mapper.updated.getBaseUrl());
        assertEquals("/mcp", mapper.updated.getEndpointPath());
        assertNull(mapper.updated.getCommand());
        assertEquals(0, manager.addCalls);
        assertEquals(0, manager.applySpecCalls);
        assertFalse(manager.connectCalled);
        assertEquals(0, mapper.updateEnabledCalls);
    }

    @Test
    @DisplayName("非法 URL 不更新；只改成 bearer 会保存认证方式，响应里没有令牌")
    void illegalHttpEditDoesNotUpdate() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = httpRow();
        McpServerAdminController controller = newController(new StubManager(), mapper);
        Map<String, Object> badUrl = new LinkedHashMap<>();
        badUrl.put("baseUrl", "https://127.0.0.1");
        assertEquals(400, controller.update("remote-a", badUrl, authed()).getStatusCode().value());
        assertNull(mapper.updated);
        Map<String, Object> bearer = new LinkedHashMap<>();
        bearer.put("authType", "bearer");
        ResponseEntity<Map<String, Object>> saved = controller.update("remote-a", bearer, authed());
        assertEquals(200, saved.getStatusCode().value(), String.valueOf(saved.getBody()));
        assertEquals("bearer", mapper.updated.getAuthType());
        assertFalse(String.valueOf(saved.getBody()).contains("token"));
        assertEquals("https://1.1.1.1", mapper.updated.getBaseUrl());
    }

    private static McpServerConfig httpRow() {
        McpServerConfig row = new McpServerConfig();
        row.setName("remote-a");
        row.setTransport("streamable_http");
        row.setBaseUrl("https://1.1.1.1");
        row.setEndpointPath("/mcp");
        row.setAuthType("none");
        row.setTrust("untrusted");
        row.setDisabledToolsJson("[]");
        row.setConnectTimeoutSeconds(0);
        row.setMaxResultChars(0);
        row.setEnabled(false);
        row.setCreatedBy(7L);
        row.setId(41L);
        return row;
    }

    @Test
    @DisplayName("本人 HTTP 服务可保存凭证，查询不返回明文或密文")
    void ownerCanSaveCredentialWithoutEcho() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        mapper.row = row;
        mapper.all.add(row);
        McpServerAdminController controller = newController(new StubManager(), mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", "fake-token-not-real");
        body.put("userId", 9L);
        ResponseEntity<Map<String, Object>> saved = controller.saveCredential("remote-a", body, authed());
        assertEquals(200, saved.getStatusCode().value());
        assertEquals(true, saved.getBody().get("configured"));
        assertFalse(String.valueOf(saved.getBody()).contains("fake-token-not-real"));
        ResponseEntity<Map<String, Object>> status = controller.credentialStatus("remote-a", authed());
        assertEquals(true, status.getBody().get("configured"));
        assertFalse(String.valueOf(status.getBody()).contains("fake-token-not-real"));
        assertFalse(String.valueOf(status.getBody()).contains("v1."));
        assertEquals(1, credentialSink.rows.size());
        assertEquals(7L, credentialSink.rows.get(0).getUserId());
    }

    @Test
    @DisplayName("未配置时 configured=false；轮换不增加第二行")
    void missingCredentialIsFalseAndRotateReplaces() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        mapper.row = row;
        mapper.all.add(row);
        McpServerAdminController controller = newController(new StubManager(), mapper);
        assertEquals(false, controller.credentialStatus("remote-a", authed()).getBody().get("configured"));
        controller.saveCredential("remote-a", Map.of("token", "fake-token-not-real"), authed());
        controller.saveCredential("remote-a", Map.of("token", "fake-token-rotated"), authed());
        assertEquals(1, credentialSink.rows.size());
        assertFalse(credentialSink.rows.get(0).getCiphertext().contains("fake-token"));
    }

    @Test
    @DisplayName("没有主密钥时保存失败，且没有凭证行")
    void missingMasterKeyDoesNotStoreCredential() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        mapper.row = row;
        mapper.all.add(row);
        MemoryCredentials sink = new MemoryCredentials();
        McpServerAdminController controller = transactional(new McpServerAdminController(
                new StubManager(), mapper, policies, approvals,
                new McpServerProvisionService(mapper, new StubManager()),
                new McpCredentialService(mapper, sink, new McpCredentialCrypto(null))));
        ResponseEntity<Map<String, Object>> res = controller.saveCredential(
                "remote-a", Map.of("token", "fake-token-not-real"), authed());
        assertEquals(400, res.getStatusCode().value());
        assertTrue(sink.rows.isEmpty());
        assertFalse(String.valueOf(res.getBody()).contains("fake-token-not-real"));
    }

    @Test
    @DisplayName("别人的服务、同名服务、stdio 和不存在的服务不能串用凭证")
    void credentialRejectedForForeignSameNameAndStdio() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig mine = httpRow();
        mapper.all.add(mine);
        mapper.row = mine;
        McpServerAdminController controller = newController(new StubManager(), mapper);
        assertEquals(400, controller.saveCredential("remote-a", Map.of("token", "for-b"), asUser(9L)).getStatusCode().value());
        assertTrue(credentialSink.rows.isEmpty());
        McpServerConfig other = httpRow();
        other.setId(99L);
        other.setCreatedBy(9L);
        mapper.all.add(other);
        controller.saveCredential("remote-a", Map.of("token", "for-a"), authed());
        controller.saveCredential("remote-a", Map.of("token", "for-b"), asUser(9L));
        assertEquals(2, credentialSink.rows.size());
        assertEquals(41L, credentialSink.rows.get(0).getServerConfigId());
        assertEquals(99L, credentialSink.rows.get(1).getServerConfigId());
        McpServerConfig stdio = fullRow(false);
        stdio.setId(7L);
        mapper.all.add(stdio);
        assertEquals(400, controller.saveCredential("playwright", Map.of("token", "nope"), authed()).getStatusCode().value());
        assertEquals(400, controller.credentialStatus("missing", authed()).getStatusCode().value());
    }

    @Test
    @DisplayName("更新普通配置不改凭证；删除本人 HTTP 服务时按配置 id 清凭证")
    void updateKeepsCredentialAndDeleteRemovesIt() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        mapper.row = row;
        mapper.all.add(row);
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-not-real"), authed());
        String cipher = credentialSink.rows.get(0).getCiphertext();
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("connectTimeoutSeconds", 45);
        assertEquals(200, controller.update("remote-a", edit, authed()).getStatusCode().value());
        assertEquals(cipher, credentialSink.rows.get(0).getCiphertext());
        assertEquals(200, controller.delete("remote-a", authed()).getStatusCode().value());
        assertTrue(credentialSink.rows.isEmpty());
    }

    @Test
    @DisplayName("编辑 HTTP 服务后运行规格仍带配置主键，Bearer 连接才能核对凭证")
    void updateKeepsServerConfigIdInSpec() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        row.setAuthType("bearer");
        mapper.row = row;
        mapper.all.add(row);
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("connectTimeoutSeconds", 45);
        assertEquals(200, controller.update("remote-a", edit, authed()).getStatusCode().value());

        assertNotNull(manager.lastSpec, "停用分支也要把新规格交给运行时");
        assertEquals(41L, manager.lastSpec.getServerConfigId(),
                "规格必须带配置主键，否则 Bearer 凭证无法按归属读取");
    }

    @Test
    @DisplayName("停用只改启用状态，不删除已保存的凭证")
    void disableKeepsCredential() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        row.setEnabled(true);
        mapper.row = row;
        mapper.all.add(row);
        StubManager manager = new StubManager();
        manager.slot = "remote-a";
        manager.slotOwner = 7L;
        McpServerAdminController controller = newController(manager, mapper);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-v1"), authed());

        ResponseEntity<Map<String, Object>> res =
                controller.setEnabled("remote-a", Map.of("enabled", false), authed());

        assertEquals(200, res.getStatusCode().value());
        assertEquals(1, mapper.updateEnabledCalls);
        assertEquals(Boolean.FALSE, mapper.lastEnabled, "停用写入的是禁用");
        assertEquals(1, credentialSink.rows.size(), "停用不得删凭证");
    }

    @Test
    @DisplayName("重新启用不读凭证，也不宣称旧会话换了令牌")
    void reenableDoesNotReadOrClaimToken() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        row.setAuthType("bearer");
        mapper.row = row;
        mapper.all.add(row);
        CountingCredentials sink = new CountingCredentials();
        McpServerAdminController controller = controllerWith(new StubManager(), mapper, sink);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-v1"), authed());
        sink.finds = 0;

        ResponseEntity<Map<String, Object>> res =
                controller.setEnabled("remote-a", Map.of("enabled", true), authed());

        assertEquals(200, res.getStatusCode().value());
        assertEquals(0, sink.finds, "启用接口本身不读凭证，新连接才读");
        assertFalse(String.valueOf(res.getBody()).contains("fake-token"));
        assertFalse(String.valueOf(res.getBody()).contains("令牌"));
    }

    @Test
    @DisplayName("改回 none 清掉旧凭证；别人的同名服务凭证不动")
    void leavingBearerClearsOnlyOwnCredential() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig mine = httpRow();
        mine.setAuthType("bearer");
        mapper.row = mine;
        mapper.all.add(mine);
        McpServerConfig other = httpRow();
        other.setId(99L);
        other.setCreatedBy(9L);
        other.setAuthType("bearer");
        mapper.all.add(other);
        McpServerAdminController controller = newController(new StubManager(), mapper);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-v1"), authed());
        controller.saveCredential("remote-a", Map.of("token", "fake-token-for-b"), asUser(9L));

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("authType", "none");
        ResponseEntity<Map<String, Object>> res = controller.update("remote-a", edit, authed());

        assertEquals(200, res.getStatusCode().value());
        assertEquals("none", mapper.updated.getAuthType());
        assertEquals(1, credentialSink.rows.size(), "只清当前用户的凭证");
        assertEquals(9L, credentialSink.rows.get(0).getUserId());
        assertEquals(99L, credentialSink.rows.get(0).getServerConfigId());
        assertFalse(String.valueOf(res.getBody()).contains("fake-token"));
    }

    @Test
    @DisplayName("编辑校验失败时，凭证和认证方式都保持原样")
    void failedEditKeepsAuthAndCredential() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        row.setAuthType("bearer");
        mapper.row = row;
        mapper.all.add(row);
        McpServerAdminController controller = newController(new StubManager(), mapper);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-v1"), authed());
        String cipher = credentialSink.rows.get(0).getCiphertext();

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("baseUrl", "https://127.0.0.1");
        edit.put("authType", "none");
        ResponseEntity<Map<String, Object>> res = controller.update("remote-a", edit, authed());

        assertEquals(400, res.getStatusCode().value());
        assertNull(mapper.updated, "校验失败不得落库");
        assertEquals("bearer", mapper.row.getAuthType());
        assertEquals(1, credentialSink.rows.size());
        assertEquals(cipher, credentialSink.rows.get(0).getCiphertext());
    }

    @Test
    @DisplayName("改回 none 时凭证清理失败则抛出，配置更新回滚")
    void credentialCleanupFailureRollsBackAuthChange() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        row.setAuthType("bearer");
        mapper.row = row;
        mapper.all.add(row);
        FailingCredentials sink = new FailingCredentials();
        McpServerAdminController controller = controllerWith(new StubManager(), mapper, sink);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-v1"), authed());
        sink.failDelete = true;

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("authType", "none");
        assertThrows(IllegalStateException.class, () -> controller.update("remote-a", edit, authed()));

        assertNull(mapper.updated, "凭证没清掉时配置更新必须回滚");
        assertEquals("bearer", mapper.row.getAuthType());
        assertEquals(1, sink.rows.size());
    }

    @Test
    @DisplayName("运行时拒绝删除时不清理凭证")
    void runtimeRejectKeepsCredential() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig row = httpRow();
        mapper.row = row;
        mapper.all.add(row);
        StubManager manager = new StubManager();
        manager.slot = "remote-a";
        manager.slotOwner = 7L;
        manager.removeRejects = true;
        McpServerAdminController controller = newController(manager, mapper);
        controller.saveCredential("remote-a", Map.of("token", "fake-token-not-real"), authed());
        assertEquals(400, controller.delete("remote-a", authed()).getStatusCode().value());
        assertEquals(1, credentialSink.rows.size());
    }

    private McpServerAdminController controllerWith(StubManager manager, RecordingMapper mapper,
                                                     McpServerCredentialMapper sink) {
        return transactional(new McpServerAdminController(
                manager, mapper, policies, approvals,
                new McpServerProvisionService(mapper, manager),
                new McpCredentialService(mapper, sink,
                        new McpCredentialCrypto("0123456789abcdef0123456789abcdef"))));
    }

    private static class MemoryCredentials implements McpServerCredentialMapper {
        final List<McpServerCredential> rows = new ArrayList<>();
        @Override public int insert(McpServerCredential row) { rows.add(row); return 1; }
        @Override public int updateCiphertext(long userId, long serverConfigId, String ciphertext) {
            McpServerCredential row = find(userId, serverConfigId);
            if (row == null) return 0;
            row.setCiphertext(ciphertext);
            return 1;
        }
        @Override public McpServerCredential find(long userId, long serverConfigId) {
            for (McpServerCredential row : rows) {
                if (row.getUserId() == userId && row.getServerConfigId() == serverConfigId) return row;
            }
            return null;
        }
        @Override public int count(long userId, long serverConfigId) {
            return find(userId, serverConfigId) == null ? 0 : 1;
        }
        @Override public int delete(long userId, long serverConfigId) {
            return rows.removeIf(row -> row.getUserId() == userId && row.getServerConfigId() == serverConfigId) ? 1 : 0;
        }
    }

    /** 记下 load 会走的 find 次数，用来证明启停接口自己不读凭证。 */
    private static final class CountingCredentials extends MemoryCredentials {
        int finds;
        @Override public McpServerCredential find(long userId, long serverConfigId) {
            finds++;
            return super.find(userId, serverConfigId);
        }
    }

    /** 凭证删除失败。用来确认配置更新会一起回滚。 */
    private static final class FailingCredentials extends MemoryCredentials {
        boolean failDelete;
        @Override public int delete(long userId, long serverConfigId) {
            if (failDelete) throw new IllegalStateException("凭证删除失败");
            return super.delete(userId, serverConfigId);
        }
    }

    @Test
    @DisplayName("列表响应只含环境变量键名，不含值")
    void listHidesEnvValues() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig existing = new McpServerConfig();
        existing.setName("echo");
        existing.setCommand("npx");
        existing.setEnvJson("{\"API_KEY\":\"secret-value\"}");
        existing.setEnabled(true);
        existing.setCreatedBy(7L);
        mapper.row = existing;
        mapper.all.add(existing);

        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpClientManager manager = new McpClientManager(props) {
            @Override
            public List<McpServerStatus> statuses(long userId) {
                return List.of(new McpServerStatus("echo", "connected", null, 1, 1));
            }

            @Override
            public boolean isYamlServer(String name) { return false; }
        };
        McpServerAdminController controller = newController(manager, mapper);
        String body = String.valueOf(controller.list(authed()).getBody());
        assertTrue(body.contains("API_KEY"), body);
        assertFalse(body.contains("secret-value"), body);
    }

    @Test
    @DisplayName("测试 9a：数据库有配置、运行时没有 slot 时，列表仍然显示（state=stopped）")
    void listShowsStoppedServiceWithoutSlot() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(false);
        mapper.all.add(mapper.row);
        McpClientManager manager = new McpClientManager(new McpProperties()) {
            @Override public List<McpServerStatus> statuses(long userId) { return List.of(); }
            @Override public boolean isYamlServer(String name) { return false; }
        };
        McpServerAdminController controller = newController(manager, mapper);

        List<Map<String, Object>> servers = servers(controller.list(authed()));

        assertEquals(1, servers.size(), "停用的服务重启后不能从列表消失");
        Map<String, Object> row = servers.get(0);
        assertEquals("playwright", row.get("name"));
        assertEquals("stopped", row.get("state"), "没有运行时就是 stopped，不能说成 failed/connected");
        assertEquals(false, row.get("enabled"));
        assertEquals("npx", row.get("command"), "停用行也要能回填编辑表单");
        assertTrue(String.valueOf(row.get("args")).contains("playwright/mcp"), String.valueOf(row.get("args")));
    }

    @Test
    @DisplayName("测试 9b：既有配置又有 slot 时只出现一行，不重复展示")
    void listDoesNotDuplicateRunningService() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        mapper.all.add(mapper.row);
        McpClientManager manager = new McpClientManager(new McpProperties()) {
            @Override public List<McpServerStatus> statuses(long userId) {
                return List.of(new McpServerStatus("playwright", "connected", null, 8, 7));
            }
            @Override public boolean isYamlServer(String name) { return false; }
        };
        McpServerAdminController controller = newController(manager, mapper);

        List<Map<String, Object>> servers = servers(controller.list(authed()));

        assertEquals(1, servers.size(), "运行时已经有这一行，不能再补一条 stopped");
        assertEquals("connected", servers.get(0).get("state"));
        assertEquals(8, servers.get(0).get("toolCount"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> servers(ResponseEntity<Map<String, Object>> res) {
        return (List<Map<String, Object>>) res.getBody().get("servers");
    }

    /** 只用 JWT 拦截器写进去的 userId 属性；不依赖 spring-test 的 Mock 请求。 */
    private static HttpServletRequest authed() {
        return asUser(7L);
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

    // ==================== 阶段 2A：编辑配置不丢失 + 停用服务不部分写入 ====================

    private static McpServerConfig fullRow(boolean enabled) {
        McpServerConfig row = new McpServerConfig();
        row.setName("playwright");
        row.setCommand("npx");
        row.setArgsJson("[\"-y\",\"@playwright/mcp@latest\"]");
        row.setEnvJson("{\"API_KEY\":\"secret-value\"}");
        row.setDisabledToolsJson("[\"browser_run_code_unsafe\"]");
        row.setTrust("trusted");
        row.setConnectTimeoutSeconds(60);
        row.setMaxResultChars(12000);
        row.setEnabled(enabled);
        row.setCreatedBy(7L);      // 归属：authed() 就是这个用户
        return row;
    }

    @Test
    @DisplayName("列表回传编辑所需字段（排除名单/trust/超时/结果长度），仍不含 env 值")
    void listReturnsEditableFields() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        mapper.all.add(mapper.row);

        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpClientManager manager = new McpClientManager(props) {
            @Override
            public List<McpServerStatus> statuses(long userId) {
                return List.of(new McpServerStatus("playwright", "connected", null, 3, 3));
            }

            @Override
            public boolean isYamlServer(String name) { return false; }
        };
        McpServerAdminController controller = newController(manager, mapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = controller.list(authed()).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) body.get("servers")).get(0);

        assertEquals(List.of("browser_run_code_unsafe"), row.get("disabledTools"));
        assertEquals("trusted", row.get("trust"));
        assertEquals(60, row.get("connectTimeoutSeconds"));
        assertEquals(12000, row.get("maxResultChars"));
        assertTrue(String.valueOf(row).contains("API_KEY"), "键名仍返回");
        assertFalse(String.valueOf(row).contains("secret-value"), "值绝不返回");
    }

    @Test
    @DisplayName("场景 A：只提交 command 时，四个未提交字段全部保持原值")
    void editKeepsUnsubmittedFields() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "uvx");
        assertEquals(200, controller.update("playwright", body, authed()).getStatusCode().value());

        McpServerConfig saved = mapper.updated;
        assertEquals("uvx", saved.getCommand());
        assertEquals("[\"browser_run_code_unsafe\"]", saved.getDisabledToolsJson(), "排除名单不得被清空");
        assertEquals("trusted", saved.getTrust(), "trust 不得被重置");
        assertEquals(60, saved.getConnectTimeoutSeconds(), "超时不得被重置");
        assertEquals(12000, saved.getMaxResultChars(), "结果长度不得被重置");
        assertEquals("{\"API_KEY\":\"secret-value\"}", saved.getEnvJson(), "env 不得被清空");
        assertEquals("[\"-y\",\"@playwright/mcp@latest\"]", saved.getArgsJson(), "args 不得被清空");
    }

    @Test
    @DisplayName("场景 B/C：显式传新名单覆盖；显式传空数组才清空")
    void explicitDisabledToolsOnly() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("command", "npx");
        changed.put("disabledTools", List.of("browser_run_code_unsafe", "browser_file_upload"));
        controller.update("playwright", changed, authed());
        assertEquals("[\"browser_run_code_unsafe\",\"browser_file_upload\"]", mapper.updated.getDisabledToolsJson());

        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put("command", "npx");
        cleared.put("disabledTools", List.of());
        controller.update("playwright", cleared, authed());
        assertEquals("[]", mapper.updated.getDisabledToolsJson(), "显式空数组才代表清空");
        assertEquals("trusted", mapper.updated.getTrust(), "清空名单不应顺带重置其他字段");
    }

    @Test
    @DisplayName("停用服务：只落库、不启动，响应如实报告已保存")
    void editDisabledServiceSavesWithoutStarting() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(false);
        StubManager manager = new StubManager();
        manager.slot = null;                       // 进程重启过：停用的行不建 slot
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "npx");
        body.put("args", List.of("-y", "@playwright/mcp@latest", "--headless"));

        @SuppressWarnings("unchecked")
        Map<String, Object> res = controller.update("playwright", body, authed()).getBody();

        assertEquals(true, res.get("configSaved"), "库已改，必须说已保存");
        assertEquals(false, res.get("connected"));
        assertNotEquals("已拒绝", res.get("action"), "不能再报『服务不存在/已拒绝』");
        assertTrue(String.valueOf(res.get("action")).contains("已停用"), String.valueOf(res.get("action")));
        assertEquals(false, manager.connectCalled, "停用服务不得因编辑被拉起");
        assertEquals(0, mapper.updateEnabledCalls, "编辑不得改动 enabled（启用/停用走独立接口）");
        assertNotNull(manager.lastSpec, "停用服务也要把新配置换成运行规格，供下次启用使用");
        assertEquals(1, manager.updateSpecCalls);
    }

    @Test
    @DisplayName("停用但本进程仍有 slot：换规格、不连接")
    void editDisabledServiceWithResidualSlot() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(false);
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        manager.slotState = "disconnected";
        McpServerAdminController controller = newController(manager, mapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> res = controller.update("playwright",
                Map.of("command", "npx", "trust", "untrusted"), authed()).getBody();

        assertEquals(true, res.get("configSaved"));
        assertEquals(false, manager.connectCalled);
        assertEquals(1, manager.updateSpecCalls, "必须把新 spec 换进 slot，否则下次启用用旧配置");
        assertEquals("untrusted", mapper.updated.getTrust());
    }

    @Test
    @DisplayName("启用但没有 slot：报『已保存（未运行）』，不伪装成已连接")
    void editEnabledWithoutSlot() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = new StubManager();
        manager.slot = null;
        McpServerAdminController controller = newController(manager, mapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> res = controller.update("playwright",
                Map.of("command", "npx"), authed()).getBody();

        assertEquals(true, res.get("configSaved"));
        assertEquals(false, res.get("connected"));
        assertTrue(String.valueOf(res.get("action")).contains("未运行"), String.valueOf(res.get("action")));
        assertEquals(0, manager.applySpecCalls, "没有 slot 时没有可替换的运行时");
    }

    @Test
    @DisplayName("正常运行的服务：同一 Slot 换规格并重连，响应保持原语义")
    void connectedServiceStillReconnects() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        manager.slotState = "connected";
        McpServerAdminController controller = newController(manager, mapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> res = controller.update("playwright",
                Map.of("command", "npx", "args", List.of("-y", "pkg")), authed()).getBody();

        assertEquals(1, manager.applySpecCalls);
        assertEquals(true, res.get("configSaved"));
        assertEquals(true, res.get("connected"));
        assertEquals(3, res.get("toolCount"));
    }

    @Test
    @DisplayName("运行时不接受新配置：configSaved=true + lastError，而不是完全失败")
    void runtimeRejectedStillReportsSaved() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        manager.applyFails = true;
        McpServerAdminController controller = newController(manager, mapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> res = controller.update("playwright",
                Map.of("command", "npx"), authed()).getBody();

        assertEquals(true, res.get("configSaved"), "配置确实落库了");
        assertEquals(false, res.get("connected"));
        assertEquals("配置已保存，运行时未应用", res.get("action"));
        assertEquals("运行时拒绝", res.get("lastError"));
    }

    // ==================== 阶段 2B：删除服务清历史授权 ====================

    private static StubManager runningManager() {
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        return manager;
    }

    @Test
    @DisplayName("测试 1：删除服务后，它的持久化 ALLOW 不再存在")
    void deleteClearsAllow() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "playwright", "browser_evaluate", "ALLOW");
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> res = controller.delete("playwright", authed()).getBody();

        assertEquals(List.of(), policies.snapshot(), "ALLOW 必须被清掉");
        assertEquals(1, res.get("policiesCleared"));
        assertNull(mapper.row, "配置行也要删");
        assertEquals(false, res.get("connected"));
    }

    @Test
    @DisplayName("测试 2：DENY、ASK 和服务级默认行一起清理")
    void deleteClearsDenyAskAndServerDefault() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "playwright", "delete_item", "DENY")
                .put(7L, "playwright", "read_item", "ASK")
                .put(7L, "playwright", "", "ALLOW");     // tool_name='' 是服务级默认
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> res = controller.delete("playwright", authed()).getBody();

        assertEquals(List.of(), policies.snapshot());
        assertEquals(3, res.get("policiesCleared"), "三种授权都要算进清理数");
    }

    @Test
    @DisplayName("测试 3：只删目标服务的授权，其他服务原样保留")
    void deleteDoesNotTouchOtherServer() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "playwright", "read_item", "ALLOW")
                .put(7L, "other", "b", "ALLOW")
                .put(9L, "other", "c", "DENY");
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        controller.delete("playwright", authed());

        assertEquals(List.of("7|other|b|ALLOW", "9|other|c|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 4：删除后重建同名服务，不会继承旧授权（回落到未配置 ASK）")
    void readdedSameNameDoesNotInherit() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        // 用不在 F1 词表里的工具名，才能断言「未配置 → ASK 且可本会话授权」
        policies.put(7L, "playwright", "read_item", "ALLOW");
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        controller.delete("playwright", authed());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "playwright");
        body.put("command", "npx");
        ResponseEntity<Map<String, Object>> addRes = controller.add(body, authed());
        assertEquals(200, addRes.getStatusCode().value());
        // 阶段 1A：新增只保存不启动——不调 addServer，不建槽位、不连进程。
        assertEquals(0, manager.addCalls, "保存不应触发 addServer");
        assertEquals(true, addRes.getBody().get("configSaved"));
        assertEquals(false, addRes.getBody().get("connected"), "响应如实报告未连接");
        assertNotNull(mapper.row, "配置已落库");
        assertEquals(false, mapper.row.isEnabled(), "落库的服务默认禁用");
        assertEquals(7L, mapper.row.getCreatedBy(), "归属来自可信 userId，不是 body");

        McpUserPolicyDbStore store = new McpUserPolicyDbStore(policies);
        McpPermissionExplainer explainer = new McpPermissionExplainer(store, null);
        McpPermissionExplainer.ExplainResult er =
                explainer.explain(7L, "playwright", "read_item", null);
        assertEquals("ASK", er.effect.name(), er.reason);
        assertTrue(er.sessionGrant, "全新工具应可本会话授权：" + er.reason);
        assertTrue(er.reason.contains("未配置"), er.reason);
    }

    @Test
    @DisplayName("阶段 1A：保存后默认禁用、不连；用户手动启用走原有 addServer 连接链路")
    void savedDisabledThenManualEnableConnects() {
        RecordingMapper mapper = new RecordingMapper();
        StubManager manager = new StubManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "echo");
        body.put("command", "npx");
        Map<String, Object> addRes = controller.add(body, authed()).getBody();
        assertEquals(true, addRes.get("configSaved"));
        assertEquals(false, addRes.get("connected"), "新增只保存不连");
        assertEquals(false, mapper.row.isEnabled(), "落库默认禁用");
        assertEquals(7L, mapper.row.getCreatedBy(), "归属用可信 userId");
        assertEquals(0, manager.addCalls, "保存路径不得触发 addServer");

        // 用户走 MCP 管理页「启用」：无槽位 → addServer → connectSlot（沿用现有链路）
        controller.setEnabled("echo", Map.of("enabled", true), authed());
        assertEquals(1, manager.addCalls, "手动启用走原有 addServer 连接链路");
        assertNotNull(manager.lastSpec, "启用时把保存的 spec 传给 addServer");
    }

    @Test
    @DisplayName("测试 5a：授权清理失败不能被吞掉，配置行也不能被提前删")
    void policyCleanupFailureIsNotSilent() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "playwright", "read_item", "ALLOW");
        policies.failDeleteByServer = true;
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        assertThrows(IllegalStateException.class, () -> controller.delete("playwright", authed()));

        assertNotNull(mapper.row, "先删授权再删配置：授权失败时配置必须还在");
        assertEquals(1, policies.snapshot().size());
    }

    @Test
    @DisplayName("测试 5b：运行时摘不掉就返回 400，数据库一条都不动")
    void runtimeRejectAbortsDelete() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "playwright", "read_item", "ALLOW");
        StubManager manager = runningManager();
        manager.removeRejects = true;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("运行时拒绝", res.getBody().get("message"));
        assertNotNull(mapper.row);
        assertEquals(1, policies.snapshot().size());
        assertEquals(0, policies.deleteByServerCalls, "运行时没摘掉时不应开始清授权");
    }

    @Test
    @DisplayName("测试 6a：重复删除返回「服务不存在」，不误删其他服务")
    void repeatDeleteIsPredictable() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "other", "b", "ALLOW");
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        assertEquals(200, controller.delete("playwright", authed()).getStatusCode().value());
        ResponseEntity<Map<String, Object>> again = controller.delete("playwright", authed());

        assertEquals(400, again.getStatusCode().value());
        assertEquals("服务不存在", again.getBody().get("message"));
        assertEquals(List.of("7|other|b|ALLOW"), policies.snapshot(), "其他服务不受影响");
    }

    @Test
    @DisplayName("测试 6b：服务已不存在但旧授权残留时，补清并如实告知")
    void orphanPoliciesAreCleanedOnRepeatDelete() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = null;                       // 旧版本删过配置，没清授权
        policies.put(7L, "playwright", "read_item", "ALLOW")
                .put(9L, "playwright", "", "DENY");
        StubManager manager = new StubManager();
        manager.slot = null;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

        assertEquals(200, res.getStatusCode().value());
        assertTrue(String.valueOf(res.getBody().get("action")).contains("残留"),
                String.valueOf(res.getBody().get("action")));
        assertEquals(1, res.getBody().get("policiesCleared"), "只补清当前用户的行");
        assertEquals(List.of("9|playwright||DENY"), policies.snapshot(),
                "别人的同名服务授权必须留着");
    }

    @Test
    @DisplayName("测试 7：只清当前用户对这个服务的授权，不碰别人的同名服务与其他服务")
    void deleteClearsOnlyOwnPoliciesOfThatServer() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        policies.put(7L, "playwright", "read_item", "ALLOW")
                .put(9L, "playwright", "read_item", "DENY")   // 别人的同名服务
                .put(7L, "other", "x", "ALLOW")
                .put(9L, "other", "y", "ASK");
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> res = controller.delete("playwright", authed()).getBody();

        assertEquals(1, res.get("policiesCleared"), "只清 user 7 的那一条");
        assertEquals(List.of("7|other|x|ALLOW", "9|other|y|ASK", "9|playwright|read_item|DENY"),
                policies.snapshot(), "别人的同名服务授权必须留着");
    }

    @Test
    @DisplayName("测试 8a：删除成功时清掉该服务的内存会话授权")
    void deleteForgetsSessionGrants() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        assertEquals(200, controller.delete("playwright", authed()).getStatusCode().value());
        assertEquals(List.of("playwright"), approvals.forgotten, "删掉的服务要清会话授权");
    }

    @Test
    @DisplayName("测试 8b：运行时拒绝删除时，内存会话授权不许提前清")
    void runtimeRejectKeepsSessionGrants() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = runningManager();
        manager.removeRejects = true;
        McpServerAdminController controller = newController(manager, mapper);

        assertEquals(400, controller.delete("playwright", authed()).getStatusCode().value());
        assertEquals(List.of(), approvals.forgotten, "服务还在，授权就不能先没");
    }

    @Test
    @DisplayName("测试 8c：补清孤儿配置时，同名的内存会话授权一起清")
    void orphanCleanupAlsoForgetsSessionGrants() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = null;
        policies.put(7L, "playwright", "read_item", "ALLOW");
        StubManager manager = new StubManager();
        manager.slot = null;
        McpServerAdminController controller = newController(manager, mapper);

        assertEquals(200, controller.delete("playwright", authed()).getStatusCode().value());
        assertEquals(List.of("playwright"), approvals.forgotten);
    }

    @Test
    @DisplayName("测试 10a：编辑成功（配置已落库）后清掉该服务的会话授权")
    void editForgetsSessionGrants() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "uvx");
        assertEquals(200, controller.update("playwright", body, authed()).getStatusCode().value());

        assertEquals(List.of("playwright"), approvals.forgotten,
                "换了命令就等于换了执行目标，旧授权不能继续放行");
    }

    @Test
    @DisplayName("测试 10b：校验失败或服务不存在时，不清会话授权")
    void failedEditKeepsSessionGrants() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = fullRow(true);
        StubManager manager = runningManager();
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("command", "./evil.sh");            // 路径，必须被校验拒绝
        assertEquals(400, controller.update("playwright", bad, authed()).getStatusCode().value());
        assertEquals(List.of(), approvals.forgotten, "没落库就不能清授权");

        // RecordingMapper.findByName 不区分名字，清掉 row 就等于「库里没有这条」
        mapper.row = null;
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("command", "npx");
        assertEquals(400, controller.update("playwright", ok, authed()).getStatusCode().value());
        assertEquals(List.of(), approvals.forgotten, "服务不存在时也不清");
    }

    @Test
    @DisplayName("测试 11：别人的服务一律按「不存在」处理，且一条数据都不动")
    void cannotTouchOtherUsersService() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig other = fullRow(true);
        other.setCreatedBy(9L);          // 属于 user 9
        mapper.row = other;
        mapper.all.add(other);
        StubManager manager = runningManager();
        manager.slotOwner = 9L;          // 运行时那个槽位也是 user 9 的
        policies.put(9L, "playwright", "read_item", "ALLOW");
        McpServerAdminController controller = newController(manager, mapper);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "uvx");
        assertEquals(400, controller.update("playwright", body, authed()).getStatusCode().value());
        assertEquals(400, controller.delete("playwright", authed()).getStatusCode().value());
        assertEquals(400, controller.setEnabled("playwright", Map.of("enabled", false), authed())
                .getStatusCode().value());
        assertEquals(400, controller.connect("playwright", authed()).getStatusCode().value());

        assertNull(mapper.updated, "别人的配置不能被改写");
        assertNotNull(mapper.row, "别人的配置不能被删除");
        assertEquals(0, manager.removeCalls, "别人的运行时不能被摘掉");
        assertEquals(List.of("9|playwright|read_item|ALLOW"), policies.snapshot(),
                "别人的授权不能被清");
        assertEquals(List.of(), approvals.forgotten);
    }

    @Test
    @DisplayName("测试 12：新增时拒绝与自己的服务重名，也拒绝与系统/共享服务重名")
    void addRejectsNameConflicts() {
        // 自己已经有了 playwright
        RecordingMapper mine = new RecordingMapper();
        mine.row = fullRow(true);
        mine.all.add(mine.row);
        StubManager m1 = new StubManager();
        McpServerAdminController c1 = newController(m1, mine);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "playwright");
        body.put("command", "npx");
        assertEquals(400, c1.add(body, authed()).getStatusCode().value());
        assertEquals(0, m1.addCalls, "重名时不能真的去连");

        // 系统共享服务占了这个名字（created_by 为空）
        RecordingMapper shared = new RecordingMapper();
        McpServerConfig sharedRow = fullRow(true);
        sharedRow.setCreatedBy(null);
        shared.all.add(sharedRow);
        StubManager m2 = new StubManager();
        McpServerAdminController c2 = newController(m2, shared);

        assertEquals(400, c2.add(body, authed()).getStatusCode().value());
        assertEquals(0, m2.addCalls);
    }

    // ==================== 系统共享服务不可删，也不能借「补清残留」删掉调用者自己的授权 ====================

    @Test
    @DisplayName("测试 13：系统共享配置（created_by=0 或空）不能删，调用者与其他用户的授权都保留")
    void deleteRejectsSharedConfigRow() {
        for (Long owner : new Long[]{0L, null}) {
            policies.rows.clear();
            policies.deleteByServerCalls = 0;
            approvals.forgotten.clear();

            RecordingMapper mapper = new RecordingMapper();
            McpServerConfig shared = fullRow(true);
            shared.setCreatedBy(owner);
            mapper.row = shared;
            mapper.all.add(shared);
            policies.put(7L, "playwright", "read_item", "ALLOW")
                    .put(7L, "playwright", "delete_item", "DENY")
                    .put(9L, "playwright", "read_item", "DENY");
            StubManager manager = new StubManager();
            manager.slot = null;
            McpServerAdminController controller = newController(manager, mapper);

            ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

            assertEquals(400, res.getStatusCode().value(), "owner=" + owner);
            assertEquals("系统共享服务不能删除", res.getBody().get("message"), "owner=" + owner);
            assertEquals(0, policies.deleteByServerCalls, "owner=" + owner);
            assertEquals(0, mapper.deleteCalls, "owner=" + owner);
            assertEquals(0, mapper.findAllCalls, "owner=" + owner);
            assertNotNull(mapper.row, "owner=" + owner);
            assertEquals(0, manager.removeCalls, "owner=" + owner);
            assertEquals(List.of(), approvals.forgotten, "owner=" + owner);
            assertEquals(List.of(
                    "7|playwright|delete_item|DENY",
                    "7|playwright|read_item|ALLOW",
                    "9|playwright|read_item|DENY"), policies.snapshot(), "owner=" + owner);
        }
    }

    @Test
    @DisplayName("测试 14：只有 owner 0 的运行时槽位时也拒绝，不清授权、不摘槽位")
    void deleteRejectsSharedRuntimeSlot() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "playwright", "", "ALLOW")
                .put(9L, "playwright", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "playwright";
        manager.slotOwner = 0L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("系统共享服务不能删除", res.getBody().get("message"));
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls, "判断共享服务不能把所有 env 读出来");
        assertEquals(0, manager.removeCalls, "共享槽位不能被摘掉");
        assertEquals("playwright", manager.slot);
        assertEquals(List.of(), approvals.forgotten);
        assertEquals(List.of("7|playwright||ALLOW", "9|playwright|read_item|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 15：YAML 没有运行时槽位时仍拒绝，不清授权")
    void deleteRejectsYamlWithoutSlot() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "playwright", "read_item", "ALLOW")
                .put(7L, "Playwright", "read_item", "DENY")
                .put(9L, "playwright", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = null;
        manager.properties().getServers().put("Playwright", new McpServerSpec());
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("部署配置里的服务不能删除", res.getBody().get("message"));
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(0, manager.removeCalls);
        assertEquals(List.of(), approvals.forgotten);
        assertEquals(List.of(
                "7|Playwright|read_item|DENY",
                "7|playwright|read_item|ALLOW",
                "9|playwright|read_item|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 16：共享服务名只是大小写不同时也拒绝，且不读全表")
    void deleteRejectsSharedNameCaseVariant() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig shared = fullRow(true);
        shared.setName("Playwright");
        shared.setCreatedBy(0L);
        mapper.row = shared;
        mapper.all.add(shared);
        policies.put(7L, "Playwright", "read_item", "DENY")
                .put(9L, "playwright", "read_item", "ALLOW");
        StubManager manager = new StubManager();
        manager.slot = null;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("系统共享服务不能删除", res.getBody().get("message"));
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(0, manager.removeCalls);
        assertNotNull(mapper.row);
        assertEquals(List.of(), approvals.forgotten);
        assertEquals(List.of("7|Playwright|read_item|DENY", "9|playwright|read_item|ALLOW"),
                policies.snapshot());
    }

    @Test
    @DisplayName("测试 17：同名个人服务可删，共享配置和双方授权都保留")
    void deletePersonalKeepsSameNameSharedService() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig personal = fullRow(true);
        personal.setName("Echo");
        personal.setCreatedBy(7L);
        McpServerConfig shared = fullRow(true);
        shared.setName("echo");
        shared.setCreatedBy(0L);
        mapper.row = personal;
        mapper.all.add(personal);
        mapper.all.add(shared);
        policies.put(7L, "Echo", "read_item", "ALLOW")
                .put(9L, "echo", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "Echo";
        manager.slotOwner = 7L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("echo", authed());

        assertEquals(200, res.getStatusCode().value());
        assertEquals(0, res.getBody().get("policiesCleared"), "授权分不清归属，不能清");
        assertEquals(1, mapper.deleteCalls);
        assertEquals(7L, mapper.lastDeleteOwner);
        assertEquals("Echo", mapper.lastDeleteName, "删的是库里的原样名字");
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(1, manager.removeCalls);
        assertEquals(7L, manager.lastRemoveOwner, "只摘当前用户的槽位");
        assertNull(mapper.row);
        assertFalse(mapper.all.contains(personal));
        assertTrue(mapper.all.contains(shared));
        assertEquals(0L, shared.getCreatedBy());
        assertEquals(List.of(), approvals.forgotten);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(List.of("7|Echo|read_item|ALLOW", "9|echo|read_item|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 18：没有共享服务时，大小写不同的个人服务仍会清掉自己的授权")
    void deletePersonalCaseVariantClearsOnlyOwnPolicies() {
        RecordingMapper mapper = new RecordingMapper();
        McpServerConfig personal = fullRow(true);
        personal.setName("Echo");
        personal.setCreatedBy(7L);
        mapper.row = personal;
        mapper.all.add(personal);
        policies.put(7L, "Echo", "read_item", "ALLOW")
                .put(9L, "Echo", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "Echo";
        manager.slotOwner = 7L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("echo", authed());

        assertEquals(200, res.getStatusCode().value());
        assertEquals(1, res.getBody().get("policiesCleared"));
        assertEquals(1, policies.deleteByServerCalls);
        assertEquals(1, mapper.deleteCalls);
        assertEquals(7L, mapper.lastDeleteOwner);
        assertNull(mapper.row);
        assertEquals(1, manager.removeCalls);
        assertEquals(7L, manager.lastRemoveOwner);
        assertEquals(List.of("Echo"), approvals.forgotten);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(List.of("9|Echo|read_item|DENY"), policies.snapshot(), "别人的同名授权还在");
    }

    @Test
    @DisplayName("测试 19：只剩 owner 0 槽位时，大小写不同的请求也拒绝，槽位和授权都留着")
    void deleteRejectsOwnerZeroSlotCaseVariant() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "Playwright", "read_item", "DENY")
                .put(9L, "Playwright", "read_item", "ALLOW");
        StubManager manager = new StubManager();
        manager.slot = "Playwright";
        manager.slotOwner = 0L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("playwright", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("系统共享服务不能删除", res.getBody().get("message"));
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(0, manager.removeCalls);
        assertEquals("Playwright", manager.slot);
        assertEquals(List.of(), approvals.forgotten);
        assertEquals(List.of("7|Playwright|read_item|DENY", "9|Playwright|read_item|ALLOW"),
                policies.snapshot());
    }

    @Test
    @DisplayName("测试 20：没有库行、个人槽位大小写一致时，摘槽位并只清当前用户授权")
    void deletePersonalSlotWithoutRowUsesExactName() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "echo", "read_item", "ALLOW")
                .put(9L, "echo", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "echo";
        manager.slotOwner = 7L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("echo", authed());

        assertEquals(200, res.getStatusCode().value());
        assertFalse(String.valueOf(res.getBody().get("action")).contains("残留"));
        assertEquals(1, res.getBody().get("policiesCleared"));
        assertEquals(1, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls, "没有配置行可删");
        assertEquals(0, mapper.findAllCalls);
        assertEquals(1, manager.removeCalls);
        assertEquals(7L, manager.lastRemoveOwner);
        assertEquals("echo", manager.lastRemoveName);
        assertNull(manager.slot);
        assertEquals(List.of("echo"), approvals.forgotten);
        assertEquals(List.of("9|echo|read_item|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 21：没有库行时，大小写不同的个人槽位也按原样摘掉，不当成孤儿")
    void deletePersonalSlotCaseVariantIsNotOrphan() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "Echo", "read_item", "ALLOW")
                .put(9L, "Echo", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "Echo";
        manager.slotOwner = 7L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("echo", authed());

        assertEquals(200, res.getStatusCode().value());
        assertFalse(String.valueOf(res.getBody().get("action")).contains("残留"),
                String.valueOf(res.getBody().get("action")));
        assertEquals(1, res.getBody().get("policiesCleared"));
        assertEquals(1, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(1, manager.removeCalls);
        assertEquals(7L, manager.lastRemoveOwner);
        assertEquals("Echo", manager.lastRemoveName, "摘的是槽位上的原样名字");
        assertNull(manager.slot);
        assertEquals(List.of("Echo"), approvals.forgotten);
        assertEquals(List.of("9|Echo|read_item|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 22：别人的槽位不会因为大小写不同被摘掉，别人的授权也留着")
    void deleteDoesNotTakeAnotherUsersSlotByCase() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "echo", "read_item", "ALLOW")
                .put(9L, "Echo", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "Echo";
        manager.slotOwner = 9L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("echo", authed());

        assertEquals(200, res.getStatusCode().value());
        assertEquals(1, policies.deleteByServerCalls, "只清当前用户自己的残留");
        assertEquals(0, manager.removeCalls);
        assertEquals("Echo", manager.slot);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(List.of("9|Echo|read_item|DENY"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 23：YAML 键 café、请求 cafe 时仍拒绝，不清授权")
    void deleteRejectsYamlAccentVariant() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "café", "read_item", "DENY")
                .put(9L, "café", "read_item", "ALLOW");
        StubManager manager = new StubManager();
        manager.slot = null;
        manager.properties().getServers().put("café", new McpServerSpec());
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("cafe", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("部署配置里的服务不能删除", res.getBody().get("message"));
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(0, manager.removeCalls);
        assertEquals(List.of(), approvals.forgotten);
        assertTrue(McpClientManager.namesMatch("café", "cafe"));
        assertTrue(McpClientManager.namesMatch("Café", "CAFE"));
        assertFalse(McpClientManager.namesMatch("cafe", "caf"));
        assertEquals(List.of("7|café|read_item|DENY", "9|café|read_item|ALLOW"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 24：只剩 owner 0 槽位 café 时，请求 cafe 也拒绝")
    void deleteRejectsOwnerZeroSlotAccentVariant() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "café", "read_item", "DENY")
                .put(9L, "café", "read_item", "ALLOW");
        StubManager manager = new StubManager();
        manager.slot = "café";
        manager.slotOwner = 0L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("cafe", authed());

        assertEquals(400, res.getStatusCode().value());
        assertEquals("系统共享服务不能删除", res.getBody().get("message"));
        assertEquals(0, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(0, manager.removeCalls);
        assertEquals("café", manager.slot);
        assertEquals(List.of(), approvals.forgotten);
        assertEquals(List.of("7|café|read_item|DENY", "9|café|read_item|ALLOW"), policies.snapshot());
    }

    @Test
    @DisplayName("测试 25：没有库行时，个人槽位 café 用请求 cafe 也按原样摘掉")
    void deletePersonalSlotAccentVariantIsNotOrphan() {
        RecordingMapper mapper = new RecordingMapper();
        policies.put(7L, "café", "read_item", "ALLOW")
                .put(9L, "café", "read_item", "DENY");
        StubManager manager = new StubManager();
        manager.slot = "café";
        manager.slotOwner = 7L;
        McpServerAdminController controller = newController(manager, mapper);

        ResponseEntity<Map<String, Object>> res = controller.delete("cafe", authed());

        assertEquals(200, res.getStatusCode().value());
        assertFalse(String.valueOf(res.getBody().get("action")).contains("残留"),
                String.valueOf(res.getBody().get("action")));
        assertEquals(1, res.getBody().get("policiesCleared"));
        assertEquals(1, policies.deleteByServerCalls);
        assertEquals(0, mapper.deleteCalls);
        assertEquals(0, mapper.findAllCalls);
        assertEquals(1, manager.removeCalls);
        assertEquals(7L, manager.lastRemoveOwner);
        assertEquals("café", manager.lastRemoveName);
        assertNull(manager.slot);
        assertEquals(List.of("café"), approvals.forgotten);
        assertEquals(List.of("9|café|read_item|DENY"), policies.snapshot());
    }

    /** 停用/断开/连接三种状态都可由 slotState 控制；不启动任何进程。只模拟「当前用户自己的」那个槽位。 */
    static final class StubManager extends McpClientManager {
        String slot;
        /** 这个槽位属于哪个用户；测试别人的服务时设成 9。 */
        long slotOwner = 7L;
        String slotState = "disconnected";
        boolean applyFails;
        boolean removeRejects;
        /** 为 true 时，当前 slot 按 YAML 部署服务处理。默认 false，不影响其他用例。 */
        boolean yaml;
        int applySpecCalls;
        int updateSpecCalls;
        int removeCalls;
        int addCalls;
        long lastRemoveOwner = -1;
        String lastRemoveName;
        boolean connectCalled;
        McpServerSpec lastSpec;

        StubManager() { super(new McpProperties()); }

        @Override public String findServer(String name) {
            return slot != null && slot.equals(name) ? name : null;
        }

        @Override public String findServer(long userId, String name) {
            return userId == slotOwner && slot != null && slot.equals(name) ? name : null;
        }

        @Override public String findOwnServer(long userId, String name) {
            return userId == slotOwner && slot != null && slot.equals(name) ? name : null;
        }

        /** 假槽位不在真实 map 里。只认 slotOwner 这一个归属，折叠后相同则返回槽位原样。 */
        @Override public String storedSlotName(long ownerId, String name) {
            if (slot == null || name == null || ownerId != slotOwner) return null;
            if (McpClientManager.namesMatch(slot, name)) return slot;
            return null;
        }

        @Override public boolean isYamlServer(String name) {
            return yaml && slot != null && slot.equals(name);
        }

        @Override public McpServerChange removeServer(long userId, String name) {
            removeCalls++;
            lastRemoveOwner = userId;
            lastRemoveName = name;
            McpClientManager.McpServerChange c = new McpClientManager.McpServerChange();
            c.name = name;
            if (removeRejects) {
                c.action = "已拒绝";
                c.lastError = "运行时拒绝";
                return c;
            }
            slot = null;
            c.action = "已删除";
            c.removed = true;
            return c;
        }

        @Override public McpServerChange removeServer(String name) {
            return removeServer(0L, name);
        }

        @Override public McpServerChange addServer(long ownerId, String name, McpServerSpec spec, boolean fromDatabase) {
            addCalls++;
            slot = name;
            lastSpec = spec;
            McpClientManager.McpServerChange c = new McpClientManager.McpServerChange();
            c.name = name;
            c.action = "已添加";
            c.configSaved = true;
            c.connected = true;
            c.toolCount = 1;
            return c;
        }

        @Override public boolean updateSpecOnly(long ownerId, String name, McpServerSpec spec) {
            updateSpecCalls++;
            lastSpec = spec;
            return !"connected".equals(slotState) && !"connecting".equals(slotState);
        }

        @Override public McpServerChange applySpecAndReconnect(long ownerId, String name, McpServerSpec spec) {
            applySpecCalls++;
            lastSpec = spec;
            connectCalled = true;
            McpClientManager.McpServerChange c = new McpClientManager.McpServerChange();
            c.name = name;
            if (applyFails) {
                c.action = "已拒绝";
                c.lastError = "运行时拒绝";
                return c;
            }
            c.action = "配置已更新";
            c.configSaved = true;
            c.connected = true;
            c.toolCount = 3;
            return c;
        }
    }

    /** 只记住最后一次更新，不访问数据库。 */
    static final class RecordingMapper implements McpServerConfigMapper {
        McpServerConfig row;
        McpServerConfig updated;
        /** findAll 的返回值：模拟「数据库里有、运行时没有」的停用服务。 */
        final List<McpServerConfig> all = new ArrayList<>();
        int updateEnabledCalls;
        Boolean lastEnabled;
        int deleteCalls;
        int findAllCalls;
        long lastDeleteOwner = -1;
        String lastDeleteName;

        @Override public int insert(McpServerConfig config) { row = config; return 1; }
        @Override public int update(McpServerConfig config) {
            McpServerConfig before = copy(row);
            updated = config;
            row = config;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager
                        .registerSynchronization(new TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                                    updated = null;
                                    row = before;
                                }
                            }
                        });
            }
            return 1;
        }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) {
            updateEnabledCalls++;
            lastEnabled = enabled;
            return 1;
        }
        @Override public int delete(long ownerId, String name) {
            deleteCalls++;
            lastDeleteOwner = ownerId;
            lastDeleteName = name;
            all.removeIf(c -> ownedName(c, ownerId, name));
            if (ownedName(row, ownerId, name)) row = null;
            return 1;
        }
        @Override public McpServerConfig findByName(String name) { return row; }
        @Override public McpServerConfig findById(long id) {
            for (McpServerConfig c : stored()) {
                if (c != null && c.getId() != null && c.getId() == id) return c;
            }
            return null;
        }
        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) {
            for (McpServerConfig c : stored()) {
                if (c == null || c.getCreatedBy() == null || c.getCreatedBy() != ownerId) continue;
                if (McpServerAdminController.sameServerName(c.getName(), name)) return c;
            }
            return null;
        }
        @Override public McpServerConfig findSharedByName(String name) {
            for (McpServerConfig c : stored()) {
                if (c == null || !McpServerAdminController.sameServerName(c.getName(), name)) continue;
                Long owner = c.getCreatedBy();
                if (owner == null || owner == 0L) return c;
            }
            return null;
        }
        @Override public List<McpServerConfig> findAll() {
            findAllCalls++;
            return all;
        }
        @Override public List<McpServerConfig> findAllByOwner(long ownerId) {
            List<McpServerConfig> out = new ArrayList<>();
            for (McpServerConfig c : all) {
                if (c != null && c.getCreatedBy() != null && c.getCreatedBy() == ownerId) out.add(c);
            }
            return out;
        }

        /** row 不一定在 all 里。同一对象只算一次。 */
        private List<McpServerConfig> stored() {
            List<McpServerConfig> out = new ArrayList<>(all);
            if (row != null && !all.contains(row)) out.add(row);
            return out;
        }

        /** 事务回滚时用：记下更新前的认证方式等字段，不共享可变引用。 */
        private static McpServerConfig copy(McpServerConfig src) {
            if (src == null) return null;
            McpServerConfig c = new McpServerConfig();
            c.setId(src.getId());
            c.setName(src.getName());
            c.setCreatedBy(src.getCreatedBy());
            c.setTransport(src.getTransport());
            c.setBaseUrl(src.getBaseUrl());
            c.setEndpointPath(src.getEndpointPath());
            c.setAuthType(src.getAuthType());
            c.setTrust(src.getTrust());
            c.setDisabledToolsJson(src.getDisabledToolsJson());
            c.setConnectTimeoutSeconds(src.getConnectTimeoutSeconds());
            c.setMaxResultChars(src.getMaxResultChars());
            c.setEnabled(src.isEnabled());
            c.setCommand(src.getCommand());
            return c;
        }

        private static boolean ownedName(McpServerConfig config, long ownerId, String name) {
            return config != null && config.getCreatedBy() != null && config.getCreatedBy() == ownerId
                    && McpServerAdminController.sameServerName(config.getName(), name);
        }
    }

    /**
     * 内存版授权表。真实表用 {@code (user_id, server_name, tool_name)} 唯一键，
     * 服务名是唯一关联键，没有外键也没有级联。
     */
    static final class FakePolicyMapper implements McpUserPolicyMapper {
        final Map<String, McpUserPolicy> rows = new LinkedHashMap<>();
        boolean failDeleteByServer;
        int deleteByServerCalls;

        FakePolicyMapper put(long userId, String server, String tool, String effect) {
            McpUserPolicy p = new McpUserPolicy();
            p.setUserId(userId);
            p.setServerName(server);
            p.setToolName(tool == null ? "" : tool);
            p.setEffect(effect);
            p.setDefinitionHash(null);
            rows.put(key(userId, server, p.getToolName()), p);
            return this;
        }

        /** 便于断言剩余内容：user|server|tool|effect，按固定顺序。 */
        List<String> snapshot() {
            List<String> out = new ArrayList<>();
            for (McpUserPolicy p : rows.values()) {
                out.add(p.getUserId() + "|" + p.getServerName() + "|" + p.getToolName() + "|" + p.getEffect());
            }
            out.sort(String::compareTo);
            return out;
        }

        private static String key(long userId, String server, String tool) {
            return userId + "\u0000" + server + "\u0000" + (tool == null ? "" : tool);
        }

        @Override public int upsert(long userId, String server, String tool, String effect, String definitionHash) {
            put(userId, server, tool, effect);
            rows.get(key(userId, server, tool)).setDefinitionHash(definitionHash);
            return 1;
        }

        @Override public McpUserPolicy find(long userId, String server, String tool) {
            return rows.get(key(userId, server, tool == null ? "" : tool));
        }

        @Override public int deleteOne(long userId, String server, String tool) {
            return rows.remove(key(userId, server, tool)) == null ? 0 : 1;
        }

        @Override public int deleteTools(long userId, String server) {
            int before = rows.size();
            rows.entrySet().removeIf(e -> e.getValue().getUserId() == userId
                    && server.equals(e.getValue().getServerName())
                    && !e.getValue().getToolName().isEmpty());
            return before - rows.size();
        }

        @Override public int deleteByUserAndServer(long userId, String server) {
            deleteByServerCalls++;
            if (failDeleteByServer) throw new IllegalStateException("模拟授权清理失败");
            int before = rows.size();
            rows.entrySet().removeIf(e -> e.getValue().getUserId() == userId
                    && McpServerAdminController.sameServerName(server, e.getValue().getServerName()));
            return before - rows.size();
        }
    }

    /** 只记录被清了哪个服务的内存会话授权；不碰真实状态。 */
    static final class SpyApprovals extends ApprovalService {
        final List<String> forgotten = new ArrayList<>();

        SpyApprovals() { super(1); }

        @Override public int forgetServer(long userId, String serverName) {
            forgotten.add(serverName);
            return 1;
        }
    }
}
