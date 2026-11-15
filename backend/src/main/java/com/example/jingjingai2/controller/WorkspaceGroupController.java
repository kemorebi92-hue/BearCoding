package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.WorkspaceListResponse;
import com.example.jingjingai2.entity.Workspace;
import com.example.jingjingai2.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作区组接口（侧边栏一次拿齐 / 重命名 / 删除记录）。
 * 与 {@link WorkspaceController}（会话级 /ai/workspace/*）分开，路径是复数 /ai/workspaces。
 */
@RestController
@RequestMapping("/ai/workspaces")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class WorkspaceGroupController {

    private final WorkspaceService workspaceService;

    public WorkspaceGroupController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public WorkspaceListResponse list(HttpServletRequest request) {
        return workspaceService.listGrouped(userId(request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable("id") Long id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String name = body == null || body.get("name") == null ? null : String.valueOf(body.get("name"));
        try {
            Workspace ws = workspaceService.rename(userId(request), id, name);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", ws.getId());
            resp.put("name", ws.getName());
            resp.put("boundPath", ws.getBoundPath());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id, HttpServletRequest request) {
        try {
            workspaceService.deleteWorkspace(userId(request), id);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }
}
