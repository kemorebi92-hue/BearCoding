package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.entity.McpServerCredential;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpServerCredentialMapper;
import com.example.jingjingai2.mcp.McpCredentialCrypto;
import com.example.jingjingai2.mcp.McpCredentialException;
import org.springframework.stereotype.Service;

/**
 * 按用户和服务保存 MCP 凭证。用户 ID 必须由调用方从登录上下文传入。
 * 本类不提供 HTTP 接口，也不把密文放进配置实体。
 *
 * <p>删除服务时还没有调用这里。接入点是
 * {@code McpServerAdminController.delete} 在 {@code configs.delete} 成功之后、
 * 同一事务内，用该行的 id 调用 {@link #delete(long, long)}。本轮不改那段流程。
 */
@Service
public class McpCredentialService {

    private final McpServerConfigMapper configs;
    private final McpServerCredentialMapper credentials;
    private final McpCredentialCrypto crypto;

    public McpCredentialService(McpServerConfigMapper configs,
                                McpServerCredentialMapper credentials,
                                McpCredentialCrypto crypto) {
        this.configs = configs;
        this.credentials = credentials;
        this.crypto = crypto;
    }

    /** 保存或覆盖。同一用户同一服务只有一行。 */
    public void put(long trustedUserId, long serverConfigId, String token) {
        requireOwned(trustedUserId, serverConfigId);
        String ciphertext = crypto.encrypt(token);
        if (credentials.find(trustedUserId, serverConfigId) == null) {
            McpServerCredential row = new McpServerCredential();
            row.setUserId(trustedUserId);
            row.setServerConfigId(serverConfigId);
            row.setCiphertext(ciphertext);
            credentials.insert(row);
        } else {
            credentials.updateCiphertext(trustedUserId, serverConfigId, ciphertext);
        }
    }

    /** 只判断有没有行，不解密，也不把密文带出。 */
    public boolean configured(long trustedUserId, long serverConfigId) {
        requireOwned(trustedUserId, serverConfigId);
        return credentials.count(trustedUserId, serverConfigId) > 0;
    }

    /** 确认归属后解密。解不开就失败，不会把密文交回去。 */
    public String load(long trustedUserId, long serverConfigId) {
        requireOwned(trustedUserId, serverConfigId);
        McpServerCredential row = credentials.find(trustedUserId, serverConfigId);
        if (row == null || row.getCiphertext() == null) {
            throw new McpCredentialException("尚未设置凭证");
        }
        return crypto.decrypt(row.getCiphertext());
    }

    public void delete(long trustedUserId, long serverConfigId) {
        requireOwned(trustedUserId, serverConfigId);
        credentials.delete(trustedUserId, serverConfigId);
    }

    private McpServerConfig requireOwned(long trustedUserId, long serverConfigId) {
        if (trustedUserId <= 0) {
            throw new McpCredentialException("服务不存在");
        }
        McpServerConfig config = configs.findById(serverConfigId);
        if (config == null || config.getCreatedBy() == null || config.getCreatedBy() != trustedUserId) {
            throw new McpCredentialException("服务不存在");
        }
        return config;
    }
}
