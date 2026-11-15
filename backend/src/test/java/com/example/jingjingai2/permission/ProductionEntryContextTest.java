package com.example.jingjingai2.permission;

import com.example.jingjingai2.controller.ChatController;
import com.example.jingjingai2.mcp.FakeMcpServers;
import com.example.jingjingai2.mcp.FakeMcpSession;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpRemoteTool;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三个生产入口在调用 dispatch 时的上下文。
 * 非流式 / 流式走 ChatController.openPermissionScope；子代理走 SubagentRunner.run。
 */
class ProductionEntryContextTest {

    private McpClientManager manager;
    private ApprovalService approvals;

    @AfterEach
    void tearDown() {
        ToolDispatcher.installGate(null);
        if (approvals != null) approvals.clear();
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("非流式入口：DENY 不下发；ASK 返回请使用流式聊天且不执行；上下文里有 userId")
    void nonStreamingDeniesAndAsksWithoutRunning() throws Exception {
        Harness h = harness();
        ToolCallback[] full = manager.callbacks();
        ToolCallback[] round = (ToolCallback[]) h.round.invoke(
                h.controller, full, List.of(new UserMessage("hi")), false, "conv", 7L, null);
        assertFalse(names(round).contains("mcp__alpha__write_note"), names(round).toString());
        assertTrue(names(round).contains("mcp__alpha__read_item"), names(round).toString());

        try (ToolCallContext.Scope ignored = h.open(7L, false)) {
            assertNotNull(ToolCallContext.current());
            assertEquals(7L, ToolCallContext.current().userId);
            assertNull(ToolCallContext.current().events);
            String denied = ToolDispatcher.dispatch(full, "mcp__alpha__write_note", "{}");
            assertTrue(denied.contains("已拒绝"), denied);
            assertFalse(denied.startsWith("ok:"), denied);
            String asked = ToolDispatcher.dispatch(full, "mcp__alpha__read_item", "{}");
            assertTrue(asked.contains("请使用流式聊天"), asked);
            assertFalse(asked.startsWith("ok:"), asked);
        }
    }

    @Test
    @DisplayName("流式入口打开的上下文带 userId，审批记录记的是这个用户")
    void streamingScopeCarriesUserId() throws Exception {
        Harness h = harness();
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (ToolCallContext.Scope ignored = h.open(7L, true)) {
                assertEquals(7L, ToolCallContext.current().userId);
                assertNotNull(ToolCallContext.current().events);
                out.set(ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__read_item", "{}"));
            } catch (Exception e) {
                out.set(String.valueOf(e));
            }
        });
        worker.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals(7L, rec.userId);
        approvals.decide(rec.id, 7L, "REJECT");
        worker.join(3000);
        assertTrue(out.get().contains("用户拒绝"), out.get());
    }

