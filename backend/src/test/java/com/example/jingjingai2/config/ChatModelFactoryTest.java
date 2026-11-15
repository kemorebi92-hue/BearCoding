package com.example.jingjingai2.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatModelFactoryTest {

    private final ChatModelFactory factory = new ChatModelFactory();

    @Test
    void keepsBareHostForSpringAiDefaultV1Path() {
        ChatModelFactory.Endpoint endpoint = factory.normalizeEndpoint("https://api.deepseek.com/");

        assertEquals("https://api.deepseek.com", endpoint.baseUrl());
        assertNull(endpoint.completionsPath());
    }

    @Test
    void appendsChatCompletionsToProviderVersionPath() {
        ChatModelFactory.Endpoint kimi = factory.normalizeEndpoint("https://api.moonshot.cn/v1");
        ChatModelFactory.Endpoint qwen = factory.normalizeEndpoint(
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        ChatModelFactory.Endpoint glm = factory.normalizeEndpoint(
                "https://open.bigmodel.cn/api/paas/v4");

        assertEquals("https://api.moonshot.cn", kimi.baseUrl());
        assertEquals("/v1/chat/completions", kimi.completionsPath());
        assertEquals("/compatible-mode/v1/chat/completions", qwen.completionsPath());
        assertEquals("/api/paas/v4/chat/completions", glm.completionsPath());
    }

    @Test
    void acceptsFullChatCompletionsEndpoint() {
        ChatModelFactory.Endpoint endpoint = factory.normalizeEndpoint(
                "https://gateway.example.com/openai/v1/chat/completions");

        assertEquals("https://gateway.example.com", endpoint.baseUrl());
        assertEquals("/openai/v1/chat/completions", endpoint.completionsPath());
    }
}
