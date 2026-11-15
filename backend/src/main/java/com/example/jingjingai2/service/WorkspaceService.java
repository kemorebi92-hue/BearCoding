package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.FileNode;
import com.example.jingjingai2.DTO.WorkspaceGroup;
import com.example.jingjingai2.DTO.WorkspaceListResponse;
import com.example.jingjingai2.entity.Workspace;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mapper.WorkspaceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 工作区服务：绑定目录是「工作区」一级，会话挂在工作区下。
 *
 * 根目录解析：会话 workspace_id 非空且 bound_path 仍是目录 → 用它；
 * 否则用该会话自己的默认沙箱 {root}/{userId}/{conversationId}/。
 *
 * 旧的 {root}/{userId}/.bindings/{conversationId}.path 只在启动迁移时读一次，
 * 运行时判断不再依赖它们（文件保留以便回滚）。
 */
@Service
public class WorkspaceService {

    public static final String DEFAULT_WORKSPACE_NAME = "默认工作区";

    private final Path workspacesRoot;
    private final WorkspaceMapper workspaceMapper;
    private final ConversationMapper conversationMapper;

    public WorkspaceService(@Value("${app.workspace.root:./workspaces}") String root,
                            WorkspaceMapper workspaceMapper,
                            ConversationMapper conversationMapper) {
        this.workspacesRoot = Paths.get(root).toAbsolutePath().normalize();
        this.workspaceMapper = workspaceMapper;
        this.conversationMapper = conversationMapper;
    }

    /** 测试 / 迁移用：工作区文件根 */
    public Path rootDir() {
        return workspacesRoot;
    }

    /** 计算某用户某会话的默认沙箱绝对路径（不创建） */
    public Path workspaceFor(Long userId, String conversationId) {
        if (userId == null) throw new SecurityException("未登录用户没有工作区");
        Path p = workspacesRoot
                .resolve(String.valueOf(userId))
                .resolve(sanitizeSegment(conversationId))
                .normalize();
        if (!p.startsWith(workspacesRoot)) {
            throw new SecurityException("非法工作区路径");
        }
        return p;
    }

