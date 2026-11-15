package com.example.jingjingai2.DTO;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 新增 / 更新模型配置时的请求体（前端表单提交）。
 */
public class ModelConfigRequest {
    @JsonAlias({"vendor", "manufacturer"})
    private String provider;   // 厂商（展示用）
    @JsonAlias({"base_url", "apiBaseUrl", "api_base_url", "endpoint"})
    private String baseUrl;    // 接口地址
    @JsonAlias("api_key")
    private String apiKey;     // API 密钥（明文传入，后端加密存储）
    @JsonAlias({"model_name", "model"})
    private String modelName;  // 模型名
    @JsonAlias({"context_window_tokens", "contextWindow"})
    private Integer contextWindowTokens;
    @JsonAlias({"default_model", "isDefault", "is_default"})
    private boolean defaultModel; // 是否设为默认

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
    public boolean isDefaultModel() { return defaultModel; }
    public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }
}
