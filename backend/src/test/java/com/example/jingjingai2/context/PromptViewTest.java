package com.example.jingjingai2.context;

import com.example.jingjingai2.controller.ChatController;
import com.example.jingjingai2.utils.PlanInjectionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptViewTest {

    @Test
    void viewIsAStructuralCopyThatKeepsTheSameMessages() {
        List<Message> original = new ArrayList<>();
        original.add(new SystemMessage("规则"));
        original.add(new UserMessage("问题"));
        PlanInjectionMessage plan = new PlanInjectionMessage("plan-1", "当前计划");
        original.add(plan);
        AssistantMessage call = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"),
                new AssistantMessage.ToolCall("call_2", "function", "grepFile", "{}")));
        ToolResponseMessage response = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call_2", "grepFile", "二"),
                new ToolResponseMessage.ToolResponse("call_1", "readFile", "一")));
        original.add(call);
        original.add(response);

        PromptView view = PromptView.of(original);
        assertEquals(original.size(), view.messages().size());
        for (int i = 0; i < original.size(); i++) {
            assertSame(original.get(i), view.messages().get(i));
        }
        assertEquals("plan-1", PlanInjectionMessage.idOf(view.messages().get(2)));
        assertSame(call.getToolCalls(), ((AssistantMessage) view.messages().get(3)).getToolCalls());
        assertEquals("call_1", ((ToolResponseMessage) view.messages().get(4)).getResponses().get(1).id());
        assertTrue(MessageProtocolValidator.check(view.messages()).ok());

        view.messages().get(0);
        assertThrows(UnsupportedOperationException.class, () -> view.messages().add(new UserMessage("不能加")));
        assertThrows(UnsupportedOperationException.class, () -> view.messages().remove(0));
        assertEquals(5, original.size());
        assertEquals("问题", ((UserMessage) original.get(1)).getText());
    }

    @Test
    void brokenHistoryIsRefusedOnTheViewAndTheOriginalStays() throws Exception {
        List<Message> original = new ArrayList<>();
        original.add(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"))));
        PromptView view = PromptView.of(original);
        List<Message> before = List.copyOf(original);

        Method gate = ChatController.class.getDeclaredMethod("refuseBrokenProtocol", List.class);
        gate.setAccessible(true);
        boolean refused = (boolean) gate.invoke(null, view.messages());

        assertTrue(refused);
        assertEquals(before, original);
        assertEquals(1, original.size());
        assertFalse(MessageProtocolValidator.check(view.messages()).ok());
    }
}
