package com.example.jingjingai2.mcp;

import com.example.jingjingai2.permission.AllowAllGate;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多用户运行时隔离：同名服务、同名工具、各自的会话。
 *
 * <p>不拉进程：覆盖 {@code openSession(ownerId, name, spec)}，按「谁的服务」交回不同的假 session，
 * 这样能直接断言「这次调用到底进了哪个 session」。
 */
class McpUserIsolationTest {

    private static final long U1 = 7L;
    private static final long U2 = 9L;

    private McpClientManager manager;
    private final Map<String, AtomicInteger> seq = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        // 本类验证的是「谁的服务进了哪个 session」，与审批无关：
        // 显式安装放行一切的网关，不再依赖「没有网关 = 不需要授权」那条已关闭的 fail-open。
        AllowAllGate.install();
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        Map<String, McpServerSpec> yaml = new LinkedHashMap<>();
        yaml.put("shared", spec());
        props.setServers(yaml);

        manager = new McpClientManager(props) {
            @Override McpSession openSession(long ownerId, String serverName, McpServerSpec s) {
                int n = seq.computeIfAbsent(ownerId + "/" + serverName,
                        k -> new AtomicInteger()).incrementAndGet();
                String tag = (ownerId == 0L ? "sys" : "u" + ownerId) + "#" + n;
                return new FakeMcpSession(serverName, toolsOf(serverName))
                        .handler((name, args) -> McpCallResult.ok(tag + ":" + name));
            }
        };
        manager.start();                                  // 部署级 shared
        manager.addServer(U1, "echo", spec(), true);      // 用户 7 的 echo
        manager.addServer(U2, "echo", spec(), true);      // 用户 9 的同名 echo
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.stop();
        AllowAllGate.uninstall();
    }

    @Test
    @DisplayName("同名服务同名工具：各自的回调进各自的 session，互不覆盖")
    void sameNameToolsRouteToOwnSession() {
        ToolCallback[] u1 = manager.callbacks(U1);
        ToolCallback[] u2 = manager.callbacks(U2);

        assertEquals(List.of("mcp__shared__lookup", "mcp__echo__echo"), names(u1));
        assertEquals(names(u1), names(u2), "两边都应看到「共享 + 自己的 echo」");

        assertEquals("u7#1:echo", dispatch(u1, "mcp__echo__echo"));
        assertEquals("u9#1:echo", dispatch(u2, "mcp__echo__echo"));
        assertEquals("sys#1:lookup", dispatch(u1, "mcp__shared__lookup"));
        assertEquals("sys#1:lookup", dispatch(u2, "mcp__shared__lookup"));
    }

    @Test
    @DisplayName("看不到别人的服务：状态与工具清单都按登录用户过滤")
    void otherUsersServicesAreInvisible() {
        manager.addServer(U2, "beta", spec(), true);

        assertEquals(List.of("echo", "shared"), serverNames(manager.statuses(U1)));
        assertEquals(List.of("beta", "echo", "shared"), serverNames(manager.statuses(U2)));
        assertFalse(names(manager.callbacks(U1)).contains("mcp__beta__echo"),
                "别人的服务不能出现在我的工具清单里");
        assertNull(manager.findServer(U1, "beta"), "按名字也不能解析到别人的服务");
        assertNull(manager.findOwnServer(U1, "beta"));
    }

    @Test
    @DisplayName("部署级视图（userId=0）只含共享/部署服务，不含任何人的个人服务")
    void deploymentViewHasNoPersonalTools() {
        assertEquals(List.of("mcp__shared__lookup"), names(manager.callbacks(0L)));
        assertEquals(List.of("shared"), serverNames(manager.statuses(0L)));
    }

    @Test
    @DisplayName("删掉自己的服务不影响别人的同名服务；旧回调也不会落到新建的同名服务上")
    void removingOwnServiceKeepsOthersAndInvalidatesOldCallbacks() {
        ToolCallback oldEcho = find(manager.callbacks(U1), "mcp__echo__echo");
        assertNotNull(oldEcho);

        manager.removeServer(U1, "echo");

        assertFalse(names(manager.callbacks(U1)).contains("mcp__echo__echo"), "自己的没了");
        assertTrue(names(manager.callbacks(U2)).contains("mcp__echo__echo"), "别人的还在");
        assertEquals("u9#1:echo", dispatch(manager.callbacks(U2), "mcp__echo__echo"));

        // 旧回调持有的槽位已经不在 slots 里：必须失败，不能按名字去找新建的服务
        String stale = oldEcho.call("{}");
        assertTrue(stale.contains("服务已删除或重建"), stale);

        // 同名重建后，新回调进新 session，旧回调依旧失效
        manager.addServer(U1, "echo", spec(), true);
        assertEquals("u7#2:echo", dispatch(manager.callbacks(U1), "mcp__echo__echo"));
        assertTrue(oldEcho.call("{}").contains("服务已删除或重建"),
                "删除前拿到的审批/回调不能执行到重建后的服务");
    }

    @Test
    @DisplayName("个人服务不能与部署/共享服务重名（否则同一用户会看到两个同名工具）")
    void personalServiceCannotShadowSharedName() {
        McpClientManager.McpServerChange change = manager.addServer(U1, "shared", spec(), true);
        assertTrue(change.rejected(), String.valueOf(change.action));
    }

    @Test
    @DisplayName("断开自己的服务，别人的同名服务仍然可调用")
    void disconnectOwnServiceDoesNotAffectOthers() {
        manager.disconnectServer(U1, "echo");
        assertFalse(names(manager.callbacks(U1)).contains("mcp__echo__echo"));
        assertEquals("u9#1:echo", dispatch(manager.callbacks(U2), "mcp__echo__echo"));
    }

    // ===== helpers =====

    private static McpServerSpec spec() {
        McpServerSpec s = new McpServerSpec();
        s.setCommand("java");
        return s;
    }

    private static List<McpRemoteTool> toolsOf(String server) {
        String tool = "shared".equals(server) ? "lookup" : "echo";
        return List.of(new McpRemoteTool(tool, tool, "{\"type\":\"object\"}"));
    }

    private static String dispatch(ToolCallback[] cbs, String name) {
        return ToolDispatcher.dispatch(cbs, name, "{}");
    }

    private static List<String> names(ToolCallback[] cbs) {
        List<String> out = new ArrayList<>();
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null) out.add(cb.getToolDefinition().name());
        }
        return out;
    }

    private static List<String> serverNames(List<McpServerStatus> statuses) {
        List<String> out = new ArrayList<>();
        for (McpServerStatus s : statuses) out.add(s.getName());
        out.sort(String::compareTo);
        return out;
    }

    private static ToolCallback find(ToolCallback[] cbs, String name) {
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null && name.equals(cb.getToolDefinition().name())) {
                return cb;
            }
        }
        return null;
    }
}
