package com.example.jingjingai2.sandbox;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.mcp.McpOutputStore;
import com.example.jingjingai2.mcp.McpSpill;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 Windows 目录联接（mklink /J，不需要管理员）验证只读例外挡得住逃逸。
 * 非 Windows 上跳过并说明。
 */
class SpillJunctionEscapeTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("落盘目录里指向 dir 之外的目录联接：读文件和搜索都被真实路径校验拒绝")
    void junctionPointingOutsideIsRejected() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        if (!windows) {
            System.out.println("[skip] 目录联接只在 Windows 上测");
            return;
        }
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(tmp.resolve("mcp-output").toString());
        McpOutputStore store = new McpOutputStore(props);
        FileTool fileTool = new FileTool(new PathSandbox(workspace.toString(), List.of(store)));

        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "conv-1", true, false, null, () -> false))) {
            McpSpill spill = store.handle("srv", "read_item", filler(20000), 8000);
            assertTrue(spill.spilled);
        }

        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        Path link = store.sessionRoot("conv-1").resolve("escape");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), outside.toString()).start();
        int code = p.waitFor();
        if (code != 0) {
            String err = new String(p.getInputStream().readAllBytes());
            System.out.println("[skip] mklink /J 失败（退出码 " + code + "）: " + err.trim());
            return;
        }
        assertTrue(Files.isDirectory(link), "联接应已创建");

        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "conv-1", true, false, null, () -> false))) {
            String read = fileTool.readFile(link.resolve("secret.txt").toString(), null, null);
            assertTrue(read.contains("越界"), "目录联接逃逸必须被拒: " + read);
            String grep = fileTool.grepFile("secret", link.toString(), null, null, null, null);
            assertTrue(grep.contains("越界"), "搜索也不能顺着联接出去: " + grep);
        }
    }

    private static String filler(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('a');
        return sb.toString();
    }
}
