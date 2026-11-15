package com.example.jingjingai2.memory;

import com.example.jingjingai2.context.TokenEstimator;

/**
 * 一条可选择的记忆。工作区记忆和用户记忆共用这一个形状，
 * 选择层不关心它来自文件还是数据库。
 *
 * <p>{@code id} 是稳定标识：工作区用 {@code workspace:{filename}}，
 * 用户记忆用 {@code user:{category}:{memKey}}。日志只打这个 id，不打正文。
 *
 * <p>{@code protectedItem} 只在调用方能从数据本身证明时才置 true
 * （目前只有用户记忆的 {@code constraint} 类别）。选择层不会自行猜测。
 */
public final class MemoryItem {

    public enum Source { WORKSPACE, USER }

    private final Source source;
    private final String id;
    private final String category;
    private final String ref;
    private final String content;
    private final boolean protectedItem;
    private final Long updatedAtMillis;

    public MemoryItem(Source source, String id, String category, String ref,
                      String content, boolean protectedItem, Long updatedAtMillis) {
        this.source = source;
        this.id = id;
        this.category = category;
        this.ref = ref;
        this.content = content == null ? "" : content;
        this.protectedItem = protectedItem;
        this.updatedAtMillis = updatedAtMillis;
    }

    public Source source() { return source; }
    public String id() { return id; }
    public String category() { return category; }
    public String ref() { return ref; }
    public String content() { return content; }
    public boolean protectedItem() { return protectedItem; }
    public Long updatedAtMillis() { return updatedAtMillis; }

    public int chars() { return content.length(); }

    public int tokens() { return TokenEstimator.estimate(content); }
}
