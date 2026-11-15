package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.PermissionProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolPermissionGate;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * saveUserMemory / forgetUserMemory 走现有 ASK 审批。
 * 未批准不写库；批准后才写；卡片上能看到键和内容；FULL_ACCESS 也不能跳过。
 */
class UserMemoryApprovalTest {

    private static final Long USER = 7L;
    private static final String CONV = "conv-memory-approval";
    private static final String ARGS =
            "{\"category\":\"preference\",\"key\":\"reply_lang\",\"content\":\"以后都用中文回复\"}";

    private ApprovalService approvals;
    private FakeUserMemoryMapper memoryMapper;
    private UserMemoryService memoryService;
    private ToolCallback[] callbacks;
    private AtomicReference<Map<String, Object>> card;

    @BeforeEach
    void install() {
        approvals = new ApprovalService(5);
        memoryMapper = new FakeUserMemoryMapper();
        UserMemoryProperties props = new UserMemoryProperties();
        props.setEnabled(true);
        memoryService = new UserMemoryService(memoryMapper, props);
        callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new UserMemoryTool(memoryService))
                .build()
                .getToolCallbacks();
        card = new AtomicReference<>();
        ToolPermissionGate gate = new ToolPermissionGate(
                new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties());
        gate.setConversationService(new ConversationService(null, null) {
            @Override
            public String permissionMode(Long userId, String conversationId) {
                return MODE_FULL_ACCESS;
            }
        });
        ToolDispatcher.installGate(gate);
        ConversationContext.set(USER, CONV);
    }

    @AfterEach
    void uninstall() {
        approvals.clear();
        ToolDispatcher.installGate(null);
        ConversationContext.clear();
    }

    @Test
    @DisplayName("拒绝或尚未批准时不写入；批准后才写入当前用户")
    void saveWaitsForApprovalAndShowsThePayload() throws Exception {
        String[] result = new String[1];
        Thread worker = new Thread(() -> result[0] = dispatch("saveUserMemory", ARGS));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        assertEquals(0, memoryService.countByUser(USER));
        Map<String, Object> shown = card.get();
        assertNotNull(shown);
        assertEquals("saveUserMemory", shown.get("toolName"));
        assertEquals(Boolean.FALSE, shown.get("canSessionGrant"));
        String arguments = String.valueOf(shown.get("arguments"));
        assertTrue(arguments.contains("reply_lang"), arguments);
        assertTrue(arguments.contains("以后都用中文回复"), arguments);
        assertTrue(String.valueOf(shown.get("risk")).contains("长期记忆"));

        approvals.decide(rec.id, USER, "REJECT");
        worker.join(3000);
        assertTrue(result[0].contains("用户拒绝了该操作"), result[0]);
        assertEquals(0, memoryService.countByUser(USER));

        Thread again = new Thread(() -> result[0] = dispatch("saveUserMemory", ARGS));
        again.start();
        ApprovalService.Record approved = awaitRecord();
        assertEquals(0, memoryService.countByUser(USER), "批准前仍然不能写");
        approvals.decide(approved.id, USER, "APPROVE");
        again.join(3000);
        assertTrue(result[0].contains("已记住"), result[0]);
        assertEquals(USER, memoryService.getOne(USER, "preference", "reply_lang").getUserId());
        assertEquals("以后都用中文回复",
                memoryService.getOne(USER, "preference", "reply_lang").getContent());
        assertNull(memoryService.getOne(8L, "preference", "reply_lang"));
    }

    @Test
    @DisplayName("删除也要单独批准，未批准不删")
    void forgetRequiresItsOwnApproval() throws Exception {
        assertTrue(memoryService.upsert(USER, "preference", "reply_lang", "中文", CONV).isAccepted());
        String[] result = new String[1];
        Thread worker = new Thread(() -> result[0] = dispatch("forgetUserMemory",
                "{\"category\":\"preference\",\"key\":\"reply_lang\"}"));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        assertNotNull(memoryService.getOne(USER, "preference", "reply_lang"));
        assertEquals(Boolean.FALSE, card.get().get("canSessionGrant"));
        approvals.decide(rec.id, USER, "REJECT");
        worker.join(3000);
        assertTrue(result[0].contains("用户拒绝了该操作"), result[0]);
        assertNotNull(memoryService.getOne(USER, "preference", "reply_lang"));

        Thread again = new Thread(() -> result[0] = dispatch("forgetUserMemory",
                "{\"category\":\"preference\",\"key\":\"reply_lang\"}"));
        again.start();
        ApprovalService.Record approved = awaitRecord();
        ApprovalService.HttpResult foreign = approvals.decide(approved.id, 8L, "APPROVE");
        assertEquals(403, foreign.http, "别人不能批准这次删除");
        approvals.decide(approved.id, USER, "APPROVE");
        again.join(3000);
        assertTrue(result[0].contains("已删除"), result[0]);
        assertNull(memoryService.getOne(USER, "preference", "reply_lang"));
    }

    @Test
    @DisplayName("即使批准，密钥内容仍然拒绝写入")
    void approvedSecretIsStillRejectedByTheTool() throws Exception {
        String args = "{\"category\":\"constraint\",\"key\":\"token\",\"content\":\"password=supersecret\"}";
        String[] result = new String[1];
        Thread worker = new Thread(() -> result[0] = dispatch("saveUserMemory", args));
        worker.start();
        ApprovalService.Record rec = awaitRecord();
        approvals.decide(rec.id, USER, "APPROVE");
        worker.join(3000);
        assertTrue(result[0].contains("拒绝写入"), result[0]);
        assertEquals(0, memoryService.countByUser(USER));
    }

    private String dispatch(String name, String args) {
        ConversationContext.set(USER, CONV);
        try {
            ToolCallContext ctx = new ToolCallContext(USER, CONV, true, false, new ToolCallContext.Events() {
                @Override public void approval(Map<String, Object> payload) { card.set(payload); }
                @Override public void approvalResult(Map<String, Object> payload) {}
            }, () -> false);
            try (ToolCallContext.Scope ignored = ToolCallContext.open(ctx)) {
                return ToolDispatcher.dispatch(callbacks, name, args);
            }
        } finally {
            ConversationContext.clear();
        }
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
}
