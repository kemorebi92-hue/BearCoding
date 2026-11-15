package com.example.jingjingai2.tool;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 给 Artifact 引用签名配置一个**用途专用子密钥**。
 *
 * <p>桌面模式已经通过 {@code app.mcp.desktop-key-material} 提供 32 字节 DPAPI 保护的
 * 稳定材料。这里不直接拿它当签名密钥，而是用 HMAC-SHA256(material, label) 派生一个
 * 只用于 Artifact 引用的子密钥；因此：
 * <ul>
 *   <li>不新增密钥文件、不改 DPAPI 架构；</li>
 *   <li>与 MCP 凭证加密使用的是同一份稳定材料，但密钥用途分离；</li>
 *   <li>应用重启后同一份材料派生出同一个子密钥，历史引用仍可验证。</li>
 * </ul>
 *
 * <p>没有稳定材料时（development、测试或非桌面启动）退回进程内随机密钥；重启后旧引用
 * 一律验证失败，按不可信处理。启动失败不阻塞应用：引用安全降级比整个 Host 起不来更好。
 */
@Component
public class ArtifactRefKeyProvider {

    /** 子密钥用途标签；改动它会让所有旧引用失效，不要随意改。 */
    static final String LABEL = "jingjing-artifact-ref-v1";

    public static final String PROPERTY = "app.artifact.key-material";
    private static final int MIN_MATERIAL_CHARS = 32;

    public ArtifactRefKeyProvider(Environment environment) {
        String material = environment == null ? null : environment.getProperty(PROPERTY);
        if (material == null || material.isBlank() || material.length() < MIN_MATERIAL_CHARS) {
            ToolArtifactRef.useProcessRandomKey("未配置桌面稳定密钥材料");
            return;
        }
        try {
            ToolArtifactRef.configure(derive(material));
            System.out.println("[ArtifactRef] 已配置稳定签名子密钥（用途 " + LABEL + "）");
        } catch (Exception e) {
            ToolArtifactRef.useProcessRandomKey("桌面稳定密钥材料无效");
        }
    }

    /** HMAC-SHA256(material, label)。material 不落日志、不写盘。 */
    static byte[] derive(String material) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(material.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(LABEL.getBytes(StandardCharsets.UTF_8));
    }
}
