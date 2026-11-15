package com.example.jingjingai2.permission;

import com.example.jingjingai2.agent.ToolStubs;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpToolCallback;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 派发层的结构化执行结论：{@link ToolDispatcher#dispatch} 的每条出口必须给出
 * 「工具到底有没有被执行」，而不是让调用方去猜返回文案。
 *
 * <p>全部走真实的 {@link ToolDispatcher} + 真实的 {@link ToolPermissionGate} +
 * 真实的 {@link ApprovalService}；工具本体是计数桩，断言的是**真实执行了几次**。
 * 不启动 Spring、不连数据库、不访问网络、不碰任何第三方服务。
 */
class ToolDispatchOutcomeTest {

    private ApprovalService approvals;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void install() {
        calls.set(0);
        ToolExecutionStatus.take();       // 清掉上一条用例可能留下的结论
        approvals = new ApprovalService(1);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties()));
    }

    @AfterEach
    void uninstall() {
        approvals.clear();
        ToolDispatcher.installGate(null);
        ToolExecutionStatus.take();
    }

    @Test
    @DisplayName("ALLOW：工具真的执行了 → EXECUTED，审计 approved=true / high_risk=false")
    void allowIsExecuted() {
        Run r = run(ctx(true), "readFile", "{}", stub("readFile", "文件内容"));
        assertEquals("文件内容", r.result);
        assertEquals(1, calls.get());
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.kind);
        assertNotNull(r.audit);
        assertTrue(r.audit.approved);
        assertFalse(r.audit.highRisk);
    }

    @Test
    @DisplayName("ASK 被用户拒绝：工具一次都没执行 → DENIED，审计 approved=false / high_risk=true")
    void askRejectedIsDenied() throws Exception {
        Run[] out = new Run[1];
        Thread worker = new Thread(() -> out[0] = run(ctx(true), "writeFile", "{\"path\":\"a\"}",
                stub("writeFile", "wrote")));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        assertEquals(0, calls.get(), "审批未决前不得执行");
        approvals.decide(rec.id, 7L, "REJECT");
        worker.join(3000);

        assertNotNull(out[0]);
        assertEquals(0, calls.get(), "被拒绝的调用不得执行");
        assertEquals(ToolPermissionGate.REJECTED, out[0].result, "拒绝文案原样交回模型");
        assertEquals(ToolExecutionStatus.Kind.DENIED, out[0].kind);
        assertNotNull(out[0].audit);
        assertFalse(out[0].audit.approved);
        assertTrue(out[0].audit.highRisk);
    }

    @Test
    @DisplayName("ASK 批准后真实执行一次 → EXECUTED，审计 approved=true / high_risk=true")
    void askApprovedIsExecuted() throws Exception {
        Run[] out = new Run[1];
        Thread worker = new Thread(() -> out[0] = run(ctx(true), "writeFile", "{\"path\":\"a\"}",
                stub("writeFile", "wrote")));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        approvals.decide(rec.id, 7L, "APPROVE");
        worker.join(3000);

        assertNotNull(out[0]);
        assertEquals(1, calls.get());
        assertEquals("wrote", out[0].result);
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, out[0].kind);
        assertNotNull(out[0].audit);
        assertTrue(out[0].audit.approved);
        assertTrue(out[0].audit.highRisk);
    }

    @Test
    @DisplayName("非流式链路的 ASK：没有卡片可弹 → 未执行（NEED_STREAM），不是「执行成功」")
    void nonStreamingAskIsDenied() {
        Run r = run(ctx(false), "writeFile", "{}", stub("writeFile", "wrote"));
        assertEquals(0, calls.get());
        assertEquals(ToolPermissionGate.NEED_STREAM, r.result);
        assertEquals(ToolExecutionStatus.Kind.DENIED, r.kind);
        assertNotNull(r.audit);
        assertFalse(r.audit.approved);
    }

    @Test
    @DisplayName("工具执行成功但返回业务错误文本 → 仍是 EXECUTED（不因文案误判为未执行）")
    void businessErrorTextIsStillExecuted() {
        Run r = run(ctx(true), "readFile", "{}",
                stub("readFile", "执行失败: 命令返回码 1，失败原因见日志"));
        assertEquals(1, calls.get(), "工具本体确实被执行了");
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.kind, "业务错误文本不改变「执行过」这个事实");
    }

    @Test
    @DisplayName("工具返回的文本正好是网关拒绝文案时也不误判：结论只看控制流")
    void refusalLookingToolTextIsStillExecuted() {
        // 这是本修复最容易踩的坑：绝不能靠「结果里有拒绝/失败字样」反推工具没执行。
        Run r = run(ctx(true), "readFile", "{}",
                stub("readFile", "已拒绝：地址不被允许（内网）。用户拒绝了该操作"));
        assertEquals(1, calls.get());
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.kind);
        assertNotNull(r.audit);
        assertTrue(r.audit.approved, "网关确实放行了这次调用");
    }

    @Test
    @DisplayName("清单里没有这个工具 → NOT_FOUND，且审计能区分它不是权限拒绝")
    void unknownToolIsNotFound() {
        Run r = run(ctx(true), "noSuchTool", "{}", stub("readFile", "x"));
        assertEquals(0, calls.get());
        assertTrue(r.result.startsWith("未知的工具"), r.result);
        assertEquals(ToolExecutionStatus.Kind.NOT_FOUND, r.kind);
        assertNotNull(r.audit);
        assertFalse(r.audit.approved, "没有执行 = 没有被批准执行");
        assertFalse(r.audit.highRisk, "它不是权限拒绝，不该标成高危拒绝");
    }

    @Test
    @DisplayName("工具本体抛异常 → FAILED（执行了但没正常返回）")
    void throwingToolIsFailed() {
        ToolCallback boom = ToolStubs.of("readFile", args -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        Run r = run(ctx(true), "readFile", "{}", boom);
        assertEquals(1, calls.get());
        assertTrue(r.result.startsWith("工具调用失败"), r.result);
        assertEquals(ToolExecutionStatus.Kind.FAILED, r.kind);
    }

    @Test
    @DisplayName("子代理无法请求审批：工具一次都没执行 → DENIED，文案是 NEED_PARENT 而不是 REJECTED")
    void subagentCannotRequestApproval() {
        // userId 与 events 都没有、且来自子代理：网关不弹卡片，也不调用工具本体。
        ToolCallContext subagent = new ToolCallContext(null, "conv", false, true, null, () -> false);
        Run r = run(subagent, "writeFile", "{}", stub("writeFile", "wrote"));
        assertEquals(0, calls.get());
        assertEquals(ToolPermissionGate.NEED_PARENT, r.result);      // 与 REJECTED 是两个常量
        assertEquals(ToolExecutionStatus.Kind.DENIED, r.kind);
        assertNotNull(r.audit);
        assertFalse(r.audit.approved);
        assertTrue(r.audit.highRisk, "与其它未执行的 ASK 一样记成高危、未批准");
    }

    @Test
    @DisplayName("锁定现状：回调正常返回「MCP 工具调用失败」字符串时，dispatch 仍记 EXECUTED")
    void mcpErrorStringIsCurrentlyExecuted() {
        // 不是期望的最终语义。McpToolCallback 把异常收成普通返回值，dispatch 只看 call() 没抛。
        // 将来若改成 FAILED，这个断言必须跟着改。
        String text = McpToolCallback.ERROR_PREFIX + "连接已断开";
        Run r = run(ctx(true), "readFile", "{}", stub("readFile", text));
        assertEquals(1, calls.get(), "回调确实返回了，不是网关拒绝");
        assertEquals(text, r.result);
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.kind);
    }

    @Test
    @DisplayName("锁定现状：回调正常返回 UNKNOWN_OUTCOME 时，dispatch 仍记 EXECUTED")
    void unknownOutcomeStringIsCurrentlyExecuted() {
        // 同上：结果未知被包成普通字符串。本测试不启动 MCP，只把该常量交回 dispatch。
        Run r = run(ctx(true), "readFile", "{}", stub("readFile", McpToolCallback.UNKNOWN_OUTCOME));
        assertEquals(1, calls.get());
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, r.result);
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, r.kind);
    }

    @Test
    @DisplayName("回调先写下「结果未知」：派发层不覆盖成 EXECUTED，原文仍是 UNKNOWN_OUTCOME")
    void unknownRecordedByCallbackIsNotExecuted() {
        Run r = run(ctx(true), "readFile", "{}",
                recording("readFile", ToolExecutionStatus.Kind.UNKNOWN, McpToolCallback.UNKNOWN_OUTCOME));
        assertEquals(1, calls.get());
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, r.result);
        assertEquals(ToolExecutionStatus.Kind.UNKNOWN, r.kind);
        assertFalse(ToolExecutionStatus.succeeded(r.kind));
        assertNotNull(r.audit);
        assertTrue(r.audit.approved, "网关已经放行，这不是拒绝");
        assertFalse(r.audit.highRisk);
    }

    @Test
    @DisplayName("回调先写下「没发出去」：记 FAILED，不是成功，也不是权限拒绝")
    void notSentRecordedByCallbackIsFailedNotDenied() {
        String text = McpToolCallback.ERROR_PREFIX + "MCP 进程已退出";
        Run r = run(ctx(true), "readFile", "{}",
                recording("readFile", ToolExecutionStatus.Kind.FAILED, text));
        assertEquals(1, calls.get());
        assertEquals(text, r.result);
        assertEquals(ToolExecutionStatus.Kind.FAILED, r.kind);
        assertNotEquals(ToolPermissionGate.REJECTED, r.result);
        assertTrue(r.audit.approved);
    }

    @Test
    @DisplayName("同线程先未知、再成功：第二次不被第一次的状态污染")
    void unknownThenSuccessDoesNotStick() {
        Run first = run(ctx(true), "readFile", "{}",
                recording("readFile", ToolExecutionStatus.Kind.UNKNOWN, McpToolCallback.UNKNOWN_OUTCOME));
        assertEquals(ToolExecutionStatus.Kind.UNKNOWN, first.kind);
        calls.set(0);
        Run second = run(ctx(true), "readFile", "{}", stub("readFile", "文件内容"));
        assertEquals(1, calls.get());
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, second.kind);
        assertEquals("文件内容", second.result);
    }

    // ==================== 测试辅助 ====================

    /** 一次派发的三件可观测事实：返回文本、结构化结论、网关审计。 */
    private record Run(String result, ToolExecutionStatus.Kind kind, ToolCallContext.Audit audit) {}

    private Run run(ToolCallContext ctx, String name, String args, ToolCallback cb) {
        try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx)) {
            String result = ToolDispatcher.dispatch(new ToolCallback[]{cb}, name, args);
            // 审计和结论都是 ThreadLocal，必须在作用域内取走
            return new Run(result, ToolExecutionStatus.take(), ToolCallContext.takeAudit());
        }
    }

    private ToolCallback stub(String name, String result) {
        return ToolStubs.of(name, args -> {
            calls.incrementAndGet();
            return result;
        });
    }

    /** 模拟 MCP 回调：先写下结论，再正常返回文本。 */
    private ToolCallback recording(String name, ToolExecutionStatus.Kind kind, String result) {
        return ToolStubs.of(name, args -> {
            calls.incrementAndGet();
            ToolExecutionStatus.record(kind);
            return result;
        });
    }
    private static ToolCallContext ctx(boolean streaming) {
        return new ToolCallContext(7L, "conv", streaming, false, streaming ? noop() : null, () -> false);
    }

    private static ToolCallContext.Events noop() {
        return new ToolCallContext.Events() {
            @Override public void approval(java.util.Map<String, Object> payload) {}
            @Override public void approvalResult(java.util.Map<String, Object> payload) {}
        };
    }

    private ApprovalService.Record awaitRecord() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (ApprovalService.Record r : approvals.pending()) {
                if (r.status() == ApprovalService.Status.PENDING) return r;
            }
            Thread.sleep(20);
        }
        fail("没有等到 PENDING 审批");
        return null;
    }
}
