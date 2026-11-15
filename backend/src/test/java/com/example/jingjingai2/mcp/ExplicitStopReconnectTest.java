package com.example.jingjingai2.mcp;

import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.McpPermissionExplainer;
import com.example.jingjingai2.permission.MemoryMcpUserPolicyStore;
import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.PermissionProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolPermissionGate;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户主动断开或停用之后，旧回调不能把服务再连上。
 * 意外断线和显式重连仍按原来的路径。不拉进程。
 */
class ExplicitStopReconnectTest {

    private static final long USER = 7L;

    private McpClientManager manager;
    private final AtomicInteger opens = new AtomicInteger();
    private final List<FakeMcpSession> opened = new ArrayList<>();
    private final AtomicReference<McpServerSpec> lastOpenedSpec = new AtomicReference<>();
    private ApprovalService approvals;

    @BeforeEach
    void setUp() {
        opens.set(0);
        opened.clear();
        lastOpenedSpec.set(null);
        ToolDispatcher.installGate(null);
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        manager = new McpClientManager(props) {
            @Override
            McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
                opens.incrementAndGet();
                lastOpenedSpec.set(spec);
                FakeMcpSession session = new FakeMcpSession(serverName, List.of(
                        new McpRemoteTool("echo", "echo", "{\"type\":\"object\"}")));
                opened.add(session);
                return session;
            }
        };
        manager.start();
        assertFalse(manager.addServer(0L, "alpha", spec(), true).rejected());
        assertEquals(1, opens.get(), "前置：只连接了一次");
    }

    @AfterEach
    void tearDown() {
        if (approvals != null) approvals.clear();
        ToolDispatcher.installGate(null);
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("C：主动断开后，旧审批即使批准也不会 connect，工具也不执行")
    void disconnectThenApproveDoesNotReconnect() throws Exception {
        String result = approveAfterDisconnect("APPROVE");
        assertEquals(1, opens.get(), "不能再调用连接");
        assertEquals(0, opened.get(0).callCount());
        assertTrue(result.contains("已断开"), result);
        assertFalse(result.startsWith("ok:"), result);
    }

    @Test
    @DisplayName("C：主动停用后，旧的本会话允许同样不能把服务拉起来")
    void disableThenApproveSessionDoesNotReconnect() throws Exception {
        // 停用在运行时就是 disconnectServer：McpServerAdminController.setEnabled(false) 走的是它。
        String result = approveAfterDisconnect("APPROVE_SESSION");
        assertEquals(1, opens.get(), "停用后不能自动重连");
        assertEquals(0, opened.get(0).callCount());
        assertTrue(result.contains("已断开"), result);
        assertFalse(result.startsWith("ok:"), result);
    }

    @Test
    @DisplayName("停用后的普通工具调用也不能把服务恢复运行")
    void plainCallAfterDisconnectDoesNotReconnect() {
        ToolCallback cb = find(manager.callbacks(), "mcp__alpha__echo");
        manager.disconnectServer("alpha");
        assertEquals("disconnected", stateOf("alpha"));
        String result = cb.call("{}");
        assertEquals(1, opens.get());
        assertEquals(0, opened.get(0).callCount());
        assertTrue(result.contains("已断开"), result);
    }

    @Test
    @DisplayName("D：会话意外死亡后，原有重连仍然发生")
    void deadSessionStillReconnects() {
        ToolCallback cb = find(manager.callbacks(), "mcp__alpha__echo");
        opened.get(0).die();
        String result = cb.call("{}");
        assertEquals(2, opens.get(), "意外断线应重连");
        assertTrue(result.startsWith("ok:echo"), result);
    }

    @Test
    @DisplayName("D：远端明确返回失败时，即使文本像进程已退出，也不重发")
    void deadLookingResultDoesNotRetry() {
        ToolCallback cb = find(manager.callbacks(), "mcp__alpha__echo");
        opened.get(0).handler((name, args) -> McpCallResult.failedAtTool("进程已退出"));
        String result = cb.call("{}");
        assertEquals(1, opens.get(), "明确的失败结果不该触发重连");
        assertEquals(1, opened.get(0).callCount(), "不该把同一次调用再发一遍");
        assertTrue(result.startsWith(McpToolCallback.ERROR_PREFIX), result);
        assertTrue(result.contains("进程已退出"), result);
    }

    @Test
    @DisplayName("D：用户之后显式连接，正常调用可以执行")
    void explicitConnectAfterDisconnectWorks() {
        manager.disconnectServer("alpha");
        assertEquals(1, opens.get());
        McpClientManager.McpServerChange change = manager.connectServer("alpha");
        assertFalse(change.rejected(), String.valueOf(change.lastError));
        assertEquals(2, opens.get());
        ToolCallback cb = find(manager.callbacks(), "mcp__alpha__echo");
        assertNotNull(cb);
        assertTrue(cb.call("{}").startsWith("ok:echo"));
        assertEquals(2, opens.get(), "已经连上的调用不应再开一次进程");
    }

    @Test
    @DisplayName("E：服务删掉重建后，旧回调打不到新槽位，也不会为此再连接")
    void replacedServerOldCallbackDoesNotHitNewSlot() {
        ToolCallback old = find(manager.callbacks(), "mcp__alpha__echo");
        assertFalse(manager.removeServer("alpha").rejected());
        assertFalse(manager.addServer(0L, "alpha", spec(), true).rejected());
        assertEquals(2, opens.get());
        String stale = old.call("{}");
        assertTrue(stale.contains("服务已删除或重建"), stale);
        assertEquals(2, opens.get(), "旧回调不能再发起连接");
        assertEquals(0, opened.get(0).callCount());
        assertTrue(find(manager.callbacks(), "mcp__alpha__echo").call("{}").startsWith("ok:echo"));
    }

    @Test
    @DisplayName("改 args 后同一 Slot 重连：旧回调继续可用，不再报服务已重建")
    void applySpecAndReconnectKeepsOldCallbackWorking() {
        ToolCallback old = find(manager.callbacks(), "mcp__alpha__echo");
        McpSession before = manager.sessionOf("alpha");
        assertNotNull(before);

        McpServerSpec next = spec();
        next.setArgs(java.util.List.of("-y", "@playwright/mcp@0.0.83"));
        McpClientManager.McpServerChange change = manager.applySpecAndReconnect("alpha", next);
        assertFalse(change.rejected(), String.valueOf(change.lastError));
        assertEquals(2, opens.get(), "应关掉旧会话再开一次");
        assertNotSame(before, manager.sessionOf("alpha"), "会话要换新，但回调仍握着同一 Slot");
        assertEquals(java.util.List.of("-y", "@playwright/mcp@0.0.83"), lastOpenedSpec.get().getArgs());

        String result = old.call("{}");
        assertTrue(result.startsWith("ok:echo"), result);
        assertFalse(result.contains("服务已删除或重建"), result);
        assertEquals(2, opens.get(), "旧回调走已重连的会话，不应再开进程");
        assertEquals(1, opened.get(1).callCount(), "应打到新会话");
    }

    @Test
    @DisplayName("replaceServer 仍换 Slot：旧回调继续报服务已重建")
    void replaceServerStillInvalidatesOldCallback() {
        ToolCallback old = find(manager.callbacks(), "mcp__alpha__echo");
        McpServerSpec next = spec();
        next.setCommand("node");
        assertFalse(manager.replaceServer("alpha", next).rejected());
        assertEquals(2, opens.get());
        String stale = old.call("{}");
        assertTrue(stale.contains("服务已删除或重建"), stale);
        assertEquals(2, opens.get(), "旧回调不能再发起连接");
        assertTrue(find(manager.callbacks(), "mcp__alpha__echo").call("{}").startsWith("ok:echo"));
    }

    private String approveAfterDisconnect(String decision) throws Exception {
        McpPermissionExplainer explainer = new McpPermissionExplainer(new MemoryMcpUserPolicyStore(), manager);
        approvals = new ApprovalService(3);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties(), explainer));
        ToolCallback cb = find(manager.callbacks(), "mcp__alpha__echo");
        AtomicReference<String> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(new ToolCallContext(
                    USER, "c1", true, false, noop(), () -> false))) {
                result.set(ToolDispatcher.dispatch(new ToolCallback[]{cb}, "mcp__alpha__echo", "{}"));
            }
        });
        worker.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals(0, opened.get(0).callCount());
        manager.disconnectServer("alpha");
        assertEquals("disconnected", stateOf("alpha"));
        assertEquals(200, approvals.decide(rec.id, USER, decision).http);
        worker.join(4000);
        assertFalse(worker.isAlive());
        return result.get();
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

    private String stateOf(String name) {
        for (McpServerStatus status : manager.statuses()) {
            if (name.equals(status.getName())) return status.getState();
        }
        return null;
    }

    private static McpServerSpec spec() {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        return spec;
    }

    private static ToolCallback find(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb != null && cb.getToolDefinition() != null && name.equals(cb.getToolDefinition().name())) {
                return cb;
            }
        }
        return null;
    }

    private static ToolCallContext.Events noop() {
        return new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
    }
}
