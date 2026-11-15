package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ContextCompactorBudgetTest {
    public static org.springframework.ai.openai.OpenAiChatModel successfulSummaryModel() {
        var model = org.mockito.Mockito.mock(org.springframework.ai.openai.OpenAiChatModel.class);
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                        new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("已保留历史事实的真实摘要")))));
        return model;
    }

    @Test
    @DisplayName("1M 窗口下 9000 token 的历史不压缩")
    void largeWindowDoesNotCompactModestHistory() {
        ContextCompactor compactor = new ContextCompactor(null, 24_000, 1_000_000, 65);
        List<MessageRecord> history = new ArrayList<>();
        history.add(new MessageRecord("user", "a".repeat(4000)));
        history.add(new MessageRecord("assistant", "b".repeat(4000)));
        List<Message> out = compactor.buildMessages(history, "继续");
        assertFalse(out.get(0) instanceof SystemMessage);
        assertEquals(3, out.size());
    }

    @Test
    @DisplayName("总输入超过窗口的 65% 才把更早的历史压成摘要")
    void compactsWhenOverWindowLine() {
        ContextCompactor compactor = new ContextCompactor(successfulSummaryModel(), 100, 1_000, 65);
        List<MessageRecord> history = new ArrayList<>();
        history.add(new MessageRecord("user", "old ".repeat(800)));
        history.add(new MessageRecord("assistant", "kept"));
        List<Message> out = compactor.buildMessages(history, "now");
        assertTrue(out.get(0) instanceof SystemMessage);
        assertTrue(out.get(0).getText().contains("[历史摘要]"));
    }
}
