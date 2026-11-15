package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.WorkspaceGroup;
import com.example.jingjingai2.DTO.WorkspaceListResponse;
import com.example.jingjingai2.entity.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区归属：迁移聚类、绑定/解绑、resolveRoot、越权、删会话不删目录。
 * 不碰 MySQL，用内存假 Mapper。
 */
class WorkspaceServiceTest {

    private static final Long USER_A = 11L;
    private static final Long USER_B = 22L;

    @TempDir Path tmp;

    private FakeWorkspaceMapper wsMapper;
    private FakeConversationMapper convMapper;
    private WorkspaceService service;
    private Path projectDir;
    private Path otherDir;

    @BeforeEach
    void setUp() throws Exception {
        wsMapper = new FakeWorkspaceMapper();
        convMapper = new FakeConversationMapper(wsMapper);
        service = new WorkspaceService(tmp.toString(), wsMapper, convMapper);
        projectDir = Files.createDirectories(tmp.resolve("proj"));
        otherDir = Files.createDirectories(tmp.resolve("other"));
        Files.writeString(projectDir.resolve("readme.txt"), "hi", StandardCharsets.UTF_8);
    }

    private void seedConversation(Long userId, String convId) {
        convMapper.touch(userId, convId, "会话 " + convId, null, ConversationService.MODE_ASK);
    }

