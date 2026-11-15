package com.example.jingjingai2.usermemory;

import java.util.Locale;

/**
 * 用户记忆第一期类别。未知值在 Service 层拒绝，不进库。
 *
 * <p>声明顺序 = 注入裁剪顺序：constraint → preference → profile → project_context。
 * v1 不加 progress / next_step（那是项目日记，会过期，且会污染别的会话）。
 */
public enum UserMemoryCategory {
    CONSTRAINT("constraint"),
    PREFERENCE("preference"),
    PROFILE("profile"),
    PROJECT_CONTEXT("project_context");

    private final String wire;

    UserMemoryCategory(String wire) {
        this.wire = wire;
    }

    /** 落库 / 工具入参用的小写字符串。 */
    public String wire() {
        return wire;
    }

    /**
     * 解析工具/调用方传入的类别。大小写不敏感；连字符与下划线互通。
     * 认不出就返回 {@code null}（由 Service 拒绝，不抛）。
     */
    public static UserMemoryCategory fromWire(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (UserMemoryCategory c : values()) {
            if (c.wire.equals(s) || c.name().equalsIgnoreCase(s)) return c;
        }
        return null;
    }
}
