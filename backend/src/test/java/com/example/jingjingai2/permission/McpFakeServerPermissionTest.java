package com.example.jingjingai2.permission;

import com.example.jingjingai2.controller.McpPermissionController;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpListedTool;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.mcp.McpServerStatus;
import com.example.jingjingai2.tool.ToolDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真的拉起一个 node stdio MCP，走接口和审批，不经过假 session。
 */
class McpFakeServerPermissionTest {

    private static final long USER = 9L;
    private McpClientManager manager;
    private ApprovalService approvals;

    @AfterEach
    void stop() {
        ToolDispatcher.installGate(null);
        if (approvals != null) approvals.clear();
        if (manager != null) manager.stop();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("假 MCP：注解、未暴露、定义变更、审批、跨服务污染")
    void stdioServerCoversApiAndApproval() throws Exception {
        Path script = Path.of("src", "test", "resources", "mcp", "fake-mcp-server.js").toAbsolutePath();
        assertTrue(Files.isRegularFile(script), script.toString());
        MemoryMcpUserPolicyStore store = new MemoryMcpUserPolicyStore();
        McpRoundActivation activation = new McpRoundActivation();
        manager = start(script, "version-one");
        assertConnected("alpha");
        assertConnected("beta");

        McpListedTool read = manager.findListed("alpha", "read_item");
        assertNotNull(read);
        assertEquals(Boolean.TRUE, read.annotations.readOnlyHint);
        assertNull(read.annotations.destructiveHint);
        McpListedTool wipe = manager.findListed("alpha", "wipe_all");
        assertEquals(Boolean.TRUE, wipe.annotations.destructiveHint);
        assertNull(wipe.annotations.readOnlyHint);
        McpListedTool secret = manager.findListed("alpha", "secret_tool");
        assertFalse(secret.exposed);
        assertTrue(manager.findListed("alpha", "write_note").exposed);

        McpPermissionExplainer explainer = new McpPermissionExplainer(store, manager);
        McpPermissionController api = new McpPermissionController(manager, explainer, store, activation,
                new ApprovalService(3));
        HttpServletRequest user = asUser(USER);
        assertEquals(401, api.tools(null, asUser(null)).getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> catalog = api.tools("c1", user).getBody();
        Map<String, Object> secretRow = toolRow(catalog, "alpha", "secret_tool");
        assertEquals(Boolean.FALSE, secretRow.get("exposed"));
        assertEquals("ASK", secretRow.get("effect"));
        ResponseEntity<Map<String, Object>> blocked = api.update(Map.of(
                "server", "alpha", "tool", "secret_tool", "effect", "ALLOW"), user);
        assertEquals(400, blocked.getStatusCode().value());
        assertTrue(String.valueOf(blocked.getBody().get("message")).contains("未暴露"));

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "serverDefault", "ALLOW"), user).getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> afterAllow = api.tools("c1", user).getBody();
        Map<String, Object> deleteRow = toolRow(afterAllow, "alpha", "delete_item");
        assertEquals("ASK", deleteRow.get("effect"), String.valueOf(deleteRow.get("reason")));
        assertEquals(Boolean.TRUE, deleteRow.get("sessionGrant"));
        assertEquals(Boolean.TRUE, deleteRow.get("maybeForceAsk"));
        Map<String, Object> writeRow = toolRow(afterAllow, "alpha", "write_note");
        assertEquals("ALLOW", writeRow.get("effect"), String.valueOf(writeRow.get("reason")));

        approvals = new ApprovalService(3);
        ToolPermissionGate gate = new ToolPermissionGate(new PermissionPolicy(new PermissionProperties()),
                approvals, manager.properties(), explainer);
        ToolDispatcher.installGate(gate);
        ToolCallContext ctx = new ToolCallContext(USER, "c1", true, false, noop(),
                () -> manager.isConversationTainted("c1"));

        String wrote = call(ctx, "mcp__alpha__write_note", "{}");
        assertTrue(wrote.startsWith("ok:write_note"), wrote);

        AtomicReference<String> deniedCall = new AtomicReference<>();
        Thread t = new Thread(() -> deniedCall.set(call(ctx, "mcp__alpha__delete_item", "{}")));
        t.start();
        ApprovalService.Record rec = awaitPending();
        assertTrue(rec.canSessionGrant);
        assertTrue(rec.risk.contains("强制询问"), rec.risk);
        approvals.decide(rec.id, USER, "APPROVE_SESSION");
        t.join(3000);
        assertTrue(deniedCall.get().startsWith("ok:delete_item"), deniedCall.get());
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__alpha__delete_item"));

        manager.noteToolResult("c1", "mcp__alpha__read_item");
        AtomicReference<String> peek = new AtomicReference<>();
        Thread p = new Thread(() -> peek.set(call(ctx, "mcp__beta__peek", "{}")));
        p.start();
        ApprovalService.Record prec = awaitPending();
        assertTrue(prec.risk.contains("其他 MCP"), prec.risk);
        assertTrue(prec.canSessionGrant);
        approvals.decide(prec.id, USER, "REJECT");
        p.join(3000);
        assertTrue(peek.get().contains("用户拒绝"), peek.get());

        String same = call(ctx, "mcp__alpha__write_note", "{}");
        assertTrue(same.startsWith("ok:write_note"), same);

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "ALLOW"), user).getStatusCode().value());
        Map<String, Object> clearDefault = new LinkedHashMap<>();
        clearDefault.put("server", "alpha");
        clearDefault.put("serverDefault", null);
        assertEquals(200, api.update(clearDefault, user).getStatusCode().value());
        manager.stop();
        manager = start(script, "version-two");
        McpPermissionExplainer again = new McpPermissionExplainer(store, manager);
        McpPermissionController api2 = new McpPermissionController(manager, again, store, activation,
                new ApprovalService(3));
        Map<String, Object> changed = toolRow(api2.tools("c1", user).getBody(), "alpha", "read_item");
        assertEquals(Boolean.TRUE, changed.get("definitionChanged"));
        assertEquals("ALLOW", changed.get("userSetting"));
        assertEquals("ASK", changed.get("effect"), String.valueOf(changed.get("reason")));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("假 MCP：破坏性标注可被单独 ALLOW 盖过，名字和哈希变化不能被放行盖过")
    void destructiveHintAndHashOnStdio() throws Exception {
        Path script = Path.of("src", "test", "resources", "mcp", "fake-mcp-server.js").toAbsolutePath();
        MemoryMcpUserPolicyStore store = new MemoryMcpUserPolicyStore();
        manager = start(script, "version-one");
        assertConnected("alpha");
        McpPermissionExplainer explainer = new McpPermissionExplainer(store, manager);
        McpPermissionController api = new McpPermissionController(
                manager, explainer, store, new McpRoundActivation(), new ApprovalService(3));
        HttpServletRequest user = asUser(USER);

        Map<String, Object> fresh = toolRow(api.tools(null, user).getBody(), "alpha", "archive_box");
        assertEquals("ASK", fresh.get("effect"), String.valueOf(fresh.get("reason")));
        assertEquals(Boolean.TRUE, fresh.get("sessionGrant"));
        assertTrue(String.valueOf(fresh.get("reason")).contains("单独设置"), String.valueOf(fresh.get("reason")));

        assertEquals(200, api.update(Map.of("server", "alpha", "serverDefault", "ALLOW"), user).getStatusCode().value());
        Map<String, Object> serverAllow = toolRow(api.tools(null, user).getBody(), "alpha", "archive_box");
        assertEquals("ASK", serverAllow.get("effect"), String.valueOf(serverAllow.get("reason")));
        assertEquals(Boolean.TRUE, serverAllow.get("sessionGrant"));

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "archive_box", "effect", "ALLOW"), user).getStatusCode().value());
        Map<String, Object> toolAllow = toolRow(api.tools(null, user).getBody(), "alpha", "archive_box");
        assertEquals("ALLOW", toolAllow.get("effect"), String.valueOf(toolAllow.get("reason")));

        approvals = new ApprovalService(3);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, manager.properties(), explainer));
        ToolCallContext ctx = new ToolCallContext(USER, "c2", true, false, noop(), () -> false);
        String archived = call(ctx, "mcp__alpha__archive_box", "{}");
        assertTrue(archived.startsWith("ok:archive_box"), archived);

        for (String name : List.of("delete_item", "evaluate_note", "unsafe_ping")) {
            assertEquals(200, api.update(Map.of(
                    "server", "alpha", "tool", name, "effect", "ALLOW"), user).getStatusCode().value());
            McpPermissionExplainer.ExplainResult er = explainer.explain(USER, "alpha", name, "{}");
            assertEquals("ASK", er.effect.name(), name + " " + er.reason);
            assertTrue(er.sessionGrant, name);
        }

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "write_note", "effect", "DENY"), user).getStatusCode().value());
        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "ALLOW"), user).getStatusCode().value());
        manager.stop();
        manager = start(script, "version-two");
        McpPermissionExplainer again = new McpPermissionExplainer(store, manager);
        McpPermissionController api2 = new McpPermissionController(
                manager, again, store, new McpRoundActivation(), new ApprovalService(3));
        Map<String, Object> read = toolRow(api2.tools(null, user).getBody(), "alpha", "read_item");
        assertEquals(Boolean.TRUE, read.get("definitionChanged"));
        assertEquals("ASK", read.get("effect"), String.valueOf(read.get("reason")));
        Map<String, Object> denied = toolRow(api2.tools(null, user).getBody(), "alpha", "write_note");
        assertEquals(Boolean.TRUE, denied.get("definitionChanged"));
        assertEquals("DENY", denied.get("effect"), String.valueOf(denied.get("reason")));

        assertEquals(200, api2.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "ALLOW"), user).getStatusCode().value());
        Map<String, Object> reset = toolRow(api2.tools(null, user).getBody(), "alpha", "read_item");
        assertEquals(Boolean.FALSE, reset.get("definitionChanged"));
        assertEquals("ALLOW", reset.get("effect"), String.valueOf(reset.get("reason")));
    }

    private String call(ToolCallContext ctx, String name, String args) {
        try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx)) {
            return ToolDispatcher.dispatch(manager.callbacks(), name, args);
        }
    }

    private McpClientManager start(Path script, String readDesc) {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        props.setInitializeTimeoutMs(15_000);
        props.setRequestTimeoutMs(15_000);
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        McpServerSpec alpha = nodeSpec(script, "main", readDesc);
        // 默认暴露全部；这里用 disabled-tools 表达「未暴露」，enabled-tools 已废弃不再收窄。
        alpha.setDisabledTools(List.of("secret_tool", "big_text", "big_secret"));
        servers.put("alpha", alpha);
        servers.put("beta", nodeSpec(script, "other", readDesc));
        props.setServers(servers);
        McpClientManager started = new McpClientManager(props);
        started.start();
        return started;
    }

    private static McpServerSpec nodeSpec(Path script, String role, String readDesc) {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("node");
        spec.setArgs(List.of(script.toString()));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SERVER_ROLE", role);
        env.put("TOOL_DESC", readDesc);
        spec.setEnv(env);
        spec.setTrust("untrusted");
        spec.setConnectTimeoutSeconds(15);
        return spec;
    }

    private void assertConnected(String name) {
        for (McpServerStatus s : manager.statuses()) {
            if (name.equals(s.getName())) {
                assertEquals("connected", s.getState(), s.getLastError());
                return;
            }
        }
        fail("没有服务 " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolRow(Map<String, Object> body, String server, String tool) {
        List<Map<String, Object>> servers = (List<Map<String, Object>>) body.get("servers");
        for (Map<String, Object> s : servers) {
            if (!server.equals(s.get("name"))) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) s.get("tools")) {
                if (tool.equals(t.get("name"))) return t;
            }
        }
        fail("找不到 " + server + "/" + tool);
        return Map.of();
    }

    private ApprovalService.Record awaitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            for (ApprovalService.Record r : approvals.pending()) {
                if (r.status() == ApprovalService.Status.PENDING) return r;
            }
            Thread.sleep(20);
        }
        fail("没有等到审批");
        return null;
    }

    private static ToolCallContext.Events noop() {
        return new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
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
}
