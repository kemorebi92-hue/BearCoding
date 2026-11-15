package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.mcp.McpOutputStore;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 落盘统计要求登录，并且只能看自己的会话。 */
class McpOutputControllerTest {

    @Test
    @DisplayName("未登录 401；别人的会话 404；自己的会话 200")
    void ownershipChecked() {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("alpha", spec);
        props.setServers(servers);
        McpClientManager manager = new McpClientManager(props);

        McpOutputStore store = new McpOutputStore(new McpOutputProperties());
        // 只有 userId=7 拥有 conv-1；别的用户看不到
        ConversationService conversations = new ConversationService(null, null) {
            @Override
            public ConversationSummary get(Long userId, String conversationId) {
                if (userId != null && userId == 7L && "conv-1".equals(conversationId)) {
                    ConversationSummary s = new ConversationSummary();
                    s.setConversationId("conv-1");
                    return s;
                }
                return null;
            }
        };
        McpOutputController controller = new McpOutputController(manager, store, conversations);

        assertEquals(401, controller.stats("conv-1", asUser(null)).getStatusCode().value());
        assertEquals(404, controller.stats("conv-1", asUser(8L)).getStatusCode().value());
        assertEquals(404, controller.stats("someone-else", asUser(7L)).getStatusCode().value());
        assertEquals(400, controller.stats("", asUser(7L)).getStatusCode().value());
        assertEquals(200, controller.stats("conv-1", asUser(7L)).getStatusCode().value());

        Map<String, Object> body = controller.stats("conv-1", asUser(7L)).getBody();
        assertNotNull(body);
        assertEquals("conv-1", body.get("conversationId"));
    }

    private static HttpServletRequest asUser(Long id) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) {
                        return args != null && "userId".equals(args[0]) ? id : null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }
}
