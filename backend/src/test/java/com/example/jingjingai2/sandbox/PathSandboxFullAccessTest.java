package com.example.jingjingai2.sandbox;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.service.ConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FULL_ACCESS 的路径范围与 MCP 只读根保护。全部用临时目录和假会话服务，不碰真实用户文件。
 */
class PathSandboxFullAccessTest {

    private Path workspace;
    private Path outside;
    private Path readonly;
    private PathSandbox sandbox;
    private final Modes modes = new Modes();

    /** 按调用返回预设模式；fail 非空时模拟数据库异常。 */
    static final class Modes extends ConversationService {
        String mode;
        RuntimeException fail;

        Modes() {
            super(null, null);
        }

        @Override
        public String permissionMode(Long userId, String conversationId) {
            if (fail != null) throw fail;
            return mode;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("fa-ws");
        outside = Files.createTempDirectory("fa-out");
        readonly = Files.createTempDirectory("fa-ro");
        Files.writeString(outside.resolve("secret.txt"), "TOP_SECRET");
        Files.writeString(readonly.resolve("spill.txt"), "SPILL");
        sandbox = newSandbox(workspace, readonly);
        WorkspaceContext.set(workspace);
        ConversationContext.set(7L, "conv");
    }

    @AfterEach
    void tearDown() throws Exception {
        WorkspaceContext.clear();
        ConversationContext.clear();
        delete(workspace);
        delete(outside);
        delete(readonly);
    }

    private PathSandbox newSandbox(Path root, Path readOnly) {
        PathSandbox s = new PathSandbox(root.toString(), List.of(() -> List.of(readOnly)));
        s.setConversationService(modes);
        return s;
    }

    @Test
    @DisplayName("ASK 工作区内通过，工作区外拒绝")
    void askStaysInside() {
        modes.mode = ConversationService.MODE_ASK;
        assertNotNull(sandbox.resolveWithin("a.txt"));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.resolveWithin(outside.resolve("secret.txt").toString()));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.resolveForRead(outside.resolve("secret.txt").toString()));
    }

    @Test
    @DisplayName("WORKSPACE_EDIT 工作区外仍拒绝")
    void workspaceEditStaysInside() {
        modes.mode = ConversationService.MODE_WORKSPACE_EDIT;
        assertThrows(SandboxViolationException.class,
                () -> sandbox.resolveWithin(outside.resolve("secret.txt").toString()));
    }

    @Test
    @DisplayName("FULL_ACCESS 工作区外绝对路径、相对 ../ 路径、工作区内都通过")
    void fullAccessAllowsOutside() {
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        assertTrue(sandbox.resolveWithin(outside.resolve("secret.txt").toString()).isAbsolute());
        assertTrue(sandbox.resolveForRead(outside.resolve("secret.txt").toString()).isAbsolute());
        assertNotNull(sandbox.resolveWithin("a.txt"));
        // ../secret.txt 规范化后落在工作区外，FULL_ACCESS 允许
        Path outsideSibling = workspace.resolveSibling("fa-sibling.txt");
        assertDoesNotThrow(() -> sandbox.resolveWithin("../" + outsideSibling.getFileName()));
    }

    @Test
    @DisplayName("FULL_ACCESS 下不存在的目标路径也能通过")
    void fullAccessAllowsMissingTarget() {
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        Path missing = outside.resolve("not-created-yet.txt");
        assertFalse(Files.exists(missing));
        assertEquals(missing.toAbsolutePath().normalize(), sandbox.resolveWithin(missing.toString()));
    }

    @Test
    @DisplayName("空路径、UNC、非法冒号在 FULL_ACCESS 下仍拒绝")
    void hardChecksAlwaysApply() {
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin(""));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin("\\\\server\\share\\x.txt"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin("file.txt:stream"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveForRead("\\\\server\\share\\x.txt"));
    }

    @Test
    @DisplayName("只读根可读、不可写，ASK 和 FULL_ACCESS 都一样")
    void readOnlyRootIsReadOnly() {
        for (String mode : new String[]{ConversationService.MODE_ASK,
                ConversationService.MODE_WORKSPACE_EDIT, ConversationService.MODE_FULL_ACCESS}) {
            modes.mode = mode;
            assertDoesNotThrow(() -> sandbox.resolveForRead(readonly.resolve("spill.txt").toString()), mode);
            assertThrows(SandboxViolationException.class,
                    () -> sandbox.resolveWithin(readonly.resolve("spill.txt").toString()), mode);
            assertThrows(SandboxViolationException.class,
                    () -> sandbox.resolveWithin(readonly.toString()), mode);
        }
    }

    @Test
    @DisplayName("FULL_ACCESS 不能删除或移动只读根的祖先目录")
    void readOnlyRootAncestorProtected() {
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        // 只读根的父目录（临时目录的父级）属于祖先，删除它会连带破坏只读根
        Path ancestor = readonly.getParent();
        assertThrows(SandboxViolationException.class,
                () -> sandbox.resolveWithin(ancestor.toString()));
    }

    @Test
    @DisplayName("只读根与工作区重叠时，写入仍被拒绝")
    void overlappingReadOnlyRootStillProtected() throws Exception {
        Path nested = Files.createDirectories(workspace.resolve("mcp-output"));
        Files.writeString(nested.resolve("spill.txt"), "SPILL");
        PathSandbox overlapping = newSandbox(workspace, nested);
        modes.mode = ConversationService.MODE_FULL_ACCESS;

        assertThrows(SandboxViolationException.class,
                () -> overlapping.resolveWithin(nested.resolve("spill.txt").toString()));
        // 工作区内、只读根之外的普通路径仍然可以写
        assertDoesNotThrow(() -> overlapping.resolveWithin(workspace.resolve("normal.txt").toString()));
    }

    @Test
    @DisplayName("只读根的普通父目录中创建无关文件仍然允许")
    void unrelatedFileInReadOnlyRootParentAllowed() {
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        Path sibling = readonly.getParent().resolve("unrelated-" + System.nanoTime() + ".txt");
        assertEquals(sibling.toAbsolutePath().normalize(), sandbox.resolveWithin(sibling.toString()));
    }

    @Test
    @DisplayName("会话服务缺失、身份缺失、查询异常时都不放开")
    void failsClosed() {
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        String target = outside.resolve("secret.txt").toString();

        PathSandbox noService = new PathSandbox(workspace.toString(), List.of());
        assertThrows(SandboxViolationException.class, () -> noService.resolveWithin(target));

        ConversationContext.clear();
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin(target));

        ConversationContext.set(null, "conv");
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin(target));

        ConversationContext.set(7L, "conv");
        modes.fail = new RuntimeException("db down");
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin(target));
    }

    @Test
    @DisplayName("两个会话权限不串用")
    void sessionsIsolated() {
        ConversationContext.set(7L, "a");
        modes.mode = ConversationService.MODE_FULL_ACCESS;
        assertDoesNotThrow(() -> sandbox.resolveWithin(outside.resolve("secret.txt").toString()));

        ConversationContext.set(7L, "b");
        modes.mode = ConversationService.MODE_ASK;
        assertThrows(SandboxViolationException.class,
                () -> sandbox.resolveWithin(outside.resolve("secret.txt").toString()));
    }

    private static void delete(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (Exception ignored) {}
            });
        }
    }
}
