package com.example.jingjingai2.permission;

import com.example.jingjingai2.agent.ToolStubs;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A1：权限网关的 fail-closed 边界。
 *
 * <p>这里钉死的是「<b>缺什么就一定不执行</b>」，而不是某个布尔返回值：
 * 每个用例都断言工具本体<b>真实被调用的次数</b>，以及派发层写下的结构化执行结论。
 *
 * <p>被关掉的两条旧 fail-open：
 * <ol>
 *   <li>{@code ToolPermissionGate.authorize} 在 {@code ToolCallContext.current() == null} 时
 *       直接 {@code return null}（= 放行）；</li>
 *   <li>{@code ToolDispatcher.dispatch} 在 {@code gate == null} 时直接执行工具。</li>
 * </ol>
 *
 * <p>不启动 Spring、不连数据库、不访问网络、不碰任何第三方服务。
 */
class FailClosedPermissionTest {

    private static final String MCP_WRITE = "mcp__alpha__write_note";
    private static final String MCP_READ = "mcp__alpha__read_item";

    @TempDir
    Path dir;

    private final AtomicInteger calls = new AtomicInteger();
    private ApprovalService approvals;

    /** 可编程的 MCP 解释器：只回答「这个工具 ALLOW / ASK / DENY」，不碰真实授权存储。 */
    private final Map<String, McpPermissionExplainer.ExplainResult> mcpVerdict = new java.util.LinkedHashMap<>();

    private final McpPermissionExplainer explainer = new McpPermissionExplainer(null, null) {
        @Override
        public ExplainResult explain(Long userId, String server, String tool, String arguments) {
            ExplainResult preset = mcpVerdict.get(tool);
            return preset != null ? preset : new ExplainResult(Effect.ASK, "未配置，默认询问", true, false, false, false);
        }
    };

