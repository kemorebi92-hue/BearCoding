package com.example.jingjingai2;

import com.example.jingjingai2.ratelimit.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流器测试：验证滑动窗口的放行/拦截边界，以及并发下的线程安全。
 */
class RateLimiterTest {

    @Test
    @DisplayName("窗口内未超额度时全部放行")
    void allowsWithinQuota() {
        RateLimiter limiter = new RateLimiter(60_000, 3);
        assertNull(limiter.tryAcquire(1L));
        assertNull(limiter.tryAcquire(1L));
        assertNull(limiter.tryAcquire(1L));
    }

    @Test
    @DisplayName("超过额度后拦截，并给出可读提示")
    void blocksAfterQuotaExceeded() {
        RateLimiter limiter = new RateLimiter(60_000, 2);
        assertNull(limiter.tryAcquire(7L));
        assertNull(limiter.tryAcquire(7L));
        String msg = limiter.tryAcquire(7L);
        assertNotNull(msg, "第 3 次应被限流");
        assertTrue(msg.contains("请求过于频繁"), "应返回可直接展示给用户的提示");
    }

    @Test
    @DisplayName("不同用户额度相互独立")
    void quotaIsPerUser() {
        RateLimiter limiter = new RateLimiter(60_000, 1);
        assertNull(limiter.tryAcquire(100L), "用户100 首次应放行");
        assertNotNull(limiter.tryAcquire(100L), "用户100 第二次应被拦");
        // 另一个用户不受影响
        assertNull(limiter.tryAcquire(200L), "用户200 应独立于用户100");
    }

    @Test
    @DisplayName("reset 后可重新放行")
    void resetClearsQuota() {
        RateLimiter limiter = new RateLimiter(60_000, 1);
        assertNull(limiter.tryAcquire(9L));
        assertNotNull(limiter.tryAcquire(9L));
        limiter.reset(9L);
        assertNull(limiter.tryAcquire(9L), "reset 后应恢复放行");
    }

    @Test
    @DisplayName("并发下不会超额放行（线程安全）")
    void threadSafeUnderConcurrency() throws Exception {
        int threads = 16;
        int quota = 5;
        RateLimiter limiter = new RateLimiter(60_000, quota);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程尝试 2 次，共 32 次请求，配额只有 5
                    if (limiter.tryAcquire(42L) == null) allowed.incrementAndGet();
                    if (limiter.tryAcquire(42L) == null) allowed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertTrue(allowed.get() <= quota,
                "并发下放行次数不得超过配额，实际放行 " + allowed.get() + "，配额 " + quota);
        assertTrue(allowed.get() >= 1, "至少应有请求通过");
    }
}
