package com.example.jingjingai2.agent.turn;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 提示词与工具表的规范化：哈希之前先变成唯一的文本形式，
 * 这样"同一个提示"无论工具注册顺序、JSON 键顺序如何都得到同一个哈希。
 */
public final class PromptCanonical {

    private PromptCanonical() {}

    /**
     * 工具表 → 规范化 JSON：按工具名排序，每个工具的键也按字母排序，
     * 内容是 name + description + input schema 三样，不是名字列表。
     * schema 先解析再按键递归排序后重新序列化，所以键序不同但语义相同的 schema 哈希相同。
     */
    public static String toolsJson(ToolCallback[] callbacks) {
        List<ToolView> tools = new ArrayList<>();
        if (callbacks != null) {
            for (ToolCallback cb : callbacks) {
                if (cb == null || cb.getToolDefinition() == null) continue;
                ToolDefinition def = cb.getToolDefinition();
                tools.add(new ToolView(def.name(), def.description(), def.inputSchema()));
            }
        }
        tools.sort((a, b) -> {
            String an = a.name() == null ? "" : a.name();
            String bn = b.name() == null ? "" : b.name();
            return an.compareTo(bn);
        });
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) sb.append(',');
            ToolView t = tools.get(i);
            // 键按字母序：description、inputSchema、name
            sb.append('{');
            sb.append("\"description\":").append(jsonString(t.description()));
            sb.append(",\"inputSchema\":").append(canonicalizeSchema(t.inputSchema()));
            sb.append(",\"name\":").append(jsonString(t.name()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** schema 文本解析成 JSON 后按键排序重新输出；解析不了就当普通字符串处理。 */
    @SuppressWarnings("unchecked")
    private static String canonicalizeSchema(String schema) {
        if (schema == null || schema.isBlank()) return "null";
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(schema, Object.class);
            return toCanonical(sortKeys(parsed));
        } catch (Exception e) {
            return jsonString(schema);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortKeys(Object node) {
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sortKeys(e.getValue()));
            }
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(sortKeys(o));
            return out;
        }
        return node;
    }

    private static String toCanonical(Object node) {
        if (node == null) return "null";
        if (node instanceof String s) return jsonString(s);
        if (node instanceof Boolean || node instanceof Number) return node.toString();
        if (node instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonString(String.valueOf(e.getKey()))).append(':').append(toCanonical(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (node instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toCanonical(list.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return jsonString(String.valueOf(node));
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private record ToolView(String name, String description, String inputSchema) {}
}
