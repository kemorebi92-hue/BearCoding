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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一轮内核：**模型调用 → 白名单执行 →（可选）截断 → 追加工具结果 → 取消点**。
 *
 * 抽它的理由是"三处别各写一份"（非流式主循环 / SSE 主循环 / 子代理房间），
 * 所以这里的测试重点不是"能不能跑"，而是**那些容易被漏掉的语义细节**：
 * 自然收尾要不要追加助手消息、带 tool_calls 的助手消息必须先进 messages、取消点在哪、
 * 截断落在哪一层。
 */
class ToolLoopStepTest {

    private List<Message> messages() {
        List<Message> m = new ArrayList<>();
        m.add(new SystemMessage("sys"));
        m.add(new UserMessage("原始问题"));
        return m;
    }

    private static ToolLoopStep.Result run(FakeChatModel model, List<Message> messages,
                                           ToolLoopStep.CancelCheck cancel,
                                           ToolLoopStep.ToolExecutor executor,
                                           Integer maxChars) {
        return ToolLoopStep.run(messages, new org.springframework.ai.chat.prompt.Prompt(messages),
                model, cancel, executor, maxChars);
    }

    @Test
    @DisplayName("模型不再调工具 → NATURAL_STOP，且【不】追加助手消息（怎么用它由调用方决定）")
    void naturalStopDoesNotAppend() {
        FakeChatModel model = new FakeChatModel().thenReply("我查完了，结论是 X", 1);
        List<Message> msgs = messages();
        int before = msgs.size();

        ToolLoopStep.Result r = run(model, msgs, null, call -> ToolLoopStep.ToolExec.ok("unused"), null);

        assertEquals(ToolLoopStep.Outcome.NATURAL_STOP, r.outcome);
        assertEquals(before, msgs.size(), "自然收尾这一轮不该改动 messages —— 主循环要自己决定怎么用这段文字");
        assertEquals("我查完了，结论是 X", r.assistant.getText());
        assertEquals(0, r.toolsExecuted);
    }

    @Test
    @DisplayName("有工具调用 → 先追加助手消息、再追加工具结果，outcome=CONTINUE")
    void appendsAssistantThenToolResponse() {
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")));
        List<Message> msgs = messages();

        ToolLoopStep.Result r = run(model, msgs, null, call -> ToolLoopStep.ToolExec.ok("文件内容"), null);

        assertEquals(ToolLoopStep.Outcome.CONTINUE, r.outcome);
        assertEquals(1, r.toolsExecuted);
        assertEquals(4, msgs.size());
        assertTrue(msgs.get(2) instanceof AssistantMessage, "第 3 条必须是助手消息 —— OpenAI 协议要求 tool_calls 先入列");
        assertTrue(msgs.get(3) instanceof ToolResponseMessage);
        ToolResponseMessage trm = (ToolResponseMessage) msgs.get(3);
        assertEquals(1, trm.getResponses().size());
        assertEquals("c1", trm.getResponses().get(0).id());
        assertEquals("readFile", trm.getResponses().get(0).name());
        assertEquals("文件内容", trm.getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("一轮里多个工具调用 → 全部执行、全部追加，且顺序与模型给的顺序一致")
    void executesAllToolCallsInOneRound() {
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("c1", "readFile", "{}"),
                FakeChatModel.call("c2", "grepFile", "{}")));
        List<Message> msgs = messages();

        ToolLoopStep.Result r = run(model, msgs, null, call -> ToolLoopStep.ToolExec.ok("结果-" + call.name()), null);

        assertEquals(2, r.toolsExecuted);
        assertEquals(5, msgs.size());
        assertEquals("结果-readFile",
                ((ToolResponseMessage) msgs.get(3)).getResponses().get(0).responseData());
        assertEquals("结果-grepFile",
                ((ToolResponseMessage) msgs.get(4)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("取消点命中 → CANCELLED，后续工具不再执行")
    void cancelStopsBeforeNextTool() {
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("c1", "readFile", "{}"),
                FakeChatModel.call("c2", "readFile", "{}")));
        List<Message> msgs = messages();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger executed = new AtomicInteger();

        ToolLoopStep.Result r = run(model, msgs, cancelled::get, call -> {
            executed.incrementAndGet();
            cancelled.set(true);   // 第一个工具执行期间用户点了"停止"
            return ToolLoopStep.ToolExec.ok("ok");
        }, null);

        assertEquals(ToolLoopStep.Outcome.CANCELLED, r.outcome);
        assertEquals(1, executed.get(), "取消后不该再执行第二个工具");
        assertEquals(1, r.toolsExecuted);
    }

    @Test
    @DisplayName("执行器要求中止整趟 → ABORTED（高危命令待审批就是走这条路），且不再往下执行")
    void abortStopsWholeRound() {
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("c1", "executeCommand", "{}"),
                FakeChatModel.call("c2", "readFile", "{}")));
        List<Message> msgs = messages();
        AtomicInteger executed = new AtomicInteger();

        ToolLoopStep.Result r = run(model, msgs, null, call -> {
            executed.incrementAndGet();
            return "executeCommand".equals(call.name())
                    ? ToolLoopStep.ToolExec.abort("rm -rf /") : ToolLoopStep.ToolExec.ok("ok");
        }, null);

        assertEquals(ToolLoopStep.Outcome.ABORTED, r.outcome);
        assertEquals("rm -rf /", r.abortReason);
        assertEquals(1, executed.get());
        assertEquals(0, r.toolsExecuted, "被中止的那次不算执行成功");
    }

    @Test
    @DisplayName("maxChars 在这一层生效：追加进 messages 的工具结果已经被截断")
    void clipsToolResultsWhenLimitGiven() {
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("c1", "readFile", "{}")));
        List<Message> msgs = messages();
        String huge = "A".repeat(20000);

        run(model, msgs, null, call -> ToolLoopStep.ToolExec.ok(huge), 4000);

        String stored = ((ToolResponseMessage) msgs.get(3)).getResponses().get(0).responseData();
        assertTrue(stored.length() < 4000, "存进房间的结果必须受控，实际 " + stored.length());
        assertTrue(stored.contains("已截断"));
    }

    @Test
    @DisplayName("maxChars=null → 主循环不做第二道闸（计划已冻结，不在这里加新行为）")
    void doesNotClipWhenLimitIsNull() {
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("c1", "readFile", "{}")));
        List<Message> msgs = messages();
        String huge = "A".repeat(20000);

        run(model, msgs, null, call -> ToolLoopStep.ToolExec.ok(huge), null);

        String stored = ((ToolResponseMessage) msgs.get(3)).getResponses().get(0).responseData();
        assertEquals(20000, stored.length());
    }

    @Test
    @DisplayName("空响应（没有 result）不抛异常，按「没有工具调用」处理")
    void handlesNullResponse() {
        FakeChatModel model = new FakeChatModel().then(p -> null);
        List<Message> msgs = messages();

        ToolLoopStep.Result r = run(model, msgs, null, call -> ToolLoopStep.ToolExec.ok("x"), null);

        assertEquals(ToolLoopStep.Outcome.NATURAL_STOP, r.outcome);
        assertNull(r.assistant);
        assertEquals(2, msgs.size());
    }
}
