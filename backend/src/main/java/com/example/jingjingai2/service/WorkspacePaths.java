package com.example.jingjingai2.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 工作区绑定路径的规范化：Windows 下统一大小写和分隔符，去掉末尾分隔符。
 * 纯函数，不碰磁盘（是否存在 / 是不是目录由调用方另验）。
 */
public final class WorkspacePaths {

    /** 与 schema 里 bound_path VARCHAR(760) 对齐（utf8mb4 唯一索引上限） */
    public static final int MAX_BOUND_PATH_CHARS = 760;

    private WorkspacePaths() {}

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 把用户输入的路径收成「可做唯一约束」的绝对路径字符串。
     *
     * @throws IllegalArgumentException 空串 / 无法解析 / 磁盘根 / 超长
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path abs;
        try {
            abs = Paths.get(raw.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法路径: " + raw);
        }
        if (abs.getParent() == null) {
            throw new IllegalArgumentException("出于安全，不允许绑定磁盘根目录: " + abs);
        }
        String s = abs.toString();
        while (s.endsWith("\\") || s.endsWith("/")) {
            Path p = Paths.get(s);
            if (p.getParent() == null) break;
            s = s.substring(0, s.length() - 1);
        }
        if (isWindows()) {
            s = s.replace('/', '\\').toLowerCase(Locale.ROOT);
        }
        if (s.length() > MAX_BOUND_PATH_CHARS) {
            throw new IllegalArgumentException("路径过长（超过 " + MAX_BOUND_PATH_CHARS + " 字符）");
        }
        return s;
    }

    /** 侧边栏文件夹名：路径最后一段。 */
    public static String displayName(String normalized) {
        if (normalized == null || normalized.isBlank()) return "工作区";
        Path p = Paths.get(normalized);
        Path name = p.getFileName();
        if (name == null || name.toString().isBlank()) return "工作区";
        String n = name.toString();
        return n.length() <= 128 ? n : n.substring(0, 128);
    }
}
