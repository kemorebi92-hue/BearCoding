package com.example.jingjingai2.security;

/** DPAPI 或密钥文件失败。消息只描述原因，不包含密钥或密文。 */
public final class DpapiException extends RuntimeException {

    public enum Kind {
        UNSUPPORTED,
        INVALID_INPUT,
        NOT_FOUND,
        FORMAT,
        VERSION,
        PROTECT_FAILED,
        UNPROTECT_FAILED,
        IO
    }

    private final Kind kind;

    public DpapiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public DpapiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
