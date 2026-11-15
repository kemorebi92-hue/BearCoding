package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpSchemaSanitizerTest {

    @Test
    @DisplayName("空 / 非法 JSON 回退 {type:object}")
    void fallbackOnEmptyOrBad() {
        assertEquals(McpSchemaSanitizer.FALLBACK, McpSchemaSanitizer.sanitize(null));
        assertEquals(McpSchemaSanitizer.FALLBACK, McpSchemaSanitizer.sanitize(""));
        assertEquals(McpSchemaSanitizer.FALLBACK, McpSchemaSanitizer.sanitize("not json"));
    }

    @Test
    @DisplayName("剥掉 $ref / $defs，保留 properties")
    void stripsRef() {
        String in = """
                {"type":"object","$ref":"#/defs/X","$defs":{"X":{}},"properties":{"q":{"type":"string"}}}
                """;
        String out = McpSchemaSanitizer.sanitize(in);
        assertFalse(out.contains("$ref"), out);
        assertFalse(out.contains("$defs"), out);
        assertTrue(out.contains("\"q\""), out);
        assertTrue(out.contains("object"), out);
    }

    @Test
    @DisplayName("anyOf 取 object 分支，oneOf / allOf 同样剥掉")
    void stripsUnion() {
        String in = """
                {"anyOf":[{"type":"string"},{"type":"object","properties":{"n":{"type":"integer"}}}]}
                """;
        String out = McpSchemaSanitizer.sanitize(in);
        assertFalse(out.contains("anyOf"), out);
        assertTrue(out.contains("\"n\""), out);

        String oneOf = """
                {"oneOf":[{"type":"object","properties":{"a":{"type":"string"}}}]}
                """;
        String o = McpSchemaSanitizer.sanitize(oneOf);
        assertFalse(o.contains("oneOf"), o);
        assertTrue(o.contains("\"a\""), o);
    }

    @Test
    @DisplayName("干净的 object schema 原样能用（字段还在）")
    void keepsPlainObject() {
        String in = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
        String out = McpSchemaSanitizer.sanitize(in);
        assertTrue(out.contains("text"));
        assertTrue(out.contains("required"));
    }
}
