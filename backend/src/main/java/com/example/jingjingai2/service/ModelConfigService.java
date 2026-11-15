package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.ModelConfigRequest;
import com.example.jingjingai2.DTO.ModelConfigResponse;
import com.example.jingjingai2.entity.ModelConfig;
import com.example.jingjingai2.mapper.ModelConfigMapper;
import com.example.jingjingai2.utils.CryptoUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户模型配置服务：负责 CRUD、默认模型互斥、密钥加密/解密。
 *
 * 安全约束：
 *  - 落库前加密 apiKey，列表接口只返回脱敏占位（apiKeyMasked）；
 *  - 只有 getDecrypted / getDefaultDecrypted 会在运行时解密，用于构建 ChatModel。
 */
@Service
public class ModelConfigService {

    private final ModelConfigMapper mapper;
    private final CryptoUtil crypto;

    public ModelConfigService(ModelConfigMapper mapper, CryptoUtil crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    /** 选中模型的窗口。没配或不是正数时返回 0，调用方用保守值，不用全局 100 万。 */
    public int contextWindowOf(Long userId, Long modelConfigId) {
        ModelConfig cfg = modelConfigId == null
                ? mapper.findDefault(userId)
                : mapper.findByIdAndUser(modelConfigId, userId);
        if (cfg == null || cfg.getContextWindowTokens() == null || cfg.getContextWindowTokens() <= 0) return 0;
        return cfg.getContextWindowTokens();
    }

    private static Integer positiveOrNull(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    /** 列出某用户的所有模型（密钥脱敏） */
    public List<ModelConfigResponse> listForUser(Long userId) {
        List<ModelConfigResponse> res = new ArrayList<>();
        for (ModelConfig c : mapper.findByUserId(userId)) {
            res.add(toResponse(c));
        }
        return res;
    }

    /** 新增模型：第一个模型自动设为默认 */
    @Transactional
    public ModelConfigResponse add(Long userId, ModelConfigRequest req) {
        boolean isFirst = mapper.findByUserId(userId).isEmpty();
        ModelConfig c = new ModelConfig();
        c.setUserId(userId);
        c.setProvider(requireText(req.getProvider(), "厂商名称不能为空"));
        c.setBaseUrl(requireText(req.getBaseUrl(), "接口地址不能为空"));
        c.setApiKey(crypto.encrypt(requireText(req.getApiKey(), "API 密钥不能为空")));
        c.setModelName(requireText(req.getModelName(), "模型名不能为空"));
        c.setContextWindowTokens(positiveOrNull(req.getContextWindowTokens()));
        c.setDefaultModel(isFirst || req.isDefaultModel());
        mapper.insert(c);
        if (req.isDefaultModel() && !isFirst) {
            mapper.clearDefaults(userId);
            mapper.setDefault(c.getId(), userId);
        }
        return toResponse(c);
    }

    /** 更新模型：只有传入了新密钥才覆盖原值 */
    @Transactional
    public ModelConfigResponse update(Long userId, Long id, ModelConfigRequest req) {
        ModelConfig c = mapper.findByIdAndUser(id, userId);
        if (c == null) throw new IllegalArgumentException("模型配置不存在");
        c.setProvider(requireText(req.getProvider(), "厂商名称不能为空"));
        c.setBaseUrl(requireText(req.getBaseUrl(), "接口地址不能为空"));
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            c.setApiKey(crypto.encrypt(req.getApiKey()));
        }
        c.setModelName(requireText(req.getModelName(), "模型名不能为空"));
        c.setContextWindowTokens(positiveOrNull(req.getContextWindowTokens()));
        mapper.update(c);
        if (req.isDefaultModel()) {
            mapper.clearDefaults(userId);
            mapper.setDefault(id, userId);
        }
        return toResponse(c);
    }

    /** 删除模型：若删掉的是默认且仍有剩余，把第一个补为默认 */
    @Transactional
    public void delete(Long userId, Long id) {
        mapper.delete(id, userId);
        if (mapper.findDefault(userId) == null) {
            List<ModelConfig> remaining = mapper.findByUserId(userId);
            if (!remaining.isEmpty()) {
                mapper.setDefault(remaining.get(0).getId(), userId);
            }
        }
    }

    /** 设为默认：清空其他再置位（保证单默认） */
    @Transactional
    public void setDefault(Long userId, Long id) {
        if (mapper.findByIdAndUser(id, userId) == null) {
            throw new IllegalArgumentException("模型配置不存在");
        }
        mapper.clearDefaults(userId);
        mapper.setDefault(id, userId);
    }

    /** 运行时取已解密配置（按 id） */
    public ModelConfig getDecrypted(Long id, Long userId) {
        ModelConfig c = mapper.findByIdAndUser(id, userId);
        if (c == null) return null;
        c.setApiKey(crypto.decrypt(c.getApiKey()));
        return c;
    }

    /** 运行时取已解密默认配置 */
    public ModelConfig getDefaultDecrypted(Long userId) {
        ModelConfig c = mapper.findDefault(userId);
        if (c == null) return null;
        c.setApiKey(crypto.decrypt(c.getApiKey()));
        return c;
    }

    private ModelConfigResponse toResponse(ModelConfig c) {
        ModelConfigResponse r = new ModelConfigResponse();
        r.setId(c.getId());
        r.setProvider(c.getProvider());
        r.setBaseUrl(c.getBaseUrl());
        r.setModelName(c.getModelName());
        r.setDefaultModel(c.isDefaultModel());
        r.setApiKeyMasked("已加密保存");
        r.setCreatedAt(c.getCreatedAt());
        return r;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
