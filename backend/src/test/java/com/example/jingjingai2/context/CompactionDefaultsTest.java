package com.example.jingjingai2.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionDefaultsTest {

    @Test
    @DisplayName("不设置新配置时，默认仍是 history 和 summary")
    void unsetConfigKeepsStageTwoBehavior() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertEquals("history", defaultOf(yml, "compact-scope"));
        assertEquals("summary", defaultOf(yml, "compact-strategy"));
        assertEquals("0", defaultOf(yml, "digest-keep-tool-groups"));
        assertEquals("0.9", defaultOf(yml, "tiered-accept-ratio"));
        assertEquals("32000", defaultOf(yml, "compact-threshold-total"));
        assertEquals("3", defaultOf(yml, "compact-min-gap"));
        assertEquals("0.30", defaultOf(yml, "compact-growth-ratio"));
        assertEquals("1000", defaultOf(yml, "compact-min-message-budget"));

        java.util.List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.UserMessage("字".repeat(4000)));
        int[] calls = {0};
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 1000, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("阶段二的摘要", 1, 1, false, false);
                });
        assertEquals(1, calls[0]);
        assertEquals("SUMMARY", outcome.compactMode());
        assertTrue(joined(messages).contains("[历史摘要]"));
    }

    private static String defaultOf(String yml, String key) {
        Matcher matcher = Pattern.compile(key + ":\\s*\\$\\{[A-Z0-9_]+:([^}]+)}").matcher(yml);
        assertTrue(matcher.find(), key);
        return matcher.group(1);
    }

    private static String joined(java.util.List<org.springframework.ai.chat.messages.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (org.springframework.ai.chat.messages.Message message : messages) {
            if (message.getText() != null) sb.append(message.getText());
        }
        return sb.toString();
    }
}