    @Test
    @DisplayName("非流式父会话捕获不到审批链路；流式父会话能把 userId 交出去")
    void nonStreamingParentCannotCaptureSubagentLink() {
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "parent", false, false, null, () -> false))) {
            assertNull(ToolCallContext.ApprovalLink.capture("非流式"));
        }
        ToolCallContext.Events events = new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "parent", true, false, events, () -> false))) {
            ToolCallContext.ApprovalLink link = ToolCallContext.ApprovalLink.capture("流式");
            assertNotNull(link);
            assertEquals(7L, link.userId);
        }
    }

    @Test
    @DisplayName("流式链路：deferred 只下发激活过的、搜索不出 DENY，未批准前不执行")
    void streamingDeferredRespectsPermissions() throws Exception {
        Harness h = harness();
        // 只改本测试的 props，不动全局配置
        manager.properties().setToolLoading("deferred");

        ToolCallback[] full = manager.callbacks();
        // 历史里留着 DENY 工具的旧激活标记：不能因此把它放回本轮清单
        List<Message> msgs = List.of(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("1", "mcpToolSearch",
                        "ACTIVATED: mcp__alpha__read_item, mcp__alpha__write_note\n"))));

        ToolCallback[] round = (ToolCallback[]) h.round.invoke(
                h.controller, full, msgs, false, "conv", 7L, null);
        List<String> roundNames = names(round);
        assertTrue(roundNames.contains("mcpToolSearch"), roundNames.toString());
        assertTrue(roundNames.contains("mcp__alpha__read_item"), roundNames.toString());
        assertFalse(roundNames.contains("mcp__alpha__write_note"),
                "DENY 工具不能因旧激活标记回到清单：" + roundNames);

        // 搜索目录同样经过 DENY 过滤
        ToolCallback search = null;
        for (ToolCallback cb : round) {
            if (cb != null && cb.getToolDefinition() != null
                    && "mcpToolSearch".equals(cb.getToolDefinition().name())) {
                search = cb;
            }
        }
        assertNotNull(search);
        assertFalse(search.call("{\"query\":\"write_note\"}").contains("mcp__alpha__write_note"));
        assertTrue(search.call("{\"query\":\"read_item\"}").contains("mcp__alpha__read_item"));

        // 合法工具仍走统一网关：ASK 未批准前不执行
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (ToolCallContext.Scope ignored = h.open(7L, true)) {
                out.set(ToolDispatcher.dispatch(full, "mcp__alpha__read_item", "{}"));
            }
        });
        worker.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals("mcp__alpha__read_item", rec.toolName);
        assertNull(out.get(), "未批准前不得执行");
        approvals.decide(rec.id, 7L, "REJECT");
        worker.join(3000);
        assertTrue(out.get().contains("用户拒绝"), out.get());
    }

    private Harness harness() throws Exception {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        spec.setTrust("untrusted");
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("alpha", spec);
        props.setServers(servers);
        FakeMcpSession alpha = new FakeMcpSession("alpha", List.of(
                new McpRemoteTool("read_item", "read", "{\"type\":\"object\"}"),
                new McpRemoteTool("write_note", "write", "{\"type\":\"object\"}")));
        manager = FakeMcpServers.start(props, Map.of("alpha", alpha));
        MemoryMcpUserPolicyStore store = new MemoryMcpUserPolicyStore();
        store.setTool(7L, "alpha", "write_note", "DENY", manager.findListed("alpha", "write_note").definitionHash());
        McpPermissionExplainer explainer = new McpPermissionExplainer(store, manager);
        approvals = new ApprovalService(2);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, props, explainer));

        Constructor<?> ctor = ChatController.class.getConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == McpClientManager.class) args[i] = manager;
            if (types[i] == ApprovalService.class) args[i] = approvals;
        }
        ChatController controller = (ChatController) ctor.newInstance(args);
        controller.setMcpPermissionExplainer(explainer);
        Method round = ChatController.class.getDeclaredMethod("computeRoundCallbacks",
                ToolCallback[].class, List.class, boolean.class, String.class, Long.class, SseEmitter.class);
        round.setAccessible(true);
        Method open = ChatController.class.getDeclaredMethod("openPermissionScope",
                Long.class, String.class, boolean.class, SseEmitter.class, List.class, AtomicInteger.class);
        open.setAccessible(true);
        return new Harness(controller, round, open);
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

    private static List<String> names(ToolCallback[] cbs) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (cbs == null) return out;
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null) out.add(cb.getToolDefinition().name());
        }
        return out;
    }

    private final class Harness {
        final ChatController controller;
        final Method round;
        final Method open;

        Harness(ChatController controller, Method round, Method open) {
            this.controller = controller;
            this.round = round;
            this.open = open;
        }

        ToolCallContext.Scope open(long userId, boolean streaming) {
            try {
                SseEmitter emitter = streaming ? new SseEmitter(5_000L) : null;
                return (ToolCallContext.Scope) open.invoke(
                        controller, userId, "conv", streaming, emitter, List.of(new UserMessage("hi")),
                        new AtomicInteger(-1));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
