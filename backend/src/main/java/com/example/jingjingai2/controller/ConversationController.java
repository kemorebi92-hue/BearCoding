package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.service.TaskPlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理接口（会话列表 / 历史消息 / 重命名 / 删除 / 停止生成）。
 *
 * 为什么放在 /ai 下面：
 *  WebConfig 里 JWT 拦截器排除了 /api/**（那些是旧接口），所以受登录保护的接口必须挂在 /ai 下。
 *  同时每个方法都用 userId 过滤数据 —— 不同用户的会话互相看不到（多租户隔离）。
 */
@RestController
@RequestMapping("/ai/conversations")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final TaskPlanService planService;
    private final ApprovalService approvalService;

    public ConversationController(ConversationService conversationService,
                                  AgentCancellationRegistry cancellationRegistry,
                                  TaskPlanService planService,
                                  ApprovalService approvalService) {
        this.conversationService = conversationService;
        this.cancellationRegistry = cancellationRegistry;
        this.planService = planService;
        this.approvalService = approvalService;
    }

    /** 会话列表（按最近活跃倒序） */
    @GetMapping
    public List<ConversationSummary> list(HttpServletRequest request) {
        return conversationService.list(userId(request));
    }

    /**
     * 显式新建一个会话（可选）。
     * 正常流程其实不需要调它：前端点"新对话"只生成 UUID，第一条消息落库时才自动建会话记录，
     * 这样能避免列表里出现一堆没人说过的空会话。保留这个接口是为了让 API 语义完整。
     */
    @PostMapping
    public ConversationSummary create(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        String conversationId = body != null && body.get("conversationId") != null
                ? String.valueOf(body.get("conversationId"))
                : UUID.randomUUID().toString();
        Object title = body == null ? null : body.get("title");
        Long workspaceId = null;
        if (body != null && body.get("workspaceId") != null && !String.valueOf(body.get("workspaceId")).isBlank()) {
            try {
                workspaceId = Long.valueOf(String.valueOf(body.get("workspaceId")));
            } catch (NumberFormatException ignored) {
                workspaceId = null;
            }
        }
        // touch 会插入一条只含标题的空会话；workspaceId 只在 INSERT 时生效
        conversationService.touch(userId(request), conversationId,
                title == null ? null : String.valueOf(title), workspaceId);
        ConversationSummary created = conversationService.get(userId(request), conversationId);
        return created != null ? created : new ConversationSummary();
    }

    /** 某个会话的历史消息（时间正序，含时间戳） */
    @GetMapping("/{conversationId}/messages")
    public List<MessageRecord> messages(@PathVariable("conversationId") String conversationId, HttpServletRequest request) {
        return conversationService.history(userId(request), conversationId);
    }

    /** 重命名会话 */
    @PatchMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable("conversationId") String conversationId,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        boolean ok = conversationService.rename(userId(request), conversationId, title);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", ok);
        resp.put("title", title);
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.badRequest().body(resp);
    }

    /** 删除会话（连同它的消息一起删） */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("conversationId") String conversationId,
                                                      HttpServletRequest request) {
        Long userId = userId(request);
        boolean ok = conversationService.delete(userId, conversationId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", ok);
        if (!ok) return ResponseEntity.badRequest().body(resp);
        // 会话确实删掉了，才清它名下的「本会话允许」。
        // 顺序反过来不行：删除抛异常或返回 false 时，授权先没了、会话却还在。
        approvalService.forgetConversation(userId, conversationId);
        return ResponseEntity.ok(resp);
    }

    /** 当前会话的任务计划（前端切换会话/刷新后恢复计划面板用） */
    @GetMapping("/{conversationId}/plan")
    public List<PlanStep> plan(@PathVariable("conversationId") String conversationId,
                               HttpServletRequest request) {
        return planService.stepsOf(userId(request), conversationId);
    }

    /**
     * 保存当前会话的权限模式。只改这一列，不进 ChatRequest。
     * 会话不属于当前用户或不存在 → 404；模式不是三档之一 → 400。
     */
    @PutMapping("/{conversationId}/permission-mode")
    public ResponseEntity<Map<String, Object>> permissionMode(
            @PathVariable("conversationId") String conversationId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Long userId = userId(request);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (conversationService.get(userId, conversationId) == null) {
            resp.put("error", "会话不存在");
            return ResponseEntity.status(404).body(resp);
        }
        String mode = body == null || body.get("permissionMode") == null
                ? null : String.valueOf(body.get("permissionMode"));
        if (!conversationService.updatePermissionMode(userId, conversationId, mode)) {
            resp.put("error", "非法的权限模式");
            return ResponseEntity.badRequest().body(resp);
        }
        resp.put("success", true);
        resp.put("conversationId", conversationId);
        resp.put("permissionMode", mode);
        return ResponseEntity.ok(resp);
    }

    /**
     * 停止生成：给后台正在跑的 Agent 循环置一个取消标志，
     * 循环会在下一个安全点（每轮开头 / 每个工具执行前）主动收尾退出，并推一条 stopped 事件。
     */
    @PostMapping("/{conversationId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("conversationId") String conversationId,
                                                     HttpServletRequest request) {
        Long userId = userId(request);
        if (conversationService.get(userId, conversationId) == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("error", "会话不存在");
            return ResponseEntity.status(404).body(missing);
        }
        boolean accepted = cancellationRegistry.cancel(userId, conversationId,
                () -> approvalService.cancelConversation(userId, conversationId));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accepted", accepted);
        resp.put("conversationId", conversationId);
        return ResponseEntity.ok(resp);
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }
}
