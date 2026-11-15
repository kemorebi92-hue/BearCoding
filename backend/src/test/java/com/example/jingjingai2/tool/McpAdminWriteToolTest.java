package com.example.jingjingai2.tool;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.controller.McpServerAdminController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写类 MCP 管理工具只做三件事：从会话上下文取身份、按白名单收参数、把结果说清楚。
 * 真正的改配置/启停/连接/删除逻辑在 controller 上，由 {@code McpServerAdminControllerTest} 覆盖；
 * 这里用子类桩验证工具这一层，不连数据库、不启动进程。
 */
class McpAdminWriteToolTest {

    private StubAdmin admin;
    private McpAdminWriteTool tool;

    @BeforeEach
    void setUp() {
        admin = new StubAdmin();
        tool = new McpAdminWriteTool(admin);
        ConversationContext.set(7L, "conv");
    }

    @AfterEach
    void tearDown() {
        ConversationContext.clear();
    }

    @Test
    @DisplayName("改配置：白名单字段原样传给 controller，userId 取自会话上下文")
    void updatePassesWhitelistedFields() {
        admin.updateResponse = ok(Map.of("action", "配置已保存（服务已停用，未连接）", "name", "memory"));
        String out = tool.updateMyMcpServer("memory", "{\"trust\":\"untrusted\",\"maxResultChars\":4000}");
        assertEquals(7L, admin.lastUpdateUser);
        assertEquals("memory", admin.lastUpdateName);
        assertEquals("untrusted", admin.lastPatch.get("trust"));
        assertEquals(4000, admin.lastPatch.get("maxResultChars"));
        assertTrue(out.startsWith("操作完成"), out);
    }

    @Test
    @DisplayName("改配置：env 与未知字段一律拒绝，不调用 controller")
    void updateRejectsEnvAndUnknownFields() {
        assertTrue(tool.updateMyMcpServer("memory", "{\"env\":{\"TOKEN\":\"x\"}}").contains("不接受 env"));
        assertTrue(tool.updateMyMcpServer("memory", "{\"enabled\":false}").contains("不能修改字段：enabled"));
        assertNull(admin.lastUpdateName, "被拒绝的参数不应下发");
    }

    @Test
    @DisplayName("改配置：参数不是 JSON 对象、空 patch、空服务名都直接拒绝")
    void updateRejectsBadInput() {
        assertTrue(tool.updateMyMcpServer("memory", "not-json").contains("JSON"));
        assertTrue(tool.updateMyMcpServer("memory", "{}").contains("没有要修改的字段"));
        assertTrue(tool.updateMyMcpServer(" ", "{\"trust\":\"trusted\"}").contains("服务名不能为空"));
    }

    @Test
    @DisplayName("启停：布尔值透传")
    void enablePassesThrough() {
        admin.enableResponse = ok(Map.of("action", "已启用", "name", "memory", "connected", true, "toolCount", 9));
        String out = tool.setMyMcpServerEnabled("memory", true);
        assertEquals(Boolean.TRUE, admin.lastEnabled);
        assertEquals(7L, admin.lastEnableUser);
        assertTrue(out.contains("已连接"), out);
        assertTrue(out.contains("9"), out);
    }

    @Test
    @DisplayName("连接控制：只接受四个动作，大小写归一后下发")
    void controlValidatesAction() {
        admin.controlResponse = ok(Map.of("action", "已断开", "name", "memory"));
        tool.controlMyMcpServer("memory", "Reconnect");
        assertEquals("reconnect", admin.lastAction);
        assertTrue(tool.controlMyMcpServer("memory", "delete").contains("action 只能是"));
        assertTrue(tool.controlMyMcpServer("memory", "").contains("action 只能是"));
    }

    @Test
    @DisplayName("删除：confirm 与服务名不一致时不调用 controller，一致才删")
    void deleteRequiresMatchingConfirm() {
        assertTrue(tool.deleteMyMcpServer("memory", "other").contains("未删除"));
        assertTrue(tool.deleteMyMcpServer("memory", "").contains("未删除"));
        assertNull(admin.lastDeleteName);

        admin.deleteResponse = ok(Map.of("action", "已删除", "name", "memory", "policiesCleared", 2));
        String out = tool.deleteMyMcpServer("memory", " memory ");
        assertEquals("memory", admin.lastDeleteName);
        assertEquals(7L, admin.lastDeleteUser);
        assertTrue(out.contains("清理 2 条授权记录"), out);
    }

    @Test
    @DisplayName("没有会话上下文时一律拒绝，不调用 controller")
    void rejectsWithoutContext() {
        ConversationContext.clear();
        assertTrue(tool.updateMyMcpServer("memory", "{\"trust\":\"trusted\"}").contains("不在会话上下文"));
        assertTrue(tool.setMyMcpServerEnabled("memory", true).contains("不在会话上下文"));
        assertTrue(tool.controlMyMcpServer("memory", "connect").contains("不在会话上下文"));
        assertTrue(tool.deleteMyMcpServer("memory", "memory").contains("不在会话上下文"));
        assertNull(admin.lastDeleteName);
    }

    @Test
    @DisplayName("失败响应原样说明原因，不泄露配置全文")
    void failureIsReported() {
        admin.controlResponse = ResponseEntity.badRequest().body(Map.of("message", "服务不存在"));
        assertEquals("操作失败：服务不存在", tool.controlMyMcpServer("nope", "connect"));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    /** 只记录调用参数的 controller 子类，不碰 Mapper / 运行时。 */
    static final class StubAdmin extends McpServerAdminController {
        Long lastUpdateUser;
        String lastUpdateName;
        Map<String, Object> lastPatch;
        Long lastEnableUser;
        String lastEnableName;
        Boolean lastEnabled;
        Long lastControlUser;
        String lastControlName;
        String lastAction;
        Long lastDeleteUser;
        String lastDeleteName;

        ResponseEntity<Map<String, Object>> updateResponse = ResponseEntity.ok(Map.of("action", "已处理"));
        ResponseEntity<Map<String, Object>> enableResponse = ResponseEntity.ok(Map.of("action", "已处理"));
        ResponseEntity<Map<String, Object>> controlResponse = ResponseEntity.ok(Map.of("action", "已处理"));
        ResponseEntity<Map<String, Object>> deleteResponse = ResponseEntity.ok(Map.of("action", "已处理"));

        StubAdmin() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ResponseEntity<Map<String, Object>> updateAsUser(Long userId, String name, Map<String, Object> body) {
            lastUpdateUser = userId;
            lastUpdateName = name;
            lastPatch = new LinkedHashMap<>(body);
            return updateResponse;
        }

        @Override
        public ResponseEntity<Map<String, Object>> setEnabledAsUser(Long userId, String name, boolean enabled) {
            lastEnableUser = userId;
            lastEnableName = name;
            lastEnabled = enabled;
            return enableResponse;
        }

        @Override
        public ResponseEntity<Map<String, Object>> controlAsUser(Long userId, String name, String action) {
            lastControlUser = userId;
            lastControlName = name;
            lastAction = action;
            return controlResponse;
        }

        @Override
        public ResponseEntity<Map<String, Object>> deleteAsUser(Long userId, String name) {
            lastDeleteUser = userId;
            lastDeleteName = name;
            return deleteResponse;
        }
    }
}
