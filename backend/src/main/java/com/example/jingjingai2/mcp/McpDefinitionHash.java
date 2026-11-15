package com.example.jingjingai2.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 工具定义指纹：name + description + inputSchema。不含 annotations。 */
public final class McpDefinitionHash {

    private McpDefinitionHash() {}

    public static String of(String name, String description, String schema) {
        String raw = nullToEmpty(name) + "\n" + nullToEmpty(description) + "\n" + nullToEmpty(schema);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
