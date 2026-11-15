package com.example.jingjingai2.mcp;

import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 落盘不改变污染规则：结果照旧被标记，写类工具照旧升级。 */
class McpSpillTaintTest {

    @TempDir
    Path tmp;

    private McpClientManager manager;

    @AfterEach
    void stop() {
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("落盘之后污染标记仍在，历史里是预览 + 路径而不是全文")
    void spillKeepsTaint() {
        Harness h = harness(true);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "conv-1", true, false, null, () -> false))) {
            String preview = h.callback.call("{\"chars\": 20000}");
            assertTrue(preview.length() < 20000, "给模型的是预览");
            assertTrue(preview.contains("【结果过长，完整内容已保存"), preview);
            assertTrue(preview.contains("不要改写或使用相对路径"), preview);
            assertTrue(preview.contains("TAIL-MARKER"), "预览要保留尾部");
            assertTrue(preview.indexOf("TAIL-MARKER") > 100, "尾部标记不能替代头部");
            assertTrue(preview.endsWith("[/artifact-ref]"), "系统可信引用必须在最后");
            assertNotNull(ToolArtifactRef.parse(preview), "必须能解析出系统签名引用");
            String spilled = readOnlyFile(h.store.sessionRoot("conv-1"));
            assertTrue(spilled.endsWith("TAIL-MARKER"), "落盘文件里必须是完整内容");

            List<Message> messages = new ArrayList<>();
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("1", h.exposed, preview))));
            McpUntrustedGuard.wrapUntrusted(messages, "conv-1", manager);
            String wrapped = ((ToolResponseMessage) messages.get(0)).getResponses().get(0).responseData();
            assertTrue(wrapped.startsWith(McpUntrustedGuard.UNTRUSTED_PREFIX), wrapped.substring(0, 60));
            assertTrue(manager.isConversationTainted("conv-1"), "落盘不能让污染标记丢失");
            assertEquals(java.util.Set.of("alpha"), manager.taintSources("conv-1"));
        }
    }

    @Test
    @DisplayName("落盘后写文件仍升成 ASK，且 ask 模式下污点不摘工具（阶段 1.5 规则的回归）")
    void spillDoesNotWeakenTaintPolicy() {
        Harness h = harness(true);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "conv-1", true, false, null, () -> false))) {
            h.callback.call("{\"chars\": 20000}");
            // 和 ChatController 一样：工具结果回来后由 noteToolResult 打污染标记
            h.noteTaint.run();
        }
        assertTrue(manager.isConversationTainted("conv-1"), "落盘不能让污染标记丢失");
        assertFalse(manager.shouldStripDangerous("conv-1", List.of()),
                "ask 模式下污点不摘工具，只升级审批");
    }

    @Test
    @DisplayName("spill-enabled=false：没有文件、内容走旧截断，污染照旧")
    void disabledKeepsOldBehaviour() {
        Harness h = harness(false);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "conv-1", true, false, null, () -> false))) {
            String text = h.callback.call("{\"chars\": 20000}");
            assertTrue(text.contains("已截断为头+尾共"), text);
            assertFalse(Files.exists(new McpOutputStore(h.outputProps).sessionRoot("conv-1")));
            h.noteTaint.run();
            assertTrue(manager.isConversationTainted("conv-1"));
        }
    }

    private Harness harness(boolean spillEnabled) {
        McpOutputProperties outputProps = new McpOutputProperties();
        outputProps.setDir(tmp.resolve("mcp-output").toString());
        outputProps.setSpillEnabled(spillEnabled);
        McpOutputStore store = new McpOutputStore(outputProps);

        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        spec.setTrust("untrusted");
        spec.setMaxResultChars(8000);
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("alpha", spec);
        props.setServers(servers);

        FakeMcpSession fake = new FakeMcpSession("alpha", List.of(
                new McpRemoteTool("big_text", "big", "{\"type\":\"object\"}")));
        fake.handler((name, args) -> McpCallResult.ok(multiline(20000) + "TAIL-MARKER"));

        manager = FakeMcpServers.start(props, Map.of("alpha", fake));
        manager.setOutputStore(store);
        ToolCallback cb = manager.callbacks()[0];
        Harness h = new Harness();
        h.callback = cb;
        h.exposed = cb.getToolDefinition().name();
        h.outputProps = outputProps;
        h.store = store;
        h.noteTaint = () -> manager.noteToolResult("conv-1", h.exposed);
        return h;
    }

    private static final class Harness {
        ToolCallback callback;
        String exposed;
        McpOutputProperties outputProps;
        McpOutputStore store;
        Runnable noteTaint;
    }

    private static String readOnlyFile(Path dir) {
        try (var ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) return Files.readString(p);
            }
        } catch (Exception e) {
            fail("读不到落盘文件: " + e.getMessage());
        }
        fail("落盘目录里没有文件: " + dir);
        return "";
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** 多行：单行超长会被 capLines 削掉，测旧截断必须用多行。 */
    static String multiline(int n) {
        StringBuilder sb = new StringBuilder(n + 8);
        while (sb.length() < n) {
            int room = n - sb.length();
            sb.append(repeat("y", Math.min(200, room)));
            if (sb.length() < n) sb.append('\n');
        }
        return sb.toString();
    }
}
