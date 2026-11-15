package com.example.jingjingai2.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 历史摘要的跨请求复用槽（Phase M1）。
 *
 * <p>为什么需要：默认工作集路径每个用户请求都会装配一次历史。如果每次都把同一段旧历史重新摘要一遍，
 * 模型费用会随请求数线性增长，而这正是本阶段明确不允许的「无控制重复付费摘要」。
 *
 * <p>为什么不用数据库：现有 schema 里没有「会话摘要」表，新增一张表会扩大持久化架构，超出 M1 范围。
 * 这里只做<strong>进程内有界缓存</strong>：
 * <ul>
 *   <li>键是会话作用域（{@code userId|conversationId}），不同会话不可能互相命中；</li>
 *   <li>值是 {@link Slot}：一版摘要覆盖范围 + 上一次尝试过的可摘要边界；</li>
 *   <li>最多 {@link #MAX_ENTRIES} 个会话，LRU 淘汰，重启即失效；</li>
 *   <li>不保存原文，只保存摘要正文和覆盖范围（原文仍在 SQLite）。</li>
 * </ul>
 *
 * <p>{@code attemptedBoundary} 是「同一范围不重复付费」的闸门：上一次在某个边界上摘要失败、
 * 或摘要结果没有变小，就把边界记下来；边界没有长过它之前不再自动调用模型。
 */
final class HistorySummaryCache {

    /** 进程内最多记住多少个会话的摘要。每个条目只有一段几百字的摘要和 id 列表。 */
    static final int MAX_ENTRIES = 64;

    /**
     * @param coverage         有效摘要；null 表示这个会话还没有成功摘要，只有「试过」的记录
     * @param attemptedBoundary 上一次尝试摘要时的可摘要边界（被覆盖条数）；-1 表示没试过
     */
    record Slot(HistorySummaryCoverage coverage, int attemptedBoundary) {}

    private final Map<String, Slot> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    synchronized Slot get(String scope) {
        return scope == null ? null : entries.get(scope);
    }

    synchronized void put(String scope, Slot slot) {
        if (scope == null || slot == null) return;
        entries.put(scope, slot);
    }

    synchronized void remove(String scope) {
        if (scope == null) return;
        entries.remove(scope);
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }
}
