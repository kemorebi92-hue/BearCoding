package com.example.jingjingai2.memory;

import com.example.jingjingai2.config.MemoryProperties;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区长期记忆：缺目录零开销、索引优先、超预算截断、路径/密钥文件名 fail-closed。
 */
class WorkspaceMemoryTest {

    private Path root;
    private MemoryProperties props;
    private WorkspaceMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("memory-test");
        props = new MemoryProperties();
        memory = new WorkspaceMemory(props);
        WorkspaceContext.clear();
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

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("关掉 → 不读盘、injected=false")
    void disabledDoesNotInject() throws Exception {
        write("memory/MEMORY.md", "进度：已完成登录");
        props.setEnabled(false);
        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertFalse(snap.enabled);
        assertFalse(snap.injected);
        assertEquals("disabled", snap.reason);
        assertTrue(snap.text.isEmpty());
    }

    @Test
    @DisplayName("没有 memory/ 目录 → 零开销，不注入")
    void missingDirIsNoop() {
        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertTrue(snap.enabled);
        assertFalse(snap.injected);
        assertEquals("no-dir", snap.reason);
    }

    @Test
    @DisplayName("空目录 → 不注入")
    void emptyDirIsNoop() throws Exception {
        Files.createDirectories(root.resolve("memory"));
        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertFalse(snap.injected);
        assertEquals("empty", snap.reason);
    }

    @Test
    @DisplayName("工作区为 null → 不注入")
    void nullWorkspaceIsNoop() {
        WorkspaceMemory.Snapshot snap = memory.load(null);
        assertFalse(snap.injected);
        assertEquals("no-workspace", snap.reason);
    }

    @Test
    @DisplayName("MEMORY.md 最先，其它 md 按文件名；非 md / 隐藏文件跳过")
    void indexFirstThenAlphabetical() throws Exception {
        write("memory/zeta.md", "专题 Z");
        write("memory/alpha.md", "专题 A");
        write("memory/MEMORY.md", "进度：核心");
        write("memory/notes.txt", "不该出现");
        write("memory/.hidden.md", "隐藏");
        write("README.md", "工作区根的 md 不该进记忆");

        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertTrue(snap.injected, snap.reason);
        assertEquals(java.util.List.of("MEMORY.md", "alpha.md", "zeta.md"), snap.files);
        assertTrue(snap.text.contains("【长期记忆】"));
        assertTrue(snap.text.contains("不是必须遵守的规则"));
        int mem = snap.text.indexOf("### MEMORY.md");
        int alpha = snap.text.indexOf("### alpha.md");
        int zeta = snap.text.indexOf("### zeta.md");
        assertTrue(mem >= 0 && mem < alpha && alpha < zeta, snap.text);
        assertFalse(snap.text.contains("不该出现"));
        assertFalse(snap.text.contains("隐藏"));
        assertFalse(snap.text.contains("工作区根的 md"));
        assertTrue(snap.tokens > 0);
        assertTrue(snap.chars > 0);
        assertEquals(0, snap.skipped);
    }

    @Test
    @DisplayName("不递归：子目录里的 md 不注入")
    void doesNotRecurse() throws Exception {
        write("memory/MEMORY.md", "索引");
        write("memory/nested/deep.md", "不该出现");
        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertEquals(java.util.List.of("MEMORY.md"), snap.files);
        assertFalse(snap.text.contains("不该出现"));
    }

    @Test
    @DisplayName("单篇超长截断；合计超预算后面的文件跳过")
    void capsTruncateAndSkip() throws Exception {
        props.setMaxFileChars(20);
        props.setMaxTotalChars(20);
        props.setMaxFiles(5);
        write("memory/MEMORY.md", "一二三四五六七八九十abcdefghijEXTRA");
        write("memory/more.md", "后面这篇也应该被预算挡住");

        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertTrue(snap.injected);
        assertTrue(snap.text.contains("…（已截断）"));
        assertEquals(java.util.List.of("MEMORY.md"), snap.files);
        assertTrue(snap.skipped >= 1, "合计预算应跳过后续文件: " + snap.skipped);
    }

    @Test
    @DisplayName("max-files：索引优先留下，多出来的丢掉")
    void maxFilesKeepsIndex() throws Exception {
        props.setMaxFiles(2);
        write("memory/MEMORY.md", "索引");
        write("memory/a.md", "A");
        write("memory/b.md", "B");
        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertEquals(java.util.List.of("MEMORY.md", "a.md"), snap.files);
        assertEquals(1, snap.skipped);
        assertFalse(snap.text.contains("### b.md"));
    }

    @Test
    @DisplayName("文件名像密钥的跳过")
    void skipsSecretFilenames() throws Exception {
        write("memory/MEMORY.md", "正常");
        write("memory/secrets.md", "api-key=xxx");
        write("memory/db-password.md", "root/root");
        WorkspaceMemory.Snapshot snap = memory.load(root);
        assertEquals(java.util.List.of("MEMORY.md"), snap.files);
        assertTrue(snap.skipped >= 2);
        assertFalse(snap.text.contains("api-key"));
    }

    @Test
    @DisplayName("save：空名写 MEMORY.md；append 追加；非法路径拒绝")
    void saveSanitizesAndWrites() {
        WorkspaceContext.set(root);
        String r1 = memory.save(root, "", "# 核心\n登录已做完", false);
        assertTrue(r1.startsWith("✅"), r1);
        assertTrue(Files.isRegularFile(root.resolve("memory/MEMORY.md")));

        String r2 = memory.save(root, "progress", "下一步：接记忆模块", true);
        assertTrue(r2.startsWith("✅"), r2);
        assertTrue(Files.isRegularFile(root.resolve("memory/progress.md")));

        String r3 = memory.save(root, "../escape.md", "nope", false);
        assertTrue(r3.contains("非法") || r3.contains("越界"), r3);
        assertFalse(Files.exists(root.resolve("escape.md")));

        String r4 = memory.save(root, "secrets.md", "key", false);
        assertTrue(r4.contains("密钥"), r4);

        String r5 = memory.save(root, "C:/Windows/x.md", "x", false);
        assertTrue(r5.contains("非法") || r5.contains("越界"), r5);
    }

    @Test
    @DisplayName("save：关掉 / 无工作区 / 空内容都拒绝")
    void saveFailClosed() {
        props.setEnabled(false);
        assertTrue(memory.save(root, "MEMORY.md", "x", false).contains("未启用"));
        props.setEnabled(true);
        assertTrue(memory.save(null, "MEMORY.md", "x", false).contains("没有工作区"));
        assertTrue(memory.save(root, "MEMORY.md", "  ", false).contains("内容为空"));
    }

    @Test
    @DisplayName("sanitizeFilename：空→索引；补 .md；挡穿越")
    void sanitizeFilename() {
        assertEquals("MEMORY.md", memory.sanitizeFilename(null));
        assertEquals("MEMORY.md", memory.sanitizeFilename(""));
        assertEquals("progress.md", memory.sanitizeFilename("progress"));
        assertEquals("decisions.md", memory.sanitizeFilename("decisions.md"));
        assertNull(memory.sanitizeFilename("../x.md"));
        assertNull(memory.sanitizeFilename("a/b.md"));
        assertNull(memory.sanitizeFilename(".."));
        assertNull(memory.sanitizeFilename(".hidden.md"));
    }
}
