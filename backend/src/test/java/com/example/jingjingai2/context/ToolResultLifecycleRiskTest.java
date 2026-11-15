package com.example.jingjingai2.context;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4-3C-1：只复现工具结果生命周期的三个组合风险。
 * 不改生产代码，不连数据库，不调用模型。
 */
class ToolResultLifecycleRiskTest {

    private static final Path ROOT = Path.of("D:/ws-lifecycle-risk").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    @Test
    @DisplayName("HMAC 有效不等于快照完整：篡改或删除后不能当原始内容恢复")
    void snapshotMutationOrDeletionBreaksContentIntegrity() throws Exception {
        String original = "compile-error: missing symbol Invoice\nline: src/Bill.java:18\n";
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(tmp.resolve("tool-output").toString());
        ToolOutputStore store = new ToolOutputStore(props);

        ToolOutputStore.Stored stored;
        try (ToolCallContext.Scope ignored = context()) {
            stored = store.store("executeCommand", original);
        }
        assertNotNull(stored, "测试落盘必须成功");
        String marker = ToolArtifactRef.marker(
                stored.path().toString(), original.length(), "executeCommand", sha256(original));
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(marker);
        assertNotNull(ref, "刚生成的签名引用必须有效");

        assertEquals(original, Files.readString(stored.path()), "未变化的快照应与原始内容一致");
        String intactRead = readSnapshot(store, marker);
        assertTrue(intactRead.contains("missing symbol Invoice"), intactRead);
        assertFalse(intactRead.contains("读取失败"), intactRead);

        String mutated = original.replace("Invoice", "Tampered");
        Files.writeString(stored.path(), mutated);
        String mutatedRead = readSnapshot(store, marker);
        assertNotNull(ToolArtifactRef.parse(marker), "快照变化后 HMAC 仍只证明标记未被篡改");
        assertTrue(mutatedRead.startsWith("读取失败"), mutatedRead);
        assertTrue(mutatedRead.contains("快照内容校验不一致"), mutatedRead);
        assertFalse(mutatedRead.contains("Tampered"), "哈希不一致时不能返回被替换的正文");
        assertNotEquals(sha256(original), sha256(Files.readString(stored.path())),
                "内容哈希必须能发现 HMAC 发现不了的快照变化");

        Files.delete(stored.path());
        assertNotNull(ToolArtifactRef.parse(marker), "文件删除后 HMAC 仍可能有效");
        String missingRead = readSnapshot(store, marker);
        assertTrue(missingRead.startsWith("读取失败"), missingRead);
        assertTrue(missingRead.contains("文件不存在"), missingRead);
        assertFalse(missingRead.contains(original), "删除后的恢复结果不能包含原始内容");
    }

    @Test
    @DisplayName("唯一无引用编译错误在压缩触发后仍然保留")
    void defaultHistoryCompactionKeepsUniqueUnrecoverableError() {
        String error = "src/Bill.java:18: error: cannot find symbol InvoiceService. "
                + "location: class BillingWorkflow\n";
        assertTrue(error.length() >= 80, "用例必须超过默认 80 字门槛");
        assertNull(ToolArtifactRef.parse(error), "唯一证据不能预先带恢复引用");

        List<Message> messages = new ArrayList<>();
        messages.add(call("error", "Bill.java"));
        messages.add(response("error", error));
        messages.add(call("latest", "done.txt"));
        messages.add(response("latest", "done"));

        int defaultBudget = 24000;
        assertTrue(ContextBudget.historyTokens(messages) <= defaultBudget,
                "单条证据本身不应超过默认保留预算");
        assertEquals(0, ToolHistoryCompactor.compactInPlace(messages, defaultBudget),
                "未超过默认预算时不应替换");

        int replaced = ToolHistoryCompactor.compactInPlace(messages, 1);
        assertEquals(0, replaced, "唯一且无可靠副本的证据不能替换");
        assertEquals(error, dataOf(messages, "error"));
        ToolHistoryCompactor.Result result = ToolHistoryCompactor.compact(messages, 1);
        assertFalse(result.complete());
        assertTrue(result.blocked() >= 1);
        assertTrue(joined(messages).contains("cannot find symbol InvoiceService"));
        assertTrue(joined(messages).contains("src/Bill.java:18"));
        assertTrue(MessageProtocolValidator.check(messages).ok());
    }

    private static String readSnapshot(ToolOutputStore store, String path) {
        PathSandbox sandbox = new PathSandbox(tmpRoot().toString(), List.of(store));
        try (ToolCallContext.Scope ignored = context()) {
            return new FileTool(sandbox).readFile(path, null, null);
        }
    }

    private static Path tmpRoot() {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    }

    private static ToolCallContext.Scope context() {
        return ToolCallContext.open(new ToolCallContext(7L, "lifecycle-risk", false, false, null, () -> false));
    }

    private static AssistantMessage call(String id, String path) {
        String args = "{\"path\":\"" + path.replace("\\", "\\\\") + "\"}";
        return new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(id, "function", "readFile", args)));
    }

    private static ToolResponseMessage response(String id, String data) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, "readFile", data)));
    }

    private static String dataOf(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && id.equals(response.id())) return response.responseData();
            }
        }
        return null;
    }

    private static String joined(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && response.responseData() != null) out.append(response.responseData()).append('\n');
            }
        }
        return out.toString();
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
