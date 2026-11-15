package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 把 MCP {@code tools/call} 的 content 数组收成一段给模型看的纯文本。
 *
 * <p>text 拼接；image / audio / resource 换成 {@code [binary content omitted]}，
 * 避免把 base64 推进对话。截断发生在写入 messages <strong>之前</strong>。
 */
public final class McpContentMapper {

    public static final String BINARY_PLACEHOLDER = "[binary content omitted]";

    private McpContentMapper() {}

    /**
     * @param result {@code tools/call} 的 result 对象（含 content / isError）
     * @param maxChars {@code app.mcp.max-result-chars}；≤0 表示不截断
     */
    public static String toText(JsonNode result, int maxChars) {
        if (result == null || result.isNull()) return "";
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                appendPart(sb, part);
            }
        } else if (content != null && content.isTextual()) {
            sb.append(content.asText());
        }
        if (sb.length() == 0) {
            JsonNode msg = result.get("message");
            if (msg != null && msg.isTextual()) sb.append(msg.asText());
        }
        return truncate(sb.toString(), maxChars);
    }

    /**
     * 完整文本，不截断、不按行削。落盘用这份。图片仍是占位符，不把二进制写进文件。
     */
    public static String toFullText(JsonNode result) {
        if (result == null || result.isNull()) return "";
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode part : content) appendPart(sb, part);
        } else if (content != null && content.isTextual()) {
            sb.append(content.asText());
        }
        if (sb.length() == 0) {
            JsonNode msg = result.get("message");
            if (msg != null && msg.isTextual()) sb.append(msg.asText());
        }
        return sb.toString();
    }

    /** 单行超过这个长度先截掉尾巴，避免 YAML snapshot 一行把预算吃光。 */
    public static final int MAX_LINE_CHARS = 300;

    /**
     * 截断说明含「头+尾共 N / 共 M」，并建议换更具体的页面。
     */
    public static String truncationNotice(int shown, int total) {
        return "\n（页面内容过长，已截断为头+尾共 " + shown + " 字符，共 " + total
                + " 字符；如需其他部分，请导航到更具体的页面）";
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) return "";
        text = capLines(text, MAX_LINE_CHARS);
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        int total = text.length();
        int shown = 0;
        String notice = truncationNotice(shown, total);
        int budget = Math.max(0, maxChars - notice.length());
        for (int i = 0; i < 4; i++) {
            shown = budget;
            notice = truncationNotice(shown, total);
            budget = Math.max(0, maxChars - notice.length());
        }
        if (shown >= total) return text;
        int head = (int) Math.round(shown * 0.7);
        int tail = shown - head;
        if (head < 0) head = 0;
        if (tail < 0) tail = 0;
        if (head > text.length()) head = text.length();
        StringBuilder out = new StringBuilder(shown + notice.length() + 8);
        out.append(text, 0, head);
        out.append(truncationNotice(head + tail, total));
        if (tail > 0) {
            int from = Math.max(head, text.length() - tail);
            out.append(text, from, text.length());
        }
        return out.toString();
    }

    static String capLines(String text, int maxLine) {
        if (text == null || text.isEmpty() || maxLine <= 0) return text == null ? "" : text;
        if (text.length() <= maxLine && text.indexOf('\n') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            int len = end - start;
            if (len > maxLine) {
                sb.append(text, start, start + maxLine).append('…');
            } else {
                sb.append(text, start, end);
            }
            if (nl < 0) break;
            sb.append('\n');
            start = nl + 1;
        }
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, JsonNode part) {
        if (part == null || part.isNull()) return;
        String type = part.path("type").asText("");
        String piece;
        if ("resource".equals(type)) {
            String body = textResourceBody(part);
            if (body == null) {
                piece = BINARY_PLACEHOLDER;
            } else if (body.isEmpty()) {
                return;   // 空正文和空 TextContent 一样跳过，不占位
            } else {
                piece = body;
            }
        } else if ("image".equals(type) || "audio".equals(type)
                || "resource_link".equals(type) || "blob".equals(type)) {
            piece = BINARY_PLACEHOLDER;
        } else if (part.has("text")) {
            piece = part.path("text").asText("");
        } else if ("text".equals(type)) {
            piece = "";
        } else {
            piece = BINARY_PLACEHOLDER;
        }
        if (piece.isEmpty() && !"text".equals(type) && !part.has("text")) {
            piece = BINARY_PLACEHOLDER;
        }
        if (piece.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(piece);
    }

    /**
     * resource 里的文本正文：只有 {@code resource.text} 是字符串、且没有 {@code blob} 时才返回。
     * blob、缺字段、非对象一律返回 null，由调用方按二进制占位处理——不猜未知结构。
     */
    private static String textResourceBody(JsonNode part) {
        JsonNode resource = part.path("resource");
        if (!resource.isObject() || resource.has("blob")) return null;
        JsonNode text = resource.get("text");
        if (text == null || !text.isTextual()) return null;
        return text.asText("");
    }
}
