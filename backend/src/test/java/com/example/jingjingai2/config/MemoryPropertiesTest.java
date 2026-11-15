package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆开关的默认值契约：必须和 application.yml 对齐。
 */
class MemoryPropertiesTest {

    @Test
    @DisplayName("Java 对象默认与 application.yml 对齐：开、memory/、MEMORY.md、12 篇、25k 合计")
    void runtimeAlignedDefaults() {
        MemoryProperties p = new MemoryProperties();
        assertTrue(p.isEnabled());
        assertEquals("memory", p.getDir());
        assertEquals("MEMORY.md", p.getIndexName());
        assertEquals(12, p.getMaxFiles());
        assertEquals(8000, p.getMaxFileChars());
        assertEquals(25000, p.getMaxTotalChars());
        assertEquals(65536, p.getMaxFileBytes());
        assertTrue(p.isAutoConsolidate());
    }
}
