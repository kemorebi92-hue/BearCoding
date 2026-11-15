package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageTokenBudgetTest {

    @Test
    @DisplayName("按角色拆分：system/user/assistant/tool")
    void breaksDownByRole() {
        List<Message> msgs = List.of(
                new SystemMessage("系统提示一二三四五六七八九十"),
                new UserMessage("用户问题"),
                new AssistantMessage("助手回复"),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("1", "readFile", "工具结果内容很长很长很长")))
        );
        MessageTokenBudget.Breakdown b = MessageTokenBudget.of(msgs);
        assertTrue(b.system() > 0);
        assertTrue(b.user() > 0);
        assertTrue(b.assistant() > 0);
        assertTrue(b.tool() > 0);
        assertEquals(b.system() + b.user() + b.assistant() + b.tool() + b.other(), b.total());
        assertEquals(b.tool(), MessageTokenBudget.toolResultTokens(msgs));
    }
}

class DispatchSoftHintTest {

    private List<Message> withToolChars(int chars) {
        String body = "中".repeat(chars); // CJK ≈ 1 tok/字
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("q"));
        msgs.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("1", "readFile", body))));
        return msgs;
    }

    @Test
    @DisplayName("未超阈值不注入；enabled=false 不注入")
    void belowThresholdOrDisabledNoInject() {
        DispatchSoftHint h = new DispatchSoftHint(100);
        List<Message> msgs = withToolChars(50);
        assertFalse(h.maybeInject(msgs, true));
        assertFalse(h.isInjected());
        assertEquals(3, msgs.size());

        List<Message> big = withToolChars(200);
        assertFalse(h.maybeInject(big, false));
        assertFalse(h.isInjected());
    }

    @Test
    @DisplayName("超过阈值注入一次，不重复")
    void injectsOnceAboveThreshold() {
        DispatchSoftHint h = new DispatchSoftHint(100);
        List<Message> msgs = withToolChars(150);
        assertTrue(h.maybeInject(msgs, true));
        assertTrue(h.isInjected());
        assertTrue(msgs.get(msgs.size() - 1) instanceof SystemMessage);
        assertTrue(((SystemMessage) msgs.get(msgs.size() - 1)).getText()
                .contains("建议派 explore"));
        int size = msgs.size();
        assertFalse(h.maybeInject(msgs, true), "第二次不得再注入");
        assertEquals(size, msgs.size());
    }
}
