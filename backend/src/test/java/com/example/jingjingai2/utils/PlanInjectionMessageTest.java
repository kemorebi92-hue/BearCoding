package com.example.jingjingai2.utils;

import com.example.jingjingai2.context.RoundCompactor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanInjectionMessageTest {

    private static final String PLAN = "当前计划：先看 ChatController，再改注入。";

    @Test
    void identityStaysOnTheMessageAndOutOfTheText() {
        PlanInjectionMessage plan = new PlanInjectionMessage("plan-1", PLAN);
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("用户原话"));
        messages.add(plan);

        assertEquals("plan-1", PlanInjectionMessage.idOf(messages.get(1)));
        assertEquals(PLAN, plan.getText());
        assertFalse(plan.getText().contains("plan-1"));
        assertEquals(1, PlanInjectionMessage.indexOf(messages, "plan-1"));
    }

    @Test
    void insertBeforeThePlanDoesNotChangeWhichMessageIsRemoved() {
        List<Message> messages = listWithPlan("plan-1");
        messages.add(0, new SystemMessage("后插入的污点标记"));
        messages.add(1, new UserMessage("插在计划前面"));

        assertTrue(PlanInjectionMessage.remove(messages, "plan-1"));
        assertEquals(4, messages.size());
        assertEquals("用户原话", ((UserMessage) messages.get(3)).getText());
        assertEquals("系统提示", ((SystemMessage) messages.get(2)).getText());
        assertFalse(messages.stream().anyMatch(m -> "plan-1".equals(PlanInjectionMessage.idOf(m))));
    }

    @Test
    void rebuildingTheListKeepsTheSamePlanIdentity() {
        List<Message> messages = listWithPlan("plan-1");
        Message plan = messages.get(2);

        List<Message> rebuilt = new ArrayList<>();
        rebuilt.add(messages.get(0));
        rebuilt.add(plan);
        messages.clear();
        messages.addAll(rebuilt);
        messages.add(1, new SystemMessage("重建后再插入"));

        assertEquals(2, PlanInjectionMessage.indexOf(messages, "plan-1"));
        assertTrue(PlanInjectionMessage.remove(messages, "plan-1"));
        assertEquals("系统提示", ((SystemMessage) messages.get(0)).getText());
        assertEquals("重建后再插入", ((SystemMessage) messages.get(1)).getText());
        assertFalse(messages.stream().anyMatch(m -> "plan-1".equals(PlanInjectionMessage.idOf(m))));
    }

    @Test
    void copyKeepsTheIdentityAndTheText() {
        PlanInjectionMessage plan = new PlanInjectionMessage("plan-1", PLAN);
        SystemMessage copied = plan.copy();

        assertTrue(copied instanceof PlanInjectionMessage);
        assertEquals("plan-1", PlanInjectionMessage.idOf(copied));
        assertEquals(PLAN, copied.getText());
    }

    @Test
    void missingPlanIsLeftAlone() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("[历史摘要] 计划已被压进摘要"));
        messages.add(new UserMessage("还在的用户消息"));
        messages.add(new AssistantMessage("还在的助手消息"));

        assertFalse(PlanInjectionMessage.remove(messages, "plan-1"));
        assertEquals(3, messages.size());
        assertEquals("还在的用户消息", ((UserMessage) messages.get(1)).getText());
        assertEquals("还在的助手消息", ((AssistantMessage) messages.get(2)).getText());
    }

    @Test
    void replacingThePlanRemovesOnlyThePreviousInjection() {
        List<Message> messages = listWithPlan("plan-1");
        messages.add(new AssistantMessage("助手"));
        messages.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "readFile", "文件内容"))));

        assertTrue(PlanInjectionMessage.remove(messages, "plan-1"));
        messages.add(new PlanInjectionMessage("plan-2", "新的计划一行"));

        assertEquals(-1, PlanInjectionMessage.indexOf(messages, "plan-1"));
        assertEquals(messages.size() - 1, PlanInjectionMessage.indexOf(messages, "plan-2"));
        assertEquals(1, messages.stream().filter(m -> m instanceof PlanInjectionMessage).count());
        assertEquals("助手", ((AssistantMessage) messages.get(2)).getText());
        assertEquals("文件内容", ((ToolResponseMessage) messages.get(3)).getResponses().get(0).responseData());
    }

    @Test
    void sameSlotOrSameTextIsNotTheSamePlan() {
        List<Message> first = listWithPlan("plan-a");
        List<Message> second = listWithPlan("plan-b");
        second.set(2, new SystemMessage(PLAN));

        assertEquals(2, PlanInjectionMessage.indexOf(first, "plan-a"));
        assertEquals(-1, PlanInjectionMessage.indexOf(first, "plan-b"));
        assertEquals(-1, PlanInjectionMessage.indexOf(second, "plan-a"));
        assertFalse(PlanInjectionMessage.remove(second, "plan-a"));
        assertEquals(PLAN, ((SystemMessage) second.get(2)).getText());
    }

    @Test
    void digestKeepsThePlanInstanceAndSummaryDropsIt() {
        String id = "plan-digest";
        List<Message> digestList = compactionList(id);
        RoundCompactor.apply(digestList, new org.springframework.ai.tool.ToolCallback[0], 0,
                80, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("不应被调用", 0, 0, false, false),
                "digest");
        assertTrue(digestList.stream().anyMatch(m -> m.getText() != null && m.getText().startsWith("[压缩索引] ")));
        assertTrue(PlanInjectionMessage.indexOf(digestList, id) >= 0);

        List<Message> summaryList = compactionList(id);
        RoundCompactor.apply(summaryList, new org.springframework.ai.tool.ToolCallback[0], 0,
                80, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("摘要", 0, 0, false, false),
                "summary");
        assertTrue(summaryList.stream().anyMatch(m -> m.getText() != null && m.getText().startsWith("[历史摘要]")));
        assertEquals(-1, PlanInjectionMessage.indexOf(summaryList, id));
        assertFalse(PlanInjectionMessage.remove(summaryList, id));
        assertTrue(summaryList.stream().anyMatch(m -> m instanceof UserMessage
                && "还在".equals(m.getText())));
    }

    private static List<Message> listWithPlan(String id) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new UserMessage("用户原话"));
        messages.add(new PlanInjectionMessage(id, PLAN));
        return messages;
    }

    /** 计划正文很长，会被摘要进 older；短用户消息留在 recent。 */
    private static List<Message> compactionList(String id) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("规则"));
        messages.add(new UserMessage("还在"));
        messages.add(new PlanInjectionMessage(id, "计".repeat(800)));
        return messages;
    }
}
