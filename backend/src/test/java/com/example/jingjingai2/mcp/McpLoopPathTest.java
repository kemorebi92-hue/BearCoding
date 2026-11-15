package com.example.jingjingai2.mcp;

import com.example.jingjingai2.permission.AllowAllGate;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.utils.PlanNudgePolicy;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 不构造 ChatController：走和主循环相同的 Guard → Dispatcher → Callback 路径。
 *
 * <p>本类验证的是「清单裁剪 + 派发」这条管道，不是审批本身，所以显式安装放行一切的网关
 * （{@link AllowAllGate}）；真实审批行为由 permission 包下的测试覆盖。
 */
class McpLoopPathTest {

    @BeforeEach
    void installGate() {
        AllowAllGate.install();
    }

    @AfterEach
    void uninstallGate() {
        AllowAllGate.uninstall();
    }

    @Test
    @DisplayName("下发了的 mcp__ 能执行；没下发的（幻觉名）被 ToolGuard 拒绝")
    void guardThenDispatch() {
        FakeMcpSession session = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}")))
                .handler((n, a) -> McpCallResult.ok("pong"));
        ToolCallback mcp = new McpToolCallback(session, "mcp__echo__echo",
                session.listTools().get(0), 8000);
        ToolCallback[] round = McpToolBridge.concat(
                new ToolCallback[]{McpTestProcess.stub("readFile")},
                new ToolCallback[]{mcp});

        Set<String> allowed = names(round);
        assertNull(ToolGuard.rejectReason("mcp__echo__echo", allowed));
        assertEquals("pong", ToolDispatcher.dispatch(round, "mcp__echo__echo", "{}"));

        String reject = ToolGuard.rejectReason("mcp__github__create_issue", allowed);
        assertNotNull(reject);
        assertTrue(reject.contains("mcp__github__create_issue"));
        // 即使 Dispatcher 里碰巧有同名，Guard 先拦 —— 本测试清单里没有这个名字
        assertNull(ToolDispatcher.find(round, "mcp__github__create_issue"));
    }

    @Test
    @DisplayName("HARD stripWriteTools：mcp__ 从本轮清单消失，执行层也拒")
    void stripRemovesMcp() {
        ToolCallback[] all = McpToolBridge.concat(
                new ToolCallback[]{McpTestProcess.stub("readFile"), McpTestProcess.stub("writeFile")},
                new ToolCallback[]{McpTestProcess.stub("mcp__echo__echo")});
        ToolCallback[] kept = strip(all);
        Set<String> allowed = names(kept);
        assertTrue(allowed.contains("readFile"));
        assertFalse(allowed.contains("writeFile"));
        assertFalse(allowed.contains("mcp__echo__echo"));
        assertNotNull(ToolGuard.rejectReason("mcp__echo__echo", allowed));
    }

    @Test
    @DisplayName("deferred：未激活的 mcp__ 被 Guard 拒绝并提示 mcpToolSearch；激活后可执行")
    void deferredUnactivatedRejectedThenActivated() {
        FakeMcpSession session = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}")))
                .handler((n, a) -> McpCallResult.ok("pong"));
        ToolCallback mcp = new McpToolCallback(session, "mcp__echo__echo",
                session.listTools().get(0), 8000);
        ToolCallback[] full = McpToolBridge.concat(
                new ToolCallback[]{McpTestProcess.stub("readFile")},
                new ToolCallback[]{mcp});
        ToolCallback search = new McpToolSearchCallback(new ToolCallback[]{mcp},
                java.util.Map.of("mcp__echo__echo", new String[]{"echo", "echo"}), 5);

        ToolCallback[] round0 = McpRoundSelector.select(full, search, List.of(),
                McpRoundSelector.Mode.DEFERRED);
        Set<String> allowed0 = names(round0);
        assertTrue(allowed0.contains("mcpToolSearch"));
        assertFalse(allowed0.contains("mcp__echo__echo"));
        String reject = ToolGuard.rejectReason("mcp__echo__echo", allowed0);
        assertNotNull(reject);
        assertTrue(reject.contains("mcpToolSearch"));
        assertEquals("pong", ToolDispatcher.dispatch(full, "mcp__echo__echo", "{}"),
                "catalog 里仍能派发，但 Guard 必须先拦");
        assertNotNull(ToolGuard.rejectReason("mcp__echo__echo", allowed0));

        ToolCallback[] round1 = McpRoundSelector.select(full, search,
                List.of("mcp__echo__echo"), McpRoundSelector.Mode.DEFERRED);
        assertNull(ToolGuard.rejectReason("mcp__echo__echo", names(round1)));
        assertEquals("pong", ToolDispatcher.dispatch(round1, "mcp__echo__echo", "{}"));
    }

    private static ToolCallback[] strip(ToolCallback[] callbacks) {
        java.util.List<ToolCallback> kept = new java.util.ArrayList<>();
        for (ToolCallback cb : callbacks) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            if (PlanNudgePolicy.isStrippableTool(cb.getToolDefinition().name())) continue;
            kept.add(cb);
        }
        return kept.toArray(new ToolCallback[0]);
    }

    private static Set<String> names(ToolCallback[] cbs) {
        Set<String> s = new LinkedHashSet<>();
        for (ToolCallback cb : cbs) s.add(cb.getToolDefinition().name());
        return s;
    }
}
