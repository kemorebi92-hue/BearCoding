package com.example.jingjingai2.memory;

import com.example.jingjingai2.DTO.ChatResponse;
import com.example.jingjingai2.config.MemoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动总结闸门：闲聊不花调用；改文件 / 推进计划才写 MEMORY.md。
 */
class MemoryConsolidatorTest {

    private Path root;
    private MemoryProperties props;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("memory-consol-test");
        props = new MemoryProperties();
        consolidator = new MemoryConsolidator(props, new WorkspaceMemory(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    private static ChatResponse.ToolCallRecord rec(String name, String input, boolean ok) {
        ChatResponse.ToolCallRecord r = new ChatResponse.ToolCallRecord();
        r.setToolName(name);
        r.setInput(input);
        r.setResult(ok ? "✅" : "失败");
        r.setSuccess(ok);
        return r;
    }

    @Test
    @DisplayName("关掉 / 关掉自动总结 / 没有工具 → 不跑")
    void skipsWhenDisabledOrIdle() {
        props.setEnabled(false);
        assertEquals("disabled", consolidator.decide(List.of(rec("editFile", "{}", true))).reason);
        props.setEnabled(true);
        props.setAutoConsolidate(false);
        assertEquals("no-auto", consolidator.decide(List.of(rec("editFile", "{}", true))).reason);
        props.setAutoConsolidate(true);
        assertEquals("no-progress", consolidator.decide(List.of()).reason);
        assertEquals("no-progress", consolidator.decide(null).reason);
    }

    @Test
    @DisplayName("只读 / 失败的写入 / explore 子代理 → 闲聊零开销")
    void skipsReadOnlyAndFailedWrites() {
        assertFalse(consolidator.decide(List.of(
                rec("readFile", "{\"path\":\"a.java\"}", true),
                rec("grepFile", "{}", true),
                rec("webSearch", "{}", true))).run);
        assertFalse(consolidator.decide(List.of(
                rec("editFile", "{\"path\":\"a.java\"}", false))).run);
        assertFalse(consolidator.decide(List.of(
                rec("spawnSubagent", "{\"mode\":\"explore\",\"goal\":\"查一下\"}", true))).run);
    }

    @Test
    @DisplayName("成功改文件 / 建计划 / implement 子代理 → 要总结")
    void runsOnDurableProgress() {
        assertTrue(consolidator.decide(List.of(
                rec("editFile", "{\"path\":\"a.java\"}", true))).run);
        assertTrue(consolidator.decide(List.of(
                rec("createPlan", "{}", true))).run);
        assertTrue(consolidator.decide(List.of(
                rec("updatePlanStep", "{\"stepId\":\"s1\"}", true))).run);
        assertTrue(consolidator.decide(List.of(
                rec("spawnSubagent", "{\"mode\":\"implement\",\"goal\":\"改登录\"}", true))).run);
    }

    @Test
    @DisplayName("模型这轮已经写过 MEMORY.md → 不再额外总结")
    void skipsWhenIndexAlreadySaved() {
        MemoryConsolidator.Decision d = consolidator.decide(List.of(
                rec("editFile", "{\"path\":\"a.java\"}", true),
                rec("saveMemory", "{\"name\":\"MEMORY.md\",\"content\":\"x\"}", true)));
        assertFalse(d.run);
        assertEquals("already-saved", d.reason);
    }

    @Test
    @DisplayName("只写了专题文件（不是索引）→ 仍要更新 MEMORY.md")
    void topicalSaveStillConsolidates() {
        assertTrue(consolidator.decide(List.of(
                rec("saveMemory", "{\"name\":\"decisions.md\",\"content\":\"用 JWT\"}", true))).run);
        // 空 name = 索引
        assertEquals("already-saved", consolidator.decide(List.of(
                rec("saveMemory", "{\"name\":\"\",\"content\":\"x\"}", true))).reason);
    }

    @Test
    @DisplayName("parseBody：优先抠标记；没有标记但像索引也可以；密钥拒绝")
    void parseBodyExtractsOrRejects() {
        String marked = "好的\n<<<MEMORY\n# 核心\n登录\n# 当前进度\n做完了\n# 下一步\n接支付\nMEMORY>>>\n废话";
        String body = consolidator.parseBody(marked);
        assertNotNull(body);
        assertTrue(body.contains("# 当前进度"));
        assertFalse(body.contains("废话"));

        String fallback = "# 核心\nX\n# 当前进度\nY\n# 下一步\nZ";
        assertEquals(fallback, consolidator.parseBody(fallback));

        assertNull(consolidator.parseBody("随便说两句"));
        assertNull(consolidator.parseBody("<<<MEMORY\n# 核心\napi-key = sk-abcdefghijklmnopqrstuvwxyz\n# 当前进度\n无\n# 下一步\n无\nMEMORY>>>"));
        assertNull(consolidator.parseBody(null));
        assertNull(consolidator.parseBody("   "));
    }

    @Test
    @DisplayName("writeIndex：生成 memory/MEMORY.md；空内容拒绝")
    void writeIndexPersists() throws Exception {
        String r = consolidator.writeIndex(root, "# 核心\n菁菁\n# 当前进度\n记忆模块已接\n# 下一步\n测自动总结");
        assertTrue(r.startsWith("✅"), r);
        Path file = root.resolve("memory/MEMORY.md");
        assertTrue(Files.isRegularFile(file));
        String disk = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(disk.contains("记忆模块已接"));

        assertTrue(consolidator.writeIndex(root, "  ").contains("为空"));
        assertTrue(consolidator.writeIndex(root, "password=supersecret").contains("密钥"));
    }

    @Test
    @DisplayName("buildUserPayload 带上旧索引、用户话、改过的路径")
    void payloadIncludesOldIndexAndPaths() throws Exception {
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("memory/MEMORY.md"), "# 核心\n旧约定", StandardCharsets.UTF_8);
        String payload = consolidator.buildUserPayload(
                root,
                "把登录改成 JWT",
                "已经改完 UserService",
                List.of(rec("editFile", "{\"path\":\"UserService.java\"}", true)),
                "☑ s1 done 改登录");
        assertTrue(payload.contains("旧约定"));
        assertTrue(payload.contains("JWT"));
        assertTrue(payload.contains("UserService.java"));
        assertTrue(payload.contains("改登录"));
    }
}
