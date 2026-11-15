package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1F：取消 → 真实保存 → 再次续跑的完整生命周期。
 * 走真实的 SubagentService / SubagentRunner / SubagentSessions，不启动 Spring，不连库，不调真实模型。
 */
class SubagentResumeLifecycleTest {

    private static final String CONV = "conv-life";
    private static final String GOAL = "把 a.java 的调用点找全";

    private final SubagentProperties props = new SubagentProperties();
    private final SubagentMemory memory = new SubagentMemory();
    private final SubagentSessions sessions = new SubagentSessions();
    private final SubagentTelemetry telemetry = new SubagentTelemetry();
    private final SubagentPool pool = new SubagentPool(props);
    private final AgentCancellationRegistry cancellation = new AgentCancellationRegistry();
    private final AtomicInteger reads = new AtomicInteger();

    @AfterEach
    void tearDown() {
        pool.destroy();
        cancellation.clear(CONV);
        props.setMaxResumesPerGoal(1);
    }

    @Test
    void cancelMidToolSavesCleanHistoryAndResumeSeesIt() {
        props.setMaxResumesPerGoal(2);
        // 读第一次就取消：c2 执行前的取消检查会命中，留下「assistant(c1,c2) + 只有 c1 的结果」
        ToolCallback read = ToolStubs.of("readFile", args -> {
            reads.incrementAndGet();
            cancellation.cancel(CONV);
            return "a.java 的片段";
        });
        SubagentService service = new SubagentService(props,
                new SubagentRunner(props, cancellation, new ToolCallback[]{read}),
                pool, memory, sessions, telemetry);

        // ===== 准备一个真实会话：空回答 → PARTIAL → 服务把历史存进会话 =====
        SubagentSpec base = new SubagentSpec(GOAL, SubagentSpec.Mode.EXPLORE, 0, CONV);
        service.spawnOnce(base, new FakeChatModel().thenReply("", 1));
        SubagentSessions.Session session = sessions.latestForGoal(CONV, base);
        assertNotNull(session, "PARTIAL 结果应该留下可续跑的会话");
        String resumeId = session.resumeId();

        // ===== 场景 A：续跑时取消 =====
        cancellation.arm(CONV);
        FakeChatModel modelA = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{}"),
                        FakeChatModel.call("c2", "readFile", "{}")));
        SubagentSpec resumeA = new SubagentSpec(GOAL, null, null, null,
                SubagentSpec.Mode.EXPLORE, 0, CONV, null, resumeId);

        String textA = service.spawnOnce(resumeA, modelA);

        assertEquals(1, modelA.callCount(), "取消后不得再发起第二轮模型请求");
        assertEquals(1, reads.get(), "只有 c1 被执行，c2 不得执行");
        assertTrue(textA.contains("CANCELLED"), "实际：" + textA);
        assertNotNull(sessions.find(CONV, resumeId), "取消的续跑应保留会话");

        List<Message> saved = session.historySnapshot();
        assertTrue(MessageProtocolValidator.check(saved).ok(), "保存的历史必须合法");
        assertTrue(saved.stream().noneMatch(m -> m instanceof ToolResponseMessage),
                "c1 的部分结果不得残留");
        assertTrue(saved.stream().noneMatch(m -> m instanceof AssistantMessage am
                && am.getToolCalls() != null && !am.getToolCalls().isEmpty()),
                "不完整的 assistant(c1,c2) 不得残留");
        assertTrue(saved.stream().noneMatch(m -> m instanceof UserMessage um
                && um.getText() != null && um.getText().startsWith("[预算]")),
                "预算提示仍要清掉");
        assertTrue(saved.stream().anyMatch(m -> m instanceof UserMessage um
                && um.getText() != null && um.getText().contains(GOAL)),
                "此前的完整历史要保留");
        assertEquals(3, saved.size(), "系统提示 + 原任务 + 续跑说明");

        // ===== 场景 B：用场景 A 实际保存的历史再次续跑 =====
        // 新一次用户请求会重新 arm 取消标志（与 ChatController 的 arm 行为一致）。
        cancellation.arm(CONV);
        FakeChatModel modelB = new FakeChatModel().thenReply("补齐结论：调用点共两处", 1);
        SubagentSpec resumeB = new SubagentSpec(GOAL, null, null, null,
                SubagentSpec.Mode.EXPLORE, 0, CONV, null, resumeId);

        String textB = service.spawnOnce(resumeB, modelB);

        assertEquals(1, modelB.callCount());
        assertEquals(1, reads.get(), "续跑不得重复执行已完成的工具");
        assertTrue(textB.contains("原因：SUCCESS"), "实际：" + textB);
        assertTrue(textB.contains("调用点共两处"), "实际：" + textB);

        List<Message> firstPrompt = modelB.prompts().get(0).getInstructions();
        assertTrue(MessageProtocolValidator.check(firstPrompt).ok(), "续跑的第一份 Prompt 必须合法");
        assertTrue(firstPrompt.stream().noneMatch(m -> m instanceof ToolResponseMessage),
                "不得把半组工具结果带进续跑");
        assertTrue(firstPrompt.stream().noneMatch(m -> m instanceof AssistantMessage am
                && am.getToolCalls() != null && !am.getToolCalls().isEmpty()),
                "不得把悬空工具调用带进续跑");
        assertTrue(firstPrompt.stream().anyMatch(m -> m instanceof UserMessage um
                && um.getText() != null && um.getText().contains(GOAL)),
                "续跑要能看到此前的任务");
        assertFalse(firstPrompt.isEmpty());
    }
}
