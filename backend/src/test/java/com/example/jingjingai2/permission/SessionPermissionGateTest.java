package com.example.jingjingai2.permission;

import com.example.jingjingai2.agent.ToolStubs;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话三档权限对普通工具的审批判定。断言的是工具真实执行了几次，不是某个布尔返回值。
 * 会话模式用内存假服务，按 userId + conversationId 取值，不连接数据库。
 */
class SessionPermissionGateTest {

    private static final String MCP = "mcp__playwright__browser_navigate";
    /** 网关传给解释器的是去掉 mcp__server__ 前缀后的原始名。 */
    private static final String MCP_TOOL = "browser_navigate";

    private final AtomicInteger calls = new AtomicInteger();
    private ApprovalService approvals;
    private ToolPermissionGate gate;

    /** userId + conversationId → 模式。查不到返回 null，与真实服务一致。 */
    private static final class Modes extends ConversationService {
        final Map<String, String> modes = new LinkedHashMap<>();

        Modes() {
            super(null, null);
        }

        @Override
        public String permissionMode(Long userId, String conversationId) {
            if (userId == null || conversationId == null) return null;
            return modes.get(key(userId, conversationId));
        }
    }

    /**
     * 可控的 MCP 解释器：按工具名返回预设结果，用来证明会话模式盖不过 MCP。
     * 不碰真实的授权存储和客户端。
     */
    private static final class Fixed extends McpPermissionExplainer {
        final Map<String, ExplainResult> byTool = new LinkedHashMap<>();

        Fixed() {
            super(null, null);
        }

        @Override
        public ExplainResult explain(Long userId, String server, String tool, String arguments) {
            ExplainResult r = byTool.get(tool);
            return r != null ? r : ask("未配置，默认询问");
        }

        static ExplainResult ask(String reason) {
            return new ExplainResult(Effect.ASK, reason, true, false, false, false);
        }

        static ExplainResult deny(String reason) {
            return new ExplainResult(Effect.DENY, reason, false, false, false, false);
        }

        static ExplainResult allow(String reason) {
            return new ExplainResult(Effect.ALLOW, reason, false, false, false, false);
        }

        /** 名字或参数命中强制询问：不能本会话允许，且单独记授权。 */
        static ExplainResult forced(String reason) {
            return new ExplainResult(Effect.ASK, reason, false, false, false, true);
        }
    }

    private final Modes modes = new Modes();
    private final Fixed explainer = new Fixed();