    @BeforeEach
    void installGate() {
        calls.set(0);
        ToolExecutionStatus.take();
        mcpVerdict.clear();
        approvals = new ApprovalService(2);
        // 真实的内置策略表：写文件 / 终端 / 删除 / MCP / 用户记忆都是 ASK。
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties(), explainer));
    }

    @AfterEach
    void uninstallGate() {
        approvals.clear();
        ToolDispatcher.installGate(null);
        ToolExecutionStatus.take();
    }

    // ==================== ① 缺少权限上下文 ====================

    @Test
    @DisplayName("缺少权限上下文：写文件 / 终端 / 删除 / MCP 一律不执行，且记成高危未批准")
    void missingContextDeniesControlledTools() {
        for (String[] call : List.of(
                new String[]{"writeFile", "{\"path\":\"a.txt\",\"content\":\"x\"}"},
                new String[]{"executeCommand", "{\"command\":\"dir\"}"},
                new String[]{"deletePath", "{\"path\":\"a.txt\"}"},
                new String[]{"saveUserMemory", "{\"category\":\"preference\",\"key\":\"k\",\"content\":\"v\"}"},
                new String[]{MCP_WRITE, "{}"},
                new String[]{MCP_READ, "{}"})) {
            calls.set(0);
            Result r = run(null, call[0], call[1]);
            assertEquals(ToolPermissionGate.NO_CONTEXT, r.text, call[0] + " 缺上下文时必须拒绝");
            assertEquals(0, calls.get(), call[0] + " 缺上下文时不得执行");
            assertEquals(ToolExecutionStatus.Kind.DENIED, r.status, call[0] + " 必须记成 DENIED");
            // 审计本身挂在上下文上，没有上下文就没有审计槽位：这里只能断言"没有被记成批准"。
            assertNull(r.audit, call[0] + " 没有上下文就没有审计槽位");
            assertFalse(r.approved());
        }
    }

    // ==================== ② 缺少权限网关 ====================

    @Test
    @DisplayName("缺少权限网关：受权限约束的工具不执行；免审批工具仍可执行")
    void missingGateDeniesControlledTools() {
        ToolDispatcher.installGate(null);
        assertFalse(ToolDispatcher.gateInstalled());

        for (String[] call : List.of(
                new String[]{"writeFile", "{\"path\":\"a.txt\"}"},
                new String[]{"executeCommand", "{\"command\":\"dir\"}"},
                new String[]{"deletePath", "{\"path\":\"a.txt\"}"},
                new String[]{"saveUserMemory", "{}"},
                new String[]{MCP_WRITE, "{}"})) {
            calls.set(0);
            Result r = run(streamingCtx(7L), call[0], call[1]);
            assertEquals(ToolDispatcher.NO_GATE, r.text, call[0] + " 缺网关时必须拒绝");
            assertEquals(0, calls.get(), call[0] + " 缺网关时不得执行");
            assertEquals(ToolExecutionStatus.Kind.DENIED, r.status);
            assertTrue(r.highRisk(), "缺网关是安全基础设施问题，应记高危");
            assertFalse(r.approved());
        }

        // 免审批工具不受影响：缺网关不该把正常读操作也拒掉。
        calls.set(0);
        Result read = run(streamingCtx(7L), "readFile", "{\"path\":\"a.txt\"}");
        assertEquals("ran", read.text);
        assertEquals(1, calls.get());
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, read.status);
    }

    // ==================== ③ 上下文损坏 / 不完整 ====================

    @Test
    @DisplayName("上下文不完整：缺 userId / 缺 SSE 通道 / 子代理无审批链路，都 fail-closed")
    void incompleteContextIsFailClosed() {
        // a) 有 SSE 通道但没有 userId：审批卡不知道该给谁
        calls.set(0);
        Result noUser = run(new ToolCallContext(null, "conv", true, false, noop(), () -> false),
                "writeFile", "{}");
        assertEquals(ToolPermissionGate.NEED_STREAM, noUser.text);
        assertEquals(0, calls.get());

        // b) 有 userId 但没有 SSE 通道（非流式）
        calls.set(0);
        Result noChannel = run(new ToolCallContext(7L, "conv", false, false, null, () -> false),
                "executeCommand", "{\"command\":\"dir\"}");
        assertEquals(ToolPermissionGate.NEED_STREAM, noChannel.text);
        assertEquals(0, calls.get());

        // c) 子代理拿不到父会话的审批链路
        calls.set(0);
        Result subagent = run(ToolCallContext.forSubagent("conv", () -> false), MCP_WRITE, "{}");
        assertEquals(ToolPermissionGate.NEED_PARENT, subagent.text);
        assertEquals(0, calls.get());

        // 三种情况都必须记成「没执行 = 没被批准」
        for (Result r : List.of(noUser, noChannel, subagent)) {
            assertEquals(ToolExecutionStatus.Kind.DENIED, r.status);
            assertTrue(r.highRisk());
            assertFalse(r.approved());
        }
    }

    // ==================== ④ 正常 ALLOW ====================

    @Test
    @DisplayName("正常 ALLOW：免审批工具直接执行，审计 approved=true / high_risk=false")
    void allowExecutes() {
        Result r = run(streamingCtx(7L), "readFile", "{\"path\":\"a.txt\"}");
        assertEquals("ran", r.text);
        assertEquals(1, calls.get());
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.status);
        assertTrue(r.approved());
        assertFalse(r.highRisk());
    }

    // ==================== ⑤⑥⑦ ASK / DENY / 拒绝后无副作用 ====================

    @Test
    @DisplayName("正常 ASK：批准前不执行，批准后执行一次；拒绝则文件一个字节都不变")
    void askApprovedRunsAndRejectedLeavesNoSideEffect() throws Exception {
        Path target = dir.resolve("a.txt");
        Files.writeString(target, "original");

        // 拒绝：不执行，文件不变
        String[] rejected = new String[1];
        Thread worker = new Thread(() -> rejected[0] = writeFileViaApproval(target));
        worker.start();
        ApprovalService.Record card = awaitPending();
        assertEquals("writeFile", card.toolName);
        assertEquals(7L, card.userId);
        assertEquals(0, calls.get(), "审批未决前不得执行");
        approvals.decide(card.id, 7L, "REJECT");
        worker.join(3000);
        assertEquals(ToolPermissionGate.REJECTED, rejected[0]);
        assertEquals(0, calls.get(), "被拒绝的调用不得执行");
        assertEquals("original", Files.readString(target), "拒绝后文件不得被改动");

        // 批准：执行一次
        String[] approved = new String[1];
        Thread second = new Thread(() -> approved[0] = writeFileViaApproval(target));
        second.start();
        ApprovalService.Record ok = awaitPending();
        approvals.decide(ok.id, 7L, "APPROVE");
        second.join(3000);
        assertEquals("ran", approved[0]);
        assertEquals(1, calls.get());
        assertEquals("original", Files.readString(target), "桩工具本身不写文件，断言的是「有没有被调用」");
    }

    @Test
    @DisplayName("正常 DENY：MCP 策略拒绝不能靠审批绕过，也不生成审批卡")
    void mcpDenyCannotBeBypassed() {
        mcpVerdict.put("write_note",
                new McpPermissionExplainer.ExplainResult(
                        McpPermissionExplainer.Effect.DENY, "服务级策略禁止写入", false, false, false, false));
        Result r = run(streamingCtx(7L), MCP_WRITE, "{}");
        assertTrue(r.text.startsWith("已拒绝"), r.text);
        assertEquals(0, calls.get());
        assertEquals(0, approvals.pending().size(), "DENY 不该弹卡");
        assertEquals(ToolExecutionStatus.Kind.DENIED, r.status);
        assertFalse(r.approved());
    }

    // ==================== ⑧⑨⑩ 文件 / 终端 / MCP 执行 ====================

    @Test
    @DisplayName("批准后文件写、终端执行、MCP 调用都真实执行一次")
    void approvedFileTerminalAndMcpToolsRun() throws Exception {
        assertEquals("ran", approveAndRun("writeFile", "{\"path\":\"a.txt\",\"content\":\"x\"}"));
        assertEquals("ran", approveAndRun("executeCommand", "{\"command\":\"dir\"}"));
        assertEquals("ran", approveAndRun(MCP_WRITE, "{}"));
        assertEquals(3, calls.get(), "三次批准 = 三次真实执行");
    }

    // ==================== ⑪ 子代理不能绕过 ====================

    @Test
    @DisplayName("子代理带父会话审批链路时仍走同一条 ASK；拒绝后不执行")
    void subagentWithParentLinkStillAsksAndCanBeRejected() throws Exception {
        String[] out = new String[1];
        Thread worker = new Thread(() -> out[0] = run(
                new ToolCallContext(7L, "conv", true, true, noop(), () -> false, "explore"),
                "writeFile", "{}").text);
        worker.start();
        ApprovalService.Record card = awaitPending();
        assertEquals(0, calls.get());
        approvals.decide(card.id, 7L, "REJECT");
        worker.join(3000);
        assertEquals(ToolPermissionGate.REJECTED, out[0]);
        assertEquals(0, calls.get(), "子代理不能绕过主系统的权限限制");
    }

    // ==================== ⑫ 合法无需审批的工具 ====================

    @Test
    @DisplayName("免审批清单：缺网关、缺上下文都照常执行；写文件 / 终端 / MCP 不在清单里")
    void approvalFreeToolsRunWithoutSecurityInfrastructure() {
        List<String> approvalFree = List.of(
                "readFile", "listDirectory", "globFile", "grepFile",
                "createPlan", "updatePlanStep", "spawnSubagent", "webSearch",
                "searchContext", "saveMemory", "mcpToolSearch");

        for (String tool : approvalFree) {
            assertTrue(PermissionPolicy.approvalFree(tool), tool + " 应属于免审批清单");
        }
        for (String controlled : List.of("writeFile", "editFile", "executeCommand", "deletePath",
                "saveUserMemory", "forgetUserMemory", MCP_WRITE, "mcpToolSearchX")) {
            assertFalse(PermissionPolicy.approvalFree(controlled), controlled + " 不应属于免审批清单");
        }
        assertFalse(PermissionPolicy.approvalFree(null));
        assertFalse(PermissionPolicy.approvalFree(""));

        // 没有网关 + 没有上下文：清单内的照常执行
        ToolDispatcher.installGate(null);
        for (String tool : approvalFree) {
            calls.set(0);
            Result r = run(null, tool, "{}");
            assertEquals("ran", r.text, tool + " 是免审批工具，不该被 fail-closed 误伤");
            assertEquals(1, calls.get());
            assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.status);
        }
    }

    // ==================== ToolGuard 的本轮限制仍然有效 ====================

    @Test
    @DisplayName("ToolGuard 的本轮清单限制不受权限改动影响：清单外工具照样拒")
    void toolGuardStillLimitsRound() {
        Set<String> offered = Set.of("readFile", "createPlan");
        assertNull(ToolGuard.rejectReason("readFile", offered));
        String rejected = ToolGuard.rejectReason("writeFile", offered);
        assertNotNull(rejected);
        assertTrue(rejected.contains("writeFile"), rejected);
        // 污点 + 危险内置工具：即使网关放行，Guard 也先拦
        assertNotNull(ToolGuard.rejectReason("executeCommand", Set.of("executeCommand"), true));
    }

    // ==================== 辅助 ====================

    /** 一次派发的可观测事实：返回文本、结构化结论、网关审计。 */
    private record Result(String text, ToolExecutionStatus.Kind status, ToolCallContext.Audit audit) {
        boolean highRisk() { return audit != null && audit.highRisk; }
        boolean approved() { return audit != null && audit.approved; }
    }

    private Result run(ToolCallContext ctx, String tool, String args) {
        ToolCallback cb = ToolStubs.of(tool, a -> {
            calls.incrementAndGet();
            return "ran";
        });
        try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx)) {
            String text = ToolDispatcher.dispatch(new ToolCallback[]{cb}, tool, args);
            // 结论和审计都是 ThreadLocal，必须在作用域内取走
            return new Result(text, ToolExecutionStatus.take(), ToolCallContext.takeAudit());
        }
    }

    private String writeFileViaApproval(Path target) {
        String args = "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\"}";
        return run(streamingCtx(7L), "writeFile", args).text;
    }

    /** 起一个线程走 ASK，主线程批准后返回执行结果。 */
    private String approveAndRun(String tool, String args) throws Exception {
        String[] out = new String[1];
        int before = calls.get();
        Thread worker = new Thread(() -> out[0] = run(streamingCtx(7L), tool, args).text);
        worker.start();
        ApprovalService.Record card = awaitPending();
        assertEquals(tool, card.toolName);
        assertEquals(before, calls.get(), tool + " 批准前不能执行");
        approvals.decide(card.id, 7L, "APPROVE");
        worker.join(3000);
        return out[0];
    }

    private ApprovalService.Record awaitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (ApprovalService.Record r : approvals.pending()) {
                if (r.status() == ApprovalService.Status.PENDING) return r;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("没有等到 PENDING 审批");
    }

    private static ToolCallContext streamingCtx(Long userId) {
        return new ToolCallContext(userId, "conv", true, false, noop(), () -> false);
    }

    private static ToolCallContext.Events noop() {
        return new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
    }
}
