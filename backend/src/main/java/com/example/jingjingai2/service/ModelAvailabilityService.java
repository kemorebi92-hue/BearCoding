package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.ModelConfig;
import com.example.jingjingai2.mapper.ModelConfigMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * “现在到底有没有可用模型”的唯一判定处。
 *
 * <p>两件事必须分开看：
 * <ul>
 *   <li><strong>系统模型</strong>：{@code spring.ai.openai.api-key} 是否真的配置了。
 *       {@code AiConfig} 在没配 key 时仍会创建一个指向 {@code http://127.0.0.1} 的占位
 *       ChatModel（为了满足依赖注入），它<strong>不是</strong>可用模型。</li>
 *   <li><strong>用户模型</strong>：当前用户在 {@code user_model_config} 里是否至少有一条。</li>
 * </ul>
 *
 * <p>两者都没有时，前端应显示“请先配置模型”并引导到 {@code /models}，
 * 后端聊天入口抛 {@link ModelNotConfiguredException}，绝不把请求发给占位地址。
 */
@Service
public class ModelAvailabilityService {

    private final ModelConfigMapper mapper;
    private final String systemApiKey;

    public ModelAvailabilityService(ModelConfigMapper mapper,
                                    @Value("${spring.ai.openai.api-key:}") String systemApiKey) {
        this.mapper = mapper;
        this.systemApiKey = systemApiKey == null ? "" : systemApiKey;
    }

    /** 系统模型是否真的可用（有非空 API Key）。 */
    public boolean systemModelAvailable() {
        return !systemApiKey.isBlank();
    }

    /** 当前用户是否至少保存过一个模型。 */
    public boolean hasUserModels(Long userId) {
        if (userId == null || mapper == null) return false;
        List<ModelConfig> models = mapper.findByUserId(userId);
        return models != null && !models.isEmpty();
    }

    /** 是否存在任一可用模型。 */
    public boolean anyModelAvailable(Long userId) {
        return systemModelAvailable() || hasUserModels(userId);
    }

    /** 给前端首屏引导用的状态。字段名与前端 utils/modelAvailability.js 对齐。 */
    public Status status(Long userId) {
        boolean system = systemModelAvailable();
        boolean user = hasUserModels(userId);
        return new Status(system, user, system || user);
    }

    public record Status(boolean systemModelAvailable, boolean hasUserModels, boolean modelConfigured) {}
}
