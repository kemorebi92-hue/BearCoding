package com.example.jingjingai2.permission;

import com.example.jingjingai2.controller.McpPermissionController;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.tool.ToolDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Playwright，白名单清空。默认跳过，MCP_LIVE_PLAYWRIGHT=true 才跑。
 * 不改本机 mcp-run.yml。
 */
class PlaywrightPermissionLiveTest {

    private static final long USER = 11L;
    private McpClientManager manager;
    private ApprovalService approvals;

    @AfterEach
    void stop() {
        ToolDispatcher.installGate(null);
        if (approvals != null) approvals.clear();
        if (manager != null) manager.stop();
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    @DisplayName("Playwright：单独 ALLOW 盖过破坏性标注，名字和回环盖不过")
    void emptyWhitelistAndForceAsk() throws Exception {
        Assumptions.assumeTrue(liveEnabled(),
                "跳过：设置 MCP_LIVE_PLAYWRIGHT=true 才连真实 Playwright");
        String browser = detectBrowser();
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        props.setInitializeTimeoutMs(60_000);
        props.setRequestTimeoutMs(60_000);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("npx");
        Path out = Path.of("target", "playwright-mcp-output").toAbsolutePath();
        spec.setArgs(List.of(
                "-y", "@playwright/mcp@0.0.83",
                "--headless", "--isolated",
                "--browser", browser,
                "--output-dir", out.toString()));
        spec.setEnv(playwrightEnv());
        spec.setConnectTimeoutSeconds(60);
        spec.setMaxResultChars(6000);
        spec.setTrust("untrusted");
        spec.setDescription("live permission");
        props.setServers(Map.of("playwright", spec));

        manager = new McpClientManager(props);
        manager.start();
        assertEquals("connected", manager.statuses().get(0).getState(),
                manager.statuses().get(0).getLastError());

        MemoryMcpUserPolicyStore store = new MemoryMcpUserPolicyStore();
        McpPermissionExplainer explainer = new McpPermissionExplainer(store, manager);
        McpPermissionController api = new McpPermissionController(
                manager, explainer, store, new McpRoundActivation(), new ApprovalService(30));
        HttpServletRequest user = asUser(USER);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = api.tools(null, user).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) body.get("servers")).get(0).get("tools");
        assertEquals(25, tools.size(), tools.stream().map(t -> String.valueOf(t.get("name"))).toList().toString());
        System.out.println("MAYBE_FORCE " + tools.stream()
                .filter(t -> Boolean.TRUE.equals(t.get("maybeForceAsk")))
                .map(t -> String.valueOf(t.get("name"))).toList());
        System.out.println("DESTRUCTIVE " + tools.stream().filter(t -> {
            Object ann = t.get("annotations");
            return ann instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("destructiveHint"));
        }).map(t -> String.valueOf(t.get("name"))).toList());
        for (Map<String, Object> t : tools) {
            assertEquals(Boolean.TRUE, t.get("exposed"), String.valueOf(t.get("name")));
        }
        Map<String, Object> evaluate = row(tools, "browser_evaluate");
        Map<String, Object> unsafe = row(tools, "browser_run_code_unsafe");
        assertEquals(Boolean.TRUE, evaluate.get("maybeForceAsk"));
        assertEquals(Boolean.TRUE, unsafe.get("maybeForceAsk"));

        assertEquals(200, api.update(Map.of("server", "playwright", "serverDefault", "ALLOW"), user).getStatusCode().value());
        McpPermissionExplainer.ExplainResult clickDefault = explainer.explain(USER, "playwright", "browser_click", "{}");
        assertEquals("ASK", clickDefault.effect.name(), clickDefault.reason);
        assertTrue(clickDefault.sessionGrant, clickDefault.reason);
        assertTrue(clickDefault.reason.contains("单独设置"), clickDefault.reason);

        approvals = new ApprovalService(5);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, props, explainer));
        ToolCallContext ctx = new ToolCallContext(USER, "live", true, false, noop(), () -> false);
        assertDestructiveAsk(ctx, "mcp__playwright__browser_click");

        for (String name : List.of("browser_navigate", "browser_snapshot", "browser_press_key")) {
            assertEquals(200, api.update(Map.of(
                    "server", "playwright", "tool", name, "effect", "ALLOW"), user).getStatusCode().value());
        }
        String page = call(ctx, "mcp__playwright__browser_navigate", "{\"url\":\"https://example.com/\"}");
        assertTrue(page.contains("Example Domain"), page);
        assertFalse(page.contains("地址不被允许"), page);
        assertFalse(page.contains("用户拒绝"), page);
        assertFalse(page.contains("请使用流式聊天"), page);

        String snap = call(ctx, "mcp__playwright__browser_snapshot", "{}");
        assertFalse(snap.contains("用户拒绝"), snap);
        assertFalse(snap.contains("请使用流式聊天"), snap);
        assertFalse(snap.contains("超时"), snap);
        String key = call(ctx, "mcp__playwright__browser_press_key", "{\"key\":\"Escape\"}");
        assertFalse(key.contains("用户拒绝"), key);
        assertFalse(key.contains("请使用流式聊天"), key);
        assertFalse(key.contains("超时"), key);

        for (String name : List.of("browser_evaluate", "browser_run_code_unsafe")) {
            assertEquals(200, api.update(Map.of(
                    "server", "playwright", "tool", name, "effect", "ALLOW"), user).getStatusCode().value());
        }
        assertAsksWithoutSessionGrant(ctx, "mcp__playwright__browser_evaluate");
        assertAsksWithoutSessionGrant(ctx, "mcp__playwright__browser_run_code_unsafe");

        McpPermissionExplainer.ExplainResult loop = explainer.explain(
                USER, "playwright", "browser_navigate", "{\"url\":\"http://127.0.0.1/\"}");
        assertEquals("DENY", loop.effect.name(), loop.reason);
        String denied = call(ctx, "mcp__playwright__browser_navigate", "{\"url\":\"http://127.0.0.1/\"}");
        assertTrue(denied.contains("地址不被允许"), denied);
        assertTrue(denied.contains("不能通过审批绕过") || denied.contains("不能覆盖"), denied);
    }

    private void assertDestructiveAsk(ToolCallContext ctx, String exposed) throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(call(ctx, exposed, "{}")));
        t.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals(exposed, rec.toolName);
        assertTrue(rec.canSessionGrant, rec.risk);
        assertTrue(rec.risk.contains("单独设置"), rec.risk);
        approvals.decide(rec.id, USER, "REJECT");
        t.join(5000);
        assertTrue(out.get().contains("用户拒绝"), out.get());
    }

    private void assertAsksWithoutSessionGrant(ToolCallContext ctx, String exposed) throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(call(ctx, exposed, "{}")));
        t.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals(exposed, rec.toolName);
        assertTrue(rec.canSessionGrant, rec.risk);
        assertTrue(rec.risk.contains("强制询问"), rec.risk);
        approvals.decide(rec.id, USER, "REJECT");
        t.join(5000);
        assertTrue(out.get().contains("用户拒绝"), out.get());
        assertFalse(approvals.sessionGranted(USER, "live", exposed));
    }

    private String call(ToolCallContext ctx, String name, String args) {
        try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx)) {
            return ToolDispatcher.dispatch(manager.callbacks(), name, args);
        }
    }

    private ApprovalService.Record awaitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
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

    private static boolean liveEnabled() {
        String p = System.getProperty("MCP_LIVE_PLAYWRIGHT");
        if (p != null && !p.isBlank()) return Boolean.parseBoolean(p.trim());
        String e = System.getenv("MCP_LIVE_PLAYWRIGHT");
        return e != null && Boolean.parseBoolean(e.trim());
    }

    private static String detectBrowser() {
        for (String path : new String[]{
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
        }) {
            if (new File(path).isFile()) return "chrome";
        }
        for (String path : new String[]{
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
        }) {
            if (new File(path).isFile()) return "msedge";
        }
        throw new IllegalStateException("未找到 Chrome 或 Edge");
    }

    private static Map<String, String> playwrightEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : new String[]{
                "USERPROFILE", "APPDATA", "LOCALAPPDATA",
                "HOMEDRIVE", "HOMEPATH", "USERNAME",
                "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"
        }) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) env.put(key, v);
        }
        return env;
    }

    private static Map<String, Object> row(List<Map<String, Object>> tools, String name) {
        for (Map<String, Object> t : tools) {
            if (name.equals(t.get("name"))) return t;
        }
        fail("没有工具 " + name);
        return Map.of();
    }
}
