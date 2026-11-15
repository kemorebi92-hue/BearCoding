package com.example.jingjingai2.entity;

import java.time.LocalDateTime;

/**
 * 用户自定义模型配置实体（对应表 user_model_config）。
 * 一个用户可配置多个模型（一对多）。
 *
 * 安全要点：apiKey 字段在落库前由 ModelConfigService 加密，读出来也是密文；
 * 只有运行时构建 ChatModel 这一步才会解密，前端列表接口永远拿不到明文。
 */
public class ModelConfig {
    private Long id;
    private Long userId;
    private String provider;      // 厂商标识（deepseek / qwen / glm / kimi ...，仅展示用）
    private String baseUrl;       // 接口地址（用户填的“链接地址”）
    private String apiKey;        // 加密存储的 API 密钥
    private String modelName;     // 模型名（如 deepseek-chat / qwen-plus）
    /** 该模型的上下文窗口。空表示未知，调用方用保守值，不拿全局 100 万冒充。 */
    private Integer contextWindowTokens;
    private boolean defaultModel; // 是否为该用户的默认模型
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
