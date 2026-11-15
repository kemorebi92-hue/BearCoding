package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.service.TaskPlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StopOwnershipTest {

    @Test
    @DisplayName("/stop 不属于当前用户时 404，且不取消别人的审批")
    void stopChecksOwner() {
        ConversationService conversations = new ConversationService(null, null) {
            @Override
            public ConversationSummary get(Long userId, String conversationId) {
                if (Long.valueOf(7L).equals(userId) && "mine".equals(conversationId)) {
                    ConversationSummary s = new ConversationSummary();
                    s.setConversationId(conversationId);
                    return s;
                }
                return null;
            }
        };
        ApprovalService approvals = new ApprovalService(30);
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        ConversationController controller = new ConversationController(
                conversations, registry, new TaskPlanService(null, null), approvals);

        var foreign = approvals.create(7L, "theirs", "writeFile", "原生", "{}", "x", false, true);
        HttpServletRequest req = request(8L);
        ResponseEntity<Map<String, Object>> denied = controller.stop("theirs", req);
        assertEquals(404, denied.getStatusCode().value());
        assertEquals(ApprovalService.Status.PENDING, foreign.status());

        HttpServletRequest owner = request(7L);
        registry.arm("mine");
        var own = approvals.create(7L, "mine", "writeFile", "原生", "{}", "x", false, true);
        ResponseEntity<Map<String, Object>> ok = controller.stop("mine", owner);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(Boolean.TRUE, ok.getBody().get("accepted"));
        assertEquals(ApprovalService.Status.CANCELLED, own.status());
    }

    private static HttpServletRequest request(Long userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) return userId;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }
}
