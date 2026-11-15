package com.example.jingjingai2.sandbox;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.mcp.McpOutputStore;
import com.example.jingjingai2.mcp.McpSpill;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只读例外的边界：只有当前会话的落盘目录、只有读文件与搜索工具。
 */
class SpillReadAccessTest {

    @TempDir
    Path tmp;

    private Path workspace;
    private McpOutputStore store;
    private FileTool fileTool;
    private Path sessionFile;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tmp.resolve("workspace"));
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(tmp.resolve("mcp-output").toString());
        store = new McpOutputStore(props);
        PathSandbox sandbox = new PathSandbox(workspace.toString(), List.of(store));
        fileTool = new FileTool(sandbox);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "conv-1", true, false, null, () -> false))) {
            McpSpill spill = store.handle("srv", "read_item", repeat("a", 20000), 8000);
            assertTrue(spill.spilled);
            sessionFile = Path.of(spill.path);
        }
        Files.writeString(workspace.resolve("inside.txt"), "workspace file");
    }

    @Test
    @DisplayName("自己会话目录下的落盘文件可以读，工作区文件照旧")
    void ownSessionFileReadable() {
        try (ToolCallContext.Scope ignored = open("conv-1")) {
            String out = fileTool.readFile(sessionFile.toString(), 1, 5);
            assertFalse(out.contains("越界"), out);
            assertFalse(out.contains("读取失败"), out);
            assertTrue(out.contains("1\t"), out);
        }
        try (ToolCallContext.Scope ignored = open("conv-1")) {
            String ws = fileTool.readFile("inside.txt", null, null);
            assertTrue(ws.contains("workspace file"), ws);
        }
    }

    @Test
    @DisplayName(".. 穿越、UNC、备用数据流、dir 根、别的会话目录全部拒绝")
    void escapesRejected() {
        try (ToolCallContext.Scope ignored = open("conv-1")) {
            String traversal = fileTool.readFile(sessionFile.resolve("..").resolve("..")
                    .resolve("outside.txt").toString(), null, null);
            assertTrue(traversal.contains("越界") || traversal.contains("不存在"), traversal);

            String unc = fileTool.readFile("\\\\server\\share\\secret.txt", null, null);
            assertTrue(unc.contains("UNC") || unc.contains("越界"), unc);

            String ads = fileTool.readFile(sessionFile + ":stream", null, null);
            assertTrue(ads.contains("备用数据流") || ads.contains("越界"), ads);

            String root = fileTool.readFile(store.root().toString(), null, null);
            assertTrue(root.contains("越界"), root);

            try (ToolCallContext.Scope other = open("conv-2")) {
                String foreign = fileTool.readFile(sessionFile.toString(), null, null);
                assertTrue(foreign.contains("越界"), foreign);
            }
        }
        String noCtx = fileTool.readFile(sessionFile.toString(), null, null);
        assertTrue(noCtx.contains("越界"), "没有上下文时不开放例外: " + noCtx);
    }

    @Test
    @DisplayName("目录本身、以及指向 dir 之外的符号链接/联接都拒绝")
    void directoryAndLinkRejected() {
        try (ToolCallContext.Scope ignored = open("conv-1")) {
            String dir = fileTool.readFile(store.sessionRoot("conv-1").toString(), null, null);
            assertTrue(dir.contains("越界") || dir.contains("目录"), dir);
        }
        Path outside = tmp.resolve("secret.txt");
        try {
            Files.writeString(outside, "secret");
            Path link = store.sessionRoot("conv-1").resolve("link.txt");
            Files.createSymbolicLink(link, outside);
            try (ToolCallContext.Scope ignored = open("conv-1")) {
                String viaLink = fileTool.readFile(link.toString(), null, null);
                assertTrue(viaLink.contains("越界"), "符号链接逃逸必须被真实路径校验挡住: " + viaLink);
            }
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            System.out.println("[skip] 本机无法创建符号链接: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("搜索工具同样只覆盖当前会话；写/删工具不享有例外")
    void searchAllowedButWriteIsNot() throws IOException {
        try (ToolCallContext.Scope ignored = open("conv-1")) {
            String found = fileTool.grepFile("a{10}", sessionFile.toString(), null, null, null, null);
            assertFalse(found.contains("越界"), found);
            assertTrue(found.contains("匹配") || found.contains("行"), found);
        }
        try (ToolCallContext.Scope ignored = open("conv-1")) {
            String write = fileTool.writeFile(sessionFile.toString(), "overwrite");
            assertTrue(write.contains("越界"), write);
            String del = fileTool.deletePath(sessionFile.toString(), false);
            assertTrue(del.contains("越界"), del);
        }
        assertEquals(20000, Files.size(sessionFile), "写/删工具不得改动落盘文件");
    }

    private ToolCallContext.Scope open(String conversationId) {
        return ToolCallContext.open(
                new ToolCallContext(7L, conversationId, true, false, null, () -> false));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
