package com.example.jingjingai2.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 保存 32 字节随机材料。文件里只有 DPAPI blob，不保存明文。
 * 已有文件默认不覆盖。本类不读取 MCP_CREDENTIAL_KEY，也不打开数据库。
 */
public final class DpapiKeyFile {

    public static final byte[] MAGIC = {'J', 'J', 'D', 'P'};
    public static final int VERSION = 1;
    public static final int MATERIAL_BYTES = 32;
    private static final int HEADER = MAGIC.length + 1;

    private final DpapiProtector protector;

    public DpapiKeyFile(DpapiProtector protector) {
        if (protector == null) throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "缺少 DPAPI 实现");
        this.protector = protector;
    }

    public byte[] generate() {
        byte[] material = new byte[MATERIAL_BYTES];
        new SecureRandom().nextBytes(material);
        return material;
    }

    public void create(Path target, byte[] material) {
        if (target == null) throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "密钥文件路径不能为空");
        if (material == null || material.length != MATERIAL_BYTES) {
            throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "密钥材料必须是 32 字节");
        }
        if (Files.exists(target)) {
            throw new DpapiException(DpapiException.Kind.IO, "密钥文件已存在，拒绝覆盖");
        }
        byte[] body = protector.protect(material);
        byte[] file = new byte[HEADER + body.length];
        System.arraycopy(MAGIC, 0, file, 0, MAGIC.length);
        file[MAGIC.length] = VERSION;
        System.arraycopy(body, 0, file, HEADER, body.length);
        Path temp = target.resolveSibling(target.getFileName() + ".writing");
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            write(temp, file);
            restrictToCurrentUser(temp);
            byte[] readBack = readMaterial(temp);
            if (!Arrays.equals(material, readBack)) {
                throw new DpapiException(DpapiException.Kind.IO, "密钥文件回读校验失败");
            }
            move(temp, target);
        } catch (DpapiException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new DpapiException(DpapiException.Kind.IO, "无法写入密钥文件", e);
        }
    }

    public byte[] load(Path target) {
        if (target == null) throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "密钥文件路径不能为空");
        if (!Files.isRegularFile(target)) {
            throw new DpapiException(DpapiException.Kind.NOT_FOUND, "密钥文件不存在");
        }
        return readMaterial(target);
    }

    private byte[] readMaterial(Path path) {
        byte[] file;
        try {
            file = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new DpapiException(DpapiException.Kind.IO, "无法读取密钥文件", e);
        }
        if (file.length <= HEADER) throw new DpapiException(DpapiException.Kind.FORMAT, "密钥文件为空或过短");
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[i] != MAGIC[i]) throw new DpapiException(DpapiException.Kind.FORMAT, "密钥文件格式不正确");
        }
        int version = file[MAGIC.length] & 0xff;
        if (version != VERSION) throw new DpapiException(DpapiException.Kind.VERSION, "不支持的密钥文件版本");
        byte[] body = Arrays.copyOfRange(file, HEADER, file.length);
        byte[] material = protector.unprotect(body);
        if (material == null || material.length != MATERIAL_BYTES) {
            throw new DpapiException(DpapiException.Kind.FORMAT, "密钥材料长度不正确");
        }
        return material;
    }

    private static void write(Path temp, byte[] file) throws IOException {
        Files.deleteIfExists(temp);
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(file);
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 保留原始失败原因。
        }
    }

    /** Windows ACL 能设置就收紧到当前用户。设置不了只记录限制，不把文件写成“已保护”。 */
    static boolean restrictToCurrentUser(Path path) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) return false;
            UserPrincipal user = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            Set<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(user)
                    .setPermissions(permissions)
                    .build();
            view.setAcl(java.util.List.of(entry));
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    static byte[] readRaw(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readAllBytes();
        }
    }
}
