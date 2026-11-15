package com.example.jingjingai2.mcp;

/**
 * 凭证加解密或归属校验失败。消息不含密钥和令牌。
 */
public class McpCredentialException extends RuntimeException {

    public McpCredentialException(String message) {
        super(message);
    }
}
