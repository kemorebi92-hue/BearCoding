package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ChatRequest;
import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextAssetTool;
import com.example.jingjingai2.context.ContextProjectionPolicy;
import com.example.jingjingai2.context.ContextSourceLedger;
import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.service.ConversationHistoryService;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.usermemory.UserMemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用假模型从主入口验证工作集投影真的改变了发出去的 Prompt。不连数据库、不调真实模型。
 */
class WorkingSetProjectionTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    private static final String OLD_REPLY = "旧助手回复OLDREPLY" + "正文".repeat(300);
    private static final String REQUIREMENT = "任务要求必须保留这段用户要求";
    private static final String CORRECTION = "纠正：用中文，禁止英文";

    @Test
    @DisplayName("关闭开关时，实际 Prompt 与原策略一致")
    void disabledKeepsBaselinePrompt() {
        Harness off = harness(false, history());
        off.controller.chat(request(), ChatEntryPromptViewTest.httpRequest());

        List<Message> sent = off.fake.recorded.get(0);
        assertTrue(texts(sent).contains(OLD_REPLY), "关闭时旧助手回复仍在");
        assertTrue(texts(sent).contains(REQUIREMENT));
        assertFalse(texts(sent).get(0).contains("历史检索"), "关闭时不加检索指引");
    }

    @Test
    @DisplayName("开启后旧助手回复退出实际 Prompt，用户要求、纠正和授权还在")
    void enabledRetiresOldAssistantButKeepsUserRequirements() {
        Harness on = harness(true, history());
        on.controller.chat(request(), ChatEntryPromptViewTest.httpRequest());

        List<String> sent = texts(on.fake.recorded.get(0));
        assertFalse(sent.contains(OLD_REPLY), "旧助手回复应退出实际 Prompt");
        assertTrue(sent.stream().anyMatch(t -> t.contains("locator=message|21")),
                "退休说明带可直接读取的 locator，不是让人去搜的关键词");
        assertTrue(sent.contains(REQUIREMENT), "用户任务要求不丢");
        assertTrue(sent.contains(CORRECTION), "用户纠正不丢");
        assertTrue(sent.contains("授权：允许修改配置"), "用户授权不丢");
        assertTrue(sent.get(0).contains("历史检索"), "开启后系统提示带固定检索指引");
        int enabledChars = sent.stream().mapToInt(String::length).sum();
        assertTrue(enabledChars < baselineChars(), "实际发出的历史正文变短");
    }

    @Test
    @DisplayName("退休的旧回复能用 searchContext 找回，片段够用就不用再 read")
    void retiredMessageComesBackThroughSearch() {
        Harness on = harness(true, history());
        on.controller.chat(request(), ChatEntryPromptViewTest.httpRequest());
        assertFalse(texts(on.fake.recorded.get(0)).contains(OLD_REPLY));

        MessageHit hit = new MessageHit();
        hit.setId(21L);
        hit.setRole("assistant");
        hit.setSnippet(OLD_REPLY);
        when(on.mapper.searchMessagesAnyDesc(anyLong(), anyString(), eq(List.of("%OLDREPLY%")), eq(List.of("OLDREPLY")),
                anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(hit));

        ConversationContext.set(1L, "conv-1");
        try {
            String found = on.assets.searchContext("search", "message", "OLDREPLY", null, null, null);
            assertTrue(found.contains(OLD_REPLY), found);
            assertTrue(found.contains("\"role\":\"assistant\""), found);
            assertTrue(found.contains("message|21"), found);
            verify(on.mapper, org.mockito.Mockito.never()).selectMessage(anyLong(), anyString(), anyLong());
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("片段不够时按 locator 分页恢复，同一段立刻重读会被拦住")
    void pagingRecoversAndImmediateRepeatIsBlocked() {
        ContextAssetTool tool = new ContextAssetTool(mock(ConversationMapper.class),
                mock(UserMemoryService.class), mock(TaskPlanService.class));
        ConversationMapper mapper = field(tool);
        when(mapper.selectMessage(1L, "conv-1", 21L))
                .thenReturn(new MessageRecord("assistant", "p".repeat(9000)));
        ConversationContext.set(1L, "conv-1");
        try {
            String first = tool.searchContext("read", null, null, null, "message|21", 0);
            assertTrue(first.contains("\"hasMore\":true"), first);
            String second = tool.searchContext("read", null, null, null, "message|21", 4000);
            assertTrue(second.contains("\"start\":4000"), second);
            String again = tool.searchContext("read", null, null, null, "message|21", 4000);
            String blocked = tool.searchContext("read", null, null, null, "message|21", 4000);
            assertFalse(again.contains("刚刚已经读过"));
            assertTrue(blocked.contains("刚刚已经读过"), blocked);
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("不可恢复的必要工具结果留下，可恢复的旧工具交互成组退出且不成残缺对")
    void toolGroupsStayPaired() throws Exception {
        List<MessageRecord> rows = List.of(
                row(1L, "user", "要求一"), row(2L, "user", "要求二"),
                row(3L, "user", "要求三"), row(4L, "user", "要求四"));
        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(assistantCall("old-call", "readFile", "{\"path\":\"a.txt\"}"));
        actual.add(toolResult("old-call", "readFile", signed("旧工具正文可以恢复")));
        actual.add(assistantCall("live-call", "grep", "{\"pattern\":\"x\"}"));
        actual.add(toolResult("live-call", "grep", "没有快照的搜索结果必须保留"));
        for (MessageRecord row : rows) actual.add(new UserMessage(row.getContent()));

        ContextSourceLedger ledger = new ContextSourceLedger("conv-1");
        ledger.addHistory(rows, "要求四");
        ledger.absorb(actual, 0, null);
        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, 1, 0, false, true);

        String bodies = toolBodies(projected.messages());
        assertFalse(bodies.contains("旧工具正文可以恢复"), "可恢复的旧工具交互整组退出");
        assertTrue(bodies.contains("没有快照的搜索结果必须保留"), "不可恢复的工具正文留下");
        assertTrue(MessageProtocolValidator.check(projected.messages()).ok(), "投影后协议完整");
        assertTrue(ledger.get("tool:old-call").fullText().contains("旧工具正文可以恢复"), "源账本原文不变");
    }

    @Test
    @DisplayName("非流式、SSE、最终整理都让旧回复退出")
    void allThreeChannelsProject() throws Exception {
        for (boolean streaming : new boolean[]{false, true}) {
            Harness on = harness(true, history());
            if (streaming) {
                on.fake.streamingAnswer = new AssistantMessage("回答", Map.of(), List.of());
                on.controller.chatStream(request(), ChatEntryPromptViewTest.httpRequest(),
                        new MockHttpServletResponse());
                long deadline = System.currentTimeMillis() + 8000;
                while (on.fake.recorded.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
            } else {
                on.controller.chat(request(), ChatEntryPromptViewTest.httpRequest());
            }
            assertFalse(texts(on.fake.recorded.get(0)).contains(OLD_REPLY),
                    streaming ? "SSE" : "非流式");
        }
    }

    @Test
    @DisplayName("最终整理使用同一投影，旧回复同样不在发出的 Prompt 里")
    void deliveryUsesTheSameProjection() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswers.add(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"))));
        fake.nonStreamingAnswer = new AssistantMessage("整理完成", Map.of(), List.of());
        ConversationHistoryService history = ChatEntryPromptViewTest.historyMock();
        when(history.getHistory(any(), any())).thenReturn(history());
        TaskPlanService plan = mock(TaskPlanService.class);
        when(plan.budget(any(), any(), anyInt())).thenReturn(5);
        when(plan.hasPlan(any(), any())).thenReturn(false);
        when(plan.allStepsDone(any(), any())).thenReturn(true);
        when(plan.stepsOf(any(), any())).thenReturn(List.of());
        ChatController controller = ChatEntryPromptViewTest.controller(fake, history, plan);
        controller.enableWorkingSetForTest(1);

        controller.chat(request(), ChatEntryPromptViewTest.httpRequest());

        assertTrue(fake.calls.get() >= 2, "工具轮之外还有最终整理");
        for (List<Message> sent : fake.recorded) {
            assertFalse(texts(sent).contains(OLD_REPLY), "每一轮实际 Prompt 都不含旧回复");
        }
    }

    @Test
    @DisplayName("投影前后源账本正文完全一致")
    void ledgerUnchangedByProjection() {
        List<MessageRecord> rows = history();
        ContextSourceLedger ledger = new ContextSourceLedger("conv-1");
        ledger.addHistory(rows, "当前问题");
        List<Message> actual = new ArrayList<>();
        for (MessageRecord row : rows) {
            actual.add("user".equals(row.getRole()) ? new UserMessage(row.getContent())
                    : new AssistantMessage(row.getContent()));
        }
        ledger.absorb(actual, 0, null);
        Map<String, String> before = ledgerTexts(ledger);
        ContextProjectionPolicy.project(ledger, actual, null, 5, 0, 1, 0, false, true);
        assertEquals(before, ledgerTexts(ledger));
    }

    @Test
    @DisplayName("必须保留的内容超过硬窗口时安全停止，不静默丢内容")
    void hardWindowStopsInsteadOfDropping() {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-1");
        MessageRecord requirement = row(1L, "user", "u".repeat(4000));
        ledger.addHistory(List.of(requirement), "u".repeat(4000));
        List<Message> actual = List.of(new UserMessage("u".repeat(4000)));
        ledger.absorb(actual, 0, null);
        ContextProjectionPolicy.Result result = ContextProjectionPolicy.project(
                ledger, actual, null, 0, 0, 24000, 10, false, true);
        assertEquals(ContextProjectionPolicy.HARD_WINDOW_EXCEEDED, result.error());
        assertEquals(actual, result.messages(), "超窗时返回原消息，不丢内容");
    }

    @Test
    @DisplayName("关闭开关的投影结果与开启前的基线相同")
    void disabledProjectionMatchesOriginal() {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-1");
        ledger.addHistory(history(), "当前问题");
        List<Message> actual = messagesOf(history());
        ledger.absorb(actual, 0, null);
        ContextProjectionPolicy.Result off = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, 1, 0, false, false);
        assertEquals(actual, off.messages());
    }

    private static int baselineChars() {
        Harness off = harness(false, history());
        off.controller.chat(request(), ChatEntryPromptViewTest.httpRequest());
        return texts(off.fake.recorded.get(0)).stream().mapToInt(String::length).sum();
    }

    private static Harness harness(boolean enabled, List<MessageRecord> rows) {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("好的", Map.of(), List.of());
        ConversationHistoryService history = ChatEntryPromptViewTest.historyMock();
        when(history.getHistory(any(), any())).thenReturn(rows);
        ConversationMapper mapper = mock(ConversationMapper.class);
        ContextAssetTool assets = new ContextAssetTool(mapper, mock(UserMemoryService.class),
                mock(TaskPlanService.class));
        ChatController controller = ChatEntryPromptViewTest.controller(fake, history);
        inject(controller, "contextAssetTool", assets);
        if (enabled) controller.enableWorkingSetForTest(1);
        return new Harness(controller, fake, assets, mapper);
    }

    private static List<MessageRecord> history() {
        return List.of(
                row(20L, "user", REQUIREMENT),
                row(21L, "assistant", OLD_REPLY),
                row(22L, "user", CORRECTION),
                row(23L, "assistant", "收到纠正"),
                row(24L, "user", "授权：允许修改配置"),
                row(25L, "assistant", "最近回复一"),
                row(26L, "user", "最近问题二"),
                row(27L, "assistant", "最近回复二"),
                row(28L, "user", "最近问题三"),
                row(29L, "assistant", "最近回复三"),
                row(30L, "user", "当前问题"));
    }

    private static MessageRecord row(Long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(id * 1000));
        return record;
    }

    private static List<Message> messagesOf(List<MessageRecord> rows) {
        List<Message> out = new ArrayList<>();
        for (MessageRecord row : rows) {
            out.add("user".equals(row.getRole()) ? new UserMessage(row.getContent())
                    : new AssistantMessage(row.getContent()));
        }
        return out;
    }

    private static List<String> texts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message message : messages) out.add(message.getText() == null ? "" : message.getText());
        return out;
    }

    private static String toolBodies(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    out.append(response.responseData());
                }
            }
        }
        return out.toString();
    }

    private static Map<String, String> ledgerTexts(ContextSourceLedger ledger) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (ContextSourceLedger.Item item : ledger.items()) out.put(item.id(), item.fullText());
        return out;
    }

    private static ChatRequest request() {
        ChatRequest request = ChatEntryPromptViewTest.request("当前问题");
        request.setConversationId("conv-1");
        return request;
    }

    private static AssistantMessage assistantCall(String id, String name, String arguments) {
        return new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)));
    }

    private static ToolResponseMessage toolResult(String id, String name, String body) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, name, body)));
    }

    /** 真实快照文件：退休前会检查标记指向的文件现在还在。 */
    private String signed(String body) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile(tmp, "ws-snapshot", ".txt");
        java.nio.file.Files.writeString(file, body);
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return body + com.example.jingjingai2.tool.ToolArtifactRef.marker(
                file.toString(), body.length(), "readFile", sha);
    }

    private static void inject(Object target, String name, Object value) {
        try {
            Field field = ChatController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConversationMapper field(ContextAssetTool tool) {
        try {
            Field field = ContextAssetTool.class.getDeclaredField("conversationMapper");
            field.setAccessible(true);
            return (ConversationMapper) field.get(tool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Harness(ChatController controller, ChatEntryPromptViewTest.FakeModel fake,
                           ContextAssetTool assets, ConversationMapper mapper) {}
}
