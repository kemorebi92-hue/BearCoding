package com.example.jingjingai2.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SensitiveRedactor} 单测：顶层敏感键、嵌套对象、数组、以及 JSON 字符串值内部的敏感字段。
 * 不使用真实凭证。
 */
class SensitiveRedactorNestedTest {

    @Test
    @DisplayName("顶层敏感键继续脱敏")
    void topLevelSensitiveKeyRedacted() {
        String out = SensitiveRedactor.redact("{\"token\":\"example-secret\",\"name\":\"ok\"}");
        assertTrue(out.contains("\"token\":\"***\""), out);
        assertTrue(out.contains("\"name\":\"ok\""), out);
        assertFalse(out.contains("example-secret"), out);
    }

    @Test
    @DisplayName("params 是 JSON 字符串时，其内部敏感字段被脱敏")
    void jsonStringValueInnerSensitiveRedacted() {
        String raw = "{\"templateId\":\"playwright\",\"serviceName\":\"my-pw\","
                + "\"params\":\"{\\\"token\\\":\\\"example-secret\\\",\\\"note\\\":\\\"keep\\\"}\"}";
        String out = SensitiveRedactor.redact(raw);
        assertFalse(out.contains("example-secret"), "字符串内部的 token 值不得明文：" + out);
        assertTrue(out.contains("playwright"), "非敏感字段保持可读");
        assertTrue(out.contains("my-pw"), out);
        assertTrue(out.contains("keep"), "字符串内部的非敏感字段保持可读");
    }

    @Test
    @DisplayName("嵌套对象里的敏感键被脱敏")
    void nestedObjectSensitiveRedacted() {
        String raw = "{\"outer\":{\"password\":\"example-secret\",\"plain\":\"visible\"}}";
        String out = SensitiveRedactor.redact(raw);
        assertFalse(out.contains("example-secret"), out);
        assertTrue(out.contains("visible"), out);
    }

    @Test
    @DisplayName("数组元素是 JSON 字符串时，内部敏感字段被脱敏")
    void arrayOfJsonStringsRedacted() {
        String raw = "{\"list\":[\"{\\\"secret\\\":\\\"example-secret\\\"}\",\"plain text\"]}";
        String out = SensitiveRedactor.redact(raw);
        assertFalse(out.contains("example-secret"), out);
        assertTrue(out.contains("plain text"), "非 JSON 字符串原样保留");
    }

    @Test
    @DisplayName("多层嵌套：对象里的字符串里再套对象，敏感键仍被脱敏")
    void deeplyNestedJsonStringRedacted() {
        String raw = "{\"a\":{\"b\":\"{\\\"authorization\\\":\\\"example-secret\\\",\\\"c\\\":1}\"}}";
        String out = SensitiveRedactor.redact(raw);
        assertFalse(out.contains("example-secret"), out);
    }

    @Test
    @DisplayName("审批卡路径（redact）与日志路径（redactForLog）都脱敏 params 内部")
    void bothPathsRedactInnerJson() {
        String raw = "{\"params\":\"{\\\"apiKey\\\":\\\"example-secret\\\"}\"}";
        String card = SensitiveRedactor.redact(raw);
        String log = SensitiveRedactor.redactForLog(raw);
        assertFalse(card.contains("example-secret"), card);
        assertFalse(log.contains("example-secret"), log);
    }

    @Test
    @DisplayName("非敏感的普通字符串保持原样，不被误改")
    void plainStringsUntouched() {
        String raw = "{\"serviceName\":\"my-pw\",\"note\":\"just text { not json\"}";
        String out = SensitiveRedactor.redact(raw);
        assertTrue(out.contains("my-pw"), out);
        assertTrue(out.contains("just text { not json"), "无法解析成对象的字符串原样保留");
    }

    @Test
    @DisplayName("无效 JSON 整体：redact 原样返回，redactForLog 只留长度不回原文")
    void invalidJsonHandledConservatively() {
        String raw = "this is not json and mentions example-secret";
        // redact：非对象/数组或解析失败时原样返回（历史行为，卡片路径）
        assertEquals(raw, SensitiveRedactor.redact(raw));
        // redactForLog：从不回退成原文，只留长度
        String log = SensitiveRedactor.redactForLog(raw);
        assertFalse(log.contains("example-secret"), log);
        assertTrue(log.contains("已省略"), log);
    }

    @Test
    @DisplayName("敏感键的各种写法（下划线、连字符、大小写）都脱敏")
    void sensitiveKeyVariants() {
        String raw = "{\"api_key\":\"example-secret\",\"X-Api-Key\":\"example-secret\","
                + "\"set-cookie\":\"example-secret\",\"client_secret\":\"example-secret\"}";
        String out = SensitiveRedactor.redact(raw);
        assertFalse(out.contains("example-secret"), out);
    }
}
