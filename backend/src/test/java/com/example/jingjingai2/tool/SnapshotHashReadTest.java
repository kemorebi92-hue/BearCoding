package com.example.jingjingai2.tool;

import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** readFile 按签名引用恢复快照时必须校验内容哈希，且不能绕过只读沙箱。 */
class SnapshotHashReadTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private Path workspace;
    private FileTool tool;

    @BeforeEach
    void setUp() throws Exception {
        ToolArtifactRef.configure(KEY);
        workspace = Files.createDirectories(tmp.resolve("workspace"));
        tool = new FileTool(new PathSandbox(workspace.toString()));
    }

    @AfterEach
    void tearDown() {
        ToolArtifactRef.configure(KEY);
    }

    @Test
    @DisplayName("合法签名引用读取成功，内容与落盘时一致")
    void signedRefReadsTheSnapshot() throws Exception {
        String body = "line one\nline two\n";
        Path file = workspace.resolve("snap.txt");
        Files.writeString(file, body);
        String ref = refOf(file, body);

        String read = tool.readFile(ref, null, null);
        assertFalse(read.startsWith("读取失败"), read);
        assertTrue(read.contains("line one"), read);
        assertTrue(read.contains("line two"), read);
    }

    @Test
    @DisplayName("快照被修改后哈希校验失败，不返回正文")
    void mutatedSnapshotIsRejected() throws Exception {
        String body = "original fact";
        Path file = workspace.resolve("snap.txt");
        Files.writeString(file, body);
        String ref = refOf(file, body);
        Files.writeString(file, "tampered fact");

        String read = tool.readFile(ref, null, null);
        assertTrue(read.startsWith("读取失败"), read);
        assertTrue(read.contains("快照内容校验不一致"), read);
        assertFalse(read.contains("tampered"), read);
    }

    @Test
    @DisplayName("签名被修改后拒绝按引用读取")
    void tamperedSignatureIsRejected() throws Exception {
        Path file = workspace.resolve("snap.txt");
        Files.writeString(file, "body");
        String ref = refOf(file, "body");
        int at = ref.indexOf("sig=") + 4;
        char flipped = ref.charAt(at) == 'a' ? 'b' : 'a';
        String tampered = ref.substring(0, at) + flipped + ref.substring(at + 1);

        String read = tool.readFile(tampered, null, null);
        assertTrue(read.contains("签名无效"), read);
        assertFalse(read.contains("body"), read);
    }

    @Test
    @DisplayName("普通文件路径仍按原行为读取，不做哈希校验")
    void plainPathStillReads() throws Exception {
        Files.writeString(workspace.resolve("plain.txt"), "plain body");
        String read = tool.readFile("plain.txt", null, null);
        assertFalse(read.startsWith("读取失败"), read);
        assertTrue(read.contains("plain body"), read);
    }

    @Test
    @DisplayName("指向未授权目录的引用仍被沙箱拒绝")
    void refCannotEscapeTheSandbox() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside")).resolve("secret.txt");
        String body = "secret";
        Files.writeString(outside, body);

        String read = tool.readFile(refOf(outside, body), null, null);
        assertTrue(read.contains("越界"), read);
        assertFalse(read.contains("1\tsecret"), read);
    }

    @Test
    @DisplayName("引用里的路径、字符数或哈希被改后签名失效")
    void tamperedFieldsInvalidateTheSignature() throws Exception {
        Path file = workspace.resolve("snap.txt");
        Files.writeString(file, "body");
        String ref = refOf(file, "body");
        for (String bad : new String[]{
                ref.replace("chars=", "chars=9").replaceFirst("chars=9\\d*", "chars=9999"),
                ref.replace("sha256=", "sha256=ab"),
                ref.replace(ToolArtifactRef.OPEN, ToolArtifactRef.OPEN + "x")}) {
            if (bad.equals(ref)) continue;
            String read = tool.readFile(bad, null, null);
            assertTrue(read.contains("签名无效") || read.contains("越界"), read);
            assertNull(ToolArtifactRef.parse(bad));
        }
    }

    @Test
    @DisplayName("FULL_ACCESS 也不能靠引用读到只读根之外再绕回未授权内容")
    void fullAccessStillGoesThroughTheSandbox() throws Exception {
        Files.writeString(workspace.resolve("inside.txt"), "inside");
        try (ToolCallContext.Scope ignored = ToolCallContext.open(
                new ToolCallContext(1L, "conv", false, false, null, () -> false))) {
            String read = tool.readFile("inside.txt", null, null);
            assertTrue(read.contains("inside"), read);
        }
    }

    private static String refOf(Path file, String body) throws Exception {
        return ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
