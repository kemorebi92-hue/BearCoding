package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** history 摘要失败时保留原始消息。模型由测试替身返回，不访问数据库。 */
class ContextCompactorFailureTest {

    @Test
    @DisplayName("摘要成功时仍替换旧历史")
    void successfulSummaryReplacesOlderHistory() {
        List<MessageRecord> records = records("旧事实在后半段", 20);
        ContextCompactor compactor = new FakeCompactor("成功摘要", false);
        List<Message> messages = compactor.buildMessages(records, "当前问题");
        assertTrue(compactor.lastStats().compacted());
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(0).getText().contains("成功摘要"));
        assertFalse(joined(messages).contains("...[已截断]"));
    }

    @Test
    @DisplayName("null、空白和异常都保留全部原始历史")
    void unavailableSummaryKeepsOriginalHistory() {
        for (FakeCompactor compactor : List.of(
                new FakeCompactor(null, true),
                new FakeCompactor("   ", true),
                new FakeCompactor("不会返回", false, true))) {
            List<MessageRecord> records = records("唯一事实-" + System.identityHashCode(compactor), 4);
            List<String> before = texts(records);
            List<Message> messages = compactor.buildMessages(records, "当前问题");
            assertFalse(compactor.lastStats().compacted(), "失败不能记成压缩成功");
            ContextCompactor.SummaryUsage usage = compactor.takeSummaryUsage();
            assertTrue(usage.fallback(), "失败状态必须传给上层");
            assertFalse(joined(messages).contains("...[已截断]"));
            assertTrue(messages.stream().noneMatch(SystemMessage.class::isInstance));
            assertEquals(before.subList(0, before.size() - 1), historyTexts(messages));
            assertEquals("当前问题", ((UserMessage) messages.get(messages.size() - 1)).getText());
            assertEquals(before, texts(records), "持久记录不能被修改");
        }
    }

    @Test
    @DisplayName("失败后仍低于触发线时可以继续；超过触发线时由上层拒绝")
    void failureBudgetUsesExistingEstimate() {
        ContextCompactor small = new ContextCompactor(null, 24000);
        List<Message> within = small.buildMessages(records("短历史", 1), "当前");
        assertFalse(ContextBudget.shouldCompact(ContextBudget.historyTokens(within), 0, 1000, 65, 100));
        assertTrue(small.takeSummaryUsage() == null, "未触发摘要时没有失败状态");

        ContextCompactor large = new FakeCompactor(null, true);
        List<Message> over = large.buildMessages(records("超长历史", 30), "当前");
        assertTrue(ContextBudget.shouldCompact(ContextBudget.historyTokens(over), 0, 1000, 65, 100));
        assertTrue(joined(over).contains("超长历史"));
        assertTrue(large.takeSummaryUsage().fallback());
    }

    private static List<MessageRecord> records(String fact, int count) {
        List<MessageRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(new MessageRecord(i % 2 == 0 ? "user" : "assistant", fact + "-" + i + "-" + "x".repeat(80)));
        }
        records.add(new MessageRecord("user", "当前问题"));
        return records;
    }

    private static List<String> texts(List<MessageRecord> records) {
        return records.stream().map(MessageRecord::getContent).toList();
    }

    private static List<String> historyTexts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < messages.size() - 1; i++) out.add(messages.get(i).getText());
        return out;
    }

    private static String joined(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message message : messages) out.append(message.getText()).append('\n');
        return out.toString();
    }

    private static final class FakeCompactor extends ContextCompactor {
        private final String text;
        private final boolean blank;
        private final boolean error;

        private FakeCompactor(String text, boolean blank) {
            this(text, blank, false);
        }

        private FakeCompactor(String text, boolean blank, boolean error) {
            super(null, 100);
            this.text = text;
            this.blank = blank;
            this.error = error;
        }

        @Override
        public SummaryUsage summarizeTracked(String olderBody) {
            return summarizeTracked(olderBody, (org.springframework.ai.chat.model.ChatModel) null);
        }

        @Override
        public SummaryUsage summarizeTracked(String olderBody, org.springframework.ai.chat.model.ChatModel model) {
            if (error) return new SummaryUsage("", 0, 0, false, true);
            if (blank) return new SummaryUsage("", 0, 0, false, true);
            return new SummaryUsage(text, 1, 1, false, false);
        }
    }
}
