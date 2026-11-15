package com.example.jingjingai2.permission;

import com.example.jingjingai2.controller.McpPermissionController;
import com.example.jingjingai2.mcp.FakeMcpServers;
import com.example.jingjingai2.mcp.FakeMcpSession;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpRemoteTool;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.mcp.McpToolAnnotations;
import com.example.jingjingai2.tool.ToolDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 待审批调用的复查，以及策略更新之后会话授权不能复活。
 * 假 session、内存策略，不连库、不拉真实 MCP。
 */
class PendingApprovalRecheckTest {

    private static final long USER = 7L;
    private static final long OTHER = 9L;

    private MemoryMcpUserPolicyStore store;
    private McpClientManager manager;
    private McpPermissionExplainer explainer;
    private ApprovalService approvals;
    private McpPermissionController api;
    private FakeMcpSession alpha;
    private List<McpRemoteTool> alphaTools;

    @BeforeEach
    void setUp() {
        store = new MemoryMcpUserPolicyStore();
        alphaTools = new ArrayList<>();
        alphaTools.add(tool("read_item", "v1", ann(true, null)));
        alphaTools.add(tool("write_note", "write", null));
        alphaTools.add(tool("delete_item", "delete one", null));
        alphaTools.add(tool("archive_box", "archive", ann(null, true)));
        alpha = new FakeMcpSession("alpha", alphaTools);
        FakeMcpSession beta = new FakeMcpSession("beta", List.of(tool("peek", "peek", null)));
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("alpha", spec());
        servers.put("beta", spec());
        props.setServers(servers);
        manager = FakeMcpServers.start(props, Map.of("alpha", alpha, "beta", beta));
        assertNotNull(manager.findListed("alpha", "read_item"));
        explainer = new McpPermissionExplainer(store, manager);
        approvals = new ApprovalService(2);
        api = new McpPermissionController(manager, explainer, store, new McpRoundActivation(), approvals);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties(), explainer));
    }

    @AfterEach
    void tearDown() {
        if (approvals != null) approvals.clear();
        ToolDispatcher.installGate(null);
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("A：等待中改成 DENY，再 APPROVE 或 APPROVE_SESSION 都不执行，也不留下会话授权")
    void denyDuringWaitBlocksApproveAndSessionGrant() throws Exception {
        assertDoesNotRun("APPROVE");
        assertDoesNotRun("APPROVE_SESSION");
    }

    @Test
    @DisplayName("A：等待中把服务默认改成 DENY，旧批准同样不能执行")
    void serverDenyDuringWaitBlocksApprove() throws Exception {
        int before = alpha.callCount();
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = startCall(USER, "c1", "mcp__alpha__read_item", "{}", out);
        awaitPending();
        store.setServerDefault(USER, "alpha", "DENY");
        approvals.decide(onlyPending().id, USER, "APPROVE");
        worker.join(4000);
        assertFalse(worker.isAlive());
        assertEquals(before, alpha.callCount(), "工具不能执行");
        assertTrue(out.get().contains("审批期间权限已变为拒绝"), out.get());
        assertTrue(out.get().contains("不能通过审批绕过"), out.get());
        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"));
    }

    @Test
    @DisplayName("复查用的是原始参数，不是卡片上打过星的展示值")
    void recheckSeesRawArguments() throws Exception {
        RecordingExplainer recording = new RecordingExplainer(store, manager);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties(), recording));
        String args = "{\"token\":\"sekrit\",\"note\":\"hello\"}";
        String result = decide(USER, "c1", "mcp__alpha__read_item", args, "APPROVE");
        assertTrue(result.startsWith("ok:"), result);
        assertEquals(List.of(args, args), recording.seen, recording.seen.toString());
        assertFalse(recording.seen.get(1).contains("***"), recording.seen.get(1));
    }

    @Test
    @DisplayName("B：策略改成 DENY 再改回 ASK 后，旧的本会话允许不会复活")
    void sessionGrantDoesNotReviveWhenPolicyReturnsToAsk() throws Exception {
        grant(USER, "c1", "mcp__alpha__read_item");
        grant(USER, "c2", "mcp__alpha__read_item");

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "DENY"), asUser(USER)).getStatusCode().value());
        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "ASK"), asUser(USER)).getStatusCode().value());

        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"));
        assertFalse(approvals.sessionGranted(USER, "c2", "mcp__alpha__read_item"));
        String asked = decide(USER, "c1", "mcp__alpha__read_item", "{}", "REJECT");
        assertTrue(asked.contains("用户拒绝了该操作"), asked);
    }

    @Test
    @DisplayName("策略更新只清这个用户的这个工具；别人、别的工具、别的服务都还在")
    void policyUpdateDoesNotCrossUserToolOrServer() throws Exception {
        grant(USER, "c1", "mcp__alpha__read_item");
        grant(USER, "c1", "mcp__alpha__write_note");
        grant(USER, "c1", "mcp__beta__peek");
        grant(OTHER, "c9", "mcp__alpha__read_item");

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "DENY"), asUser(USER)).getStatusCode().value());

        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"));
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__alpha__write_note"), "同服务的其他工具不能清");
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__beta__peek"), "其他服务不能清");
        assertTrue(approvals.sessionGranted(OTHER, "c9", "mcp__alpha__read_item"), "其他用户的同名服务不能清");
        assertTrue(call(OTHER, "c9", "mcp__alpha__read_item").startsWith("ok:"));

        assertEquals(200, api.update(Map.of(
                "server", "alpha", "serverDefault", "DENY"), asUser(USER)).getStatusCode().value());
        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__write_note"),
                "服务级更新会清掉这个用户在这个服务下的授权");
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__beta__peek"));
        assertTrue(approvals.sessionGranted(OTHER, "c9", "mcp__alpha__read_item"));
        assertTrue(call(USER, "c1", "mcp__beta__peek").startsWith("ok:"));
        assertTrue(call(OTHER, "c9", "mcp__alpha__read_item").startsWith("ok:"));
    }

    @Test
    @DisplayName("策略没写成功时不清理授权")
    void failedPolicyUpdateKeepsGrant() throws Exception {
        grant(USER, "c1", "mcp__alpha__read_item");
        ResponseEntity<Map<String, Object>> missing = api.update(Map.of(
                "server", "alpha", "tool", "missing", "effect", "DENY"), asUser(USER));
        ResponseEntity<Map<String, Object>> bad = api.update(Map.of(
                "server", "alpha", "tool", "read_item", "effect", "NOPE"), asUser(USER));
        assertEquals(400, missing.getStatusCode().value());
        assertEquals(400, bad.getStatusCode().value());
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"));
        assertTrue(call(USER, "c1", "mcp__alpha__read_item").startsWith("ok:"));
    }

    @Test
    @DisplayName("等待期间定义才变更的旧批准不能执行；弹卡时已经变更的仍可批准一次")
    void definitionChangeDuringWaitVoidsOldApproval() throws Exception {
        String hash = manager.findListed("alpha", "read_item").definitionHash();
        store.setTool(USER, "alpha", "read_item", "ASK", hash);
        int before = alpha.callCount();
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = startCall(USER, "c1", "mcp__alpha__read_item", "{}", out);
        ApprovalService.Record rec = awaitPending();
        assertFalse(rec.definitionChangedAtCard);
        alphaTools.set(0, tool("read_item", "v2", ann(true, null)));
        assertFalse(manager.refreshServer("alpha").rejected());
        assertTrue(explainer.explain(USER, "alpha", "read_item", "{}").definitionChanged);
        approvals.decide(rec.id, USER, "APPROVE_SESSION");
        worker.join(4000);
        assertFalse(worker.isAlive());
        assertEquals(before, alpha.callCount());
        assertEquals(ToolPermissionGate.REVOKED_DEFINITION, out.get());
        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"));

        store.setTool(USER, "alpha", "write_note", "ALLOW", "not-the-current-hash");
        int notes = alpha.callCount();
        String confirmed = decide(USER, "c1", "mcp__alpha__write_note", "{}", "APPROVE_SESSION");
        assertTrue(confirmed.startsWith("ok:"), confirmed);
        assertEquals(notes + 1, alpha.callCount(), "弹卡时就已经变更的，这次批准仍然执行一次");
        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__write_note"),
                "定义变更没有本会话允许");
    }

    @Test
    @DisplayName("普通 ASK 批准后执行一次；破坏性标注仍可本会话允许")
    void ordinaryAskAndDestructiveSessionGrantStillWork() throws Exception {
        int before = alpha.callCount();
        String once = decide(USER, "c1", "mcp__alpha__read_item", "{}", "APPROVE");
        assertTrue(once.startsWith("ok:"), once);
        assertEquals(before + 1, alpha.callCount());
        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"));

        String granted = decide(USER, "c1", "mcp__alpha__archive_box", "{}", "APPROVE_SESSION");
        assertTrue(granted.startsWith("ok:"), granted);
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__alpha__archive_box"));
        assertTrue(call(USER, "c1", "mcp__alpha__archive_box").startsWith("ok:"));
        assertTrue(approvals.pending().isEmpty());
    }

    @Test
    @DisplayName("危险名称本会话允许后不再问；URL 和 DENY 不能被批准绕过")
    void forceAskUrlAndDenyStillHold() throws Exception {
        String named = decide(USER, "c1", "mcp__alpha__delete_item", "{}", "APPROVE_SESSION");
        assertTrue(named.startsWith("ok:"), named);
        assertTrue(approvals.sessionGranted(USER, "c1", "mcp__alpha__delete_item"));
        assertTrue(call(USER, "c1", "mcp__alpha__delete_item").startsWith("ok:"));
        assertTrue(approvals.pending().isEmpty());

        int before = alpha.callCount();
        String url = call(USER, "c1", "mcp__alpha__read_item", "{\"url\":\"http://127.0.0.1/\"}");
        assertTrue(url.contains("地址不被允许"), url);
        assertEquals(before, alpha.callCount());

        store.setTool(USER, "alpha", "write_note", "DENY",
                manager.findListed("alpha", "write_note").definitionHash());
        String denied = call(USER, "c1", "mcp__alpha__write_note");
        assertTrue(denied.contains("已拒绝"), denied);
        assertFalse(denied.contains("审批期间"), denied);
        assertEquals(before, alpha.callCount());
    }

    private void assertDoesNotRun(String decision) throws Exception {
        int before = alpha.callCount();
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = startCall(USER, "c1", "mcp__alpha__read_item", "{}", out);
        awaitPending();
        store.setTool(USER, "alpha", "read_item", "DENY",
                manager.findListed("alpha", "read_item").definitionHash());
        approvals.decide(onlyPending().id, USER, decision);
        worker.join(4000);
        assertFalse(worker.isAlive());
        assertEquals(before, alpha.callCount(), decision);
        assertTrue(out.get().contains("审批期间权限已变为拒绝"), out.get());
        assertTrue(out.get().contains("不能通过审批绕过"), out.get());
        assertFalse(approvals.sessionGranted(USER, "c1", "mcp__alpha__read_item"), decision);
        store.resetTools(USER, "alpha");
    }

    private void grant(long userId, String conversation, String exposed) throws Exception {
        String result = decide(userId, conversation, exposed, "{}", "APPROVE_SESSION");
        assertTrue(result.startsWith("ok:"), result);
        assertTrue(approvals.sessionGranted(userId, conversation, exposed), exposed);
    }

    private String decide(long userId, String conversation, String exposed, String args, String decision)
            throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = startCall(userId, conversation, exposed, args, out);
        ApprovalService.Record rec = awaitPending();
        assertEquals(200, approvals.decide(rec.id, userId, decision).http);
        worker.join(4000);
        assertFalse(worker.isAlive(), exposed);
        return out.get();
    }

    private String call(long userId, String conversation, String exposed) {
        return call(userId, conversation, exposed, "{}");
    }

    private String call(long userId, String conversation, String exposed, String args) {
        try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx(userId, conversation))) {
            return ToolDispatcher.dispatch(manager.callbacks(), exposed, args);
        }
    }

    private Thread startCall(long userId, String conversation, String exposed, String args,
                             AtomicReference<String> out) {
        Thread worker = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx(userId, conversation))) {
                out.set(ToolDispatcher.dispatch(manager.callbacks(), exposed, args));
            }
        });
        worker.start();
        return worker;
    }

    private ApprovalService.Record awaitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (ApprovalService.Record r : approvals.pending()) {
                if (r.status() == ApprovalService.Status.PENDING) return r;
            }
            Thread.sleep(20);
        }
        fail("没有等到审批");
        return null;
    }

    private ApprovalService.Record onlyPending() {
        ApprovalService.Record found = null;
        for (ApprovalService.Record r : approvals.pending()) {
            if (r.status() != ApprovalService.Status.PENDING) continue;
            assertNull(found, "同时有多条待审批");
            found = r;
        }
        assertNotNull(found);
        return found;
    }

    private static ToolCallContext ctx(long userId, String conversation) {
        return new ToolCallContext(userId, conversation, true, false, new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        }, () -> false);
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

    private static McpServerSpec spec() {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        spec.setTrust("untrusted");
        return spec;
    }

    private static McpRemoteTool tool(String name, String description, McpToolAnnotations annotations) {
        return new McpRemoteTool(name, description, "{\"type\":\"object\"}", annotations);
    }

    private static McpToolAnnotations ann(Boolean readOnly, Boolean destructive) {
        return new McpToolAnnotations(readOnly, destructive, null, null);
    }

    private static final class RecordingExplainer extends McpPermissionExplainer {
        final List<String> seen = new ArrayList<>();

        RecordingExplainer(McpUserPolicyStore store, McpClientManager manager) {
            super(store, manager);
        }

        @Override
        public ExplainResult explain(Long userId, String server, String tool, String arguments) {
            seen.add(arguments);
            return super.explain(userId, server, tool, arguments);
        }
    }
}
