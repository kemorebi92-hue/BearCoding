package com.example.jingjingai2.config;

import com.example.jingjingai2.entity.ModelConfig;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * ChatModel 工厂：把一条用户自定义的 ModelConfig 在运行时动态构建成 OpenAiChatModel。
 *
 * 核心原理：国产大模型几乎都兼容 OpenAI 协议，所以无论 DeepSeek / 通义 / 智谱 / Kimi，
 * 只要用户填了 baseUrl + apiKey + modelName，都用同一个 OpenAiChatModel 接入，
 * 新增模型零代码改动。
 */
@Component
public class ChatModelFactory {

    public OpenAiChatModel build(ModelConfig cfg) {
        Endpoint endpoint = normalizeEndpoint(cfg.getBaseUrl());
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(requireText(cfg.getApiKey(), "apiKey cannot be null or empty"));
        if (endpoint.completionsPath() != null) {
            apiBuilder.completionsPath(endpoint.completionsPath());
        }
        OpenAiApi api = apiBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(requireText(cfg.getModelName(), "modelName cannot be null or empty"))
                        .build())
                .build();
    }

    Endpoint normalizeEndpoint(String configuredUrl) {
        String url = requireText(configuredUrl, "baseUrl cannot be null or empty");
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("接口地址格式不正确: " + configuredUrl, e);
        }
        if ((!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("接口地址必须是完整的 http/https URL: " + configuredUrl);
        }

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.isEmpty()) {
            return new Endpoint(url, null);
        }

        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String completionsPath = path.endsWith("/chat/completions")
                ? path
                : path + "/chat/completions";
        return new Endpoint(origin, completionsPath);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    record Endpoint(String baseUrl, String completionsPath) {}
}
