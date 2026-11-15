package com.example.jingjingai2.mcp;

import com.example.jingjingai2.security.DesktopMcpKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * MCP 凭证专用加密。不调用 {@code CryptoUtil}，也不使用它的环境变量或开发默认口令。
 *
 * <p>主密钥名是 {@code MCP_CREDENTIAL_KEY}。优先用操作系统环境变量，否则读
 * Spring 配置（包括 {@code application-local.yml}）。未设置、空白或短于 32 个字符时，
 * 加密和解密都失败。Bean 仍可创建，以免没配密钥时整个 Host 起不来。
 *
 * <p>密文格式：{@code v1.} + Base64(12 字节随机 nonce || AES-GCM 密文和 128 位标签)。
 * 明文不会写入日志。
 */
@Component
public class McpCredentialCrypto {

    public static final String KEY_ENV = "MCP_CREDENTIAL_KEY";
    public static final int MIN_KEY_CHARS = 32;
    static final String PREFIX = "v1.";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private volatile byte[] key;

    /** 先看操作系统环境变量。没配时 Spring 会再调 {@link #setEnvironment} 读本地配置。 */
    public McpCredentialCrypto() {
        this(System.getenv(KEY_ENV));
    }

    /** 测试传入材料。null、空白或过短都视为未配置，不生成密钥。 */
    public McpCredentialCrypto(String keyMaterial) {
        this.key = derive(keyMaterial);
    }

    /** 桌面模式的材料优先于环境变量。development 没有该属性，仍走原来的来源。 */
    @Autowired(required = false)
    public void setEnvironment(Environment environment) {
        if (environment == null) return;
        String desktop = environment.getProperty(DesktopMcpKey.PROPERTY);
        if (desktop != null && !desktop.isBlank()) {
            byte[] derived = derive(desktop);
            if (derived == null) {
                throw new McpCredentialException("桌面模式 MCP 密钥无效");
            }
            this.key = derived;
            return;
        }
        if (this.key != null) return;
        this.key = derive(environment.getProperty(KEY_ENV));
    }

    private static byte[] derive(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank() || keyMaterial.length() < MIN_KEY_CHARS) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new McpCredentialException("MCP 凭证密钥初始化失败");
        }
    }

    public boolean configured() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (!configured()) {
            throw new McpCredentialException("未配置 " + KEY_ENV + "，不能保存凭证");
        }
        if (plain == null || plain.isBlank()) {
            throw new McpCredentialException("凭证不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + body.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(body, 0, packed, nonce.length, body.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (McpCredentialException e) {
            throw e;
        } catch (Exception e) {
            throw new McpCredentialException("凭证加密失败");
        }
    }

    public String decrypt(String stored) {
        if (!configured()) {
            throw new McpCredentialException("未配置 " + KEY_ENV + "，不能读取凭证");
        }
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new McpCredentialException("凭证无法解密，请重新填写");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (packed.length <= NONCE_BYTES) {
                throw new McpCredentialException("凭证无法解密，请重新填写");
            }
            byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] body = Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (McpCredentialException e) {
            throw e;
        } catch (Exception e) {
            throw new McpCredentialException("凭证无法解密，请重新填写");
        }
    }
}