    private void writeLegacyBinding(Long userId, String convId, String rawPath) throws Exception {
        Path bf = tmp.resolve(String.valueOf(userId)).resolve(".bindings").resolve(convId + ".path");
        Files.createDirectories(bf.getParent());
        Files.writeString(bf, rawPath, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("1 迁移聚类：三条会话绑同一目录（大小写 / 斜杠不同）→ 一个 workspace，三条 workspace_id 相同")
    void migrateClustersSamePath() throws Exception {
        seedConversation(USER_A, "c1");
        seedConversation(USER_A, "c2");
        seedConversation(USER_A, "c3");
        String n = WorkspacePaths.normalize(projectDir.toString());
        writeLegacyBinding(USER_A, "c1", projectDir.toString());
        writeLegacyBinding(USER_A, "c2", projectDir.toString().replace('\\', '/'));
        if (WorkspacePaths.isWindows()) {
            writeLegacyBinding(USER_A, "c3", projectDir.toString().toUpperCase());
        } else {
            writeLegacyBinding(USER_A, "c3", projectDir.toString());
        }

        int linked = service.migrateBindings();
        assertEquals(3, linked);
        assertEquals(1, wsMapper.size(), "同一规范化路径只建一个 workspace");
        Long id1 = convMapper.selectOne(USER_A, "c1").getWorkspaceId();
        Long id2 = convMapper.selectOne(USER_A, "c2").getWorkspaceId();
        Long id3 = convMapper.selectOne(USER_A, "c3").getWorkspaceId();
        assertNotNull(id1);
        assertEquals(id1, id2);
        assertEquals(id1, id3);
        assertEquals(n, wsMapper.selectById(id1).getBoundPath());
        assertTrue(Files.exists(tmp.resolve(String.valueOf(USER_A)).resolve(".bindings").resolve("c1.path")),
                "旧绑定文件保留");
    }

    @Test
    @DisplayName("2 迁移幂等：连跑两次，workspace 行数和归属不变")
    void migrateIdempotent() throws Exception {
        seedConversation(USER_A, "c1");
        writeLegacyBinding(USER_A, "c1", projectDir.toString());
        assertEquals(1, service.migrateBindings());
        int ws = wsMapper.size();
        Long id = convMapper.selectOne(USER_A, "c1").getWorkspaceId();
        assertEquals(0, service.migrateBindings());
        assertEquals(ws, wsMapper.size());
        assertEquals(id, convMapper.selectOne(USER_A, "c1").getWorkspaceId());
    }

    @Test
    @DisplayName("3 未绑定会话迁移后 workspace_id 为 NULL，各自沙箱互不相同")
    void unboundStayNullAndIsolated() {
        seedConversation(USER_A, "u1");
        seedConversation(USER_A, "u2");
        assertEquals(0, service.migrateBindings());
        assertNull(convMapper.selectOne(USER_A, "u1").getWorkspaceId());
        assertNull(convMapper.selectOne(USER_A, "u2").getWorkspaceId());
        Path r1 = service.resolveRoot(USER_A, "u1");
        Path r2 = service.resolveRoot(USER_A, "u2");
        assertNotEquals(r1, r2);
        assertTrue(r1.startsWith(tmp.resolve("11")));
        assertTrue(r2.startsWith(tmp.resolve("11")));
    }

    @Test
    @DisplayName("4 绑定新路径 → 创建 workspace，会话挂入")
    void bindCreatesWorkspace() {
        seedConversation(USER_A, "c1");
        Map<String, Object> info = service.bindConversation(USER_A, "c1", projectDir.toString());
        assertEquals("bound", info.get("mode"));
        assertEquals(projectDir.toAbsolutePath().normalize(), Path.of((String) info.get("root")));
        ConversationSummary c = convMapper.selectOne(USER_A, "c1");
        assertNotNull(c.getWorkspaceId());
        assertEquals(1, wsMapper.size());
        assertEquals(c.getWorkspaceId(), info.get("workspaceId"));
    }

    @Test
    @DisplayName("5 绑定已有路径 → 复用同一 workspace，UNIQUE 生效")
    void bindReusesExisting() {
        seedConversation(USER_A, "c1");
        seedConversation(USER_A, "c2");
        service.bindConversation(USER_A, "c1", projectDir.toString());
        service.bindConversation(USER_A, "c2", projectDir.toString().replace('\\', '/'));
        assertEquals(1, wsMapper.size());
        assertEquals(convMapper.selectOne(USER_A, "c1").getWorkspaceId(),
                convMapper.selectOne(USER_A, "c2").getWorkspaceId());
    }

    @Test
    @DisplayName("6 解绑 → workspace_id 为 NULL，resolveRoot 回到该会话自己的沙箱")
    void unbindReturnsToOwnSandbox() {
        seedConversation(USER_A, "c1");
        service.bindConversation(USER_A, "c1", projectDir.toString());
        Path bound = service.resolveRoot(USER_A, "c1");
        assertEquals(projectDir.toAbsolutePath().normalize(), bound);
        service.unbindConversation(USER_A, "c1");
        assertNull(convMapper.selectOne(USER_A, "c1").getWorkspaceId());
        Path sandbox = service.resolveRoot(USER_A, "c1");
        assertNotEquals(bound, sandbox);
        assertEquals(service.workspaceFor(USER_A, "c1"), sandbox);
        assertEquals(1, wsMapper.size(), "解绑不删工作区行");
    }

    @Test
    @DisplayName("7 resolveRoot：绑定目录被删或不是目录时回退默认沙箱，不抛")
    void resolveRootFallsBackWhenBoundMissing() throws Exception {
        seedConversation(USER_A, "c1");
        Path gone = Files.createDirectories(tmp.resolve("will-go"));
        service.bindConversation(USER_A, "c1", gone.toString());
        Files.delete(gone);
        Path root = assertDoesNotThrow(() -> service.resolveRoot(USER_A, "c1"));
        assertEquals(service.workspaceFor(USER_A, "c1"), root);

        seedConversation(USER_A, "c2");
        Path file = tmp.resolve("not-a-dir.txt");
        Files.writeString(file, "x");
        Workspace fake = new Workspace();
        fake.setUserId(USER_A);
        fake.setName("bad");
        fake.setBoundPath(WorkspacePaths.normalize(file.toString()));
        // 文件路径 normalize 会过（它不是根），但 insert 后 resolve 必须回退
        // requireExistingDirectory 会拒绝文件，所以直接插 mapper 模拟脏数据
        wsMapper.insert(fake);
        convMapper.updateWorkspaceId(USER_A, "c2", fake.getId());
        Path fallback = assertDoesNotThrow(() -> service.resolveRoot(USER_A, "c2"));
        assertEquals(service.workspaceFor(USER_A, "c2"), fallback);
    }

    @Test
    @DisplayName("8 删除会话：目录和同工作区其他会话不受影响；删除工作区：磁盘仍在，会话回默认")
    void deleteConversationAndWorkspace() throws Exception {
        seedConversation(USER_A, "c1");
        seedConversation(USER_A, "c2");
        service.bindConversation(USER_A, "c1", projectDir.toString());
        service.bindConversation(USER_A, "c2", projectDir.toString());
        Long wsId = convMapper.selectOne(USER_A, "c1").getWorkspaceId();
        Path sandbox1 = service.workspaceFor(USER_A, "c1");
        Files.createDirectories(sandbox1);
        Files.writeString(sandbox1.resolve("keep.txt"), "x");

        new ConversationService(convMapper, service).delete(USER_A, "c1");
        assertNull(convMapper.selectOne(USER_A, "c1"));
        assertNotNull(convMapper.selectOne(USER_A, "c2"));
        assertEquals(wsId, convMapper.selectOne(USER_A, "c2").getWorkspaceId());
        assertTrue(Files.exists(sandbox1.resolve("keep.txt")), "删会话不删沙箱目录");
        assertTrue(Files.exists(projectDir.resolve("readme.txt")), "不删绑定的真实目录");

        service.deleteWorkspace(USER_A, wsId);
        assertNull(wsMapper.selectById(wsId));
        assertNull(convMapper.selectOne(USER_A, "c2").getWorkspaceId());
        assertTrue(Files.exists(projectDir.resolve("readme.txt")), "删工作区记录不删真实目录");
        assertEquals(service.workspaceFor(USER_A, "c2"), service.resolveRoot(USER_A, "c2"));
    }

    @Test
    @DisplayName("9 越权：用户 B 不能操作用户 A 的 workspaceId")
    void crossUserRejected() {
        seedConversation(USER_A, "c1");
        service.bindConversation(USER_A, "c1", projectDir.toString());
        Long wsId = convMapper.selectOne(USER_A, "c1").getWorkspaceId();
        assertThrows(SecurityException.class, () -> service.requireOwnedWorkspace(USER_B, wsId));
        assertThrows(SecurityException.class, () -> service.rename(USER_B, wsId, "hack"));
        assertThrows(SecurityException.class, () -> service.deleteWorkspace(USER_B, wsId));
        WorkspaceListResponse bList = service.listGrouped(USER_B);
        assertTrue(bList.getWorkspaces().isEmpty());
        assertTrue(bList.getDefaultConversations().isEmpty());
    }

    @Test
    @DisplayName("10 路径非法（不存在 / 是文件 / 空串）绑定被拒绝")
    void invalidPathRejected() throws Exception {
        seedConversation(USER_A, "c1");
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> service.bindConversation(USER_A, "c1", "  "));
        assertTrue(empty.getMessage().contains("空"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.bindConversation(USER_A, "c1", tmp.resolve("no-such").toString()));
        assertTrue(missing.getMessage().contains("不存在"));

        Path file = tmp.resolve("a-file.txt");
        Files.writeString(file, "x");
        IllegalArgumentException notDir = assertThrows(IllegalArgumentException.class,
                () -> service.bindConversation(USER_A, "c1", file.toString()));
        assertTrue(notDir.getMessage().contains("不是目录"));

        assertNull(convMapper.selectOne(USER_A, "c1").getWorkspaceId());
        assertEquals(0, wsMapper.size());
    }

    @Test
    @DisplayName("GET /ai/workspaces 分组：绑定组 + 默认组")
    void listGroupedShape() throws Exception {
        seedConversation(USER_A, "bound-1");
        seedConversation(USER_A, "bound-2");
        seedConversation(USER_A, "free");
        seedConversation(USER_A, "other-conv");
        service.bindConversation(USER_A, "bound-1", projectDir.toString());
        service.bindConversation(USER_A, "bound-2", projectDir.toString());
        service.bindConversation(USER_A, "other-conv", otherDir.toString());

        WorkspaceListResponse resp = service.listGrouped(USER_A);
        assertEquals(2, resp.getWorkspaces().size());
        Set<String> names = resp.getWorkspaces().stream().map(WorkspaceGroup::getName).collect(Collectors.toSet());
        assertTrue(names.contains(projectDir.getFileName().toString()));
        assertTrue(names.contains(otherDir.getFileName().toString()));
        WorkspaceGroup proj = resp.getWorkspaces().stream()
                .filter(g -> projectDir.getFileName().toString().equals(g.getName()))
                .findFirst().orElseThrow();
        assertEquals(2, proj.getConversations().size());
        Set<String> ids = proj.getConversations().stream()
                .map(ConversationSummary::getConversationId).collect(Collectors.toSet());
        assertEquals(Set.of("bound-1", "bound-2"), ids);
        assertTrue(resp.getDefaultConversations().stream()
                .anyMatch(c -> "free".equals(c.getConversationId())));

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(resp);
        System.out.println("GET /ai/workspaces sample:\n" + json);
    }

    @Test
    @DisplayName("旧 info 字段：mode + root 仍在；绑定/默认两种")
    void legacyInfoFields() {
        seedConversation(USER_A, "c1");
        Map<String, Object> def = service.info(USER_A, "c1");
        assertEquals("default", def.get("mode"));
        assertEquals(service.workspaceFor(USER_A, "c1").toString(), def.get("root"));
        assertTrue(def.containsKey("mode"));
        assertTrue(def.containsKey("root"));

        Map<String, Object> bound = service.bindConversation(USER_A, "c1", projectDir.toString());
        assertEquals("bound", bound.get("mode"));
        assertEquals(projectDir.toAbsolutePath().normalize().toString(), bound.get("root"));
    }

    @Test
    @DisplayName("pending 会话绑定：会话行不存在时也能 bind 并挂上 workspaceId")
    void bindCreatesPendingConversation() {
        Map<String, Object> info = service.bindConversation(USER_A, "pending-1", projectDir.toString());
        assertEquals("bound", info.get("mode"));
        ConversationSummary c = convMapper.selectOne(USER_A, "pending-1");
        assertNotNull(c);
        assertNotNull(c.getWorkspaceId());
    }

    @Test
    @DisplayName("touch 带 workspaceId 只在 INSERT 时生效，已有会话不改归属")
    void touchDoesNotOverrideWorkspace() {
        ConversationService conv = new ConversationService(convMapper, service);
        conv.touch(USER_A, "c1", "hello", null, ConversationService.MODE_ASK);
        service.bindConversation(USER_A, "c1", projectDir.toString());
        Long boundId = convMapper.selectOne(USER_A, "c1").getWorkspaceId();
        conv.touch(USER_A, "c1", "second", null, ConversationService.MODE_ASK);
        assertEquals(boundId, convMapper.selectOne(USER_A, "c1").getWorkspaceId());
    }
    @Test
    @DisplayName("已删会话残留的 .bindings 不再创建空 workspace 组")
    void orphanBindingDoesNotCreateWorkspace() throws Exception {
        Path dir = Files.createTempDirectory("orphan-ws");
        int before = wsMapper.listByUser(USER_A).size();
        writeLegacyBinding(USER_A, "ghost-conv-no-row", dir.toString());
        assertEquals(0, service.migrateBindings());
        assertEquals(before, wsMapper.listByUser(USER_A).size());
    }

}
