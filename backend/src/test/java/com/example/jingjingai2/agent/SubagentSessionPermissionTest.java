package com.example.jingjingai2.agent;

import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.PermissionProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolCallContext.ApprovalLink;
import com.example.jingjingai2.permission.ToolPermissionGate;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真实子代理房间走 ToolDispatcher：父线程的 ApprovalLink 恢复 userId / conversationId 后，
 * 子代理读到的必须是父会话的权限模式；恢复失败时不能意外放行。
 */
class SubagentSessionPermissionTest {

    private final SubagentProperties props = new SubagentProperties();
    private final AgentCancellationRegistry registry = new AgentCancellationRegistry();
    private final AtomicInteger writes = new AtomicInteger();
    private ApprovalService approvals;

    private static final class Modes extends ConversationService {
        final Map<String, String> modes = new LinkedHashMap<>();

        Modes() {
            super(null, null);
        }

        @Override
        public String permissionMode(Long userId, String conversationId) {
            if (userId == null || conversationId == null) return null;
            return modes.get(userId + "\0" + conversationId);
        }
    }

    private final Modes modes = new Modes();

    @BeforeEach
    void install() {
        writes.set(0);
        approvals = new ApprovalService(2);
        ToolPermissionGate gate = new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties());
        gate.setConversationService(modes);
        ToolDispatcher.installGate(gate);
    }

    @AfterEach
    void uninstall() {
        ToolDispatcher.installGate(null);
        approvals.clear();
    }

    @Test
    @DisplayName("父会话 FULL_ACCESS：子代理恢复 userId 和 conversationId 后，writeFile 直接执行一次")
    void fullAccessSubagentRunsWrite() {
        modes.modes.put("7\0conv-1", ConversationService.MODE_FULL_ACCESS);
        FakeChatModel model = writeOnceThenConclude();

        runInParent(7L, "conv-1", () -> runner().run(
                new SubagentSpec("改一个文件", SubagentSpec.Mode.IMPLEMENT, 4, "conv-1"), model, null));

        assertEquals(1, writes.get(), "子代理应当读到父会话的 FULL_ACCESS");
    }

    @Test
    @DisplayName("父会话 ASK：子代理仍然要审批，拒绝后 writeFile 一次都不执行")
    void askSubagentStillAsks() throws Exception {
        modes.modes.put("7\0conv-1", ConversationService.MODE_ASK);
        FakeChatModel model = writeOnceThenConclude();

        Thread worker = new Thread(() -> runInParent(7L, "conv-1", () -> runner().run(
                new SubagentSpec("改一个文件", SubagentSpec.Mode.IMPLEMENT, 4, "conv-1"), model, null)));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        approvals.decide(rec.id, 7L, "REJECT");
        worker.join(5000);

        assertEquals(0, writes.get(), "ASK 会话的子代理不能因为父会话而免审批");
    }

    @Test
    @DisplayName("没有 ApprovalLink：子代理拿不到 userId，即使会话是 FULL_ACCESS 也不放行")
    void noLinkDoesNotAutoAllow() {
        modes.modes.put("7\0conv-1", ConversationService.MODE_FULL_ACCESS);
        FakeChatModel model = writeOnceThenConclude();

        runner().run(new SubagentSpec("改一个文件", SubagentSpec.Mode.IMPLEMENT, 4, "conv-1"), model, null);

        assertEquals(0, writes.get(), "上下文恢复失败时不能放行");
    }

    private SubagentRunner runner() {
        return new SubagentRunner(props, registry, ToolStubs.fullSet(null, writes, null, null));
    }

    private static FakeChatModel writeOnceThenConclude() {
        return new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "writeFile", "{\"path\":\"a.java\",\"content\":\"x\"}")))
                .thenReply("结论：处理完了", 1);
    }

    /** 模拟父线程：打开带事件的上下文，再按现有 ApprovalLink 机制交给子代理线程。 */
    private static void runInParent(Long userId, String conversationId, Runnable body) {
        ToolCallContext parent = new ToolCallContext(userId, conversationId, true, false, noop(), () -> false);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(parent)) {
            ApprovalLink link = ApprovalLink.capture("implement");
            assertNotNull(link, "父线程有 userId 和事件时应当能捕获链路");
            try (ToolCallContext.Scope attached = ApprovalLink.attach(link)) {
                body.run();
            }
        }
    }

    private ApprovalService.Record awaitRecord() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            var pending = approvals.pending();
            if (!pending.isEmpty()) return pending.get(0);
            Thread.sleep(20);
        }
        throw new AssertionError("没有等到子代理的审批卡");
    }

    private static ToolCallContext.Events noop() {
        return new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
    }
}
