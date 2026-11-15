package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.agent.*;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.permission.*;
import com.example.jingjingai2.service.*;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.example.jingjingai2.controller.ChatEntryPromptViewTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class Phase31R1EntryRegressionTest {
    @TempDir Path workspace;

    /**
     * 本类验证的是「入口收尾 / 计划交付 / 停止与断线」这些编排语义，不是审批。
     *
     * <p>以前这些用例靠「没有安装网关 → 工具直接执行」跑起来，那正是本轮关掉的 fail-open。
     * 现在统一显式声明「本环境授予全部权限」（{@link AllowAllGate}），
     * 真正验证审批的两个用例会在自己的方法体里换成真实网关。
     */
    @BeforeEach void allowTools() { AllowAllGate.install(); }

    @AfterEach void clearGate() { AllowAllGate.uninstall(); }

    @Test void askPreventsLaterChildFromEnteringTheRealPool() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(batch(call("writeFile", "{\"path\":\"blocked.md\",\"content\":\"x\"}"),
                call("spawnSubagent", "{\"goal\":\"Read README.md\",\"mode\":\"explore\"}")));
        fake.nonStreamingAnswer = new AssistantMessage("当前可提供正文草稿。");
        ChatController chat = isolated(fake, historyMock(), planService(fake));
        SubagentProperties properties = new SubagentProperties();
        properties.setEnabled(true);
        SubagentRunner runner = mock(SubagentRunner.class);
        SubagentPool pool = new SubagentPool(1, null);
        SubagentService children = new SubagentService(properties, runner, pool, mock(SubagentMemory.class),
                mock(SubagentSessions.class), mock(SubagentTelemetry.class));
        setField(chat, "subagentProperties", properties);
        setField(chat, "subagentService", children);
        installGate(new ApprovalService(1));
        try {
            var result = chat.chat(request("写文件然后派子代理"), httpRequest());
            assertEquals("APPROVAL_BLOCKED", result.getExecution().get("endReason"));
            verifyNoInteractions(runner);
            assertEquals(1, pool.availablePermits());
            assertTrue(Files.notExists(workspace.resolve("blocked.md")));
        } finally {
            pool.destroy();
        }
    }

    @Test void streamRejectionExpiryAndCancellationStopLaterSpawns() throws Exception {
        for (String terminal : List.of("REJECT", "EXPIRE", "CANCEL")) {
            FakeModel fake = new FakeModel();
            fake.streamingAnswers.add(batch(call("writeFile", "{\"path\":\"blocked.md\",\"content\":\"x\"}"),
                    call("spawnSubagent", "{\"goal\":\"Read README.md\",\"mode\":\"explore\"}")));
            fake.streamingAnswer = new AssistantMessage("当前可提供正文草稿。");
            var history = historyMock();
            ChatController chat = isolated(fake, history, planService(fake));
            SubagentProperties properties = (SubagentProperties) getField(chat, "subagentProperties");
            when(properties.isEnabled()).thenReturn(true);
            SubagentService children = (SubagentService) getField(chat, "subagentService");
            ApprovalService approvals = new ApprovalService(terminal.equals("EXPIRE") ? 1 : 5);
            setField(chat, "approvalService", approvals);
            installGate(approvals);
            chat.chatStream(request("写文件然后派子代理"), httpRequest(), new MockHttpServletResponse());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (approvals.pending().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            var pending = approvals.pending().stream().findFirst().orElseThrow();
            if (terminal.equals("REJECT")) approvals.decide(pending.id, 1L, "REJECT");
            if (terminal.equals("CANCEL")) {
                assertTrue(registry(chat).cancel(1L, "conv-1"));
                approvals.cancelConversation(1L, "conv-1");
            }
            verify(history, timeout(6000)).addMessage(any(), any(), argThat(m -> "assistant".equals(m.getRole())));
            verify(children, never()).runBatch(any(), any(), any(), any());
            assertTrue(Files.notExists(workspace.resolve("blocked.md")));
            assertTrue(approvals.pending().isEmpty());
        }
    }

    @Test void naturalFakeToolTextFallsBackInBothEntries() throws Exception {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(new AssistantMessage("<tool_call>writeFile</tool_call>"));
        fake.nonStreamingAnswer = new AssistantMessage("这里是可用正文。");
        var sync = isolated(fake, historyMock(), planService(fake)).chat(request("提供正文"), httpRequest());
        assertEquals("这里是可用正文。", sync.getMessage());
        assertEquals(2, fake.calls.get());

        FakeModel stream = new FakeModel();
        stream.streamingAnswers.add(new AssistantMessage("<tool_call>writeFile</tool_call>"));
        stream.streamingAnswer = new AssistantMessage("这里是流式正文。");
        String events = streamEvents(isolated(stream, historyMock(), planService(stream)));
        assertTrue(events.contains("这里是流式正文。"), events);
        assertFalse(events.contains("<tool_call>"));
        assertEquals(2, stream.streams.get());
    }

    @Test void directorySuccessCannotValidateAnUnwrittenFileClaim() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(batch(call("createDirectory", "{\"path\":\"test-dir\"}")));
        fake.nonStreamingAnswer = new AssistantMessage("已写入 missing.md；未写入 another.md。");
        var result = isolated(fake, historyMock(), planService(fake)).chat(request("写入文件"), httpRequest());
        assertTrue(Files.isDirectory(workspace.resolve("test-dir")));
        assertEquals("INCOMPLETE", result.getExecution().get("deliveryStatus"));
        assertFalse(result.getMessage().contains("已写入 missing.md"));
    }

    @Test void stopDuringReservedCallWinsAndConcurrentRequestCannotReplaceIt() throws Exception {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(batch(done()));
        TaskPlanService plans = planService(fake);
        TaskPlan plan = createPlan(plans, "分析问题");
        var history = historyMock();
        ChatController chat = isolated(fake, history, plans);
        AgentCancellationRegistry cancellation = registry(chat);
        CountDownLatch inFinal = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        when(fake.model.call(any(Prompt.class))).thenReturn(model(batch(done()))).thenAnswer(invocation -> {
            inFinal.countDown();
            assertTrue(releaseFinal.await(5, TimeUnit.SECONDS));
            return model(new AssistantMessage("未经确认的最终正文"));
        });
        CompletableFuture<com.example.jingjingai2.DTO.ChatResponse> running =
                CompletableFuture.supplyAsync(() -> chat.chat(request("分析问题"), httpRequest()));
        try {
            assertTrue(inFinal.await(5, TimeUnit.SECONDS));
            assertTrue(chat.chat(request("另一项任务"), httpRequest()).getMessage().contains("正在执行"));
            assertTrue(cancellation.cancel(1L, "conv-1"));
        } finally {
            releaseFinal.countDown();
        }
        var result = running.get(5, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.getExecution().get("endReason"));
        assertEquals("INCOMPLETE", result.getExecution().get("deliveryStatus"));
        assertFalse(result.getMessage().contains("未经确认的最终正文"));
        assertFalse(plan.isDeliveryReturned());
        assertSame(plan, plans.get(1L, "conv-1"));
    }

    @Test void oldDonePlanIsNotClearedByUnrelatedReply() {
        FakeModel fake = new FakeModel();
        TaskPlanService plans = planService(fake);
        TaskPlan old = createPlan(plans, "分析问题");
        plans.updateStep(1L, "conv-1", "s1", "done", "分析完成");
        fake.nonStreamingAnswer = new AssistantMessage("你好，欢迎回来。");
        isolated(fake, historyMock(), plans).chat(request("你好"), httpRequest());
        assertFalse(old.isDeliveryReturned());
        assertFalse(plans.resetIfFinished(1L, "conv-1"));
    }

    @Test void streamedDoneNeedsClientReceiptAndCannotAcknowledgeReplacement() throws Exception {
        FakeModel fake = new FakeModel();
        TaskPlanService plans = planService(fake);
        TaskPlan delivered = createPlan(plans, "分析问题");
        fake.streamingAnswers.add(batch(done()));
        fake.streamingAnswer = new AssistantMessage("分析正文。");
        ChatController chat = isolated(fake, historyMock(), plans);
        String events = streamEvents(chat);
        assertTrue(events.contains("分析正文。"), events);
        assertTrue(events.contains("event:done"));
        assertFalse(delivered.isDeliveryReturned(), "server-side send does not confirm client receipt");
        assertFalse(plans.resetIfFinished(1L, "conv-1"));

        String receipt = new ObjectMapper().readTree(events.lines().filter(s -> s.startsWith("data:"))
                .map(s -> s.substring(5)).filter(s -> s.contains("\"type\":\"done\"")).findFirst().orElseThrow())
                .path("deliveryReceipt").asText();
        assertFalse(receipt.isBlank());
        var mvc = MockMvcBuilders.standaloneSetup(chat).build();
        String body = new ObjectMapper().writeValueAsString(Map.of("conversationId", "conv-1", "receipt", receipt));
        assertEquals("{\"accepted\":false}", mvc.perform(post("/ai/delivery/ack").requestAttr("userId", 2L)
                .contentType("application/json").content(body)).andReturn().getResponse().getContentAsString());
        assertEquals("{\"accepted\":true}", mvc.perform(post("/ai/delivery/ack").requestAttr("userId", 1L)
                .contentType("application/json").content(body)).andReturn().getResponse().getContentAsString());
        assertTrue(delivered.isDeliveryReturned());

        TaskPlan replacement = createPlan(plans, "另一个任务");
        plans.updateStep(1L, "conv-1", "s1", "done", "另一任务完成");
        assertEquals("{\"accepted\":false}", mvc.perform(post("/ai/delivery/ack").requestAttr("userId", 1L)
                .contentType("application/json").content(body)).andReturn().getResponse().getContentAsString());
        assertFalse(replacement.isDeliveryReturned());
    }

    @Test void disconnectDuringFinalStreamCancelsSubscriptionAndRetainsPlan() throws Exception {
        FakeModel fake = new FakeModel();
        TaskPlanService plans = planService(fake);
        TaskPlan plan = createPlan(plans, "分析问题");
        var history = historyMock();
        ChatController chat = isolated(fake, history, plans);
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch unsubscribed = new CountDownLatch(1);
        when(fake.model.stream(any(Prompt.class))).thenReturn(Flux.just(model(batch(done()))))
                .thenReturn(Flux.<ChatResponse>never().doOnSubscribe(subscription -> subscribed.countDown())
                        .doOnCancel(unsubscribed::countDown));
        var emitter = chat.chatStream(request("分析问题"), httpRequest(), new MockHttpServletResponse());
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        // Simulate the servlet invoking the emitter's registered error callback.
        @SuppressWarnings("unchecked")
        var errorCallback = (java.util.function.Consumer<Throwable>) getField(emitter, "errorCallback");
        assertNotNull(errorCallback);
        errorCallback.accept(new java.io.IOException("client disconnected"));
        assertTrue(unsubscribed.await(5, TimeUnit.SECONDS));
        verify(history, timeout(5000)).addMessage(any(), any(), argThat(m ->
                "assistant".equals(m.getRole()) && m.getContent().contains("用户已停止")));
        assertFalse(plan.isDeliveryReturned());
    }

    @Test void planDoneCannotSkipCompileAndCannotDeliverWhenCompilationFails() throws Exception {
        FakeModel fake = new FakeModel();
        TaskPlanService plans = planService(fake);
        createPlan(plans, "写入 Hello.java");
        fake.nonStreamingAnswers.add(batch(call("writeFile",
                "{\"path\":\"Hello.java\",\"content\":\"invalid java\"}"),
                call("updatePlanStep", "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"已写入 Hello.java\"}")));
        fake.nonStreamingAnswer = new AssistantMessage("已写入 Hello.java。");
        ChatController chat = isolated(fake, historyMock(), plans);
        CodeVerifier verifier = (CodeVerifier) getField(chat, "codeVerifier");
        when(verifier.verify(workspace)).thenReturn(new CodeVerifier.Result(true, false, "编译失败"));
        var result = chat.chat(request("写 Java"), httpRequest());
        assertTrue(Files.exists(workspace.resolve("Hello.java")));
        verify(verifier).verify(workspace);
        assertEquals(1, fake.calls.get());
        assertEquals("VERIFY_FAILED", result.getExecution().get("endReason"));
        assertEquals("INCOMPLETE", result.getExecution().get("deliveryStatus"));
    }

    @Test void movingOrDeletingWrittenFileInSameBatchReopensStep() throws Exception {
        for (String change : List.of("movePath", "deletePath")) {
            FakeModel fake = new FakeModel();
            TaskPlanService plans = planService(fake);
            TaskPlan plan = createPlan(plans, "写入 report.md");
            fake.nonStreamingAnswers.add(batch(call("writeFile", "{\"path\":\"report.md\",\"content\":\"ok\"}"),
                    call("updatePlanStep", "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"已写入 report.md\"}"),
                    call(change, change.equals("movePath")
                            ? "{\"from\":\"./report.md\",\"to\":\"archived.md\"}" : "{\"path\":\"./report.md\"}")));
            fake.nonStreamingAnswer = new AssistantMessage("report.md 当前不存在。");
            isolated(fake, historyMock(), plans).chat(request("写入并验证文件"), httpRequest());
            assertTrue(Files.notExists(workspace.resolve("report.md")));
            assertEquals("pending", plan.getSteps().get(0).getStatus(), change);
            assertFalse(plan.getSteps().get(0).isResultVerified(), change);
            assertFalse(plan.isDeliveryReturned(), change);
        }
    }

    @Test void stopWithChangedFilesDoesNotStartCompile() throws Exception {
        FakeModel fake = new FakeModel();
        TaskPlanService plans = planService(fake);
        var history = historyMock();
        ChatController chat = isolated(fake, history, plans);
        CountDownLatch secondRound = new CountDownLatch(1);
        when(fake.model.stream(any(Prompt.class))).thenReturn(Flux.just(model(batch(
                call("writeFile", "{\"path\":\"Hello.java\",\"content\":\"class Hello{}\"}")))))
                .thenReturn(Flux.<ChatResponse>never().doOnSubscribe(subscription -> secondRound.countDown()));
        chat.chatStream(request("写 Java"), httpRequest(), new MockHttpServletResponse());
        assertTrue(secondRound.await(5, TimeUnit.SECONDS));
        assertTrue(registry(chat).cancel(1L, "conv-1"));
        verify(history, timeout(5000)).addMessage(any(), any(), argThat(m ->
                "assistant".equals(m.getRole()) && m.getContent().contains("用户已停止")));
        verifyNoInteractions((CodeVerifier) getField(chat, "codeVerifier"));
        assertTrue(Files.exists(workspace.resolve("Hello.java")));
    }

    private ChatController isolated(FakeModel fake, ConversationHistoryService history, TaskPlanService plans) {
        ChatController chat = controller(fake, history, plans);
        WorkspaceService roots = (WorkspaceService) getField(chat, "workspaceService");
        when(roots.currentRoot(any(), any())).thenReturn(workspace);
        return chat;
    }

    private static String streamEvents(ChatController chat) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(chat).build();
        var result = mvc.perform(post("/ai/stream").requestAttr("userId", 1L)
                .contentType("application/json")
                .content("{\"message\":\"分析问题\",\"conversationId\":\"conv-1\",\"useSystemModel\":true}"))
                .andReturn();
        result.getAsyncResult(5000);
        byte[] bytes = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsByteArray();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static AgentCancellationRegistry registry(ChatController chat) {
        return (AgentCancellationRegistry) getField(chat, "cancellationRegistry");
    }

    private static void installGate(ApprovalService approvals) {
        ToolDispatcher.installGate(new ToolPermissionGate(new PermissionPolicy(new PermissionProperties()),
                approvals, new McpProperties()));
    }

    private static TaskPlanService planService(FakeModel fake) {
        ConversationMapper mapper = mock(ConversationMapper.class);
        when(mapper.updatePlanJson(anyLong(), anyString(), any())).thenReturn(1);
        return new TaskPlanService(mapper, fake.model);
    }

    private static TaskPlan createPlan(TaskPlanService service, String goal) {
        StepInput step = new StepInput();
        step.setDescription(goal);
        return service.create(1L, "conv-1", List.of(step));
    }

    private static AssistantMessage.ToolCall done() {
        return call("updatePlanStep", "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"分析完成\"}");
    }

    private static AssistantMessage.ToolCall call(String name, String args) {
        return new AssistantMessage.ToolCall(name, "function", name, args);
    }

    private static AssistantMessage batch(AssistantMessage.ToolCall... calls) {
        return new AssistantMessage("", Map.of(), List.of(calls));
    }

    private static ChatResponse model(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
