package com.example.jingjingai2.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型 API Key 的 AES-256-CBC。密钥只来自本机 DPAPI 文件解出的材料，没有公共默认口令。
 * 材料损坏或缺失时解密失败，不会换一把新密钥再把旧密文当成可读。
 */
@Component
public class CryptoUtil {

    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";
    private final byte[] key;

    public CryptoUtil(@Value("${app.model.key-material:}") String material) {
        if (material == null || material.isBlank()) {
            throw new IllegalStateException(
                    "模型密钥未初始化。需要本机 DPAPI 密钥文件，不能使用公共默认口令。");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(material.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("模型密钥材料不是合法 Base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("模型密钥材料必须是 32 字节");
        }
        this.key = raw;
    }

    /** 加密明文，返回 Base64(IV + 密文)；null 直接返回 null */
    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密（encrypt 的逆操作）；密钥不对或密文损坏时失败，不返回空串冒充成功 */
    public String decrypt(String cipherText) {
        if (cipherText == null) return null;
        try {
            byte[] data = Base64.getDecoder().decode(cipherText);
            if (data.length <= 16) throw new IllegalArgumentException("密文过短");
            byte[] iv = new byte[16];
            System.arraycopy(data, 0, iv, 0, 16);
            byte[] enc = new byte[data.length - 16];
            System.arraycopy(data, 16, enc, 0, enc.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败：模型密钥丢失、损坏，或密文不属于这把密钥", e);
        }
    }
}
