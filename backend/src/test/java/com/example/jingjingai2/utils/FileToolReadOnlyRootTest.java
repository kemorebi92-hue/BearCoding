package com.example.jingjingai2.utils;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.sandbox.WorkspaceContext;
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
 * 文件工具在 FULL_ACCESS 下对 MCP 只读根的保护：读允许、写/删/移拒绝。
 * 全部用临时目录。
 */
class FileToolReadOnlyRootTest {

    private Path workspace;
    private Path readonly;
    private FileTool fileTool;
    private final Modes modes = new Modes();

    static final class Modes extends ConversationService {
        String mode = ConversationService.MODE_FULL_ACCESS;

        Modes() {
            super(null, null);
        }

        @Override
        public String permissionMode(Long userId, String conversationId) {
            return mode;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("ft-ws");
        readonly = Files.createTempDirectory("ft-ro");
        Files.writeString(readonly.resolve("spill.txt"), "SPILL");
        PathSandbox sandbox = new PathSandbox(workspace.toString(), List.of(() -> List.of(readonly)));
        sandbox.setConversationService(modes);
        fileTool = new FileTool(sandbox);
        WorkspaceContext.set(workspace);
        ConversationContext.set(7L, "conv");
    }

    @AfterEach
    void tearDown() throws Exception {
        WorkspaceContext.clear();
        ConversationContext.clear();
        delete(workspace);
        delete(readonly);
    }

    @Test
    @DisplayName("copyPath 可以从只读根复制到工作区")
    void copyFromReadOnlyRootAllowed() {
        String result = fileTool.copyPath(readonly.resolve("spill.txt").toString(),
                workspace.resolve("copy.txt").toString());
        assertTrue(result.startsWith("✅"), result);
        assertTrue(Files.exists(workspace.resolve("copy.txt")));
    }

    @Test
    @DisplayName("copyPath 不能把目标写进只读根")
    void copyIntoReadOnlyRootDenied() throws Exception {
        Path src = Files.writeString(workspace.resolve("a.txt"), "A");
        String result = fileTool.copyPath(src.toString(), readonly.resolve("b.txt").toString());
        assertTrue(result.startsWith("复制失败"), result);
        assertFalse(Files.exists(readonly.resolve("b.txt")));
    }

    @Test
    @DisplayName("movePath 不能移动只读根内的源文件")
    void moveFromReadOnlyRootDenied() {
        String result = fileTool.movePath(readonly.resolve("spill.txt").toString(),
                workspace.resolve("moved.txt").toString());
        assertTrue(result.startsWith("移动失败"), result);
        assertTrue(Files.exists(readonly.resolve("spill.txt")), "只读根文件不能被移走");
    }

    @Test
    @DisplayName("movePath 不能把目标写进只读根")
    void moveIntoReadOnlyRootDenied() throws Exception {
        Path src = Files.writeString(workspace.resolve("a.txt"), "A");
        String result = fileTool.movePath(src.toString(), readonly.resolve("b.txt").toString());
        assertTrue(result.startsWith("移动失败"), result);
        assertTrue(Files.exists(src));
    }

    @Test
    @DisplayName("deletePath 不能删除只读根及其祖先")
    void deleteReadOnlyRootDenied() {
        String direct = fileTool.deletePath(readonly.resolve("spill.txt").toString(), true);
        assertTrue(direct.startsWith("删除失败"), direct);
        assertTrue(Files.exists(readonly.resolve("spill.txt")));

        String ancestor = fileTool.deletePath(readonly.getParent().toString(), true);
        assertTrue(ancestor.startsWith("删除失败"), ancestor);
        assertTrue(Files.exists(readonly));
    }

    @Test
    @DisplayName("工作区内的复制、移动、删除保持正常")
    void workspaceOpsStillWork() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "A");
        assertTrue(fileTool.copyPath("a.txt", "b.txt").startsWith("✅"));
        assertTrue(fileTool.movePath("b.txt", "c.txt").startsWith("✅"));
        assertTrue(fileTool.deletePath("c.txt", false).startsWith("✅"));
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
