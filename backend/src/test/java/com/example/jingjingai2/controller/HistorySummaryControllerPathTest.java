package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.service.ConversationHistoryService;
import com.example.jingjingai2.service.ModelConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase M1：默认控制器路径验证。
 *
 * <p>不是直接调 {@code assemble}，而是从 {@code /ai/chat} 主入口走一遍：证明工作集默认路径
 * 真的接上了条件历史摘要，并且同一会话的第二个用户请求复用摘要、不再重复调用摘要模型。
 * 假模型 + 假历史，不连数据库、不调真实模型。
 */
class HistorySummaryControllerPathTest {

    private static final int TURNS = 23;
    private static final int CHARS = 1000;
    private static final int WINDOW = 60_000;   // 触发线 65% = 39,000
    private static final String CURRENT = "当前问题：继续把配置改成默认值";

    @Test
    @DisplayName("默认工作集路径：历史超阈值时先摘要一次，再发主请求")
    void defaultPathSummarizesBeforeMainRequest() {
        Harness harness = harness();

        harness.controller.chat(ChatEntryPromptViewTest.request(CURRENT),
                ChatEntryPromptViewTest.httpRequest());

        assertEquals(2, harness.fake.calls.get(), "一次摘要调用 + 一次主请求");
        List<Message> summaryPrompt = harness.fake.recorded.get(0);
        assertEquals(1, summaryPrompt.size(), "摘要请求只有一段提示词");
        assertTrue(summaryPrompt.get(0).getText().contains("压缩成一段摘要"));

        List<Message> main = harness.fake.recorded.get(1);
        assertTrue(main.get(0) instanceof SystemMessage);
        assertTrue(main.get(1) instanceof SystemMessage, "摘要紧跟系统提示之后");
        String summary = main.get(1).getText();
        assertTrue(summary.startsWith("[历史摘要]"), summary);
        assertTrue(summary.contains("message|1"), summary);
        assertFalse(joined(main).contains("第0轮助手结论"), "被覆盖的旧回复不再逐条发送");
        assertTrue(joined(main).contains("第22轮用户约束"), "最近一轮原样保留");
        assertTrue(joined(main).contains(CURRENT));
        assertTrue(main.get(main.size() - 1) instanceof UserMessage);
        assertEquals(CURRENT, main.get(main.size() - 1).getText());

        ContextCompactor.HistorySummaryStats stats = harness.compactor.lastHistorySummary();
        assertTrue(stats.applied());
        assertEquals(1, stats.calls());
        assertTrue(stats.coveredCount() > 0);
        assertTrue(stats.tokensAfterEst() < stats.tokensBeforeEst());
    }

    @Test
    @DisplayName("同一会话的第二个用户请求复用摘要，不重复调用摘要模型")
    void secondRequestReusesTheSummary() {
        Harness harness = harness();
        harness.controller.chat(ChatEntryPromptViewTest.request(CURRENT),
                ChatEntryPromptViewTest.httpRequest());
        assertEquals(2, harness.fake.calls.get());

        harness.controller.chat(ChatEntryPromptViewTest.request(CURRENT),
                ChatEntryPromptViewTest.httpRequest());

        assertEquals(3, harness.fake.calls.get(), "第二个请求只多一次主调用，不重复摘要");
        assertTrue(harness.compactor.lastHistorySummary().reused());
        assertEquals(0, harness.compactor.lastHistorySummary().calls());
        List<Message> secondMain = harness.fake.recorded.get(2);
        assertTrue(secondMain.get(1) instanceof SystemMessage);
        assertTrue(secondMain.get(1).getText().startsWith("[历史摘要]"));
    }

    @Test
    @DisplayName("短历史不摘要：默认路径不会平白多一次模型调用")
    void shortHistoryAddsNoModelCall() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("好的", java.util.Map.of(), List.of());
        ConversationHistoryService history = ChatEntryPromptViewTest.historyMock();
        when(history.getHistory(any(), any())).thenReturn(List.of(
                row(1, "user", "旧问题"), row(2, "assistant", "旧回答"), row(3, "user", CURRENT)));
        ChatController controller = ChatEntryPromptViewTest.controller(fake, history);
        controller.enableWorkingSetForTest(64_000);

        controller.chat(ChatEntryPromptViewTest.request(CURRENT), ChatEntryPromptViewTest.httpRequest());

        assertEquals(1, fake.calls.get(), "短历史只发一次主请求");
        assertTrue(fake.recorded.get(0).stream().noneMatch(m -> m.getText().startsWith("[历史摘要]")));
    }

    @Test
    @DisplayName("原始历史仍完整：控制器不改数据库行")
    void originalHistoryRowsAreUntouched() {
        Harness harness = harness();
        List<String> before = texts(harness.rows);

        harness.controller.chat(ChatEntryPromptViewTest.request(CURRENT),
                ChatEntryPromptViewTest.httpRequest());

        assertEquals(before, texts(harness.rows));
    }

    // ===================== harness =====================

    private record Harness(ChatController controller, ChatEntryPromptViewTest.FakeModel fake,
                           ContextCompactor compactor, List<MessageRecord> rows) {}

    private static Harness harness() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("好的", java.util.Map.of(), List.of());
        ConversationHistoryService history = ChatEntryPromptViewTest.historyMock();
        List<MessageRecord> rows = longHistory();
        when(history.getHistory(any(), any())).thenReturn(rows);

        ChatController controller = ChatEntryPromptViewTest.controller(fake, history);
        // 测试里没有真实模型配置：把窗口固定成 60k，让触发线（39k）与硬窗口都可预测。
        ModelConfigService modelConfig = mock(ModelConfigService.class);
        when(modelConfig.contextWindowOf(any(), any())).thenReturn(WINDOW);
        inject(controller, "modelConfigService", modelConfig);

        ContextCompactor compactor = new ContextCompactor(fake.model, 24_000);
        inject(controller, "contextCompactor", compactor);
        controller.enableWorkingSetForTest(64_000);
        return new Harness(controller, fake, compactor, rows);
    }

    private static List<MessageRecord> longHistory() {
        List<MessageRecord> rows = new ArrayList<>();
        long id = 1;
        for (int turn = 0; turn < TURNS; turn++) {
            rows.add(row(id++, "user", "第" + turn + "轮用户约束：" + "约".repeat(CHARS)));
            rows.add(row(id++, "assistant", "第" + turn + "轮助手结论：" + "答".repeat(CHARS)));
        }
        rows.add(row(id, "user", CURRENT));
        return rows;
    }

    private static MessageRecord row(long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(id * 1000));
        return record;
    }

    private static List<String> texts(List<MessageRecord> rows) {
        List<String> out = new ArrayList<>();
        for (MessageRecord row : rows) out.add(row.getContent());
        return out;
    }

    private static String joined(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message message : messages) out.append(message.getText()).append('\n');
        return out.toString();
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
}
