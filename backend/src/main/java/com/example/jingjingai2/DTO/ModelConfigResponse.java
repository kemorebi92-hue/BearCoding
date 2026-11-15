package com.example.jingjingai2.DTO;

import java.time.LocalDateTime;

/**
 * 返回给前端的模型配置（永远不含明文密钥，只用脱敏占位）。
 */
public class ModelConfigResponse {
    private Long id;
    private String provider;
    private String baseUrl;
    private String modelName;
    private boolean defaultModel;
    private String apiKeyMasked; // 脱敏占位，如“已加密保存”
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public boolean isDefaultModel() { return defaultModel; }
    public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
