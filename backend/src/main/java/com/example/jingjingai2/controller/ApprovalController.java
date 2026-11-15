package com.example.jingjingai2.controller;

import com.example.jingjingai2.permission.ApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批决定。身份只认 JWT 放进 request 的 userId，不信请求体。
 */
@RestController
@RequestMapping("/ai/approvals")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/{id}/decision")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable("id") String id,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String decision = body.get("decision") == null ? "" : String.valueOf(body.get("decision"));
        ApprovalService.HttpResult result = approvals.decide(id, userId, decision);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (result.status != null) resp.put("status", result.status);
        if (result.message != null) resp.put("message", result.message);
        resp.put("approvalId", id);
        return ResponseEntity.status(result.http).body(resp);
    }
}
