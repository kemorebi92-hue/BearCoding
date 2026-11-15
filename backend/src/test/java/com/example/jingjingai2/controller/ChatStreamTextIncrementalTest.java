package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.example.jingjingai2.controller.ChatEntryPromptViewTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.getField;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 回归：/ai/stream 必须"逐段"把正文推给前端，而不是攒到最后一次性发一大段。
 *
 * <p>2026-11-10 用户报的现象是"回复不是逐段输出，而是一次性冒出一大段"。根因在服务端：
 * 主循环只把分片 append 进 roundText，收尾时再把整段 finalText 发一次，所以浏览器只收到
 * 一个 text 事件。这两个用例分别盯住「主循环正文」和「收尾轮最终回答」两条路径。</p>
 */
class ChatStreamTextIncrementalTest {
    @TempDir Path workspace;

    @Test void mainRoundTextIsPushedPerChunkAndNotRepeatedAtTheEnd() throws Exception {
        FakeModel fake = new FakeModel();
        when(fake.model.stream(any(Prompt.class))).thenReturn(Flux.just(
                textChunk("逐"), textChunk("段"), textChunk("输出")));

        String events = streamEvents(isolated(fake));

        // 三个分片 → 三个独立的 text 事件（逐个到达才是"逐段输出"）
        assertEquals(3, occurrences(events, "\"type\":\"text\""), events);
        assertTrue(events.contains("\"content\":\"逐\""), events);
        assertTrue(events.contains("\"content\":\"段\""), events);
        assertTrue(events.contains("\"content\":\"输出\""), events);
        // 收尾不得把整段再发一遍：拼起来的整段正文一次都不该作为单独事件出现
        assertEquals(0, occurrences(events, "逐段输出"), "收尾重复发送了整段正文：" + events);
    }

    @Test void wrapUpAnswerIsAlsoPushedPerChunk() throws Exception {
        FakeModel fake = new FakeModel();
        TaskPlanService plans = planService(fake);
        createPlan(plans, "分析问题");
        when(fake.model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(toolChunk(call("updatePlanStep",
                        "{\"stepId\":\"s1\",\"status\":\"done\",\"actualResult\":\"分析完成\"}"))))
                .thenReturn(Flux.just(textChunk("分析"), textChunk("正文"), textChunk("。")));

        String events = streamEvents(isolated(fake, plans));

        assertTrue(events.contains("\"content\":\"分析\""), events);
        assertTrue(events.contains("\"content\":\"正文\""), events);
        assertTrue(events.contains("\"content\":\"。\""), events);
        assertEquals(0, occurrences(events, "分析正文。"), "收尾轮重复发送了整段正文：" + events);
    }

    @Test void unsentTailOnlyCarriesWhatWasNotStreamed() {
        // 正常收尾：整段已经逐段推过 → 没有要补的
        assertEquals("", ChatController.unsentTail("正文", "正文"));
        // 正文后面追加了说明（审批阻塞提示等）→ 只补追加的那一段
        assertEquals("（补充说明）", ChatController.unsentTail("正文（补充说明）", "正文"));
        // 正文被整段替换（停止 / 校验失败 / 兜底）→ 整段补发
        assertEquals("整段兜底", ChatController.unsentTail("整段兜底", "正文"));
        // 没有任何分片被推过（强制轮、非流式兜底）→ 整段发
        assertEquals("正文", ChatController.unsentTail("正文", ""));
        assertEquals("", ChatController.unsentTail("", "正文"));
    }

    // ==================== 测试脚手架 ====================

    private ChatController isolated(FakeModel fake) {
        return isolated(fake, planService(fake));
    }

    private ChatController isolated(FakeModel fake, TaskPlanService plans) {
        ChatController chat = controller(fake, historyMock(), plans);
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
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) count++;
        return count;
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

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text, Map.of(), List.of()))));
    }

    private static ChatResponse toolChunk(AssistantMessage.ToolCall call) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(call)))));
    }

    private static AssistantMessage.ToolCall call(String name, String args) {
        return new AssistantMessage.ToolCall(name, "function", name, args);
    }
}
