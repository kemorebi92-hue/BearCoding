package com.example.jingjingai2.security;

/**
 * 用 Windows DPAPI 保护一段字节。只允许当前用户范围，不使用附加熵。
 * 非 Windows 调用必须失败，不能改用别的算法冒充。
 */
public interface DpapiProtector {

    boolean supported();

    byte[] protect(byte[] plaintext);

    byte[] unprotect(byte[] protectedData);
}
