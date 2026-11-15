package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1E：子代理发请求前的协议校验，以及续跑历史里末尾不完整工具组的清理。
 * 不启动 Spring，不连数据库，不调真实模型。
 */
class SubagentProtocolTest {

    private static final String CONV = "conv-1";

    private final SubagentProperties props = new SubagentProperties();
    private final AgentCancellationRegistry registry = new AgentCancellationRegistry();

    @Test
    @DisplayName("正常多轮：第二轮请求能看到 c1 的结果，每轮都通过协议校验")
    void normalMultiRoundStillWorks() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{}")))
                .thenReply("结论", 1);
        SubagentRunner runner = new SubagentRunner(props, registry,
                ToolStubs.fullSet(new AtomicInteger(), null, null, null));

        SubagentResult result = runner.run(
                new SubagentSpec("读一处", SubagentSpec.Mode.EXPLORE, 0, CONV), model, null);

        assertEquals(2, model.callCount());
        for (Prompt prompt : model.prompts()) {
            assertTrue(MessageProtocolValidator.check(prompt.getInstructions()).ok());
        }
        List<Message> second = model.prompts().get(1).getInstructions();
        assertTrue(second.stream().anyMatch(m -> m instanceof ToolResponseMessage trm
                && "c1".equals(trm.getResponses().get(0).id())));
        assertEquals("SUCCESS", String.valueOf(result.failureReason()));
        assertTrue(result.summary().contains("结论"));
    }

    @Test
    @DisplayName("续跑种子带悬空调用：不发请求、不执行工具、按 ABORTED 语义返回")
    void danglingSeedIsRefusedBeforeFirstCall() {
        FakeChatModel model = new FakeChatModel().thenReply("不该出现", 1);
        SubagentRunner runner = new SubagentRunner(props, registry,
                ToolStubs.fullSet(new AtomicInteger(), null, null, null));
        List<Message> seed = List.of(
                new SystemMessage("房间"),
                new UserMessage("上次的任务"),
                assistantWithCalls("c1", "c2"));
        SubagentRunner.Room room = SubagentRunner.Room.resume(seed, List.of(), null);

        SubagentResult result = runner.run(
                new SubagentSpec("继续", SubagentSpec.Mode.EXPLORE, 0, CONV), model, null, room);

        assertEquals(0, model.callCount(), "不合法历史不得发请求");
        assertEquals("TOOL_FAILURES", String.valueOf(result.failureReason()));
        assertTrue(result.detail().contains("协议"), "停因里要带协议说明: " + result.detail());
        assertTrue(room.messages.stream().noneMatch(m -> m instanceof ToolResponseMessage),
                "不得伪造工具结果");
    }

    @Test
    @DisplayName("取消发生在第二个工具执行前：留下半组，续跑历史整组摘掉")
    void cancelledMidToolLeavesIncompleteGroupAndResumeDropsIt() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{}"),
                        FakeChatModel.call("c2", "readFile", "{}")));
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("房间"), new UserMessage("任务")));
        AtomicInteger checks = new AtomicInteger();
        ToolLoopStep.Result step = ToolLoopStep.run(
                messages,
                new Prompt(messages, ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false).build()),
                model,
                () -> checks.incrementAndGet() > 1,
                call -> ToolLoopStep.ToolExec.ok("结果-" + call.id()),
                4000);

        assertEquals(ToolLoopStep.Outcome.CANCELLED, step.outcome);
        assertEquals(1, step.toolsExecuted);
        assertInstanceOf(AssistantMessage.class, messages.get(2));
        assertInstanceOf(ToolResponseMessage.class, messages.get(3));
        assertEquals(4, messages.size());
        assertFalse(MessageProtocolValidator.check(messages).ok(), "半组本身就是不合法的");

        List<Message> withBudget = new ArrayList<>(messages);
        withBudget.add(new UserMessage(SubagentRunner.BUDGET_PREFIX + " 已用 1/2"));
        List<Message> cleaned = SubagentRunner.historyForResume(withBudget);

        assertEquals(2, cleaned.size(), "半组和预算消息都要去掉");
        assertEquals("任务", ((UserMessage) cleaned.get(1)).getText());
        assertTrue(MessageProtocolValidator.check(cleaned).ok());
    }

    @Test
    @DisplayName("完整工具组与普通消息原样保留")
    void completeHistoryIsUnchanged() {
        List<Message> complete = List.of(
                new SystemMessage("房间"),
                new UserMessage("任务"),
                assistantWithCalls("c1"),
                response("c1", "文件内容"));
        List<Message> cleaned = SubagentRunner.historyForResume(complete);

        assertEquals(4, cleaned.size());
        assertEquals(complete, cleaned);
        assertEquals("c1", ((ToolResponseMessage) cleaned.get(3)).getResponses().get(0).id());
        assertTrue(MessageProtocolValidator.check(cleaned).ok());
    }

    @Test
    @DisplayName("只有预算消息要清理时，原有行为不变")
    void plainMessagesOnlyLoseTheBudgetNote() {
        List<Message> raw = List.of(
                new SystemMessage("房间"),
                new UserMessage("任务"),
                new AssistantMessage("中间结论"),
                new UserMessage(SubagentRunner.BUDGET_PREFIX + " 已用 1/1"));

        List<Message> cleaned = SubagentRunner.historyForResume(raw);

        assertEquals(3, cleaned.size());
        assertEquals("中间结论", ((AssistantMessage) cleaned.get(2)).getText());
    }

    @Test
    @DisplayName("历史中间的协议错误不静默删除，留给发请求前的校验拦")
    void midHistoryErrorIsNotSilentlyDropped() {
        List<Message> raw = List.of(
                new SystemMessage("房间"),
                assistantWithCalls("c1"),
                new UserMessage("后面还有正常消息"),
                new AssistantMessage("结论"));

        List<Message> cleaned = SubagentRunner.historyForResume(raw);

        assertEquals(raw.size(), cleaned.size());
        assertFalse(MessageProtocolValidator.check(cleaned).ok());
    }

    private static AssistantMessage assistantWithCalls(String... ids) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(new AssistantMessage.ToolCall(id, "function", "readFile", "{}"));
        }
        return new AssistantMessage("", java.util.Map.of(), calls);
    }

    private static ToolResponseMessage response(String id, String data) {
        return new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(id, "readFile", data)));
    }
}
