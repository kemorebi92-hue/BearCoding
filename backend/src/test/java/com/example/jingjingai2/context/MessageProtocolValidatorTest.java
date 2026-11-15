package com.example.jingjingai2.context;

import com.example.jingjingai2.utils.PlanInjectionMessage;
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

class MessageProtocolValidatorTest {

    @Test
    void plainConversationPasses() {
        List<Message> messages = List.of(
                new SystemMessage("规则"),
                new UserMessage("你好"),
                new AssistantMessage("你好"));
        assertTrue(MessageProtocolValidator.check(messages).ok());
        assertTrue(MessageProtocolValidator.check(null).ok());
    }

    @Test
    void oneCallAndOneResponsePassInEitherOrderOfIds() {
        assertTrue(MessageProtocolValidator.check(List.of(
                call("a", "readFile"),
                response("a", "正文"))).ok());
    }

    @Test
    void twoCallsOnOneAssistantCanBeAnsweredByOneToolMessage() {
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"),
                new AssistantMessage.ToolCall("call_2", "function", "grepFile", "{}")));
        ToolResponseMessage answers = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call_2", "grepFile", "后回答"),
                new ToolResponseMessage.ToolResponse("call_1", "readFile", "先回答")));
        MessageProtocolValidator.Result result = MessageProtocolValidator.check(List.of(
                new UserMessage("看两处"), assistant, answers));
        assertTrue(result.ok(), result.summary());
    }

    @Test
    void separateResponsesInADifferentOrderStillPass() {
        List<Message> messages = new ArrayList<>();
        messages.add(call("a", "readFile"));
        messages.add(call("b", "grepFile"));
        messages.add(response("b", "二"));
        messages.add(response("a", "一"));
        assertTrue(MessageProtocolValidator.check(messages).ok());
    }

    @Test
    void twoCompleteRoundsPass() {
        List<Message> messages = new ArrayList<>();
        messages.add(call("a", "readFile"));
        messages.add(response("a", "一"));
        messages.add(new UserMessage("继续"));
        messages.add(call("b", "grepFile"));
        messages.add(response("b", "二"));
        assertTrue(MessageProtocolValidator.check(messages).ok());
    }

    @Test
    void missingResponseIsReportedWithoutReadingTheBody() {
        String secret = "用户不该出现在诊断里";
        List<Message> messages = List.of(new UserMessage(secret), call("a", "readFile"));
        MessageProtocolValidator.Result result = MessageProtocolValidator.check(messages);
        assertFalse(result.ok());
        assertEquals(MessageProtocolValidator.Kind.MISSING_RESPONSE, result.issues().get(0).kind());
        assertEquals("a", result.issues().get(0).toolCallId());
        assertEquals(1, result.issues().get(0).messageIndex());
        assertFalse(result.summary().contains(secret));
        assertEquals(2, messages.size());
    }

    @Test
    void orphanResponseAndResponseBeforeCallAreDifferent() {
        MessageProtocolValidator.Result orphan = MessageProtocolValidator.check(List.of(response("gone", "x")));
        assertEquals(MessageProtocolValidator.Kind.ORPHAN_RESPONSE, orphan.issues().get(0).kind());

        List<Message> early = new ArrayList<>();
        early.add(response("a", "太早"));
        early.add(call("a", "readFile"));
        MessageProtocolValidator.Result before = MessageProtocolValidator.check(early);
        assertTrue(before.issues().stream().anyMatch(i ->
                i.kind() == MessageProtocolValidator.Kind.RESPONSE_BEFORE_CALL && "a".equals(i.toolCallId())));
        assertTrue(before.issues().stream().anyMatch(i ->
                i.kind() == MessageProtocolValidator.Kind.MISSING_RESPONSE));
    }

    @Test
    void duplicateCallDuplicateResponseAndBlankIds() {
        AssistantMessage duplicated = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("a", "function", "readFile", "{}"),
                new AssistantMessage.ToolCall("a", "function", "readFile", "{}"),
                new AssistantMessage.ToolCall("  ", "function", "readFile", "{}")));
        MessageProtocolValidator.Result calls = MessageProtocolValidator.check(List.of(duplicated, response("a", "ok")));
        assertTrue(calls.issues().stream().anyMatch(i -> i.kind() == MessageProtocolValidator.Kind.DUPLICATE_CALL_ID));
        assertTrue(calls.issues().stream().anyMatch(i -> i.kind() == MessageProtocolValidator.Kind.BLANK_CALL_ID));

        ToolResponseMessage twice = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("a", "readFile", "1"),
                new ToolResponseMessage.ToolResponse("a", "readFile", "2"),
                new ToolResponseMessage.ToolResponse("", "readFile", "空")));
        MessageProtocolValidator.Result responses = MessageProtocolValidator.check(List.of(call("a", "readFile"), twice));
        assertTrue(responses.issues().stream().anyMatch(i -> i.kind() == MessageProtocolValidator.Kind.DUPLICATE_RESPONSE_ID));
        assertTrue(responses.issues().stream().anyMatch(i -> i.kind() == MessageProtocolValidator.Kind.BLANK_RESPONSE_ID));
    }

    @Test
    void insertAndPlanMessageDoNotBreakAValidPair() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("规则"));
        messages.add(call("a", "readFile"));
        messages.add(response("a", "文件"));
        messages.add(1, new UserMessage("插进来"));
        messages.add(new PlanInjectionMessage("plan-1", "当前计划"));
        MessageProtocolValidator.Result result = MessageProtocolValidator.check(messages);
        assertTrue(result.ok(), result.summary());
        assertEquals("文件", ((ToolResponseMessage) messages.get(3)).getResponses().get(0).responseData());
    }

    @Test
    void digestKeepsThePairAndASplitIsRejected() {
        List<Message> intact = new ArrayList<>();
        intact.add(new SystemMessage("规则"));
        intact.add(new UserMessage("还在"));
        intact.add(call("a", "readFile"));
        intact.add(response("a", "计".repeat(400)));
        RoundCompactor.apply(intact, new org.springframework.ai.tool.ToolCallback[0], 0,
                40, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("摘要", 0, 0, false, false),
                "digest");
        assertTrue(intact.stream().anyMatch(m -> m.getText() != null && m.getText().startsWith("[压缩索引] ")));
        MessageProtocolValidator.Result digested = MessageProtocolValidator.check(intact);
        assertTrue(digested.ok(), digested.summary());

        List<Message> summary = new ArrayList<>();
        summary.add(new SystemMessage("规则"));
        summary.add(new UserMessage("还在"));
        summary.add(call("a", "readFile"));
        summary.add(response("a", "计".repeat(800)));
        RoundCompactor.apply(summary, new org.springframework.ai.tool.ToolCallback[0], 0,
                80, 0, 0, 1, new RoundCompactor.Guard(),
                text -> new RoundCompactor.Summary("摘要", 0, 0, false, false),
                "summary");
        assertTrue(MessageProtocolValidator.check(summary).ok());

        List<Message> onlyCall = List.of(call("a", "readFile"));
        assertEquals(MessageProtocolValidator.Kind.MISSING_RESPONSE,
                MessageProtocolValidator.check(onlyCall).issues().get(0).kind());
        List<Message> onlyResponse = List.of(response("a", "落单"));
        assertEquals(MessageProtocolValidator.Kind.ORPHAN_RESPONSE,
                MessageProtocolValidator.check(onlyResponse).issues().get(0).kind());
    }

    private static AssistantMessage call(String id, String name) {
        return new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(id, "function", name, "{}")));
    }

    private static ToolResponseMessage response(String id, String data) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, "tool", data)));
    }
}
