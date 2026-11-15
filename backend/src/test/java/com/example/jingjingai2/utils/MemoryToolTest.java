package com.example.jingjingai2.utils;

import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.memory.WorkspaceMemory;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * saveMemory 工具：只写当前工作区的 memory/，不接受模型乱填路径。
 */
class MemoryToolTest {

    private Path root;
    private MemoryTool tool;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("memory-tool-test");
        tool = new MemoryTool(new WorkspaceMemory(new MemoryProperties()));
        WorkspaceContext.set(root);
    }

    @AfterEach
    void tearDown() throws Exception {
        WorkspaceContext.clear();
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    @Test
    @DisplayName("默认覆盖写入 MEMORY.md")
    void writesIndex() throws Exception {
        String r = tool.saveMemory("", "# 核心\n这是菁菁 AI", null);
        assertTrue(r.startsWith("✅"), r);
        String body = Files.readString(root.resolve("memory/MEMORY.md"), StandardCharsets.UTF_8);
        assertTrue(body.contains("菁菁 AI"));
    }

    @Test
    @DisplayName("append 追加；overwrite 覆盖")
    void appendThenOverwrite() throws Exception {
        tool.saveMemory("progress.md", "第一步", "overwrite");
        tool.saveMemory("progress.md", "第二步", "append");
        String body = Files.readString(root.resolve("memory/progress.md"), StandardCharsets.UTF_8);
        assertTrue(body.contains("第一步"));
        assertTrue(body.contains("第二步"));
        tool.saveMemory("progress.md", "只留这个", "overwrite");
        body = Files.readString(root.resolve("memory/progress.md"), StandardCharsets.UTF_8);
        assertTrue(body.contains("只留这个"));
        assertFalse(body.contains("第一步"));
    }

    @Test
    @DisplayName("没有工作区上下文 → 拒绝")
    void noWorkspaceRejected() {
        WorkspaceContext.clear();
        String r = tool.saveMemory("MEMORY.md", "x", null);
        assertTrue(r.contains("没有工作区"), r);
    }
}
