package com.example.jingjingai2.agent;

import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpUrlGuard;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.PermissionProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolCallContext.ApprovalLink;
import com.example.jingjingai2.permission.ToolPermissionGate;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 子代理房间走 ToolDispatcher。没有 ApprovalLink 时不能弹卡片；挂上父会话的链路后就地等待。 */
class SubagentPermissionPathTest {

    private final SubagentProperties props = new SubagentProperties();
    private final AgentCancellationRegistry registry = new AgentCancellationRegistry();
    private ApprovalService approvals;

    @BeforeEach
    void install() {
        approvals = new ApprovalService(1);
        ToolPermissionGate gate = new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties());
        ToolDispatcher.installGate(gate);
    }

    @AfterEach
    void uninstall() {
        ToolDispatcher.installGate(null);
        McpUrlGuard.setResolverForTest(null);
    }

    @Test
    @DisplayName("implement 房间的 writeFile 不执行，并告诉模型去找主代理")
    void implementAskDoesNotRun() {
        AtomicInteger writes = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "writeFile", "{\"path\":\"a.java\",\"content\":\"x\"}")))
                .thenReply("结论：没改成", 1);
        SubagentResult result = new SubagentRunner(props, registry, ToolStubs.fullSet(null, writes, null, null))
                .run(new SubagentSpec("改一个文件", SubagentSpec.Mode.IMPLEMENT, 4, "conv-1"), model, null);
        assertEquals(0, writes.get());
        assertEquals(SubagentResult.FailureReason.SUCCESS, result.failureReason());
        String toolResult = firstToolResult(model.prompts().get(1));
        assertNotNull(toolResult);
        assertTrue(toolResult.contains("子代理无法请求"), toolResult);
    }

    @Test
    @DisplayName("explore 房间的 readFile 仍直接执行")
    void exploreReadStillRuns() {
        AtomicInteger reads = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")))
                .thenReply("结论：读过了", 1);
        new SubagentRunner(props, registry, ToolStubs.fullSet(reads, null, null, null))
                .run(new SubagentSpec("看一下", SubagentSpec.Mode.EXPLORE, 4, "conv-1"), model, null);
        assertEquals(1, reads.get());
    }

    @Test
    @DisplayName("挂上父会话链路后，writeFile 批准才执行 1 次，参数是原文")
    void linkedWriteRunsOnceAfterApprove() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>();
        String args = "{\"path\":\"a.java\",\"content\":\"x\"}";
        Outcome outcome = runLinked(writes, seen, args, "APPROVE", 7L, false);
        assertEquals(1, writes.get());
        assertEquals(args, seen.get());
        assertEquals("写一个文件", outcome.card.get("subagentLabel"));
        assertEquals(7L, outcome.record.userId);
        assertEquals("parent-conv", outcome.record.conversationId);
    }

    @Test
    @DisplayName("拒绝、超时、父会话取消都不执行，线程会退出")
    void linkedRejectTimeoutAndCancel() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Outcome rejected = runLinked(writes, new AtomicReference<>(), "{}", "REJECT", 7L, false);
        assertEquals(0, writes.get());
        assertTrue(rejected.toolText.contains("用户拒绝"), rejected.toolText);

        writes.set(0);
        Outcome timed = runLinked(writes, new AtomicReference<>(), "{}", null, 7L, false);
        assertEquals(0, writes.get());
        assertTrue(timed.toolText.contains("超时"), timed.toolText);

        writes.set(0);
        Outcome cancelled = runLinked(writes, new AtomicReference<>(), "{}", "CANCEL", 7L, false);
        assertEquals(0, writes.get());
        assertTrue(cancelled.toolText.contains("取消"), cancelled.toolText);
        assertFalse(cancelled.thread.isAlive());
    }

    @Test
    @DisplayName("别的用户不能批子代理的审批；污点后仍 ASK，但可以本会话允许")
    void linkedCrossUserAndTaint() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Outcome foreign = runLinked(writes, new AtomicReference<>(), "{}", "REJECT", 8L, false);
        assertEquals(403, foreign.foreignStatus);
        assertEquals(0, writes.get());

        writes.set(0);
        Outcome tainted = runLinked(writes, new AtomicReference<>(), "{\"path\":\"a\"}", "APPROVE", 7L, true);
        assertEquals(Boolean.TRUE, tainted.card.get("tainted"));
        assertTrue(String.valueOf(tainted.card.get("risk")).contains("此操作可能由网页内容诱发"));
        assertEquals(Boolean.TRUE, tainted.card.get("canSessionGrant"));
        assertEquals(1, writes.get());
    }

    private Outcome runLinked(AtomicInteger writes, AtomicReference<String> seen, String args,
                              String decision, long decider, boolean tainted) throws Exception {
        AtomicReference<Map<String, Object>> card = new AtomicReference<>();
        ToolCallContext.Events events = new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) { card.set(payload); }
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
        ApprovalLink link;
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "parent-conv", true, false, events, () -> false))) {
            link = ApprovalLink.capture("写一个文件");
        }
        assertNotNull(link);
        ToolCallback write = ToolStubs.of("writeFile", a -> {
            writes.incrementAndGet();
            seen.set(a);
            return "wrote";
        });
        SubagentRunner runner = new SubagentRunner(props, registry, new ToolCallback[]{write});
        if (tainted) {
            McpProperties mcp = new McpProperties();
            mcp.setEnabled(true);
            McpClientManager manager = new McpClientManager(mcp);
            manager.markTainted("parent-conv");
            Field field = SubagentRunner.class.getDeclaredField("mcpClientManager");
            field.setAccessible(true);
            field.set(runner, manager);
        }
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "writeFile", args)))
                .thenReply("结论：结束", 1);
        SubagentResult[] result = new SubagentResult[1];
        Thread thread = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ApprovalLink.attach(link)) {
                result[0] = runner.run(
                        new SubagentSpec("写一个文件", SubagentSpec.Mode.IMPLEMENT, 4, "child-conv"),
                        model, null);
            }
        });
        thread.start();
        ApprovalService.Record record = awaitPending();
        int foreignStatus = 0;
        if ("CANCEL".equals(decision)) {
            approvals.cancelConversation("parent-conv");
        } else if (decision == null) {
            // 等到 1 秒超时
        } else if (decider != 7L) {
            foreignStatus = approvals.decide(record.id, decider, "APPROVE").http;
            approvals.decide(record.id, 7L, "REJECT");
        } else {
            approvals.decide(record.id, decider, decision);
        }
        thread.join(4000);
        assertFalse(thread.isAlive());
        Outcome outcome = new Outcome();
        outcome.card = card.get();
        outcome.record = record;
        outcome.thread = thread;
        outcome.foreignStatus = foreignStatus;
        outcome.toolText = firstToolResult(model.prompts().get(1));
        return outcome;
    }

    private ApprovalService.Record awaitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (ApprovalService.Record record : approvals.pending()) return record;
            Thread.sleep(20);
        }
        fail("没有等到子代理的审批");
        return null;
    }

    private static final class Outcome {
        Map<String, Object> card;
        ApprovalService.Record record;
        Thread thread;
        int foreignStatus;
        String toolText;
    }

    private static String firstToolResult(Prompt p) {
        for (Message m : p.getInstructions()) {
            if (m instanceof ToolResponseMessage trm && !trm.getResponses().isEmpty()) {
                return trm.getResponses().get(0).responseData();
            }
        }
        return null;
    }
}
