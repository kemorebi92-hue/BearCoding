package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ChatResponse;
import com.example.jingjingai2.agent.SubagentProperties;
import com.example.jingjingai2.agent.SubagentService;
import com.example.jingjingai2.agent.SubagentTelemetry;
import com.example.jingjingai2.agent.ToolStubs;
import com.example.jingjingai2.agent.reconcile.StepToolLedger;
import com.example.jingjingai2.entity.ToolCallLog;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpToolCallback;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.PermissionProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolPermissionGate;
import com.example.jingjingai2.service.ToolCallLogService;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code tool_call_log.success} 与 SSE 工具记录里的 {@code success} 必须反映
 * 「工具到底有没有被执行」。
 *
 * <p>被测的是 {@code ChatController.executeAndRecordTool} —— **非流式与流式共用的唯一一处**
 * 执行 + 记账实现。它返回的 {@link ChatResponse.ToolCallRecord} 就是流式链路 SSE
 * {@code tool} 事件里 {@code toolCalls} 数组的元素，所以这里对记录和 sink 的断言
 * 等价于对 SSE 载荷的断言（不启动服务、不开浏览器）。
 *
 * <p>边界：真实 {@code ToolDispatcher} / {@code ToolPermissionGate} / {@code ApprovalService}，
 * 工具本体是计数桩。不启动 Spring、不连数据库（尤其不连 {@code jingjing_ai}）、不访问网络、
 * 不调用任何真实第三方服务。
 */
class ToolCallOutcomeAccountingTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final List<ToolCallLog> rows = Collections.synchronizedList(new ArrayList<>());

    private ApprovalService approvals;
    private McpClientManager manager;
    private ChatController controller;
    private Method execute;
    private Method open;

    @BeforeEach
    void setUp() throws Exception {
        calls.set(0);
        rows.clear();
        ToolExecutionStatus.take();

        McpProperties mcp = new McpProperties();           // 默认关：不连任何 MCP 服务
        manager = new McpClientManager(mcp);
        approvals = new ApprovalService(1);
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, mcp));
        controller = newController();

        execute = ChatController.class.getDeclaredMethod("executeAndRecordTool",
                ToolCallback[].class, AssistantMessage.ToolCall.class, String.class, List.class,
                SubagentService.SpawnOutcome.class, Long.class, int.class,
                StepToolLedger.class, List.class);
        execute.setAccessible(true);
        open = ChatController.class.getDeclaredMethod("openPermissionScope",
                Long.class, String.class, boolean.class, SseEmitter.class, List.class, AtomicInteger.class);
        open.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        approvals.clear();
        ToolDispatcher.installGate(null);
        ToolExecutionStatus.take();
    }

    // ==================== 执行成功 ====================

    @Test
    @DisplayName("ALLOW 工具真实执行 → 日志 success=true，SSE 记录 success=true")
    void allowedToolIsLoggedAsExecuted() throws Exception {
        Holder h = new Holder();
        h.run("readFile", "{}", new ToolCallback[]{stub("readFile", "文件内容")}, null, true);

        assertNull(h.error, String.valueOf(h.error));
        assertEquals(1, calls.get(), "工具本体必须真的执行一次");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isSuccess(), "真的执行了 → success=true");
        assertTrue(rows.get(0).isApproved());
        assertFalse(rows.get(0).isHighRisk());
        assertTrue(h.record.isSuccess());
        assertEquals(1, h.sink.size(), "SSE tool 事件里的 toolCalls 就是这份 sink");
        assertTrue(h.sink.get(0).isSuccess(), "前端据此显示绿色「完成」");
    }

    @Test
    @DisplayName("ASK 批准后真实执行 → success=true，approved=true / high_risk=true")
    void approvedToolIsLoggedAsExecuted() throws Exception {
        Holder h = new Holder();
        Thread worker = new Thread(() -> h.run("writeFile", "{\"path\":\"a\"}",
                new ToolCallback[]{stub("writeFile", "wrote")}, null, true));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        assertEquals(0, calls.get(), "审批未决前不得执行");
        approvals.decide(rec.id, 7L, "APPROVE");
        worker.join(3000);

        assertNull(h.error, String.valueOf(h.error));
        assertEquals(1, calls.get());
        assertTrue(rows.get(0).isSuccess());
        assertTrue(rows.get(0).isApproved());
        assertTrue(rows.get(0).isHighRisk());
        assertTrue(h.sink.get(0).isSuccess());
    }

    // ==================== 未执行：权限拒绝 ====================

    @Test
    @DisplayName("权限拒绝（用户拒绝）→ 工具 0 次调用，success=false，approved=false，SSE success=false")
    void permissionRejectedToolIsNotLoggedAsExecuted() throws Exception {
        Holder h = new Holder();
        Thread worker = new Thread(() -> h.run("writeFile", "{\"path\":\"a\"}",
                new ToolCallback[]{stub("writeFile", "wrote")}, null, true));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        approvals.decide(rec.id, 7L, "REJECT");
        worker.join(3000);

        assertNull(h.error, String.valueOf(h.error));
        assertEquals(0, calls.get(), "被拒绝的调用一次都不能执行");
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).isSuccess(), "没执行就不能记成功");
        assertFalse(rows.get(0).isApproved(), "审计里可区分：approved=0");
        assertTrue(rows.get(0).isHighRisk());
        assertFalse(h.sink.get(0).isSuccess(), "前端据此显示红色错误，而不是绿色「完成」");
    }

    @Test
    @DisplayName("ToolGuard 白名单拒绝 → 工具 0 次调用，且拒绝可审计（approved=false / high_risk=true）")
    void toolGuardWhitelistRejectIsAudited() throws Exception {
        // 本轮清单里只有 readFile：模型"凭记忆"喊 writeFile 必须被拒绝，而不是被派发
        ToolCallback[] round = new ToolCallback[]{stub("readFile", "x")};
        Holder h = new Holder();
        h.run("writeFile", "{\"path\":\"a\"}", round, null, true);

        assertNull(h.error, String.valueOf(h.error));
        assertEquals(0, calls.get(), "清单外工具不得执行");
        assertEquals(1, rows.size());
        assertEquals("writeFile", rows.get(0).getToolName());
        assertFalse(rows.get(0).isSuccess(), "白名单拒绝不是执行成功");
        assertFalse(rows.get(0).isApproved(),
                "修复前这条路径不写 audit，auditFlags() 回落成 {false,true}，与 ALLOW 成功一模一样");
        assertTrue(rows.get(0).isHighRisk(), "与网关拒绝保持同一套审计口径");
        assertEquals(ToolGuard.rejectReason("writeFile", Set.of("readFile"), false),
                rows.get(0).getResult(), "交回模型的是 ToolGuard 的拒绝说明");
        assertFalse(h.sink.get(0).isSuccess());
    }

    // ==================== 未执行：不是权限拒绝 ====================

    @Test
    @DisplayName("工具本体抛异常 → success=false，但 approved=true（网关确实放行了）")
    void throwingToolIsNotLoggedAsExecuted() throws Exception {
        ToolCallback boom = ToolStubs.of("readFile", args -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        Holder h = new Holder();
        h.run("readFile", "{}", new ToolCallback[]{boom}, null, true);

        assertNull(h.error, String.valueOf(h.error));
        assertEquals(1, calls.get());
        assertFalse(rows.get(0).isSuccess(), "抛异常不算执行成功");
        assertTrue(rows.get(0).isApproved(), "放行过 = approved=1，与拒绝区分");
        assertFalse(h.sink.get(0).isSuccess());
    }

    @Test
    @DisplayName("同一份 SSE sink 里：成功与拒绝分别带各自的 success，前端据此分别显示完成 / 错误")
    void mixedSinkCarriesDistinctSuccessStates() throws Exception {
        List<ChatResponse.ToolCallRecord> sink = new ArrayList<>();
        ToolCallback[] round = new ToolCallback[]{stub("readFile", "文件内容")};

        Holder ok = new Holder(sink);
        ok.run("readFile", "{}", round, null, true);
        assertNull(ok.error, String.valueOf(ok.error));

        Holder refused = new Holder(sink);
        refused.run("writeFile", "{\"path\":\"a\"}", round, null, true);
        assertNull(refused.error, String.valueOf(refused.error));

        assertEquals(1, calls.get(), "只有清单内那个工具真的执行了");
        assertEquals(2, sink.size());
        assertTrue(sink.get(0).isSuccess(), "readFile 成功 → 绿色完成");
        assertFalse(sink.get(1).isSuccess(), "writeFile 被白名单拒绝 → 红色错误");
    }

    // ==================== 不因返回文本误判 ====================

    @Test
    @DisplayName("工具执行成功但返回业务错误文本 → success 仍为 true（只看控制流，不看文案）")
    void businessErrorTextIsNotMisjudged() throws Exception {
        String businessError = "修改失败: oldStr 在文件中匹配到 0 处（未命中），未做任何修改。";
        Holder h = new Holder();
        h.run("readFile", "{}", new ToolCallback[]{stub("readFile", businessError)}, null, true);

        assertNull(h.error, String.valueOf(h.error));
        assertEquals(1, calls.get(), "工具确实执行了");
        assertTrue(rows.get(0).isSuccess(),
                "业务错误是工具的合法返回，不能因此判定「没有执行」");
        assertEquals(businessError, rows.get(0).getResult());
        assertTrue(h.sink.get(0).isSuccess());
    }

    // ==================== 批量通道（spawnSubagent） ====================

    @Test
    @DisplayName("批量通道的 spawnSubagent：success 取自它自己的真实结论，不派发第二次")
    void spawnOutcomeDrivesSuccess() throws Exception {
        Holder ok = new Holder();
        ok.run("spawnSubagent", "{}", new ToolCallback[]{stub("readFile", "x")},
                new SubagentService.SpawnOutcome("子代理完成", true), true);
        assertNull(ok.error, String.valueOf(ok.error));
        assertEquals(0, calls.get(), "批量通道已经跑完，不得再派一次");
        assertTrue(rows.get(0).isSuccess());
        assertTrue(ok.sink.get(0).isSuccess());

        rows.clear();
        Holder bad = new Holder();
        bad.run("spawnSubagent", "{}", new ToolCallback[]{stub("readFile", "x")},
                new SubagentService.SpawnOutcome("子代理失败", false), true);
        assertNull(bad.error, String.valueOf(bad.error));
        assertFalse(rows.get(0).isSuccess(), "子代理自己的结论是失败 → success=false");
        assertFalse(bad.sink.get(0).isSuccess());
    }

    @Test
    @DisplayName("锁定现状：子代理失败且没有网关审计时，approved=true、high_risk=false")
    void spawnFailureWithoutAuditIsCurrentlyApproved() throws Exception {
        // 不是期望的分类。pre 不为空时不经过网关，auditFlags() 把空审计回落成「已批准、非高危」。
        // 于是失败行和「放行后抛异常」用这三列分不开。将来若补上审计，这里必须跟着改。
        Holder bad = new Holder();
        bad.run("spawnSubagent", "{}", new ToolCallback[]{stub("readFile", "x")},
                new SubagentService.SpawnOutcome("子代理失败", false), true);
        assertNull(bad.error, String.valueOf(bad.error));
        assertEquals(0, calls.get(), "批量通道不得再派一次");
        assertFalse(rows.get(0).isSuccess());
        assertTrue(rows.get(0).isApproved());
        assertFalse(rows.get(0).isHighRisk());
        assertFalse(bad.sink.get(0).isSuccess());
    }

    @Test
    @DisplayName("结果未知进入记账：success=false，但网关放行记录仍在，原文是未知那句")
    void unknownSignalIsLoggedUnsuccessfulButNotDenied() throws Exception {
        Holder unknown = new Holder();
        ToolCallback cb = ToolStubs.of("readFile", args -> {
            calls.incrementAndGet();
            // 模拟 MCP 回调：先写下「结果未知」，再返回原文。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.UNKNOWN);
            return McpToolCallback.UNKNOWN_OUTCOME;
        });
        unknown.run("readFile", "{}", new ToolCallback[]{cb}, null, true);
        assertNull(unknown.error, String.valueOf(unknown.error));
        assertEquals(1, calls.get());
        assertFalse(rows.get(0).isSuccess());
        assertTrue(rows.get(0).isApproved(), "ALLOW 已经放行，不能改成拒绝");
        assertFalse(rows.get(0).isHighRisk());
        assertEquals(McpToolCallback.UNKNOWN_OUTCOME, rows.get(0).getResult());
        assertFalse(unknown.sink.get(0).isSuccess());

        rows.clear();
        calls.set(0);
        Holder ok = new Holder();
        ok.run("readFile", "{}", new ToolCallback[]{stub("readFile", "文件内容")}, null, true);
        assertNull(ok.error, String.valueOf(ok.error));
        assertTrue(rows.get(0).isSuccess(), "下一次成功调用不能沿用上一次的未知");
        assertTrue(ok.sink.get(0).isSuccess());
    }

    // ==================== 两条链路同源 ====================

    @Test
    @DisplayName("非流式与流式共用同一处记账实现，且不再有「默认成功」")
    void bothPathsShareOneAccountingImplementation() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/example/jingjingai2/controller/ChatController.java"));

        assertEquals(2, count(src, "= executeAndRecordTool("),
                "非流式与流式各应有一处调用，共用同一实现");
        assertFalse(src.contains("toolSuccess = true"),
                "不得再用默认 true 顶替执行结果");
        assertFalse(src.contains("startsWith(\"未知的工具\")"),
                "不得再靠结果文案判断有没有执行");
    }

    // ==================== 测试辅助 ====================

    private ChatController newController() throws Exception {
        Constructor<?> ctor = ChatController.class.getConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == McpClientManager.class) args[i] = manager;
            else if (types[i] == ApprovalService.class) args[i] = approvals;
            else if (types[i] == ToolCallLogService.class) args[i] = new ToolCallLogService(log -> rows.add(log));
            else if (types[i] == SubagentProperties.class) args[i] = new SubagentProperties();
            else if (types[i] == SubagentTelemetry.class) args[i] = new SubagentTelemetry();
        }
        return (ChatController) ctor.newInstance(args);
    }

    /** 一次调用的可观测结果。线程里跑时把异常收进来，主线程再断言。 */
    private final class Holder {
        ChatResponse.ToolCallRecord record;
        final List<ChatResponse.ToolCallRecord> sink;
        Throwable error;

        Holder() { this(new ArrayList<>()); }
        Holder(List<ChatResponse.ToolCallRecord> sink) { this.sink = sink; }

        void run(String toolName, String arguments, ToolCallback[] round,
                 SubagentService.SpawnOutcome pre, boolean streaming) {
            List<Message> messages = new ArrayList<>();
            AssistantMessage.ToolCall tc =
                    new AssistantMessage.ToolCall("call-1", "function", toolName, arguments);
            try (ToolCallContext.Scope ignored = (ToolCallContext.Scope) open.invoke(
                    controller, 7L, "conv", streaming,
                    streaming ? new SseEmitter(5_000L) : null, messages, new AtomicInteger(-1))) {
                record = (ChatResponse.ToolCallRecord) execute.invoke(
                        controller, round, tc, "conv", messages, pre, 7L, 0,
                        new StepToolLedger("conv"), sink);
            } catch (Throwable t) {
                error = t;
            }
        }
    }

    private ToolCallback stub(String name, String result) {
        return ToolStubs.of(name, args -> {
            calls.incrementAndGet();
            return result;
        });
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

    private static int count(String src, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = src.indexOf(needle, from);
            if (at < 0) return n;
            n++;
            from = at + needle.length();
        }
    }
}
