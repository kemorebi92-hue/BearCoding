package com.example.jingjingai2.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 一种用途一份 DPAPI 文件。数据库已存在而对应密钥文件没有时失败，不换新密钥假装旧密文还能读。
 */
public final class DpapiPurposeKey {

    private DpapiPurposeKey() {}

    public static Path keyFile(Path dataRoot, String fileName) {
        return dataRoot.resolve("secrets").resolve(fileName);
    }

    /** 返回 Base64 的 32 字节材料。不读环境变量，也不打开数据库。 */
    public static String resolve(Path dataRoot, Path databaseFile, DpapiProtector protector,
                                 String fileName, String purpose) {
        Path keyFile = keyFile(dataRoot, fileName);
        boolean database = databaseFile != null && Files.isRegularFile(databaseFile);
        boolean key = Files.isRegularFile(keyFile);
        DpapiKeyFile store = new DpapiKeyFile(protector);
        if (key) {
            return encode(store.load(keyFile));
        }
        if (database) {
            throw new DpapiException(DpapiException.Kind.NOT_FOUND,
                    "数据库已存在但缺少" + purpose + "文件，拒绝生成新密钥");
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
