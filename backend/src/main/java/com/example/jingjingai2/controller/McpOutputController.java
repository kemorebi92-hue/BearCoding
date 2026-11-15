package com.example.jingjingai2.controller;

import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpOutputStore;
import com.example.jingjingai2.mcp.McpServerStatus;
import com.example.jingjingai2.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本会话的 MCP 超限落盘统计。要求登录，并且会话必须属于当前用户
 * —— 和 {@code POST /ai/conversations/{id}/stop} 用同一个归属校验。
 */
@RestController
@RequestMapping("/ai/mcp/output")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class McpOutputController {

    private final McpClientManager manager;
    private final McpOutputStore store;
    private final ConversationService conversations;

    public McpOutputController(McpClientManager manager, McpOutputStore store,
                               ConversationService conversations) {
        this.manager = manager;
        this.store = store;
        this.conversations = conversations;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam(value = "conversationId", required = false) String conversationId,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "缺少 conversationId"));
        }
        if (conversations.get(userId, conversationId) == null) {
            return ResponseEntity.status(404).body(Map.of("message", "会话不存在"));
        }
        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpServerStatus status : manager.statuses(userId)) {
            McpOutputStore.Snapshot snap = store.stats(conversationId, status.getName());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", status.getName());
            row.put("count", snap.count);
            row.put("lastTool", snap.lastTool);
            row.put("lastChars", snap.lastChars);
            row.put("lastBytes", snap.lastBytes);
            servers.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("servers", servers);
        return ResponseEntity.ok(body);
    }
}
