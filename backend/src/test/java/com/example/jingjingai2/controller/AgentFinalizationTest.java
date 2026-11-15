package com.example.jingjingai2.controller;

import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.permission.*;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import java.util.Map;
import static com.example.jingjingai2.controller.ChatEntryPromptViewTest.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentFinalizationTest {
    @AfterEach void clean() { ToolDispatcher.installGate(null); }
    private static TaskPlanService plan(int limit) {
        TaskPlanService plan = mock(TaskPlanService.class);
        when(plan.budget(any(), any(), anyInt())).thenReturn(limit);
        return plan;
    }
    private static AssistantMessage tool(String id, String name, String args) {
        return new AssistantMessage("准备执行，不是交付", Map.of(), List.of(new AssistantMessage.ToolCall(id, "function", name, args)));
    }
    @Test void fourthCallIsReservedAndReturnsActualBody() {
        FakeModel fake = new FakeModel();
        for (int i = 0; i < 3; i++) fake.nonStreamingAnswers.add(tool("c"+i, "listDirectory", "{\"path\":\"missing"+i+"\"}"));
        fake.nonStreamingAnswer = new AssistantMessage("最终正文：已有资料的完整说明。未创建文件。");
        var response = controller(fake, historyMock(), plan(4)).chat(request("提供说明"), httpRequest());
        assertEquals(4, fake.calls.get());
        assertTrue(response.getMessage().startsWith("最终正文"));
        assertEquals("MODEL_CALL_RESERVE", response.getExecution().get("endReason"));
        assertEquals(3, response.getExecution().get("modelRounds"));
        assertEquals(4, response.getExecution().get("modelCalls"));
        assertEquals(3, response.getExecution().get("toolCalls"));
    }
    @Test void askBlocksBatchAndFinalAnswerCanDeliverText() {
        ApprovalService approvals = new ApprovalService(1);
        ToolDispatcher.installGate(new ToolPermissionGate(new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties()));
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(new AssistantMessage("准备保存", Map.of(), List.of(
                new AssistantMessage.ToolCall("w1", "function", "writeFile", "{\"path\":\"never-write.md\",\"content\":\"x\"}"),
                new AssistantMessage.ToolCall("w2", "function", "writeFile", "{\"path\":\"never-write.md\",\"content\":\"x\"}"),
                new AssistantMessage.ToolCall("p1", "function", "updatePlanStep", "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"已写入\"}"))));
        fake.nonStreamingAnswer = new AssistantMessage("说明正文：这里是可用的文字草稿，文件没有写入。");
        TaskPlanService plan = plan(4);
        var response = controller(fake, historyMock(), plan).chat(request("说明"), httpRequest());
        assertEquals(2, fake.calls.get());
        assertTrue(response.getMessage().contains("说明正文"));
        assertTrue(response.getToolCalls().stream().noneMatch(r -> r.isSuccess()));
        assertTrue(response.getToolCalls().get(0).getResult().contains("\"executed\":false"));
        assertTrue(approvals.pending().isEmpty());
        verify(plan, never()).updateStep(any(), any(), any(), any(), any());
        assertEquals("APPROVAL_BLOCKED", response.getExecution().get("endReason"));
    }
    @Test void naturalStopHasNoAdditionalModelCall() {
        FakeModel fake = new FakeModel(); fake.nonStreamingAnswer = new AssistantMessage("完整正文");
        var response = controller(fake, historyMock(), plan(4)).chat(request("说明"), httpRequest());
        assertEquals(1, fake.calls.get()); assertEquals("NORMAL_STOP", response.getExecution().get("endReason"));
    }
    @Test void n1tOrderThroughRealPlanToolAndPermissionGateCannotMarkWritten() {
        ApprovalService approvals = new ApprovalService(1);
        ToolDispatcher.installGate(new ToolPermissionGate(new PermissionPolicy(new PermissionProperties()), approvals, new McpProperties()));
        FakeModel fake = new FakeModel();
        TaskPlanService plan = new TaskPlanService(mock(com.example.jingjingai2.mapper.ConversationMapper.class), fake.model);
        com.example.jingjingai2.DTO.StepInput input = new com.example.jingjingai2.DTO.StepInput();
        input.setDescription("写入 DELIVERY.md"); input.setExpected("写入 DELIVERY.md");
        var state = plan.create(1L, "conv-1", List.of(input));
        fake.nonStreamingAnswers.add(new AssistantMessage("我准备写文件", Map.of(), List.of(
                new AssistantMessage.ToolCall("p", "function", "updatePlanStep", "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"已写入 DELIVERY.md\"}"),
                new AssistantMessage.ToolCall("w", "function", "writeFile", "{\"path\":\"DELIVERY.md\",\"content\":\"内容\"}"))));
        fake.nonStreamingAnswer = new AssistantMessage("交付正文：完整的说明。文件没有写入。");
        var response = controller(fake, historyMock(), plan).chat(request("提供说明"), httpRequest());
        assertEquals("pending", state.getSteps().get(0).getStatus());
        assertEquals(0, state.getSteps().get(0).getFailCount());
        assertFalse(state.getSteps().get(0).isResultVerified());
        assertFalse(response.getToolCalls().get(1).isSuccess());
        assertTrue(response.getMessage().contains("交付正文"));
        assertEquals(2, fake.calls.get());
    }
    @Test void finalPromiseOrToolCallCannotBeAcceptedAsDelivery() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(tool("c", "listDirectory", "{}"));
        fake.nonStreamingAnswer = new AssistantMessage("我会改为直接给出完整正文");
        TaskPlanService plan = plan(2);
        var response = controller(fake, historyMock(), plan).chat(request("说明"), httpRequest());
        assertEquals(2, fake.calls.get()); assertEquals("INCOMPLETE", response.getExecution().get("deliveryStatus"));
        assertFalse(response.getMessage().contains("我会"));
        FakeModel other = new FakeModel(); other.nonStreamingAnswer = tool("x", "writeFile", "{}");
        var refused = controller(other, historyMock(), plan(1)).chat(request("说明"), httpRequest());
        assertEquals(1, other.calls.get()); assertEquals("INCOMPLETE", refused.getExecution().get("deliveryStatus"));
        assertNull(refused.getToolCalls());
    }
    @Test void planDoneWithEmptyFinalAnswerIsNotDeliverySuccess() {
        FakeModel fake = new FakeModel();
        fake.nonStreamingAnswers.add(tool("c", "listDirectory", "{}"));
        fake.nonStreamingAnswer = new AssistantMessage("");
        TaskPlanService plan = plan(4); when(plan.allStepsDone(any(), any())).thenReturn(true);
        var response = controller(fake, historyMock(), plan).chat(request("说明"), httpRequest());
        assertEquals(2, fake.calls.get());
        assertTrue(response.getMessage().contains("未确认完整交付"));
        assertFalse(response.getMessage().contains("准备执行"));
        assertEquals("INCOMPLETE", response.getExecution().get("deliveryStatus"));
        assertEquals("PLAN_REPORTED_DONE", response.getExecution().get("endReason"));
    }
    @Test void cancellationWinsOverReservedFinalCallAndInputLimitIsHard() {
        FakeModel fake = new FakeModel();
        ChatController controller = controller(fake, historyMock(), plan(1));
        var cancellation = (com.example.jingjingai2.service.AgentCancellationRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(controller, "cancellationRegistry");
        when(cancellation.isCancelled(anyLong(), anyString())).thenReturn(true);
        var stopped = controller.chat(request("说明"), httpRequest());
        assertEquals(0, fake.calls.get());
        assertEquals("CANCELLED", stopped.getExecution().get("endReason"));
        ChatController limited = controller(fake, historyMock(), plan(4));
        org.springframework.test.util.ReflectionTestUtils.setField(limited, "taskInputTokenBudget", 1L);
        var response = limited.chat(request("说明"), httpRequest());
        assertEquals(0, fake.calls.get());
        assertEquals("INCOMPLETE", response.getExecution().get("deliveryStatus"));
        assertTrue(response.getMessage().contains("预算"));
    }
    @Test void streamingAlsoUsesReservedCallAndPersistsFinalBody() {
        FakeModel fake = new FakeModel();
        for (int i=0;i<3;i++) fake.streamingAnswers.add(tool("c"+i,"listDirectory","{\"path\":\"missing"+i+"\"}"));
        fake.streamingAnswer = new AssistantMessage("最终完整正文");
        var history = historyMock();
        controller(fake, history, plan(4)).chatStream(request("说明"), httpRequest(), new MockHttpServletResponse());
        verify(history, timeout(8000)).addMessage(any(),any(),argThat(m -> "assistant".equals(m.getRole()) && "最终完整正文".equals(m.getContent())));
        assertEquals(4, fake.streams.get());
        assertEquals(0, fake.calls.get());
    }
}
