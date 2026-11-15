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

class MessageDigesterTest {

    @Test
    @DisplayName("digest 不调用摘要模型，旧工具结果变成占位符，工具调用还在")
    void digestDoesNotCallTheModel() {
        boolean[] called = {false};
        List<Message> messages = script();
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, 500, 3, 0.30, 1, new RoundCompactor.Guard(),
                body -> {
                    called[0] = true;
                    return new RoundCompactor.Summary("不该出现", 9, 9, false, false);
                },
                "digest");
        assertFalse(called[0]);
        assertEquals("DIGEST", outcome.compactMode());
        assertEquals(0, outcome.summary().inputTokens());
        assertEquals(0, outcome.summary().outputTokens());
        assertTrue(joined(messages).contains("KEEP-TOKEN"));
        assertTrue(joined(messages).contains("TASK-DONE"));
        assertFalse(joined(messages).contains("最旧秘密"));
        assertTrue(joined(messages).contains("[已清除的工具结果："));
        assertTrue(joined(messages).contains("较新秘密"));
        assertTrue(hasToolCall(messages, "readFile"));
        assertTrue(joined(messages).startsWith("系统") || joined(messages).contains("[压缩索引]"));
    }

    @Test
    @DisplayName("保留组数为 0 时，被压段里的工具结果全部变成占位符")
    void keepZeroClearsEveryToolResultInTheCompactedSegment() {
        List<List<Message>> older = new ArrayList<>();
        for (int i = 0; i < 4; i++) older.add(toolGroup("c" + i, "正文" + i + "秘密"));
        MessageDigester.Result result = MessageDigester.digest(older, 100000, 0);
        String text = joined(result.messages());
        for (int i = 0; i < 4; i++) assertFalse(text.contains("正文" + i + "秘密"), text);
        assertTrue(text.contains("[已清除的工具结果："));
        assertTrue(text.contains("已清除的工具结果：4 组"));
        assertTrue(hasToolCall(result.messages(), "readFile"));
    }

    @Test
    @DisplayName("保留组数为 2 时，被压段里只留最近 2 组工具结果")
    void keepTwoLeavesTheLatestTwoToolGroups() {
        List<List<Message>> older = new ArrayList<>();
        for (int i = 0; i < 5; i++) older.add(toolGroup("c" + i, "正文" + i + "秘密"));
        MessageDigester.Result result = MessageDigester.digest(older, 100000, 2);
        String text = joined(result.messages());
        assertFalse(text.contains("正文0秘密"));
        assertFalse(text.contains("正文1秘密"));
        assertFalse(text.contains("正文2秘密"));
        assertTrue(text.contains("正文3秘密"));
        assertTrue(text.contains("正文4秘密"));
        assertTrue(text.contains("已清除的工具结果：3 组"));
    }

    @Test
    @DisplayName("纯文字助手回复截到 200 字，带工具调用的不截")
    void truncatesPlainAssistantOnly() {
        String longText = "长".repeat(250);
        AssistantMessage plain = new AssistantMessage(longText);
        AssistantMessage withCall = new AssistantMessage(longText, Map.of(),
                List.of(new AssistantMessage.ToolCall("c", "function", "readFile", "{}")));
        List<List<Message>> groups = List.of(List.of(plain), List.of(withCall));
        MessageDigester.Result result = MessageDigester.digest(groups, 100000, 0);
        String plainOut = result.messages().get(1).getText();
        assertTrue(plainOut.startsWith("长".repeat(200)));
        assertTrue(plainOut.contains("…[已截断，原长 250 字]"));
        assertEquals(longText, result.messages().get(2).getText());
        assertFalse(((AssistantMessage) result.messages().get(2)).getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("用户消息超过预算一半时，留下最早一条和最近几条")
    void capsUserMessages() {
        List<List<Message>> groups = new ArrayList<>();
        groups.add(List.of(new UserMessage("最早的一条")));
        groups.add(List.of(new UserMessage("中".repeat(100))));
        groups.add(List.of(new UserMessage("中".repeat(100))));
        groups.add(List.of(new UserMessage("最近一条")));
        MessageDigester.Result result = MessageDigester.digest(groups, 40, 0);
        String text = joined(result.messages());
        assertTrue(text.contains("最早的一条"));
        assertTrue(text.contains("最近一条"));
        assertTrue(text.contains("[省略 "));
        assertFalse(text.contains("中".repeat(50)));
    }

    @Test
    @DisplayName("占位符再跑一遍，文字不变")
    void secondPassIsIdentical() {
        List<List<Message>> groups = new ArrayList<>();
        groups.add(List.of(new AssistantMessage("答".repeat(220))));
        groups.add(toolGroup("c0", "旧工具正文"));
        groups.add(toolGroup("c1", "较新工具"));
        groups.add(toolGroup("c2", "更新工具"));
        groups.add(toolGroup("c3", "最新工具"));
        groups.add(List.of(new UserMessage("甲".repeat(80))));
        groups.add(List.of(new UserMessage("乙".repeat(80))));
        groups.add(List.of(new UserMessage("丙")));
        MessageDigester.Result once = MessageDigester.digest(groups, 30, 0);
        List<List<Message>> againGroups = regroup(once.messages());
        MessageDigester.Result twice = MessageDigester.digest(againGroups, 30, 0);
        assertEquals(joined(once.messages()), joined(twice.messages()));
    }

    @Test
    @DisplayName("digest 内部失败时退回 summary")
    void fallsBackToSummary() {
        MessageDigester.failForTest = true;
        try {
            List<Message> messages = new ArrayList<>(List.of(
                    new SystemMessage("系统"),
                    new UserMessage("话".repeat(800))));
            RoundCompactor.Outcome outcome = RoundCompactor.apply(
                    messages, null, 0, 200, 3, 0.30, 1, new RoundCompactor.Guard(),
                    body -> new RoundCompactor.Summary("退回的摘要", 4, 1, false, false),
                    "digest");
            assertEquals("SUMMARY", outcome.compactMode());
            assertTrue(joined(messages).contains("退回的摘要"));
            assertTrue(joined(messages).contains("[历史摘要]"));
        } finally {
            MessageDigester.failForTest = false;
        }
    }

    @Test
    @DisplayName("脚本化多轮：digest 之后约束还在，任务说明还在")
    void scriptedRoundsKeepTheConstraint() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统"));
        messages.add(new UserMessage("约束：回复里必须保留 KEEP-TOKEN。任务：写完后回复 TASK-DONE。"));
        RoundCompactor.Guard guard = new RoundCompactor.Guard();
        for (int i = 0; i < 6; i++) {
            messages.add(new AssistantMessage("", Map.of(),
                    List.of(new AssistantMessage.ToolCall("c" + i, "function", "readFile", "{}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("c" + i, "readFile", "旧工具正文" + "填充".repeat(200)))));
            messages.add(new UserMessage("继续，记住 KEEP-TOKEN，最后要 TASK-DONE"));
            RoundCompactor.apply(messages, null, i, 800, 1, 0.0, 1, guard,
                    body -> {
                        throw new AssertionError("digest 不应调模型");
                    },
                    "digest");
        }
        String text = joined(messages);
        assertTrue(text.contains("KEEP-TOKEN"), text);
        assertTrue(text.contains("TASK-DONE"), text);
    }

    private static List<Message> script() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统"));
        messages.add(new UserMessage("约束 KEEP-TOKEN。完成时说 TASK-DONE"));
        for (int i = 0; i < 4; i++) {
            messages.add(new AssistantMessage("", Map.of(),
                    List.of(new AssistantMessage.ToolCall("c" + i, "function", "readFile", "{}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("c" + i, "readFile",
                            (i == 0 ? "最旧秘密" : "较新秘密") + "填".repeat(300)))));
        }
        messages.add(new UserMessage("请完成任务 TASK-DONE，并保留 KEEP-TOKEN"));
        return messages;
    }

    private static List<List<Message>> regroup(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null
                    && !assistant.getToolCalls().isEmpty()) {
                List<Message> group = new ArrayList<>();
                group.add(assistant);
                if (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                    group.add(messages.get(++i));
                }
                groups.add(group);
            } else {
                groups.add(List.of(message));
            }
        }
        return groups;
    }

    private static List<Message> toolGroup(String id, String body) {
        return List.of(
                new AssistantMessage("", Map.of(),
                        List.of(new AssistantMessage.ToolCall(id, "function", "readFile", "{}"))),
                new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, "readFile", body))));
    }

    private static boolean hasToolCall(List<Message> messages, String name) {
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    if (name.equals(call.name())) return true;
                }
            }
        }
        return false;
    }

    private static String joined(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(message.getText() == null ? "" : message.getText()).append('\n');
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    sb.append(response.responseData()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
