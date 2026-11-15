package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.mcp.McpUrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * 扫工具参数里「整段就是一个 URL」的字符串。普通句子（含空白、或不像 URL）不拦。
 * 不认 Playwright 的参数名：任何工具的任何字符串字段都走同一套。
 */
public final class UrlArgumentScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final McpServerSpec.UrlPolicy POLICY = new McpServerSpec.UrlPolicy();

    private UrlArgumentScanner() {}

    /** @return null = 没有要拒绝的地址 */
    public static String reject(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        String trimmed = arguments.trim();
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node.isObject() || node.isArray()) return walk(node);
        } catch (Exception ignored) {
            // 不是 JSON，再看整段是不是一个 URL
        }
        return consider(trimmed);
    }

    private static String walk(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            return consider(node.asText());
        }
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                String why = walk(it.next().getValue());
                if (why != null) return why;
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String why = walk(child);
                if (why != null) return why;
            }
        }
        return null;
    }

    private static String consider(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || hasWhitespace(t)) return null;
        // 字符串里可能嵌了一整段 JSON（例如自然语言添加服务的 proposalJson）。
        // 那是配置文档，不是 URL；继续往里走，里面的字段照样逐个检查。
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonNode inner = MAPPER.readTree(t);
                if (inner.isObject() || inner.isArray()) return walk(inner);
            } catch (Exception ignored) {
                // 不是 JSON，按普通字符串继续
            }
        }
        if (!interesting(t)) return null;
        return McpUrlGuard.checkUrl(t, POLICY);
    }

    private static boolean hasWhitespace(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) return true;
        }
        return false;
    }

    private static boolean interesting(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("://")) return true;
        return lower.startsWith("file:") || lower.startsWith("javascript:")
                || lower.startsWith("data:") || lower.startsWith("about:")
                || lower.startsWith("chrome:") || lower.startsWith("edge:");
    }
}
