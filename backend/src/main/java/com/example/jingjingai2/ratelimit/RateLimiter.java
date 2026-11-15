package com.example.jingjingai2.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多用户限流（滑动窗口计数）。
 *
 * 为什么需要：
 *  - 每个用户的一次对话可能触发多轮模型调用 + 工具执行，成本远高于普通接口。
 *    不做限制的话，一个用户（或被泄露的 token）就能刷爆 API 额度 / 拖垮服务。
 *
 * 为什么用滑动窗口而不是固定窗口：
 *  - 固定窗口在窗口切换的边界会出现"两倍突发"（前窗口末尾 + 后窗口开头各打满）。
 *    滑动窗口按每次请求的实际时间判断，边界更平滑。
 *
 * 并发安全：
 *  - 用 ConcurrentHashMap 存每个用户的请求时间戳队列，队列操作在 synchronized 块内完成，
 *    避免 ConcurrentModificationException 与竞态。
 */
@Component
public class RateLimiter {

    /** 时间窗口大小（毫秒） */
    private final long windowMs;
    /** 一个窗口内允许的最大请求数 */
    private final int maxRequests;

    /** userId -> 该用户窗口内的请求时间戳（毫秒） */
    private final Map<Long, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${app.rate-limit.window-ms:60000}") long windowMs,
                       @Value("${app.rate-limit.max-requests:20}") int maxRequests) {
        this.windowMs = windowMs;
        this.maxRequests = maxRequests;
    }

    /**
     * 尝试获取一次配额。
     *
     * @return null 表示放行；非 null 表示被限流，返回值是可直接展示给用户的提示文案
     */
    public String tryAcquire(long userId) {
        Deque<Long> bucket = buckets.computeIfAbsent(userId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (bucket) {
            // 清掉窗口外的旧时间戳
            while (!bucket.isEmpty() && now - bucket.peekFirst() >= windowMs) {
                bucket.pollFirst();
            }
            if (bucket.size() >= maxRequests) {
                long waitSec = (windowMs - (now - bucket.peekFirst())) / 1000 + 1;
                return "请求过于频繁：每 " + (windowMs / 1000) + " 秒最多 " + maxRequests
                        + " 次对话，请约 " + waitSec + " 秒后再试。";
            }
            bucket.addLast(now);
            return null;
        }
    }

    /** 仅用于测试/运维：清空某个用户的计数 */
    public void reset(long userId) {
        buckets.remove(userId);
    }
}
