package com.example.jingjingai2.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 桌面模式的 MCP 密钥只来自 DPAPI 文件。
 * 数据库还不存在且密钥文件也没有时才生成；数据库已经在而密钥没有，则失败。
 */
public final class DesktopMcpKey {

    public static final String PROPERTY = "app.mcp.desktop-key-material";
    public static final String FILE_NAME = "mcp-credential.key";

    private DesktopMcpKey() {}

    public static Path keyFile(Path dataRoot) {
        return dataRoot.resolve("secrets").resolve(FILE_NAME);
    }

    /** 返回给现有 SHA-256 派生使用的 Base64 材料。不读环境变量。 */
    public static String resolve(Path dataRoot, Path databaseFile, DpapiProtector protector) {
        Path keyFile = keyFile(dataRoot);
        boolean database = databaseFile != null && Files.isRegularFile(databaseFile);
        boolean key = Files.isRegularFile(keyFile);
        DpapiKeyFile store = new DpapiKeyFile(protector);
        if (key) {
            return encode(store.load(keyFile));
        }
        if (database) {
            throw new DpapiException(DpapiException.Kind.NOT_FOUND,
                    "数据库已存在但缺少 MCP 密钥文件，拒绝生成新密钥");
        }
        byte[] created = store.generate();
        store.create(keyFile, created);
        return encode(created);
    }

    private static String encode(byte[] material) {
        if (material == null || material.length != DpapiKeyFile.MATERIAL_BYTES) {
            throw new DpapiException(DpapiException.Kind.FORMAT, "密钥材料长度不正确");
        }
        return Base64.getEncoder().encodeToString(material);
    }
}
