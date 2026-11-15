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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 权限模式保存接口的归属与入参校验。不连接数据库。
 */
class ConversationPermissionControllerTest {

    private static final class Fake extends ConversationService {
        final Map<String, String> modes = new LinkedHashMap<>();

        Fake() {
            super(null, null);
        }

        @Override
        public ConversationSummary get(Long userId, String conversationId) {
            if (!Long.valueOf(7L).equals(userId) || !modes.containsKey(conversationId)) return null;
            ConversationSummary s = new ConversationSummary();
            s.setConversationId(conversationId);
            s.setPermissionMode(modes.get(conversationId));
            return s;
        }

        @Override
        public boolean updatePermissionMode(Long userId, String conversationId, String mode) {
            if (!isPermissionMode(mode)) return false;
            if (!Long.valueOf(7L).equals(userId) || !modes.containsKey(conversationId)) return false;
            modes.put(conversationId, mode);
            return true;
        }
    }

    private final Fake conversations = new Fake();
    private final ConversationController controller = new ConversationController(
            conversations, new AgentCancellationRegistry(), new TaskPlanService(null, null), new ApprovalService(30));

    @Test
    @DisplayName("三种合法模式都能保存，返回保存后的值")
    void savesAllowedModes() {
        conversations.modes.put("mine", ConversationService.MODE_ASK);
        for (String mode : new String[]{ConversationService.MODE_ASK,
                ConversationService.MODE_WORKSPACE_EDIT, ConversationService.MODE_FULL_ACCESS}) {
            ResponseEntity<Map<String, Object>> resp =
                    controller.permissionMode("mine", body(mode), request(7L));
            assertEquals(200, resp.getStatusCode().value(), mode);
            assertEquals(Boolean.TRUE, resp.getBody().get("success"));
            assertEquals(mode, resp.getBody().get("permissionMode"));
            assertEquals(mode, conversations.modes.get("mine"));
        }
    }

    @Test
    @DisplayName("非法模式返回 400，原值不变")
    void rejectsIllegalMode() {
        conversations.modes.put("mine", ConversationService.MODE_ASK);
        ResponseEntity<Map<String, Object>> resp =
                controller.permissionMode("mine", body("ADMIN"), request(7L));
        assertEquals(400, resp.getStatusCode().value());
        assertEquals(ConversationService.MODE_ASK, conversations.modes.get("mine"));
    }

    @Test
    @DisplayName("别人的会话和不存在的会话都返回 404，且不写入")
    void rejectsForeignAndMissing() {
        conversations.modes.put("mine", ConversationService.MODE_ASK);

        ResponseEntity<Map<String, Object>> foreign =
                controller.permissionMode("mine", body(ConversationService.MODE_FULL_ACCESS), request(8L));
        assertEquals(404, foreign.getStatusCode().value());
        assertEquals(ConversationService.MODE_ASK, conversations.modes.get("mine"), "不能改到别人的会话");

        ResponseEntity<Map<String, Object>> missing =
                controller.permissionMode("nope", body(ConversationService.MODE_FULL_ACCESS), request(7L));
        assertEquals(404, missing.getStatusCode().value());
        assertNull(conversations.modes.get("nope"));
    }

    private static Map<String, Object> body(String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissionMode", mode);
        return body;
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
