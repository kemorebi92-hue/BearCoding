package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ChatRequest;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.agent.*;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.config.*;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.service.McpServerProvisionService;
import com.example.jingjingai2.mcp.McpServerTemplateCatalog;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.search.SearchService;
import com.example.jingjingai2.usermemory.UserMemoryService;
import com.example.jingjingai2.memory.MemoryConsolidator;
import com.example.jingjingai2.memory.WorkspaceMemory;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.ratelimit.RateLimiter;
import com.example.jingjingai2.service.*;
import com.example.jingjingai2.tool.McpAdminTool;
import com.example.jingjingai2.usermemory.UserMemoryInjector;
import com.example.jingjingai2.usermemory.UserMemoryProperties;
import com.example.jingjingai2.usermemory.UserMemoryTool;
import com.example.jingjingai2.utils.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用假模型从 chat() / chatStream() 主入口驱动。不连数据库、不调真实模型。
 * 记录到的 Prompt 列表若不可增删，说明发出去的是 PromptView（List.copyOf），不是工作 ArrayList。
 */
@org.junit.jupiter.api.parallel.ResourceLock("stdout")
class ChatEntryPromptViewTest {

    @Test
    void nonStreamingNormalPathSendsThePromptView() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("好的", Map.of(), List.of());
        ChatController controller = controller(fake, historyMock());

        com.example.jingjingai2.DTO.ChatResponse response = controller.chat(request("你好"), httpRequest());

