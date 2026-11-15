package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.FileNode;
import com.example.jingjingai2.service.DirectoryPickerService;
import com.example.jingjingai2.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区接口（放在 /ai 下，受登录拦截器保护）。
 * 提供：上传（多文件/文件夹）、文件树、文件预览/下载、清空。
 *
 * 类比 Codex / Claude Code：上传的内容放进"当前会话的工作区"，
 * 之后 Agent 的文件工具与终端都只在这个工作区内操作。
 */
@RestController
@RequestMapping("/ai/workspace")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final DirectoryPickerService directoryPickerService;

    public WorkspaceController(WorkspaceService workspaceService,
                               DirectoryPickerService directoryPickerService) {
        this.workspaceService = workspaceService;
        this.directoryPickerService = directoryPickerService;
    }

    /** 上传文件：files 与 paths 一一对应，paths 为相对路径（文件夹上传时保留层级） */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("conversationId") String conversationId,
                                    @RequestParam("files") List<MultipartFile> files,
                                    @RequestParam(value = "paths", required = false) List<String> paths,
                                    @RequestParam(value = "workspaceId", required = false) Long workspaceId,
                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            List<String> saved = workspaceService.saveUpload(userId, conversationId, files, paths, workspaceId);
            Map<String, Object> res = new HashMap<>();
            res.put("uploaded", saved.size());
            res.put("files", saved);
            return ResponseEntity.ok(res);
        } catch (SecurityException | IllegalArgumentException e) {
            // 与 /file 一致：相对路径越界、绝对路径偷渡是调用方问题，返回 400 和明确原因，不是 500
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 工作区某一层的直接子项（dir 为空时为根目录） */
    @GetMapping("/tree")
    public List<FileNode> tree(@RequestParam("conversationId") String conversationId,
                               @RequestParam(value = "workspaceId", required = false) Long workspaceId,
                               @RequestParam(value = "dir", required = false) String dir,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return workspaceService.tree(userId, conversationId, workspaceId, dir);
    }

    /**
     * 预览 / 下载单个文件。
     * download=false → 返回 JSON 文本内容（预览）；download=true → 返回文件流（下载）。
     */
    @GetMapping("/file")
    public ResponseEntity<?> file(@RequestParam("conversationId") String conversationId,
                                  @RequestParam("path") String path,
                                  @RequestParam(value = "download", required = false, defaultValue = "false") boolean download,
                                  @RequestParam(value = "workspaceId", required = false) Long workspaceId,
                                  HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            if (download) {
                Path p = workspaceService.resolveDownload(userId, conversationId, path, workspaceId);
                Resource resource = new FileSystemResource(p);
                String filename = URLEncoder.encode(p.getFileName().toString(), StandardCharsets.UTF_8)
                        .replace("+", "%20");
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);
            }
            String content = workspaceService.readFile(userId, conversationId, path, 20000, workspaceId);
            Map<String, Object> res = new HashMap<>();
            res.put("path", path);
            res.put("content", content);
            return ResponseEntity.ok(res);
        } catch (SecurityException | IllegalArgumentException e) {
            // 路径越界 / 绝对路径 / 文件不存在等 → 返回 400 与明确原因，而不是 500
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /**
     * 绑定一个"真实绝对路径"作为本会话的工作区。
     * 校验：路径存在、是目录、不是磁盘根目录。绑定后 Agent 直接读写该真实目录。
     */
    @PostMapping("/bind")
    public Map<String, Object> bind(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String conversationId = body.get("conversationId");
        String path = body.get("path");
        try {
            return workspaceService.bind(userId, conversationId, path);
        } catch (IllegalArgumentException | SecurityException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            return err;
        }
    }

    /**
     * 在本机弹出系统文件夹选择器，返回选中的绝对路径。
     *
     * <p>前端“+”按钮（浏览本地文件夹）调用。返回 {@code {path}} / {@code {cancelled:true}} /
     * {@code {error}}，与 {@link #bind} 的“200 + error 字段”错误约定一致，前端能直接展示具体原因。
     *
     * <p>只允许本机请求：后端在远程机器上弹窗既没有意义，也会让远端用户误以为选择了自己机器的目录。
     */
    @PostMapping("/pick-directory")
    public Map<String, Object> pickDirectory(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Map<String, Object> res = new HashMap<>();
        if (userId == null) {
            res.put("error", "请先登录");
            return res;
        }
        if (!isLoopback(request.getRemoteAddr())) {
            res.put("error", "文件夹选择器只能在本机使用，请手动填写路径");
            return res;
        }
        try {
            String path = directoryPickerService.pick(Paths.get(System.getProperty("user.dir")));
            if (path == null || path.isBlank()) {
                res.put("cancelled", true);
            } else {
                res.put("path", path);
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            res.put("error", e.getMessage());
        } catch (RuntimeException e) {
            res.put("error", "打开文件夹选择器失败: " + e.getMessage());
        }
        return res;
    }

    /** 只接受本机回环地址（含 IPv6 的 ::1）。 */
    private static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return false;
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }

    /** 解除绑定，回到默认工作区 */
    @PostMapping("/unbind")
    public Map<String, Object> unbind(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String conversationId = body.get("conversationId");
        workspaceService.unbind(userId, conversationId);
        return workspaceService.info(userId, conversationId);
    }

    /** 查询当前工作区信息（root + mode: bound/default） */
    @GetMapping("/info")
    public Map<String, Object> info(@RequestParam("conversationId") String conversationId,
                                    @RequestParam(value = "workspaceId", required = false) Long workspaceId,
                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return workspaceService.info(userId, conversationId, workspaceId);
    }

    /** 清空工作区（仅对默认工作区有效；绑定了真实目录时返回错误提示） */
    @DeleteMapping
    public Map<String, Object> clear(@RequestParam("conversationId") String conversationId,
                                     HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Map<String, Object> res = new HashMap<>();
        try {
            workspaceService.clear(userId, conversationId);
            res.put("message", "工作区已清空");
        } catch (IllegalStateException e) {
            res.put("error", e.getMessage());
        }
        return res;
    }
}
