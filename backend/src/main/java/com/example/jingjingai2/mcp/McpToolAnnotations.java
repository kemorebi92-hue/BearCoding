package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP tools/list 的 annotations。缺失的键是 null，不当成 false。
 *
 * <p>这些值是服务自己报的，不可信：只能用来把决定收紧，
 * {@code readOnlyHint=true} 绝不能当成自动放行。
 */
public final class McpToolAnnotations {

    public final Boolean readOnlyHint;
    public final Boolean destructiveHint;
    public final Boolean idempotentHint;
    public final Boolean openWorldHint;

    public McpToolAnnotations(Boolean readOnlyHint, Boolean destructiveHint,
                              Boolean idempotentHint, Boolean openWorldHint) {
        this.readOnlyHint = readOnlyHint;
        this.destructiveHint = destructiveHint;
        this.idempotentHint = idempotentHint;
        this.openWorldHint = openWorldHint;
    }

    public static McpToolAnnotations none() {
        return new McpToolAnnotations(null, null, null, null);
    }

    public static McpToolAnnotations from(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return none();
        return new McpToolAnnotations(
                bool(node, "readOnlyHint"),
                bool(node, "destructiveHint"),
                bool(node, "idempotentHint"),
                bool(node, "openWorldHint"));
    }

    /** 非布尔（字符串、数字）视为缺失，不猜。 */
    private static Boolean bool(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || !v.isBoolean()) return null;
        return v.booleanValue();
    }
}
