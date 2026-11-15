package com.example.jingjingai2.controller;

import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpListedTool;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.mcp.McpServerStatus;
import com.example.jingjingai2.mcp.McpToolAnnotations;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.McpPermissionExplainer;
import com.example.jingjingai2.permission.McpUserPolicyStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 当前用户的 MCP 策略。生效决定只来自 {@link McpPermissionExplainer#explain}，
 * 这里不重算。身份只认 JWT 放进 request 的 userId。
 */
@RestController
@RequestMapping("/ai/mcp")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class McpPermissionController {

    private final McpClientManager manager;
    private final McpPermissionExplainer explainer;
    private final McpUserPolicyStore store;
    private final McpRoundActivation activation;
    private final ApprovalService approvals;

    public McpPermissionController(McpClientManager manager,
                                   McpPermissionExplainer explainer,
                                   McpUserPolicyStore store,
                                   McpRoundActivation activation,
                                   ApprovalService approvals) {
        this.manager = manager;
        this.explainer = explainer;
        this.store = store;
        this.activation = activation;
        this.approvals = approvals;
    }

    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> tools(
            @RequestParam(value = "conversationId", required = false) String conversationId,
            HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpServerStatus status : manager.statuses(userId)) {
            servers.add(serverView(userId, status, conversationId));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servers", servers);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/tools")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        String server = text(body.get("server"));
        if (server == null || manager.findServer(userId, server) == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "服务不存在"));
        }
        boolean reset = Boolean.TRUE.equals(body.get("resetTools"));
        boolean hasTool = body.containsKey("tool");
        boolean hasDefault = body.containsKey("serverDefault");
        int ops = (reset ? 1 : 0) + (hasTool ? 1 : 0) + (hasDefault ? 1 : 0);
        if (ops != 1) {
            return ResponseEntity.badRequest().body(Map.of("message", "一次只改一项"));
        }
        // 只有写成功才清会话授权。上面的校验失败直接返回，到不了这里。
        // 单工具只清这个用户的这个工具；服务级默认和重置会改变多个工具的生效结果，
        // 清这个用户在这个服务下的全部会话授权。其他用户、其他服务不动。
        if (reset) {
            store.resetTools(userId, server);
            approvals.forgetServer(userId, server);
            return ResponseEntity.ok(Map.of("ok", true));
        }
        if (hasDefault) {
            String effect = text(body.get("serverDefault"));
            if (effect == null) {
                store.clearServerDefault(userId, server);
                approvals.forgetServer(userId, server);
                return ResponseEntity.ok(Map.of("ok", true));
            }
            String normalized = effectOf(effect);
            if (normalized == null) return ResponseEntity.badRequest().body(Map.of("message", "effect 只能是 ALLOW、ASK、DENY"));
            store.setServerDefault(userId, server, normalized);
            approvals.forgetServer(userId, server);
            return ResponseEntity.ok(Map.of("ok", true));
        }
        String tool = text(body.get("tool"));
        McpListedTool listed = tool == null ? null : manager.findListed(userId, server, tool);
        if (listed == null) return ResponseEntity.badRequest().body(Map.of("message", "工具不存在"));
        if (!listed.exposed) return ResponseEntity.badRequest().body(Map.of("message", "配置文件未暴露，不能修改"));
        String normalized = effectOf(text(body.get("effect")));
        if (normalized == null) return ResponseEntity.badRequest().body(Map.of("message", "effect 只能是 ALLOW、ASK、DENY"));
        store.setTool(userId, server, listed.originalName, normalized, listed.definitionHash());
        approvals.forgetTool(userId, server, listed.originalName);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Map<String, Object> serverView(Long userId, McpServerStatus status, String conversationId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", status.getName());
        row.put("state", status.getState());
        row.put("lastError", status.getLastError());
        String serverDefault = store.serverDefault(userId, status.getName());
        row.put("serverDefault", serverDefault);
        row.put("spillCount", null);
        row.put("spillLastBytes", null);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpListedTool tool : manager.listedTools(userId, status.getName())) {
            tools.add(toolView(userId, tool, serverDefault, conversationId));
        }
        row.put("toolCount", tools.size());
        row.put("tools", tools);
        return row;
    }

    private Map<String, Object> toolView(Long userId, McpListedTool tool, String serverDefault,
                                         String conversationId) {
        McpPermissionExplainer.ExplainResult er = explainer.explain(
                userId, tool.server, tool.originalName, null);
        McpToolAnnotations ann = tool.annotations;
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("readOnlyHint", ann.readOnlyHint);
        annotations.put("destructiveHint", ann.destructiveHint);
        annotations.put("idempotentHint", ann.idempotentHint);
        annotations.put("openWorldHint", ann.openWorldHint);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", tool.originalName);
        row.put("exposedName", tool.exposedName);
        row.put("description", tool.description);
        row.put("annotations", annotations);
        row.put("exposed", tool.exposed);
        row.put("userSetting", store.toolEffect(userId, tool.server, tool.originalName));
        row.put("serverDefault", serverDefault);
        row.put("effect", er.effect.name());
        row.put("reason", er.reason);
        row.put("sessionGrant", er.sessionGrant);
        row.put("maybeForceAsk", explainer.maybeForceAsk(tool.originalName));
        row.put("definitionChanged", er.definitionChanged);
        row.put("activated", activation != null && activation.activated(conversationId, tool.exposedName));
        return row;
    }

    private static Long userId(HttpServletRequest request) {
        Object v = request.getAttribute("userId");
        return v instanceof Long ? (Long) v : null;
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String effectOf(String raw) {
        if (raw == null) return null;
        String e = raw.trim().toUpperCase(Locale.ROOT);
        if ("ALLOW".equals(e) || "ASK".equals(e) || "DENY".equals(e)) return e;
        return null;
    }
}
