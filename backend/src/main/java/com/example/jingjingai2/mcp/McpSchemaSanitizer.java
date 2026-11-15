package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 把 MCP 的 inputSchema 收成 DeepSeek / OpenAI 兼容协议吃得下的扁平 JSON Schema。
 *
 * <p>剥掉 {@code $ref} / {@code $defs} / {@code anyOf|oneOf|allOf}。剥空了就回退
 * {@code {"type":"object"}}，宁可少字段也不能让整轮 chat/completions 400。
 */
public final class McpSchemaSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String FALLBACK = "{\"type\":\"object\"}";

    private McpSchemaSanitizer() {}

    public static String sanitize(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return FALLBACK;
        try {
            JsonNode root = MAPPER.readTree(schemaJson);
            JsonNode cleaned = clean(root, 0);
            if (cleaned == null || cleaned.isNull()
                    || (cleaned.isObject() && cleaned.size() == 0)) {
                return FALLBACK;
            }
            return MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    private static JsonNode clean(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > 12) {
            return objectWithType();
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode c : node) out.add(clean(c, depth + 1));
            return out;
        }
        if (!node.isObject()) return node;

        ObjectNode obj = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            obj.set(e.getKey(), e.getValue().deepCopy());
        }
        obj.remove("$ref");
        obj.remove("$schema");
        obj.remove("$id");
        obj.remove("$defs");
        obj.remove("definitions");

        JsonNode union = firstNonNull(obj.remove("anyOf"), obj.remove("oneOf"), obj.remove("allOf"));
        if (union != null) {
            JsonNode picked = pickObjectBranch(union);
            if (picked != null && picked.isObject()) {
                ObjectNode merged = (ObjectNode) picked.deepCopy();
                Iterator<Map.Entry<String, JsonNode>> extra = obj.fields();
                while (extra.hasNext()) {
                    Map.Entry<String, JsonNode> e = extra.next();
                    if (!merged.has(e.getKey())) merged.set(e.getKey(), e.getValue());
                }
                obj = merged;
                obj.remove("$ref");
                obj.remove("$schema");
                obj.remove("$id");
                obj.remove("$defs");
                obj.remove("definitions");
                obj.remove("anyOf");
                obj.remove("oneOf");
                obj.remove("allOf");
            }
        }

        if (obj.has("properties") && obj.get("properties").isObject()) {
            ObjectNode props = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> propIt = obj.get("properties").fields();
            while (propIt.hasNext()) {
                Map.Entry<String, JsonNode> e = propIt.next();
                props.set(e.getKey(), clean(e.getValue(), depth + 1));
            }
            obj.set("properties", props);
        }
        if (obj.has("items")) {
            obj.set("items", clean(obj.get("items"), depth + 1));
        }
        if (obj.has("additionalProperties") && obj.get("additionalProperties").isObject()) {
            obj.set("additionalProperties", clean(obj.get("additionalProperties"), depth + 1));
        }

        if (!obj.has("type") && !obj.has("properties") && !obj.has("enum")) {
            obj.put("type", "object");
        }
        return obj;
    }

    private static JsonNode pickObjectBranch(JsonNode union) {
        if (union == null) return null;
        if (union.isObject()) return union;
        if (!union.isArray() || union.isEmpty()) return null;
        JsonNode fallback = union.get(0);
        for (JsonNode b : union) {
            if (b != null && b.isObject()) {
                String type = b.path("type").asText("");
                if ("object".equals(type) || b.has("properties")) return b;
                if (fallback == null) fallback = b;
            }
        }
        return fallback;
    }

    private static ObjectNode objectWithType() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "object");
        return n;
    }

    private static JsonNode firstNonNull(JsonNode a, JsonNode b, JsonNode c) {
        if (a != null && !a.isNull()) return a;
        if (b != null && !b.isNull()) return b;
        if (c != null && !c.isNull()) return c;
        return null;
    }
}
