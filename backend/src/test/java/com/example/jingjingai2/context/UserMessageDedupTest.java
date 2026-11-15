package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3D-1A：当前用户消息重复注入最小修复。
 * 不连 DB、不调真实模型；流式/非流式共用 assemble/buildMessages，另用源码断言两侧调用点。
 */
class UserMessageDedupTest {

    private static ContextCompactor compactor() {
        return new ContextCompactor(null, 24_000, 1_000_000, 65);
    }

    private static List<String> userTexts(List<Message> msgs) {
        List<String> out = new ArrayList<>();
        for (Message m : msgs) {
            if (m instanceof UserMessage) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static long countUser(List<Message> msgs, String text) {
        return msgs.stream()
                .filter(m -> m instanceof UserMessage && text.equals(m.getText()))
                .count();
    }

    @Test
    @DisplayName("1. 空历史：当前用户消息只出现一次（buildMessages + assemble）")
    void emptyHistoryCurrentAppearsOnce() {
        ContextCompactor c = compactor();
        List<Message> built = c.buildMessages(List.of(), "你好");
        assertEquals(List.of("你好"), userTexts(built));
        assertEquals(1, built.size());

        List<Message> assembled = c.assemble(List.of(), "你好");
        assertEquals(List.of("你好"), userTexts(assembled));
        assertEquals(1, assembled.size());
    }

    @Test
    @DisplayName("2. 非空历史（末条 assistant）：当前用户消息只出现一次")
    void nonEmptyHistoryEndingAssistantAppendsOnce() {
        List<MessageRecord> history = List.of(
                new MessageRecord("user", "上一轮问题"),
                new MessageRecord("assistant", "上一轮回答"));
        List<Message> built = compactor().buildMessages(history, "本轮问题");
        assertEquals(List.of("上一轮问题", "本轮问题"), userTexts(built));
        assertEquals(1, countUser(built, "本轮问题"));
        assertEquals(3, built.size());

        List<Message> assembled = compactor().assemble(history, "本轮问题");
        assertEquals(List.of("上一轮问题", "本轮问题"), userTexts(assembled));
        assertEquals(1, countUser(assembled, "本轮问题"));
    }

    @Test
    @DisplayName("3. 历史末条已是本轮 user（生产路径）：不重复注入")
    void historyAlreadyContainsCurrentDoesNotDuplicate() {
        String current = "本轮只说一次";
        List<MessageRecord> history = List.of(
                new MessageRecord("user", "旧问题"),
                new MessageRecord("assistant", "旧回答"),
                new MessageRecord("user", current));

        List<Message> built = compactor().buildMessages(history, current);
        assertEquals(1, countUser(built, current));
        assertEquals(List.of("旧问题", current), userTexts(built));

        List<Message> assembled = compactor().assemble(history, current);
        assertEquals(1, countUser(assembled, current));
        assertEquals(List.of("旧问题", current), userTexts(assembled));
    }

    @Test
    @DisplayName("4. 连续两条正文相同但身份不同的 user：必须保留两条")
    void consecutiveIdenticalUserTextsBothKept() {
        String same = "完全相同的正文";
        // 模拟：上一轮 user+assistant 后，本轮又发了相同正文并已入库
        List<MessageRecord> history = List.of(
                new MessageRecord("user", same),
                new MessageRecord("assistant", "收到"),
                new MessageRecord("user", same));

        List<Message> built = compactor().buildMessages(history, same);
        assertEquals(2, countUser(built, same), "两条独立同文 user 必须都在");
        assertEquals(List.of(same, same), userTexts(built));

        List<Message> assembled = compactor().assemble(history, same);
        assertEquals(2, countUser(assembled, same));
        assertEquals(List.of(same, same), userTexts(assembled));
    }

    @Test
    @DisplayName("5. 历史末条与本轮正文不同：保留正确的本轮消息")
    void lastUserDifferentFromCurrentKeepsCurrentText() {
        // 非生产惯例调用：末条 user 与 current 不同时，trailing user 按约定让位给 current
        List<MessageRecord> history = List.of(
                new MessageRecord("user", "历史里的旧 user"),
                new MessageRecord("assistant", "答"),
                new MessageRecord("user", "将被替换的末条"));

        List<Message> built = compactor().buildMessages(history, "真正的本轮");
        assertEquals(0, countUser(built, "将被替换的末条"));
        assertEquals(1, countUser(built, "真正的本轮"));
        assertEquals(List.of("历史里的旧 user", "真正的本轮"), userTexts(built));
    }

    @Test
    @DisplayName("6. 摘要压缩触发路径也不会再次注入重复消息")
    void compactionPathDoesNotDuplicateCurrent() {
        String current = "压缩路径本轮";
        String longText = "这是一段足够长的历史，用来把估算 token 撑过摘要截断的上限。".repeat(40);
        List<MessageRecord> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new MessageRecord("user", "第" + i + "轮：" + longText));
            history.add(new MessageRecord("assistant", "答" + i + "：" + longText));
        }
        history.add(new MessageRecord("user", current)); // 已入库的本轮

        ContextCompactor c = new ContextCompactor(ContextCompactorBudgetTest.successfulSummaryModel(), 80);
        List<Message> built = c.buildMessages(history, current);
        assertTrue(c.lastStats().compacted(), "极小预算应触发摘要");
        assertTrue(built.get(0) instanceof SystemMessage);
        assertTrue(built.get(0).getText().contains("[历史摘要]"));
        assertEquals(1, countUser(built, current));
        assertInstanceOf(UserMessage.class, built.get(built.size() - 1));
        assertEquals(current, built.get(built.size() - 1).getText());
    }

    @Test
    @DisplayName("7+8. 非流式与流式装配共用同一去重逻辑（两侧均调用 assemble/buildMessages）")
    void streamingAndNonStreamingShareDedupCallSites() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/example/jingjingai2/controller/ChatController.java"));
        assertEquals(2, count(src, "contextCompactor.assemble("));
        assertEquals(2, count(src, "contextCompactor.buildMessages("));
        // 非流式与流式都仍先把 user 入库，再 getHistory → assemble/buildMessages
        assertTrue(count(src, "new MessageRecord(\"user\", userMessage)") >= 2,
                "用户消息仍先入库（流式+非流式）");
        String compactorSrc = Files.readString(Path.of(
                "src/main/java/com/example/jingjingai2/context/ContextCompactor.java"));
        assertTrue(compactorSrc.contains("priorHistory("));
        assertTrue(compactorSrc.contains("static List<MessageRecord> priorHistory"));
    }

    @Test
    @DisplayName("priorHistory：空/末条 assistant 原样；末条 user 剥一层")
    void priorHistoryRoleOnly() {
        assertTrue(ContextCompactor.priorHistory(null).isEmpty());
        assertTrue(ContextCompactor.priorHistory(List.of()).isEmpty());

        List<MessageRecord> withAssistant = List.of(
                new MessageRecord("user", "a"),
                new MessageRecord("assistant", "b"));
        assertEquals(2, ContextCompactor.priorHistory(withAssistant).size());

        List<MessageRecord> withTrailingUser = List.of(
                new MessageRecord("user", "a"),
                new MessageRecord("assistant", "b"),
                new MessageRecord("user", "c"));
        List<MessageRecord> prior = ContextCompactor.priorHistory(withTrailingUser);
        assertEquals(2, prior.size());
        assertEquals("a", prior.get(0).getContent());
        assertEquals("b", prior.get(1).getContent());
    }

    private static int count(String src, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = src.indexOf(needle, from);
            if (at < 0) return n;
            n++;
            from = at + needle.length();
        }
    }
}
