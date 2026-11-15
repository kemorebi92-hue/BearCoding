package com.example.jingjingai2.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 桌面模式首次启动时，把开发目录里的 SQLite 主文件复制到新数据目录。
 *
 * <p>只复制，不移动、不覆盖、不删除源文件。源旁边有 {@code -wal/-shm/-journal}
 * 时拒绝，避免只拷到一个不完整的主文件。复制经临时文件完成，失败时删掉临时文件。
 * 本类不打开数据库。
 */
public final class SqliteDatabasePrep {

    public static final String FILE_NAME = "jingjing.db";

    private SqliteDatabasePrep() {}

    public static Path databaseFile(Path dataRoot) {
        if (dataRoot == null || !dataRoot.isAbsolute()) {
            throw new IllegalStateException("桌面模式的数据根必须是绝对路径");
        }
        return dataRoot.resolve("data").resolve(FILE_NAME).normalize();
    }

    /**
     * 目标不存在且开发库存在时复制。目标已存在或开发库不存在则什么都不做。
     *
     * @return 这次是否发生了复制
     */
    public static boolean prepare(Path target, Path legacy) {
        if (target == null || !target.isAbsolute()) {
            throw new IllegalStateException("目标数据库路径必须是绝对路径");
        }
        if (Files.exists(target)) return false;
        if (legacy == null || !Files.isRegularFile(legacy)) return false;
        rejectSidecars(legacy);
        String sourceHash = sha256(legacy);
        Path temp = target.resolveSibling(target.getFileName() + ".copying");
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            copy(legacy, temp);
            if (!sourceHash.equals(sha256(temp)) || !sourceHash.equals(sha256(legacy))) {
                throw new IllegalStateException("复制后的数据库与源文件不一致，已中止");
            }
            moveIntoPlace(temp, target);
            if (!sourceHash.equals(sha256(target))) {
                throw new IllegalStateException("目标数据库校验失败");
            }
            return true;
        } catch (RuntimeException e) {
            deleteIfExists(temp);
            throw e;
        } catch (IOException e) {
            deleteIfExists(temp);
            throw new IllegalStateException("无法准备桌面模式数据库: " + target, e);
        }
    }

    /** 源是普通文件且旁边有 SQLite 旁路文件时失败。目录、不存在的路径不查。 */
    public static void rejectSidecars(Path legacy) {
        if (legacy == null || !Files.isRegularFile(legacy)) return;
        Path parent = legacy.getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        String name = legacy.getFileName().toString();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path candidate : stream) {
                String found = candidate.getFileName().toString();
                if (found.startsWith(name + "-")
                        && (found.endsWith("-wal") || found.endsWith("-shm") || found.endsWith("-journal"))) {
                    throw new IllegalStateException(
                            "开发数据库旁边有未合并的 SQLite 文件，拒绝只复制主文件: " + found);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法检查数据库旁路文件: " + parent, e);
        }
    }

    private static void copy(Path source, Path temp) throws IOException {
        Files.deleteIfExists(temp);
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(temp)) {
            in.transferTo(out);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 失败路径上能删则删；删不掉由调用方看到原异常。
        }
    }

    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算数据库指纹: " + file, e);
        }
    }
}
