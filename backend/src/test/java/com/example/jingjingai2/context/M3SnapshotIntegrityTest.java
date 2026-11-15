package com.example.jingjingai2.context;

import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase M3-1：退休时的快照完整性校验，以及「退休后真的读得回来」。
 *
 * <p>M2 只验证「引用签名有效 + 带 sha256 + 快照文件存在」。存在但内容已被替换的快照
 * 仍然会被退休成可恢复引用，等于把一条读回来是假正文的引用发给模型。这里把闸门补到
 * **内容哈希一致**，并且（生产路径）**当前沙箱允许读回**。
 *
 * <p>本测试用真实文件、真实 {@link FileTool} 恢复路径，不用 FakeModel：验证的是
 * 存储与读取的一致性，不是模型行为。
 */
class M3SnapshotIntegrityTest {

    @TempDir
    Path tmp;

    /** 历史本身远低于这条线，只有把工具结果算进去才会超预算。 */
    private static final int SOFT_BUDGET = 1000;
    private static final int BODY_REPEAT = 1500;

    private static final String SNAPSHOT_BODY = "第一行：编译错误\n第二行：缺少符号 Invoice\n第三行：结束\n";

    @Test
    @DisplayName("签名与哈希都有效、沙箱可读：可以退休，且恢复结果与原始内容逐行一致")
    void verifiedSnapshotRetiresAndRecoversExactly() throws Exception {
        Path snapshot = writeSnapshot("ok.txt", SNAPSHOT_BODY);
        String signed = preview() + ToolArtifactRef.marker(
                snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand", sha256(SNAPSHOT_BODY));
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, readableSandbox());

        assertEquals(ContextProjectionPolicy.DecisionKind.RETIRE,
                decision(projected, "tool:call-1").kind(), decision(projected, "tool:call-1").toString());
        String note = bodyOf(projected.messages(), "call-1");
        assertFalse(note.contains("编译错误"), "退休后正文不再发给模型");
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(note);
        assertNotNull(ref, "省略标记必须带可验证引用: " + note);
        assertEquals(sha256(SNAPSHOT_BODY), ref.sha256(), "引用必须保留内容哈希");

        // 引用真的发出去了，快照必须在租约内受保护，不能被同会话的容量清理删掉。
        assertTrue(com.example.jingjingai2.tool.ToolOutputStore.isRetained(snapshot),
                "退休成功后必须给快照打上保留租约");

        // 真实恢复路径：readFile 按引用读快照，仍然验签 + 验哈希。
        String recovered = readableFileTool().readFile(markerOf(note), null, null);
        assertEquals(numbered(SNAPSHOT_BODY), recovered, "恢复出来的正文必须与落盘原文一致");
    }

    @Test
    @DisplayName("快照内容被替换：不得退休，原始工具正文必须留下")
    void tamperedSnapshotBlocksRetirement() throws Exception {
        Path snapshot = writeSnapshot("tampered.txt", SNAPSHOT_BODY);
        String signed = preview() + ToolArtifactRef.marker(
                snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand", sha256(SNAPSHOT_BODY));
        Files.writeString(snapshot, SNAPSHOT_BODY.replace("Invoice", "Tampered"), StandardCharsets.UTF_8);

        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual);
        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, readableSandbox());

        assertEquals("TOOL_RESULT_SNAPSHOT_HASH_MISMATCH", decision(projected, "tool:call-1").reason());
        assertEquals(signed, bodyOf(projected.messages(), "call-1"), "校验失败必须保留原始正文");