    /** 计算并确保默认沙箱目录存在 */
    public Path ensureWorkspace(Long userId, String conversationId) {
        Path p = workspaceFor(userId, conversationId);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new IllegalStateException("创建工作区失败: " + e.getMessage(), e);
        }
        return p;
    }

    /**
     * 当前会话实际使用的工作区根（设计决策 4）。
     * 绑定目录被删或不是目录时回退到默认沙箱，不抛。
     */
    public Path resolveRoot(Long userId, String conversationId) {
        return resolveRoot(userId, conversationId, null);
    }

    /**
     * @param workspaceHint pending 会话还没落库时，前端把所属工作区 id 带过来，
     *                      文件树 / info 才能显示真实目录而不是空沙箱。
     */
    public Path resolveRoot(Long userId, String conversationId, Long workspaceHint) {
        if (conversationExists(userId, conversationId)) {
            Path bound = boundPathIfUsable(userId, conversationId);
            if (bound != null) return bound;
            return ensureWorkspace(userId, conversationId);
        }
        Path hinted = boundPathOfWorkspace(userId, workspaceHint);
        if (hinted != null) return hinted;
        return ensureWorkspace(userId, conversationId);
    }

    /** 兼容旧调用点：等同 {@link #resolveRoot} */
    public Path currentRoot(Long userId, String conversationId) {
        return resolveRoot(userId, conversationId);
    }

    /** 当前工作区信息：mode = bound(绑定真实目录) / default(默认工作区) */
    public Map<String, Object> info(Long userId, String conversationId) {
        return info(userId, conversationId, null);
    }

    public Map<String, Object> info(Long userId, String conversationId, Long workspaceHint) {
        ConversationSummary conv = (userId == null || conversationId == null || conversationId.isBlank())
                ? null : conversationMapper.selectOne(userId, conversationId);
        Path bound = boundPathIfUsable(userId, conversationId);
        Workspace hinted = null;
        if (conv == null && bound == null && workspaceHint != null) {
            hinted = workspaceMapper.selectByUserAndId(userId, workspaceHint);
            bound = boundPathOfWorkspace(userId, workspaceHint);
        }
        boolean isBound = bound != null;
        Map<String, Object> m = new HashMap<>();
        m.put("mode", isBound ? "bound" : "default");
        m.put("root", (isBound ? bound : workspaceFor(userId, conversationId)).toString());
        Long wsId = conv != null ? conv.getWorkspaceId() : (isBound && hinted != null ? hinted.getId() : null);
        String wsName = null;
        if (conv != null && conv.getWorkspaceName() != null) wsName = conv.getWorkspaceName();
        else if (hinted != null) wsName = hinted.getName();
        m.put("workspaceId", wsId);
        m.put("workspaceName", isBound && wsName != null ? wsName : DEFAULT_WORKSPACE_NAME);
        return m;
    }

    /**
     * 把当前会话绑到一个真实目录：已有同路径工作区则复用，否则新建。
     * 会话行不存在时先插入（pending 会话点绑定）。
     */
    public Map<String, Object> bindConversation(Long userId, String conversationId, String pathStr) {
        if (userId == null) throw new SecurityException("未登录用户不能绑定工作区");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话ID为空");
        }
        Path abs = requireExistingDirectory(pathStr);
        String normalized = WorkspacePaths.normalize(abs.toString());
        Workspace ws = findOrCreateWorkspace(userId, normalized, WorkspacePaths.displayName(normalized));
        ensureConversationRow(userId, conversationId, ws.getId());
        conversationMapper.updateWorkspaceId(userId, conversationId, ws.getId());
        return info(userId, conversationId);
    }

    /** 兼容旧接口：行为改为走工作区表 */
    public Map<String, Object> bind(Long userId, String conversationId, String pathStr) {
        return bindConversation(userId, conversationId, pathStr);
    }

    /** 当前会话解绑，回到自己的默认沙箱。不删工作区行。 */
    public void unbindConversation(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) return;
        conversationMapper.updateWorkspaceId(userId, conversationId, null);
    }

    /** 兼容旧接口 */
    public void unbind(Long userId, String conversationId) {
        unbindConversation(userId, conversationId);
    }

    public WorkspaceListResponse listGrouped(Long userId) {
        WorkspaceListResponse resp = new WorkspaceListResponse();
        if (userId == null) return resp;
        List<Workspace> all = workspaceMapper.listByUser(userId);
        if (all == null) all = List.of();
        List<WorkspaceGroup> groups = new ArrayList<>();
        for (Workspace ws : all) {
            WorkspaceGroup g = new WorkspaceGroup();
            g.setId(ws.getId());
            g.setName(ws.getName());
            g.setBoundPath(ws.getBoundPath());
            List<ConversationSummary> convs = conversationMapper.listByWorkspace(userId, ws.getId());
            if (convs != null) {
                for (ConversationSummary c : convs) fillDefaultName(c);
                g.setConversations(convs);
            }
            groups.add(g);
        }
        resp.setWorkspaces(groups);
        List<ConversationSummary> unbound = conversationMapper.listUnbound(userId);
        if (unbound != null) {
            for (ConversationSummary c : unbound) fillDefaultName(c);
            resp.setDefaultConversations(unbound);
        }
        return resp;
    }

    public Workspace rename(Long userId, Long workspaceId, String newName) {
        Workspace existing = requireOwnedWorkspace(userId, workspaceId);
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        String name = newName.trim();
        if (name.length() > 128) name = name.substring(0, 128);
        workspaceMapper.updateName(userId, workspaceId, name);
        existing.setName(name);
        return existing;
    }

    /**
     * 只删工作区记录，其下会话回默认工作区。绑定的真实目录不删磁盘。
     */
    public void deleteWorkspace(Long userId, Long workspaceId) {
        requireOwnedWorkspace(userId, workspaceId);
        conversationMapper.clearWorkspaceId(userId, workspaceId);
        workspaceMapper.deleteByUserAndId(userId, workspaceId);
    }

    public Workspace requireOwnedWorkspace(Long userId, Long workspaceId) {
        if (userId == null) throw new SecurityException("未登录");
        if (workspaceId == null) throw new IllegalArgumentException("工作区ID为空");
        Workspace ws = workspaceMapper.selectByUserAndId(userId, workspaceId);
        if (ws == null) throw new SecurityException("工作区不存在或不属于当前用户");
        return ws;
    }

    /**
     * 启动迁移：读旧 .bindings/*.path，按规范化路径聚类成 workspace，填 conversation.workspace_id。
     * 不删旧文件。重复跑只复用已有行。
     *
     * @return 本次新写入 workspace_id 的会话数（幂等再跑为 0）
     */
    public int migrateBindings() {
        if (!Files.isDirectory(workspacesRoot)) return 0;
        int linked = 0;
        try (Stream<Path> users = Files.list(workspacesRoot)) {
            for (Path userDir : users.toList()) {
                if (!Files.isDirectory(userDir)) continue;
                Long userId = parseUserId(userDir.getFileName().toString());
                if (userId == null) continue;
                Path bindings = userDir.resolve(".bindings");
                if (!Files.isDirectory(bindings)) continue;
                try (Stream<Path> files = Files.list(bindings)) {
                    for (Path bf : files.toList()) {
                        if (!Files.isRegularFile(bf)) continue;
                        String fname = bf.getFileName().toString();
                        if (!fname.endsWith(".path")) continue;
                        String conversationId = fname.substring(0, fname.length() - ".path".length());
                        if (conversationId.isBlank()) continue;
                        String raw;
                        try {
                            raw = Files.readString(bf, StandardCharsets.UTF_8).trim();
                        } catch (IOException e) {
                            continue;
                        }
                        if (raw.isBlank()) continue;
                        String normalized;
                        try {
                            normalized = WorkspacePaths.normalize(raw);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        // 会话已删只剩 .path：不要建空工作区组，否则侧边栏会出现一堆空文件夹
                        ConversationSummary existing = conversationMapper.selectOne(userId, conversationId);
                        if (existing == null) continue;
                        Workspace ws = findOrCreateWorkspace(userId, normalized, WorkspacePaths.displayName(normalized));
                        if (ws.getId().equals(existing.getWorkspaceId())) continue;
                        conversationMapper.updateWorkspaceId(userId, conversationId, ws.getId());
                        linked++;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Workspace] 绑定迁移扫描失败: " + e.getMessage());
        }
        return linked;
    }

    /**
     * 保存上传的多个文件；relativePaths 与 files 一一对应，
     * 用相对路径在工作区内重建目录结构（支持"上传整个文件夹"）。
     *
     * @return 实际落盘的文件相对路径列表
     */
    public List<String> saveUpload(Long userId, String conversationId,
                                   List<MultipartFile> files, List<String> relativePaths) {
        return saveUpload(userId, conversationId, files, relativePaths, null);
    }

    public List<String> saveUpload(Long userId, String conversationId,
                                   List<MultipartFile> files, List<String> relativePaths,
                                   Long workspaceHint) {
        Path ws = resolveRoot(userId, conversationId, workspaceHint);
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            if (f == null || f.isEmpty()) continue;
            String rel = (relativePaths != null && i < relativePaths.size()) ? relativePaths.get(i) : null;
            if (rel == null || rel.isBlank()) rel = f.getOriginalFilename();
            Path target = resolveWithin(ws, rel);
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                f.transferTo(target.toFile());
                saved.add(ws.relativize(target).toString().replace('\\', '/'));
            } catch (IOException e) {
                throw new IllegalStateException("写入文件失败: " + rel + " - " + e.getMessage(), e);
            }
        }
        return saved;
    }

    /** 列出工作区某一层的直接子项（目录在前）。只列一层，避免绑定大型项目时递归扫出几十万个文件。 */
    public List<FileNode> tree(Long userId, String conversationId) {
        return tree(userId, conversationId, null);
    }

    public List<FileNode> tree(Long userId, String conversationId, Long workspaceHint) {
        return tree(userId, conversationId, workspaceHint, "");
    }

    public List<FileNode> tree(Long userId, String conversationId, Long workspaceHint, String relDir) {
        Path ws = resolveRoot(userId, conversationId, workspaceHint);
        Path dir = (relDir == null || relDir.isBlank()) ? ws : resolveWithin(ws, relDir);
        List<FileNode> nodes = new ArrayList<>();
        if (!Files.isDirectory(dir)) return nodes;
        try (Stream<Path> children = Files.list(dir)) {
            children.forEach(p -> {
                String rel = ws.relativize(p).toString().replace('\\', '/');
                boolean isDir = Files.isDirectory(p);
                long size = 0L;
                if (!isDir) { try { size = Files.size(p); } catch (IOException ignored) {} }
                nodes.add(new FileNode(rel, p.getFileName().toString(), isDir, size));
            });
        } catch (IOException e) {
            throw new IllegalStateException("遍历工作区失败: " + e.getMessage(), e);
        }
        nodes.sort(Comparator
                .comparing((FileNode n) -> !n.isDir())
                .thenComparing(FileNode::getName, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    /** 读取单个文件文本（用于预览），超过 maxChars 截断 */
    public String readFile(Long userId, String conversationId, String relPath, int maxChars) {
        return readFile(userId, conversationId, relPath, maxChars, null);
    }

    public String readFile(Long userId, String conversationId, String relPath, int maxChars,
                           Long workspaceHint) {
        Path ws = resolveRoot(userId, conversationId, workspaceHint);
        Path target = resolveWithin(ws, relPath);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return content.length() <= maxChars
                    ? content
                    : content.substring(0, maxChars) + "\n...[内容过长已截断]";
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + e.getMessage(), e);
        }
    }

    /** 解析用于下载的文件路径 */
    public Path resolveDownload(Long userId, String conversationId, String relPath) {
        return resolveDownload(userId, conversationId, relPath, null);
    }

    public Path resolveDownload(Long userId, String conversationId, String relPath, Long workspaceHint) {
        Path ws = resolveRoot(userId, conversationId, workspaceHint);
        Path target = resolveWithin(ws, relPath);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return target;
    }

    /** 清空工作区（保留工作区根目录本身）。若已绑定真实目录则拒绝，避免误删真实文件。 */
    public void clear(Long userId, String conversationId) {
        if (boundPathIfUsable(userId, conversationId) != null
                || hasWorkspaceAssignment(userId, conversationId)) {
            throw new IllegalStateException("当前会话已绑定真实目录，出于安全不执行清空；请先解除绑定。");
        }
        Path ws = workspaceFor(userId, conversationId);
        if (!Files.exists(ws)) return;
        try (Stream<Path> walk = Files.walk(ws)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(ws))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            throw new IllegalStateException("清空工作区失败: " + e.getMessage(), e);
        }
    }

    Workspace findOrCreateWorkspace(Long userId, String normalizedPath, String name) {
        Workspace existing = workspaceMapper.selectByUserAndPath(userId, normalizedPath);
        if (existing != null) return existing;
        Workspace ws = new Workspace();
        ws.setUserId(userId);
        ws.setName(name == null || name.isBlank() ? "工作区" : name);
        ws.setBoundPath(normalizedPath);
        try {
            workspaceMapper.insert(ws);
            return ws;
        } catch (RuntimeException e) {
            Workspace raced = workspaceMapper.selectByUserAndPath(userId, normalizedPath);
            if (raced != null) return raced;
            throw e;
        }
    }

    private boolean conversationExists(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) return false;
        return conversationMapper.selectOne(userId, conversationId) != null;
    }

    private Path boundPathOfWorkspace(Long userId, Long workspaceId) {
        if (userId == null || workspaceId == null) return null;
        Workspace ws = workspaceMapper.selectByUserAndId(userId, workspaceId);
        return usableBoundPath(ws);
    }

    private Path usableBoundPath(Workspace ws) {
        if (ws == null || ws.getBoundPath() == null || ws.getBoundPath().isBlank()) return null;
        try {
            Path abs = Paths.get(ws.getBoundPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(abs)) return null;
            try {
                return abs.toRealPath();
            } catch (IOException e) {
                return abs;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Path boundPathIfUsable(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) return null;
        ConversationSummary conv = conversationMapper.selectOne(userId, conversationId);
        if (conv == null || conv.getWorkspaceId() == null) return null;
        return boundPathOfWorkspace(userId, conv.getWorkspaceId());
    }

    private boolean hasWorkspaceAssignment(Long userId, String conversationId) {
        if (userId == null || conversationId == null) return false;
        ConversationSummary conv = conversationMapper.selectOne(userId, conversationId);
        return conv != null && conv.getWorkspaceId() != null;
    }

    private void ensureConversationRow(Long userId, String conversationId, Long workspaceId) {
        ConversationSummary existing = conversationMapper.selectOne(userId, conversationId);
        if (existing != null) return;
        conversationMapper.touch(userId, conversationId, "新对话", workspaceId, ConversationService.MODE_ASK);
    }

    static Path requireExistingDirectory(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) throw new IllegalArgumentException("路径不能为空");
        Path abs;
        try {
            abs = Paths.get(pathStr.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法路径: " + pathStr);
        }
        if (!Files.exists(abs)) throw new IllegalArgumentException("路径不存在: " + abs);
        if (!Files.isDirectory(abs)) throw new IllegalArgumentException("该路径不是目录: " + abs);
        if (abs.getParent() == null) throw new IllegalArgumentException("出于安全，不允许绑定磁盘根目录: " + abs);
        return abs;
    }

    static void fillDefaultName(ConversationSummary c) {
        if (c == null) return;
        if (c.getWorkspaceId() == null || c.getWorkspaceName() == null || c.getWorkspaceName().isBlank()) {
            c.setWorkspaceName(DEFAULT_WORKSPACE_NAME);
        }
    }

    private static Long parseUserId(String segment) {
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把相对路径安全地解析到工作区内，越界/绝对路径直接拒绝 */
    private Path resolveWithin(Path ws, String rel) {
        if (rel == null || rel.isBlank()) throw new IllegalArgumentException("文件路径为空");
        String normalized = rel.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("不允许使用绝对路径: " + rel);
        }
        Path target = ws.resolve(normalized).normalize();
        if (!target.startsWith(ws)) {
            throw new SecurityException("路径越界已拦截: " + rel);
        }
        return target;
    }

    /** 会话 ID 作为目录名需消毒，防止把斜杠等字符注入成额外层级 */
    private String sanitizeSegment(String seg) {
        if (seg == null || seg.isBlank()) throw new IllegalArgumentException("会话ID为空");
        String s = seg.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return s.isBlank() ? "default" : s;
    }
}
