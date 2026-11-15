package com.example.jingjingai2.context;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 4-3D-1：压缩安全候选、内容哈希和失败回滚。不连数据库，不调用模型。 */
class ToolResultSafetyFixTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("有逐字重复候选时优先替换它，唯一证据保留")
    void compactionPrefersExactDuplicate() {
        String duplicate = "x".repeat(100);
        String unique = "src/Bill.java:18: error: cannot find symbol InvoiceService in workflow";
        List<Message> messages = new ArrayList<>();
        messages.add(call("dup-old"));
        messages.add(response("dup-old", duplicate));
        messages.add(call("unique"));
        messages.add(response("unique", unique));
        messages.add(call("dup-new"));
        messages.add(response("dup-new", duplicate));
        messages.add(call("latest"));
        messages.add(response("latest", "done"));

        ToolHistoryCompactor.Result result = ToolHistoryCompactor.compact(messages, 1);
        assertTrue(result.replaced() >= 1);
        assertFalse(result.complete(), "唯一证据会让预算仍超出");
        assertTrue(ToolHistoryCompactor.isMarker(dataOf(messages, "dup-old")));
        assertEquals(unique, dataOf(messages, "unique"));
        assertEquals(duplicate, dataOf(messages, "dup-new"));
        assertTrue(MessageProtocolValidator.check(messages).ok());
    }

    @Test
    @DisplayName("校验通过且本次进程保留的快照可以替换；旧格式和无保留快照不行")
    void onlyVerifiedRetainedSnapshotIsSafe() throws Exception {
        String original = "snapshot-body-" + "x".repeat(80);
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(tmp.resolve("out").toString());
        ToolOutputStore store = new ToolOutputStore(props);
        ToolOutputStore.Stored stored;
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(7L, "safety-fix", false, false, null, () -> false))) {
            stored = store.store("executeCommand", original);
        }
        String hashed = "visible " + "y".repeat(80) + "\n"
                + ToolArtifactRef.marker(stored.path().toString(), original.length(), "executeCommand", stored.sha256());
        String legacy = "visible " + "z".repeat(80) + "\n"
                + ToolArtifactRef.marker(stored.path().toString(), original.length(), "executeCommand");
        assertFalse(ToolArtifactRef.parse(legacy).hasContentHash(), "旧格式不能伪装成有内容校验");

        List<Message> messages = new ArrayList<>();
        messages.add(call("hashed"));
        messages.add(response("hashed", hashed));
        messages.add(call("legacy"));
        messages.add(response("legacy", legacy));
        messages.add(call("latest"));
        messages.add(response("latest", "done"));
        ToolHistoryCompactor.Result result = ToolHistoryCompactor.compact(messages, 1);
        assertTrue(ToolHistoryCompactor.isMarker(dataOf(messages, "hashed")), dataOf(messages, "hashed"));
        assertEquals(legacy, dataOf(messages, "legacy"), "无内容哈希的旧引用必须保留原文");
        assertFalse(result.complete());
        assertTrue(MessageProtocolValidator.check(messages).ok());
    }

    @Test
    @DisplayName("修改引用中的 SHA-256 后签名校验失败")
    void tamperedContentHashFailsSignature() throws Exception {
        String hash = sha256("abc");
        String marker = ToolArtifactRef.marker("C:\\a.txt", 3, "readFile", hash);
        assertTrue(ToolArtifactRef.parse(marker).hasContentHash());
        String other = sha256("abd");
        assertNull(ToolArtifactRef.parse(marker.replace(hash, other)));
    }

    @Test
    @DisplayName("压缩异常时回滚，不留下半完成替换")
    void compactionExceptionRollsBack() {
        String body = "x".repeat(100);
        List<Message> messages = new ArrayList<>() {
            private static final long serialVersionUID = 1L;
            private int reads;

            @Override
            public Message get(int index) {
                if (++reads > 20) throw new IllegalStateException("boom");
                return super.get(index);
            }
        };
        messages.add(call("a"));
        messages.add(response("a", body));
        messages.add(call("b"));
        messages.add(response("b", body));
        messages.add(call("latest"));
        messages.add(response("latest", "done"));
        ToolHistoryCompactor.Result result = ToolHistoryCompactor.compact(messages, 1);
        assertEquals(0, result.replaced());
        assertFalse(result.complete());
        assertEquals(body, dataOf(messages, "a"));
        assertEquals(body, dataOf(messages, "b"));
    }

    private static AssistantMessage call(String id) {
        return new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(id, "function", "readFile", "{\"path\":\"a.txt\"}")));
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

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
