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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TieredCompactionTest {

    @Test
    @DisplayName("工具结果为主时 tiered 采用 digest，不调用摘要")
    void toolHeavySegmentStaysDigest() {
        int[] calls = {0};
        List<Message> messages = toolHeavy();
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 800, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("不该出现", 9, 9, false, false);
                },
                "tiered", 0, 0.9);
        assertEquals(0, calls[0]);
        assertEquals("DIGEST", outcome.compactMode());
        assertFalse(joined(messages).contains("最旧工具正文"));
        assertTrue(joined(messages).contains("[已清除的工具结果："));
        assertTrue(paired(messages));
    }

    @Test
    @DisplayName("对话文字为主时 digest 超过阈值的 90%，改走摘要一次")
    void textHeavySegmentFallsBackToSummary() {
        int[] calls = {0};
        List<Message> messages = textHeavy();
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 1500, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("短摘要", 4, 1, false, false);
                },
                "tiered", 0, 0.9);
        assertEquals(1, calls[0]);
        assertEquals("SUMMARY", outcome.compactMode());
        assertTrue(joined(messages).contains("短摘要"));
        assertFalse(joined(messages).contains("[压缩索引]"));
        assertNotEquals("COMPACTION_INEFFECTIVE", outcome.decision().reason());
    }

    @Test
    @DisplayName("digest 和摘要都压不进阈值时只记一次无效，工具组不拆开")
    void bothTooBigIsIneffectiveOnce() {
        int[] calls = {0};
        List<Message> messages = textHeavy();
        messages.add(new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("c", "function", "readFile", "{}"))));
        messages.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("c", "readFile", "短结果"))));
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 1500, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("长".repeat(2000), 1, 1, false, false);
                },
                "tiered", 0, 0.9);
        assertEquals(1, calls[0]);
        assertEquals("COMPACTION_INEFFECTIVE", outcome.decision().reason());
        assertTrue(paired(messages));
        RoundCompactor.Guard guard = new RoundCompactor.Guard();
        guard.ineffective = true;
        RoundCompactor.Decision again = RoundCompactor.decide(3, 9000, 1500, 3, 0.30, guard);
        assertFalse(again.trigger());
    }

    @Test
    @DisplayName("摘要调用失败时结果标成 fallback")
    void summaryFailureIsFallback() {
        List<Message> messages = textHeavy();
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 1500, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> new RoundCompactor.Summary("截断原文", 0, 0, false, true),
                "tiered", 0, 0.9);
        assertEquals("SUMMARY", outcome.compactMode());
        assertTrue(outcome.summary().fallback());
        assertTrue(joined(messages).contains("[压缩索引]"), joined(messages));
    }

    @Test
    @DisplayName("接受比例调高后，同一段文字可以留在 digest")
    void acceptRatioIsConfigurable() {
        List<Message> strict = toolHeavy();
        RoundCompactor.Outcome low = RoundCompactor.apply(
                strict, null, 0, 800, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> new RoundCompactor.Summary("摘要", 1, 1, false, false),
                "tiered", 0, 0.05);
        assertEquals("SUMMARY", low.compactMode());

        List<Message> loose = toolHeavy();
        int[] calls = {0};
        RoundCompactor.Outcome high = RoundCompactor.apply(
                loose, null, 0, 800, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("摘要", 1, 1, false, false);
                },
                "tiered", 0, 1.0);
        assertEquals("DIGEST", high.compactMode());
        assertEquals(0, calls[0]);
    }

    private static List<Message> toolHeavy() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统"));
        for (int i = 0; i < 4; i++) {
            messages.add(new AssistantMessage("", Map.of(),
                    List.of(new AssistantMessage.ToolCall("c" + i, "function", "readFile", "{}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("c" + i, "readFile",
                            (i == 0 ? "最旧工具正文" : "较新工具正文") + "填".repeat(300)))));
        }
        messages.add(new UserMessage("继续"));
        return messages;
    }

    private static List<Message> textHeavy() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系"));
        for (int i = 0; i < 20; i++) messages.add(new UserMessage("字".repeat(100) + i));
        return messages;
    }

    private static boolean paired(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                if (i == 0 || !(messages.get(i - 1) instanceof AssistantMessage assistant)
                        || assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String joined(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message.getText() != null) sb.append(message.getText()).append('\n');
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    sb.append(response.responseData()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
