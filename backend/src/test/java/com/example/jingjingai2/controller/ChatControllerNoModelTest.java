package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ChatRequest;
import com.example.jingjingai2.DTO.ChatResponse;
import com.example.jingjingai2.utils.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 空库首次启动：后端必须明确拒绝，而不是把请求发给 http://127.0.0.1 的占位模型。
 * 用现有 ChatEntryPromptViewTest 的测试装配，不连数据库、不调真实模型。
 */
class ChatControllerNoModelTest {

    @Test
    @DisplayName("useSystemModel=true 但系统 Key 为空：返回“请先配置模型”，不调用模型")
    void systemModelRequestRejectedWithoutKey() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        ChatController controller = ChatEntryPromptViewTest.controllerWithSystemKey(
                fake, ChatEntryPromptViewTest.historyMock(), "");

        ChatResponse response = controller.chat(
                ChatEntryPromptViewTest.request("你好"), ChatEntryPromptViewTest.httpRequest());

        assertTrue(response.getMessage().contains("请先"), response.getMessage());
        assertTrue(response.getMessage().contains("模型配置"), response.getMessage());
        assertEquals(0, fake.calls.get(), "未配置模型时不能发起模型调用");
    }

    @Test
    @DisplayName("未指定模型且没有默认模型、系统 Key 也为空：同样明确拒绝")
    void defaultModelRequestRejectedWithoutAnyModel() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        ChatController controller = ChatEntryPromptViewTest.controllerWithSystemKey(
                fake, ChatEntryPromptViewTest.historyMock(), "");
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");
        request.setConversationId("conv-1");
        request.setUseSystemModel(false);

        ChatResponse response = controller.chat(request, ChatEntryPromptViewTest.httpRequest());

        assertTrue(response.getMessage().contains("请先"), response.getMessage());
        assertEquals(0, fake.calls.get());
    }

    @Test
    @DisplayName("SSE 入口同样拒绝：HTTP 200 的 SSE 错误事件里是可读的配置指引")
    void streamRejectedWithoutKey() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        ChatController controller = ChatEntryPromptViewTest.controllerWithSystemKey(
                fake, ChatEntryPromptViewTest.historyMock(), "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> controller.chatStream(
                ChatEntryPromptViewTest.request("你好"), ChatEntryPromptViewTest.httpRequest(), response));

        assertEquals(0, fake.streams.get(), "未配置模型时不能发起流式模型调用");
    }

    @Test
    @DisplayName("没有搜索 Key 时 webSearch 不在工具清单里")
    void webSearchHiddenWithoutKey() throws Exception {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        ChatController controller = ChatEntryPromptViewTest.controllerWithSystemKeyAndSearch(
                fake, ChatEntryPromptViewTest.historyMock(), "test-system-model-key", false);

        Method method = ChatController.class.getDeclaredMethod("toolObjects");
        method.setAccessible(true);
        Object[] tools = (Object[]) method.invoke(controller);

        assertTrue(Arrays.stream(tools).noneMatch(tool -> tool instanceof WebSearchTool),
                "未配置搜索 Key 时不能把 webSearch 下发给模型");
    }
}
