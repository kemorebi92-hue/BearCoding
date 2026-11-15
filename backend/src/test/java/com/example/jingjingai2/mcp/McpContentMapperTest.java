package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpContentMapperTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode result(ArrayNode content) {
        ObjectNode n = M.createObjectNode();
        n.set("content", content);
        return n;
    }

    @Test
    @DisplayName("多个 text 用换行拼接")
    void concatenatesText() throws Exception {
        ArrayNode content = M.createArrayNode();
        content.add(M.createObjectNode().put("type", "text").put("text", "hello"));
        content.add(M.createObjectNode().put("type", "text").put("text", "world"));
        assertEquals("hello\nworld", McpContentMapper.toText(result(content), 8000));
    }

    @Test
    @DisplayName("image / resource 变成占位，不把 base64 塞进对话")
    void omitsBinary() {
        ArrayNode content = M.createArrayNode();
        content.add(M.createObjectNode().put("type", "text").put("text", "cap"));
        content.add(M.createObjectNode().put("type", "image").put("data", "AAAABBBB").put("mimeType", "image/png"));
        content.add(M.createObjectNode().put("type", "resource").put("uri", "file://x"));
        String t = McpContentMapper.toText(result(content), 8000);
        assertTrue(t.startsWith("cap"));
        assertTrue(t.contains(McpContentMapper.BINARY_PLACEHOLDER));
        assertFalse(t.contains("AAAABBBB"), "base64 不得出现");
    }

    @Test
    @DisplayName("SDK 1.1.0 真实类型：文本 resource 的正文进入模型文本")
    void sdkTextResourceIsMappedToText() {
        McpSchema.EmbeddedResource embedded = new McpSchema.EmbeddedResource(
                null,
                new McpSchema.TextResourceContents(
                        "repo://spring-projects/spring-boot/README.adoc", "text/plain", "正文内容"));
        com.fasterxml.jackson.databind.JsonNode part = M.valueToTree(embedded);

        // 先钉住 SDK 的真实序列化形状：正文在 resource.text，类型是 resource
        assertEquals("resource", part.path("type").asText(), "EmbeddedResource 序列化后的 type");
        assertEquals("正文内容", part.path("resource").path("text").asText(),
                "正文存放在 resource.text");

        ArrayNode content = M.createArrayNode();
        content.add(M.valueToTree(new McpSchema.TextContent("successfully downloaded text file")));
        content.add(part);
        String t = McpContentMapper.toText(result(content), 8000);

        assertEquals("successfully downloaded text file\n正文内容", t);
        assertFalse(t.contains(McpContentMapper.BINARY_PLACEHOLDER), "文本资源不该再被当成二进制");
    }

    @Test
    @DisplayName("SDK 1.1.0 真实类型：blob / image / audio 仍是占位，base64 不进对话")
    void sdkBinaryStaysOmitted() {
        ArrayNode content = M.createArrayNode();
        content.add(M.valueToTree(new McpSchema.BlobResourceContents(
                "file://x.bin", "application/octet-stream", "QUJDRA==")));
        content.add(M.valueToTree(new McpSchema.ImageContent(null, "QUJDRA==", "image/png")));
        content.add(M.valueToTree(new McpSchema.AudioContent(null, "QUJDRA==", "audio/wav")));

        String t = McpContentMapper.toText(result(content), 8000);

        assertFalse(t.contains("QUJDRA=="), "base64 不得出现在模型可见文本里");
        assertEquals(3, t.split("\n").length, "三段都应是占位");
        assertEquals(McpContentMapper.BINARY_PLACEHOLDER + "\n"
                + McpContentMapper.BINARY_PLACEHOLDER + "\n"
                + McpContentMapper.BINARY_PLACEHOLDER, t);
    }

    @Test
    @DisplayName("未知 resource 结构不被当成文本：缺 text、text 非字符串、resource 不是对象")
    void unknownResourceStaysPlaceholder() {
        ArrayNode content = M.createArrayNode();
        content.add(M.createObjectNode().put("type", "resource")
                .set("resource", M.createObjectNode().put("uri", "file://x")));      // 没有 text
        content.add(M.createObjectNode().put("type", "resource")
                .set("resource", M.createObjectNode().put("text", 123)));            // text 不是字符串
        content.add(M.createObjectNode().put("type", "resource").put("uri", "file://y")); // resource 不是对象
        content.add(M.createObjectNode().put("type", "resource")
                .set("resource", M.createObjectNode().put("text", "t").put("blob", "QUJD"))); // text+blob 并存，不猜

        String t = McpContentMapper.toText(result(content), 8000);

        assertEquals(4, t.split("\n").length);
        assertFalse(t.contains("QUJD"), "blob 不得泄露");
        assertTrue(t.lines().allMatch(McpContentMapper.BINARY_PLACEHOLDER::equals), t);
    }

    @Test
    @DisplayName("空正文的文本 resource 跳过，不占位也不抛异常")
    void emptyTextResourceIsSkipped() {
        ArrayNode content = M.createArrayNode();
        content.add(M.createObjectNode().put("type", "text").put("text", "before"));
        content.add(M.createObjectNode().put("type", "resource")
                .set("resource", M.createObjectNode().put("text", "")));
        content.add(M.createObjectNode().put("type", "text").put("text", "after"));

        assertEquals("before\nafter", McpContentMapper.toText(result(content), 8000));
        assertEquals("before\nafter", McpContentMapper.toFullText(result(content)));
    }

    @Test
    @DisplayName("文本 resource 的超长正文仍受 max-result-chars 截断")
    void textResourceStillTruncated() {
        String big = "x".repeat(5000);
        ArrayNode content = M.createArrayNode();
        content.add(M.createObjectNode().put("type", "resource")
                .set("resource", M.createObjectNode().put("text", big)));

        String t = McpContentMapper.toText(result(content), 200);

        assertTrue(t.length() <= 200, "实际长度 " + t.length());
        assertTrue(t.startsWith("xxx"), t);
        assertTrue(t.contains("已截断"), t);
    }

    @Test
    @DisplayName("超过 max-result-chars 在写入 messages 前截断，说明含头+尾 / 共 M")
    void truncatesBeforeMessages() {
        ArrayNode content = M.createArrayNode();
        content.add(M.createObjectNode().put("type", "text").put("text", "abcdefghij"));
        String t = McpContentMapper.toText(result(content), 8);
        assertTrue(t.contains("截断"), t);
        assertTrue(t.contains("共 10 字符"), t);
        assertTrue(t.contains("导航到更具体的页面"), t);
        assertFalse(t.contains("abcdefghij"), t);
    }

    @Test
    @DisplayName("截断预算：约 70% 头 + 30% 尾，N 与 M 对得上")
    void truncationBudgetAndCounts() {
        String text = "x".repeat(200);
        String t = McpContentMapper.truncate(text, 100);
        assertTrue(t.contains("共 200 字符"), t);
        assertTrue(t.contains("已截断为头+尾共 "), t);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("已截断为头\\+尾共 (\\d+) 字符").matcher(t);
        assertTrue(m.find(), t);
        int shown = Integer.parseInt(m.group(1));
        assertTrue(shown > 0 && shown < 200, "shown=" + shown);
        assertTrue(t.startsWith("x"), t);
        int noticeAt = t.indexOf("\n（页面内容过长");
        assertTrue(noticeAt > 0, t);
        String head = t.substring(0, noticeAt);
        int noticeEnd = t.indexOf("）", noticeAt);
        String tail = noticeEnd < 0 ? "" : t.substring(noticeEnd + 1);
        assertTrue(head.chars().allMatch(c -> c == 'x'), head);
        assertTrue(tail.isEmpty() || tail.chars().allMatch(c -> c == 'x'), tail);
        assertTrue(head.length() >= tail.length(), "head=" + head.length() + " tail=" + tail.length());
    }

    @Test
    @DisplayName("单行超过 300 字符先截行，再按 maxChars")
    void capsLongLines() {
        String line = "y".repeat(400);
        String t = McpContentMapper.truncate(line, 8000);
        assertTrue(t.contains("…"), t);
        assertFalse(t.contains("y".repeat(301)), t);
        assertTrue(t.startsWith("y".repeat(300)), t);
    }

    @Test
    @DisplayName("null / 空 content 不抛")
    void nullSafe() {
        assertEquals("", McpContentMapper.toText(null, 100));
        assertEquals("", McpContentMapper.toText(M.createObjectNode(), 100));
        assertEquals("abc", McpContentMapper.truncate("abc", 0), "≤0 表示不截断");
    }
}
