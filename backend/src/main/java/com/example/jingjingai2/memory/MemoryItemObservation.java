package com.example.jingjingai2.memory;

/**
 * 一条记忆的注入观测。只带标识、规模和去向，不带正文。
 *
 * <p>{@code injected} 指正式注入（现有全量逻辑），不是影子选择。
 * {@code skipReason} 为空表示被正式注入；否则说明为什么没进。
 */
public final class MemoryItemObservation {

    private final String source;
    private final String id;
    private final String category;
    private final String ref;
    private final int chars;
    private final int tokens;
    private final boolean injected;
    private final String skipReason;
    private final boolean protectedItem;

    public MemoryItemObservation(String source, String id, String category, String ref,
                                 int chars, int tokens, boolean injected, String skipReason,
                                 boolean protectedItem) {
        this.source = source;
        this.id = id;
        this.category = category;
        this.ref = ref;
        this.chars = chars;
        this.tokens = tokens;
        this.injected = injected;
        this.skipReason = skipReason == null ? "" : skipReason;
        this.protectedItem = protectedItem;
    }

    public String source() { return source; }
    public String id() { return id; }
    public String category() { return category; }
    public String ref() { return ref; }
    public int chars() { return chars; }
    public int tokens() { return tokens; }
    public boolean injected() { return injected; }
    public String skipReason() { return skipReason; }
    public boolean protectedItem() { return protectedItem; }

    /** 一行日志。不含正文、不含路径。 */
    public String toLogLine() {
        return "source=" + source
                + " id=" + id
                + " category=" + (category == null ? "" : category)
                + " ref=" + (ref == null ? "" : ref)
                + " chars=" + chars
                + " tokens=" + tokens
                + " injected=" + injected
                + " skip=" + skipReason
                + " protected=" + protectedItem;
    }
}
