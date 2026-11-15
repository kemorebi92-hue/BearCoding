package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductDefaultConfigTest {

    @Test
    @DisplayName("源码默认：用户记忆和 MCP Host 开，工作集软预算与压缩保留量是 64000")
    void shippedDefaults() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertEquals("true", envDefault(yml, "USER_MEMORY_ENABLED"));
        assertEquals("true", envDefault(yml, "MCP_ENABLED"));
        assertEquals("64000", envDefault(yml, "APP_CONTEXT_WORKING_SET_SOFT_BUDGET_TOKENS"));
        assertEquals("true", envDefault(yml, "APP_CONTEXT_WORKING_SET_ENABLED"));
        assertEquals("true", envDefault(yml, "APP_CONTEXT_WORKING_SET_SHADOW_ENABLED"));
        assertTrue(yml.contains("history-max-tokens: 64000"), yml);
        assertTrue(yml.contains("working-set:"));
        assertFalse(yml.contains("general-purpose-enabled"), yml);
        assertFalse(yml.contains("auto-resume"), yml);
        assertFalse(yml.contains("tool-result-retirement-enabled"), yml);
    }

    private static String envDefault(String yml, String name) {
        Matcher matcher = Pattern.compile("\\$\\{" + name + ":([^}]+)}").matcher(yml);
        assertTrue(matcher.find(), name);
        return matcher.group(1);
    }
}
