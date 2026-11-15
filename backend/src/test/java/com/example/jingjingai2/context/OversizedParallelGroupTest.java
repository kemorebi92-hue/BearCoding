package com.example.jingjingai2.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已知限制，不是期望行为。一次并行工具调用整组大于保留额度时，现有规则会整组压掉，
 * 压缩后只剩固定部分加上索引或摘要。后续优化时会改这里的断言。
 */
class OversizedParallelGroupTest {

    @Test
    @DisplayName("digest：六个并行 readFile 整组被压掉")
    void digestDropsTheWholeParallelGroup() {
        List<Message> messages = sixParallelReads();
        int fixed = RoundCompactor.fixedTokens(messages, null);
        RoundCompactor.apply(messages, null, 0, 2000, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> new RoundCompactor.Summary("不该用", 1, 1, false, false), "digest", 0, 0.9);
        assertFalse(joined(messages).contains("填".repeat(40)));
        assertTrue(RoundCompactor.estimate(messages, null) <= fixed + 400);
        assertTrue(paired(messages));
    }

    @Test
    @DisplayName("summary：六个并行 readFile 整组被压掉")
    void summaryDropsTheWholeParallelGroup() {
        List<Message> messages = sixParallelReads();
        int fixed = RoundCompactor.fixedTokens(messages, null);
        RoundCompactor.apply(messages, null, 0, 2000, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> new RoundCompactor.Summary("短摘要", 1, 1, false, false), "summary");
        assertFalse(joined(messages).contains("填".repeat(40)));
        assertTrue(joined(messages).contains("短摘要"));
        assertTrue(RoundCompactor.estimate(messages, null) <= fixed + 400);
    }

    @Test
    @DisplayName("tiered：六个并行 readFile 整组被压掉，并留下 digest")
    void tieredDropsTheWholeParallelGroup() {
        int[] calls = {0};
        List<Message> messages = sixParallelReads();
        int fixed = RoundCompactor.fixedTokens(messages, null);
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 2000, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("不该用", 1, 1, false, false);
                },
                "tiered", 0, 0.9);
        assertEquals(0, calls[0]);
        assertEquals("DIGEST", outcome.compactMode());
        assertFalse(joined(messages).contains("填".repeat(40)));
        assertTrue(RoundCompactor.estimate(messages, null) <= fixed + 400);
        assertTrue(paired(messages));
    }

    private static List<Message> sixParallelReads() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("固".repeat(500)));
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(new AssistantMessage.ToolCall("c" + i, "function", "readFile",
                    "{\"path\":\"part" + i + ".txt\"}"));
        }
        messages.add(new AssistantMessage("", Map.of(), calls));
        for (int i = 1; i <= 6; i++) {
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("c" + i, "readFile", "填".repeat(400)))));
        }
        return messages;
    }

    private static boolean paired(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage)) continue;
            if (i == 0 || !(messages.get(i - 1) instanceof AssistantMessage assistant)
                    || assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
                if (!(i > 0 && messages.get(i - 1) instanceof ToolResponseMessage)) return false;
            }
        }
        return true;
    }

    private static String joined(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    sb.append(response.responseData()).append('\n');
                }
            } else if (message.getText() != null) {
                sb.append(message.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
