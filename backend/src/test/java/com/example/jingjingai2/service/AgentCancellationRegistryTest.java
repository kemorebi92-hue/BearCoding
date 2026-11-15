package com.example.jingjingai2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「停止生成」取消登记表测试。
 * 核心要验证的是：取消信号能跨线程可见（一个线程写、另一个线程读）。
 */
class AgentCancellationRegistryTest {

    @Test
    @DisplayName("未登记时查询为未取消")
    void unknownConversationNotCancelled() {
        AgentCancellationRegistry reg = new AgentCancellationRegistry();
        assertFalse(reg.isCancelled("not-exist"));
        assertFalse(reg.cancel("not-exist"), "对没登记的会话取消应返回 false（说明没有正在跑的循环）");
    }

    @Test
    @DisplayName("登记后未取消 → 取消 → 可被读到")
    void armThenCancel() {
        AgentCancellationRegistry reg = new AgentCancellationRegistry();
        reg.arm("conv-1");
        assertFalse(reg.isCancelled("conv-1"));
        assertTrue(reg.cancel("conv-1"));
        assertTrue(reg.isCancelled("conv-1"));
    }

    @Test
    @DisplayName("重新登记会重置为未取消（避免上一轮的停止状态带到下一轮）")
    void reArmResets() {
        AgentCancellationRegistry reg = new AgentCancellationRegistry();
        reg.arm("conv-1");
        reg.cancel("conv-1");
        assertTrue(reg.isCancelled("conv-1"));

        reg.arm("conv-1");
        assertFalse(reg.isCancelled("conv-1"), "新的一轮问答必须是全新的未取消状态");
    }

    @Test
    @DisplayName("清理后登记数归零，且不再可取消")
    void clearRemovesFlag() {
        AgentCancellationRegistry reg = new AgentCancellationRegistry();
        reg.arm("conv-1");
        reg.arm("conv-2");
        assertEquals(2, reg.size());

        reg.clear("conv-1");
        assertEquals(1, reg.size());
        assertFalse(reg.cancel("conv-1"));
    }

    @Test
    @DisplayName("null 会话 ID 不抛异常")
    void nullSafe() {
        AgentCancellationRegistry reg = new AgentCancellationRegistry();
        assertDoesNotThrow(() -> { reg.arm(null); reg.cancel(null); reg.clear(null); });
        assertFalse(reg.isCancelled(null));
    }

    @Test
    @DisplayName("跨线程可见：一个线程取消，另一个线程立刻能读到")
    void cancellationVisibleAcrossThreads() throws Exception {
        AgentCancellationRegistry reg = new AgentCancellationRegistry();
        reg.arm("conv-1");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 模拟后台的 Agent 循环：不断轮询取消标志（最多等 3 秒）
            pool.submit(() -> {
                started.countDown();
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline) {
                    if (reg.isCancelled("conv-1")) { observed.countDown(); return; }
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { return; }
                }
            });
            started.await(1, TimeUnit.SECONDS);
            // 模拟 Tomcat 线程处理"停止"请求
            pool.submit(() -> reg.cancel("conv-1"));

            assertTrue(observed.await(2, TimeUnit.SECONDS), "取消信号应在另一个线程中被及时读到");
        } finally {
            pool.shutdownNow();
        }
    }
}