        // 同一份被改过的快照，读取端也必须拒绝，不能返回「看似成功」的内容。
        String read = readableFileTool().readFile(markerOf(signed), null, null);
        assertTrue(read.contains("快照内容校验不一致"), read);
        assertFalse(read.contains("Tampered"), "校验失败不得返回被替换的正文");
    }

    @Test
    @DisplayName("快照不存在：不得退休")
    void missingSnapshotBlocksRetirement() throws Exception {
        Path gone = tmp.resolve("gone.txt");
        String signed = preview() + ToolArtifactRef.marker(
                gone.toString(), SNAPSHOT_BODY.length(), "executeCommand", sha256(SNAPSHOT_BODY));
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, readableSandbox());

        assertEquals("TOOL_RESULT_SNAPSHOT_MISSING", decision(projected, "tool:call-1").reason());
        assertEquals(signed, bodyOf(projected.messages(), "call-1"));
    }

    @Test
    @DisplayName("引用签名被篡改：不得退休，恢复时明确拒绝")
    void tamperedSignatureIsRefused() throws Exception {
        Path snapshot = writeSnapshot("sig.txt", SNAPSHOT_BODY);
        String marker = ToolArtifactRef.marker(
                snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand", sha256(SNAPSHOT_BODY));
        String tampered = marker.replace("sig=", "sig=0");
        List<Message> actual = singleToolPrompt(preview() + tampered);
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, readableSandbox());

        assertEquals("TOOL_RESULT_NOT_RECOVERABLE", decision(projected, "tool:call-1").reason(),
                "验签失败的引用不算可恢复快照");
        assertEquals(preview() + tampered, bodyOf(projected.messages(), "call-1"));

        String read = readableFileTool().readFile(tampered, null, null);
        assertTrue(read.contains("签名无效"), read);
        assertFalse(read.contains("编译错误"), "验签失败不得返回任何正文");
    }

    @Test
    @DisplayName("引用缺少内容哈希：不得退休，也不能按引用读取")
    void referenceWithoutContentHashIsNotRecoverable() throws Exception {
        Path snapshot = writeSnapshot("nohash.txt", SNAPSHOT_BODY);
        String legacy = preview() + ToolArtifactRef.marker(snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand");
        List<Message> actual = singleToolPrompt(legacy);
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, readableSandbox());

        assertEquals("TOOL_RESULT_NOT_RECOVERABLE", decision(projected, "tool:call-1").reason());
        assertEquals(legacy, bodyOf(projected.messages(), "call-1"));

        String read = readableFileTool().readFile(markerOf(legacy), null, null);
        assertTrue(read.contains("缺少内容哈希"), read);
        assertFalse(read.contains("编译错误"), "无法证明完整就不能返回正文");
    }

    @Test
    @DisplayName("生产探针：沙箱不允许读的快照不得退休（文件存在不等于模型读得到）")
    void unauthorizedSnapshotBlocksRetirement() throws Exception {
        Path snapshot = writeSnapshot("outside.txt", SNAPSHOT_BODY);
        String signed = preview() + ToolArtifactRef.marker(
                snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand", sha256(SNAPSHOT_BODY));
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual);

        Path otherWorkspace = Files.createDirectories(tmp.resolve("other-workspace"));
        ToolSnapshotProbe outside = new FileTool(new PathSandbox(otherWorkspace.toString()))::snapshotStatus;
        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, outside);

        assertEquals("TOOL_RESULT_SNAPSHOT_UNAUTHORIZED", decision(projected, "tool:call-1").reason());
        assertEquals(signed, bodyOf(projected.messages(), "call-1"), "读不到的快照不能换成引用");
    }

    @Test
    @DisplayName("生产探针：沙箱允许读、哈希一致时才退休")
    void authorizedSnapshotRetires() throws Exception {
        Path snapshot = writeSnapshot("inside.txt", SNAPSHOT_BODY);
        String signed = preview() + ToolArtifactRef.marker(
                snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand", sha256(SNAPSHOT_BODY));
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true, readableSandbox());

        assertEquals(ContextProjectionPolicy.DecisionKind.RETIRE,
                decision(projected, "tool:call-1").kind());
        assertTrue(MessageProtocolValidator.check(projected.messages()).ok(), "退休后配对仍完整");
        assertTrue(hasCall(projected.messages(), "call-1"));
    }

    // ===================== helpers =====================

    private ToolSnapshotProbe readableSandbox() {
        return readableFileTool()::snapshotStatus;
    }

    /** 沙箱根就是临时目录，快照在工作区内 → 允许读取。 */
    private FileTool readableFileTool() {
        return new FileTool(new PathSandbox(tmp.toString()));
    }

    private Path writeSnapshot(String name, String body) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /** 真实落盘时模型看到的是「大段预览 + 签名引用」，预览本身就会撑爆软预算。 */
    private static String preview() {
        return "输出过长，只展示前几行。完整内容已落盘。\n" + "日志行".repeat(BODY_REPEAT / 3);
    }

    private static List<Message> singleToolPrompt(String body) {
        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(new UserMessage("当前问题"));
        actual.add(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "executeCommand",
                        "{\"command\":\"mvn test\"}"))));
        actual.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "executeCommand", body))));
        return actual;
    }

    /** 按真实请求逐轮吸收：producedRound 是「第几次模型调用前看到它」。 */
    private static ContextSourceLedger ledgerFor(List<Message> actual) {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-m3");
        ledger.absorb(actual, 1, null);
        return ledger;
    }

    /** 从省略说明里取回可传回 readFile 的纯引用。 */
    private static String markerOf(String note) {
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(note);
        assertNotNull(ref, "省略说明里必须有可解析的签名引用: " + note);
        return ToolArtifactRef.marker(ref.path(), ref.chars(), ref.tool(), ref.sha256());
    }

    private static String numbered(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) end--;
        for (int i = 0; i < end; i++) sb.append(i + 1).append('\t').append(lines[i]).append('\n');
        return sb.toString();
    }

    private static ContextProjectionPolicy.Decision decision(ContextProjectionPolicy.Result projected, String id) {
        return projected.decisions().stream().filter(d -> id.equals(d.id())).findFirst()
                .orElseThrow(() -> new AssertionError("没有决策: " + id));
    }

    private static String bodyOf(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && id.equals(response.id())) return response.responseData();
            }
        }
        return null;
    }

    private static boolean hasCall(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant) || assistant.getToolCalls() == null) continue;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call != null && id.equals(call.id())) return true;
            }
        }
        return false;
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