        assertEquals(1, fake.calls.get());
        assertEquals("好的", response.getMessage());
        List<Message> sent = fake.recorded.get(0);
        assertImmutable(sent);
        assertTrue(sent.get(0) instanceof SystemMessage);
        assertTrue(sent.get(sent.size() - 1) instanceof UserMessage);
    }

    @Test
    void nonStreamingRefusesBrokenToolHistoryBeforeTheNextModelCall() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(" ", "function", "readFile", "{}")));
        ChatController controller = controller(fake, historyMock());

        com.example.jingjingai2.DTO.ChatResponse response = controller.chat(request("读文件"), httpRequest());

        assertEquals(1, fake.calls.get(), "校验失败后不得再调用模型");
        assertTrue(response.getMessage().contains("内部工具消息不完整"));
        assertImmutable(fake.recorded.get(0));
    }

    @Test
    void streamingNormalPathSendsThePromptView() {
        FakeModel fake = new FakeModel();
        fake.streamingAnswer = new AssistantMessage("流式回答", Map.of(), List.of());
        ConversationHistoryService history = historyMock();
        ChatController controller = controller(fake, history);

        controller.chatStream(request("你好"), httpRequest(), new MockHttpServletResponse());

        verify(history, timeout(8000)).addMessage(any(), any(),
                argThat(r -> r != null && "assistant".equals(r.getRole())));
        assertEquals(1, fake.streams.get());
        assertEquals(0, fake.calls.get());
        assertImmutable(fake.recorded.get(0));
    }

    @Test
    void streamingRefusesBrokenToolHistoryBeforeTheNextStreamCall() {
        FakeModel fake = new FakeModel();
        fake.streamingAnswer = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(" ", "function", "readFile", "{}")));
        ConversationHistoryService history = historyMock();
        ChatController controller = controller(fake, history);

        controller.chatStream(request("读文件"), httpRequest(), new MockHttpServletResponse());

        verify(history, timeout(8000)).addMessage(any(), any(),
                argThat(r -> r != null && "assistant".equals(r.getRole())
                        && r.getContent() != null && r.getContent().contains("内部工具消息不完整")));
        assertEquals(1, fake.streams.get(), "校验失败后不得再发起流式请求");
        assertEquals(0, fake.calls.get(), "流式校验失败不得转非流式兜底");
    }

    @Test
    void nonStreamingWrapUpUsesThePromptView() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.addAll(List.of(
                new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"))),
                new AssistantMessage("总结", Map.of(), List.of())));
        ConversationHistoryService history = historyMock();
        TaskPlanService plan = mock(TaskPlanService.class);
        when(plan.budget(any(), any(), anyInt())).thenReturn(5);
        when(plan.hasPlan(any(), any())).thenReturn(false);
        when(plan.allStepsDone(any(), any())).thenReturn(true);
        ChatController controller = controller(fake, history, plan);

        controller.chat(request("做任务"), httpRequest());

        assertEquals(2, fake.calls.get(), "工具轮 + 收尾轮各一次");
        assertImmutable(fake.recorded.get(0));
        assertImmutable(fake.recorded.get(1));
    }

    @Test
    void streamingWrapUpUsesThePromptView() {
        FakeModel fake = new FakeModel();
        fake.streamingAnswers.addAll(List.of(
                new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}"))),
                new AssistantMessage("总结", Map.of(), List.of())));
        ConversationHistoryService history = historyMock();
        TaskPlanService plan = mock(TaskPlanService.class);
        when(plan.budget(any(), any(), anyInt())).thenReturn(5);
        when(plan.hasPlan(any(), any())).thenReturn(false);
        when(plan.allStepsDone(any(), any())).thenReturn(true);
        ChatController controller = controller(fake, history, plan);

        controller.chatStream(request("做任务"), httpRequest(), new MockHttpServletResponse());

        verify(history, timeout(8000)).addMessage(any(), any(),
                argThat(r -> r != null && "assistant".equals(r.getRole())));
        assertEquals(2, fake.streams.get(), "工具轮 + 收尾轮各一次");
        assertImmutable(fake.recorded.get(0));
        assertImmutable(fake.recorded.get(1));
    }

    @Test
    void streamingFallbackUsesTheSameCheckedView() {
        FakeModel fake = new FakeModel();
        fake.streamingAnswer = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call_1", "function", "", "{}")));
        fake.nonStreamingAnswer = new AssistantMessage("兜底回答", Map.of(), List.of());
        ConversationHistoryService history = historyMock();
        ChatController controller = controller(fake, history);

        controller.chatStream(request("你好"), httpRequest(), new MockHttpServletResponse());

        verify(history, timeout(8000)).addMessage(any(), any(),
                argThat(r -> r != null && "assistant".equals(r.getRole())
                        && r.getContent() != null && r.getContent().contains("兜底回答")));
        assertEquals(1, fake.streams.get());
        assertEquals(1, fake.calls.get(), "坏工具参数触发一次非流式兜底");
        assertImmutable(fake.recorded.get(1));
        assertEquals(fake.recorded.get(0), fake.recorded.get(1), "兜底用的是同一份视图");
    }

    @Test
    void nonStreamingRequestRunsLedgerAndReconcile() throws Exception {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(reconcileToolRound());
        fake.nonStreamingAnswer = new AssistantMessage("ok", Map.of(), List.of());
        ChatController controller = controller(fake, historyMock(), planWithDoneStep());
        String convId = "conv-reconcile-" + UUID.randomUUID();
        ChatRequest req = request("做任务");
        req.setConversationId(convId);

        String out = captureReconcile(() -> controller.chat(req, httpRequest()), convId);

        assertTrue(hasAlert(out, convId, "DONE_WITHOUT_ACTUAL_RESULT"), out);
        assertTrue(hasAlert(out, convId, "DONE_WITH_BUSINESS_FAILURE"), out);
    }

    @Test
    void streamingRequestRunsLedgerAndReconcile() throws Exception {
        FakeModel fake = new FakeModel();
        fake.streamingAnswers.add(reconcileToolRound());
        fake.streamingAnswer = new AssistantMessage("ok", Map.of(), List.of());
        ChatController controller = controller(fake, historyMock(), planWithDoneStep());
        String convId = "conv-reconcile-" + UUID.randomUUID();
        ChatRequest req = request("做任务");
        req.setConversationId(convId);

        String out = captureReconcile(() -> controller.chatStream(
                req, httpRequest(), new MockHttpServletResponse()), convId);

        assertTrue(hasAlert(out, convId, "DONE_WITH_BUSINESS_FAILURE"), out);
    }

    @Test
    void reconcileFailureDoesNotBreakRequest() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("ok", Map.of(), List.of());
        TaskPlanService plan = planWithNoSteps();
        when(plan.stepsOf(any(), any())).thenThrow(new RuntimeException("boom"));
        ChatController controller = controller(fake, historyMock(), plan);

        com.example.jingjingai2.DTO.ChatResponse response = controller.chat(request("你好"), httpRequest());

        assertEquals("ok", response.getMessage());
    }

    private static AssistantMessage reconcileToolRound() {
        return new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("c1", "function", "updatePlanStep",
                        "{\"stepId\":\"s1\",\"status\":\"running\",\"actualResult\":\"\"}"),
                new AssistantMessage.ToolCall("c2", "function", "editFile",
                        "{\"path\":\"missing.txt\",\"oldStr\":\"a\",\"newStr\":\"b\"}"),
                new AssistantMessage.ToolCall("c3", "function", "updatePlanStep",
                        "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"\"}")
        ));
    }

    private static TaskPlanService planWithDoneStep() {
        TaskPlanService plan = mock(TaskPlanService.class);
        PlanStep step = new PlanStep("s1", "do it", "done");
        step.setStatus(PlanStep.DONE);
        step.setActualResult("");
        when(plan.budget(any(), any(), anyInt())).thenReturn(10);
        when(plan.hasPlan(any(), any())).thenReturn(false);
        when(plan.allStepsDone(any(), any())).thenReturn(false);
        when(plan.stepsOf(any(), any())).thenReturn(List.of(step));
        when(plan.updateStep(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            TaskPlanService.UpdateOutcome out = new TaskPlanService.UpdateOutcome();
            out.step = step;
            out.message = "done";
            return out;
        });
        return plan;
    }

    private static String captureReconcile(ThrowingRunnable action, String conversationId) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(2);
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8) {
            @Override
            public void println(String x) {
                super.println(x);
                if (x != null && x.startsWith("[TaskReconcile]")
                        && x.contains("conversation=" + conversationId)) {
                    latch.countDown();
                }
            }
        };
        System.setOut(capture);
        try {
            action.run();
            latch.await(8, TimeUnit.SECONDS);
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(original);
            capture.close();
        }
    }

    private static boolean hasAlert(String out, String conversationId, String rule) {
        return out.lines().anyMatch(line -> line.startsWith("[TaskReconcile]")
                && line.contains("conversation=" + conversationId)
                && line.contains("rule=" + rule));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertImmutable(List<Message> sent) {
        assertThrows(UnsupportedOperationException.class, () -> sent.add(new UserMessage("不能加")));
    }

    static ConversationHistoryService historyMock() {
        ConversationHistoryService history = mock(ConversationHistoryService.class);
        when(history.getHistory(any(), any())).thenReturn(List.of(new MessageRecord("user", "早先的问题")));
        return history;
    }

    static ChatRequest request(String message) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        request.setConversationId("conv-1");
        request.setUseSystemModel(true);
        return request;
    }

    static MockHttpServletRequest httpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 1L);
        return request;
    }

    static ChatController controller(FakeModel fake, ConversationHistoryService history) {
        return controller(fake, history, planWithNoSteps());
    }

    /** 空库场景用：显式指定系统模型 Key（空串 = 系统模型不可用）。 */
    static ChatController controllerWithSystemKey(FakeModel fake, ConversationHistoryService history,
                                                  String systemKey) {
        return controller(fake, history, planWithNoSteps(), systemKey, true);
    }

    /** 同时控制“搜索 Key 是否已配置”，用来验证 webSearch 的下发条件。 */
    static ChatController controllerWithSystemKeyAndSearch(FakeModel fake, ConversationHistoryService history,
                                                           String systemKey, boolean searchConfigured) {
        return controller(fake, history, planWithNoSteps(), systemKey, searchConfigured);
    }

    private static TaskPlanService planWithNoSteps() {
        TaskPlanService plan = mock(TaskPlanService.class);
        when(plan.budget(any(), any(), anyInt())).thenReturn(5);
        when(plan.hasPlan(any(), any())).thenReturn(false);
        when(plan.allStepsDone(any(), any())).thenReturn(false);
        return plan;
    }

    static ChatController controller(FakeModel fake, ConversationHistoryService history,
                                             TaskPlanService plan) {
        return controller(fake, history, plan, "test-system-model-key", true);
    }

    static ChatController controller(FakeModel fake, ConversationHistoryService history,
                                     TaskPlanService plan, String systemApiKey) {
        return controller(fake, history, plan, systemApiKey, true);
    }

    static ChatController controller(FakeModel fake, ConversationHistoryService history,
                                     TaskPlanService plan, String systemApiKey, boolean searchConfigured) {
        ConversationService conversation = mock(ConversationService.class);
        AgentCancellationRegistry cancellation = spy(new AgentCancellationRegistry());

        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryAcquire(anyLong())).thenReturn(null);

        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.currentRoot(any(), any())).thenReturn(Paths.get("target/test-ws"));

        SubagentProperties subagentProperties = Mockito.mock(SubagentProperties.class,
                Mockito.RETURNS_DEEP_STUBS);
        PlanProperties planProperties = Mockito.mock(PlanProperties.class, Mockito.RETURNS_DEEP_STUBS);
        MemoryProperties memoryProperties = Mockito.mock(MemoryProperties.class, Mockito.RETURNS_DEEP_STUBS);
        UserMemoryProperties userMemoryProperties = Mockito.mock(UserMemoryProperties.class,
                Mockito.RETURNS_DEEP_STUBS);

        WorkspaceMemory workspaceMemory = mock(WorkspaceMemory.class);
        WorkspaceMemory.Snapshot snapshot = new WorkspaceMemory(memoryProperties).load(null);
        when(workspaceMemory.load(any())).thenReturn(snapshot);

        McpClientManager mcp = mock(McpClientManager.class);
        when(mcp.isEnabled()).thenReturn(false);
        when(mcp.callbacks(anyLong())).thenReturn(null);

        ContextCompactor compactor = new ContextCompactor(fake.model, 24000);

        PathSandbox sandbox = new PathSandbox("target/test-ws");
        PlanTool planTool = new PlanTool(plan);
        SubagentService subagentService = mock(SubagentService.class);
        CodeVerifier codeVerifier = mock(CodeVerifier.class);
        when(codeVerifier.verify(any())).thenReturn(CodeVerifier.Result.skipped("test"));

        ChatController controller = new ChatController(
                mock(ChatClient.class),
                fake.model,
                history,
                conversation,
                cancellation,
                plan,
                planTool,
                new PlanToolFlat(planTool),
                planProperties,
                new TerminalTool(sandbox),
                new FileTool(sandbox),
                mock(ToolCallLogService.class),
                rateLimiter,
                compactor,
                mock(ModelConfigService.class),
                mock(ChatModelFactory.class),
                workspaceService,
                codeVerifier,
                new WebSearchTool(searchMock(searchConfigured)),
                subagentService,
                new SubagentTool(subagentService),
                mock(SubagentTelemetry.class),
                subagentProperties,
                mock(com.example.jingjingai2.context.ContextAssetTool.class),
                new UserMemoryTool(mock(UserMemoryService.class)),
                userMemoryProperties,
                mock(UserMemoryInjector.class),
                mcp,
                mock(ApprovalService.class),
                new McpAdminTool(mock(McpServerTemplateCatalog.class),
                        mock(McpServerProvisionService.class), mock(McpServerConfigMapper.class)),
                workspaceMemory,
                new MemoryTool(workspaceMemory),
                memoryProperties,
                mock(MemoryConsolidator.class),
                mock(AgentTurnRecorder.class));
        controller.enableWorkingSetShadowForTest(24000);
        setSystemApiKey(controller, systemApiKey);
        return controller;
    }

    private static SearchService searchMock(boolean configured) {
        SearchService search = mock(SearchService.class);
        when(search.isConfigured()).thenReturn(configured);
        return search;
    }

    /** systemApiKey 是 @Value 字段；测试不走 Spring，用反射模拟“系统模型已配置/未配置”。 */
    static void setSystemApiKey(ChatController controller, String key) {
        try {
            java.lang.reflect.Field field = ChatController.class.getDeclaredField("systemApiKey");
            field.setAccessible(true);
            field.set(controller, key == null ? "" : key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class FakeModel {
        final OpenAiChatModel model = mock(OpenAiChatModel.class);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger streams = new AtomicInteger();
        final List<List<Message>> recorded = Collections.synchronizedList(new ArrayList<>());
        volatile AssistantMessage nonStreamingAnswer = new AssistantMessage("ok");
        volatile AssistantMessage streamingAnswer = new AssistantMessage("ok");
        final java.util.Deque<AssistantMessage> nonStreamingAnswers = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final java.util.Deque<AssistantMessage> streamingAnswers = new java.util.concurrent.ConcurrentLinkedDeque<>();

        FakeModel() {
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                recorded.add(List.copyOf(prompt.getInstructions()));
                calls.incrementAndGet();
                AssistantMessage answer = nonStreamingAnswers.pollFirst();
                return new ChatResponse(List.of(new Generation(answer == null ? nonStreamingAnswer : answer)));
            });
            when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                recorded.add(List.copyOf(prompt.getInstructions()));
                streams.incrementAndGet();
                AssistantMessage answer = streamingAnswers.pollFirst();
                return Flux.just(new ChatResponse(List.of(
                        new Generation(answer == null ? streamingAnswer : answer))));
            });
        }
    }
}