    @BeforeEach
    void install() {
        calls.set(0);
        approvals = new ApprovalService(2);
        gate = new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties(), explainer);
        gate.setConversationService(modes);
        ToolDispatcher.installGate(gate);
    }

    @AfterEach
    void uninstall() {
        approvals.clear();
        ToolDispatcher.installGate(null);
    }

    @Test
    @DisplayName("ASK：文件写、普通命令、高危命令都仍要审批，批准前一次都不执行")
    void askKeepsExistingRules() throws Exception {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_ASK);
        assertAsked("writeFile", "{\"path\":\"a.txt\"}");
        assertAsked("executeCommand", "{\"command\":\"dir\"}");
        assertAsked("executeCommand", "{\"command\":\"rm -rf build\"}");
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("WORKSPACE_EDIT：七个指定写操作免审批，删除和命令仍要审批")
    void workspaceEditOnlyNamedWrites() throws Exception {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_WORKSPACE_EDIT);
        for (String name : new String[]{"writeFile", "editFile", "createFile", "appendFile",
                "createDirectory", "movePath", "copyPath"}) {
            calls.set(0);
            assertEquals("ran", exec(name, "{}", ctx(7L, "conv")), name);
            assertEquals(1, calls.get(), name + " 应当直接执行");
            assertEquals(0, approvals.pending().size(), name + " 不该弹卡");
        }
        calls.set(0);
        assertAsked("deletePath", "{\"path\":\"a.txt\"}");
        assertAsked("executeCommand", "{\"command\":\"dir\"}");
        assertEquals(0, calls.get(), "删除和命令在批准前不能执行");
    }

    @Test
    @DisplayName("WORKSPACE_EDIT 不扩大范围：读工具维持原放行，未登记工具仍要审批")
    void workspaceEditDoesNotWiden() throws Exception {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_WORKSPACE_EDIT);
        assertEquals("ran", exec("readFile", "{}", ctx(7L, "conv")));
        assertEquals(1, calls.get());
        calls.set(0);
        assertAsked("getCurrentDateTime", "{}");
        assertEquals(0, calls.get(), "未登记工具不能因为会话模式被放行");
    }

    @Test
    @DisplayName("FULL_ACCESS：普通文件和命令免审批，未登记工具仍审批，硬拒绝仍生效")
    void fullAccessOnlyNamedTools() throws Exception {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_FULL_ACCESS);
        for (String name : new String[]{"writeFile", "deletePath", "executeCommand"}) {
            calls.set(0);
            assertEquals("ran", exec(name, "{\"command\":\"rm -rf build\"}", ctx(7L, "conv")), name);
            assertEquals(1, calls.get(), name);
            assertEquals(0, approvals.pending().size(), name);
        }
        // 未登记工具不在免审批清单里，仍走原规则
        calls.set(0);
        assertAsked("getCurrentDateTime", "{}");
        assertEquals(0, calls.get());
        // 非 http(s) 地址在分流之前就被硬拒，会话模式盖不过
        calls.set(0);
        String denied = exec("executeCommand", "{\"command\":\"file:///etc/passwd\"}", ctx(7L, "conv"));
        assertTrue(denied.contains("已拒绝"), denied);
        assertEquals(0, calls.get(), "硬拒绝不能因为 FULL_ACCESS 被执行");
    }

    @Test
    @DisplayName("FULL_ACCESS 下 MCP 仍走自己的规则：ASK 弹卡、DENY 拒绝、ALLOW 放行、强制 ASK 仍生效")
    void fullAccessDoesNotTouchMcp() throws Exception {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_FULL_ACCESS);

        explainer.byTool.put(MCP_TOOL, Fixed.ask("需要确认"));
        calls.set(0);
        assertAsked(MCP, "{}");
        assertEquals(0, calls.get());

        calls.set(0);
        explainer.byTool.put(MCP_TOOL, Fixed.deny("该工具被设为拒绝"));
        String denied = exec(MCP, "{}", ctx(7L, "conv"));
        assertTrue(denied.startsWith("已拒绝"), denied);
        assertEquals(0, calls.get());
        assertEquals(0, approvals.pending().size(), "DENY 不弹卡");

        calls.set(0);
        explainer.byTool.put(MCP_TOOL, Fixed.allow("该工具被设为允许"));
        assertEquals("ran", exec(MCP, "{}", ctx(7L, "conv")));
        assertEquals(1, calls.get());
        assertEquals(0, approvals.pending().size());

        calls.set(0);
        explainer.byTool.put(MCP_TOOL, Fixed.forced("名字命中强制询问"));
        ApprovalService.Record forced = assertAsked(MCP, "{}");
        assertFalse(forced.canSessionGrant, "强制询问不能本会话允许");
        assertNotNull(forced.sessionGrantKey, "强制询问的授权要单独记");
        assertTrue(forced.sessionGrantKey.contains(ApprovalService.ARG_GRANT_MARK),
                "授权键应当带上参数标记: " + forced.sessionGrantKey);
    }

    @Test
    @DisplayName("会话隔离：会话 B 仍为 ASK，用户 8 读不到用户 7 的 FULL_ACCESS")
    void otherSessionAndOtherUserStillAsk() throws Exception {
        modes.modes.put(key(7L, "a"), ConversationService.MODE_FULL_ACCESS);

        assertEquals("ran", exec("writeFile", "{}", ctx(7L, "a")));
        assertEquals(1, calls.get());

        calls.set(0);
        assertAsked(ctx(7L, "b"), "writeFile", "{}");
        assertAsked(ctx(8L, "a"), "writeFile", "{}");
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("FULL_ACCESS 不绕过污点强制 ASK：网页诱发时写工具和命令仍要审批")
    void fullAccessDoesNotBypassTaintAsk() throws Exception {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_FULL_ACCESS);
        calls.set(0);
        assertAsked(taintedCtx(7L, "conv"), "writeFile", "{}");
        assertAsked(taintedCtx(7L, "conv"), "executeCommand", "{\"command\":\"dir\"}");
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("非法模式、会话不存在都回退 ASK，不自动放行")
    void invalidModeAndMissingConversationFallBackToAsk() throws Exception {
        modes.modes.put(key(7L, "bad"), "ADMIN");
        calls.set(0);
        assertAsked(ctx(7L, "bad"), "writeFile", "{}");
        assertEquals(0, calls.get());

        calls.set(0);
        assertAsked(ctx(7L, "missing"), "writeFile", "{}");
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("子代理上下文带父会话 userId 和 conversationId 时，读到父会话模式")
    void subagentContextReadsParentConversationMode() {
        modes.modes.put(key(7L, "parent"), ConversationService.MODE_FULL_ACCESS);
        ToolCallContext sub = new ToolCallContext(7L, "parent", false, true, noop(), () -> false, "explore");
        assertEquals("ran", exec("writeFile", "{}", sub));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("没有上下文时不再默认放行：写文件 fail-closed；免审批工具照常执行；上下文缺 userId 时不按会话模式放行")
    void missingContextDoesNotAutoAllow() {
        modes.modes.put(key(7L, "conv"), ConversationService.MODE_FULL_ACCESS);

        // 没有上下文：写文件是 ASK 工具，既没有 userId 也没有 SSE 通道 → 不执行。
        // 旧行为是「只做硬拒绝，其余放行」，那正是本轮要关掉的 fail-open。
        String refused = exec("writeFile", "{}", null);
        assertEquals(ToolPermissionGate.NO_CONTEXT, refused);
        assertEquals(0, calls.get(), "缺上下文时受权限约束的工具不得执行");

        // 免审批工具（策略表里无条件 ALLOW）不该被这条收紧误伤：正常 Agent 读文件仍然可用。
        calls.set(0);
        assertEquals("ran", exec("readFile", "{}", null));
        assertEquals(1, calls.get());

        // 子代理拿不到用户时，不能因为会话是 FULL_ACCESS 就免审批
        calls.set(0);
        String held = exec("writeFile", "{}", new ToolCallContext(null, "conv", false, true, null, () -> false));
        assertTrue(held.contains("子代理无法请求"), held);
        assertEquals(0, calls.get());
    }

    /** 发起一次调用并等到审批卡。返回前调用还挂着，批准前执行次数必须是 0。 */
    private ApprovalService.Record assertAsked(String name, String args) throws Exception {
        return assertAsked(ctx(7L, "conv"), name, args);
    }

    private ApprovalService.Record assertAsked(ToolCallContext ctx, String name, String args) throws Exception {
        int before = calls.get();
        String[] result = new String[1];
        Thread worker = new Thread(() -> result[0] = exec(name, args, ctx));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        assertEquals(before, calls.get(), name + " 批准前不能执行");
        approvals.decide(rec.id, ctx.userId, "REJECT");
        worker.join(3000);
        assertTrue(result[0] != null && result[0].contains("用户拒绝了该操作"), result[0]);
        return rec;
    }

    private ApprovalService.Record awaitRecord() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var pending = approvals.pending();
            if (!pending.isEmpty()) return pending.get(0);
            Thread.sleep(20);
        }
        throw new AssertionError("没有等到审批卡");
    }

    private String exec(String name, String args, ToolCallContext ctx) {
        ToolCallback cb = ToolStubs.of(name, a -> {
            calls.incrementAndGet();
            return "ran";
        });
        try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx)) {
            return ToolDispatcher.dispatch(new ToolCallback[]{cb}, name, args);
        }
    }

    private static ToolCallContext ctx(Long userId, String conversationId) {
        return new ToolCallContext(userId, conversationId, true, false, noop(), () -> false);
    }

    /** 网页污点已存在：危险内置工具的 ASK 不能被会话模式盖掉。 */
    private static ToolCallContext taintedCtx(Long userId, String conversationId) {
        return new ToolCallContext(userId, conversationId, true, false, noop(), () -> true);
    }

    private static ToolCallContext.Events noop() {
        return new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
    }

    private static String key(Long userId, String conversationId) {
        return userId + "\0" + conversationId;
    }
}
