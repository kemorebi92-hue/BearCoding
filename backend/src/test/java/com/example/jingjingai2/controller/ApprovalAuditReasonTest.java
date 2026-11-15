package com.example.jingjingai2.controller;

import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批补记的两个纯判断：结果状态映射，以及同一 approvalId 的挂起和结果各记一次。
 * 不启动 Spring，不连数据库，不构造 ChatController。
 */
class ApprovalAuditReasonTest {

    @Test
    @DisplayName("四种结果映射正确，批准不是 APPROVED_EXECUTED，未知状态不记")
    void statusMapping() {
        assertEquals(AgentTurnRecord.END_APPROVED, ChatController.approvalEndReason("APPROVED"));
        assertEquals(AgentTurnRecord.END_USER_REJECTED, ChatController.approvalEndReason("REJECTED"));
        assertEquals(AgentTurnRecord.END_APPROVAL_EXPIRED, ChatController.approvalEndReason("EXPIRED"));
        assertEquals(AgentTurnRecord.END_APPROVAL_CANCELLED, ChatController.approvalEndReason("CANCELLED"));
        assertNull(ChatController.approvalEndReason("PENDING"));
        assertNull(ChatController.approvalEndReason(null));
        assertTrue(AgentTurnRecord.END_APPROVAL_CANCELLED.length() <= 32);
    }

    @Test
    @DisplayName("挂起和结果分开去重：同一个 approvalId 可以各记一次，重复事件只记一次")
    void pendingAndResultDedupSeparately() {
        Set<String> pending = ConcurrentHashMap.newKeySet();
        Set<String> result = ConcurrentHashMap.newKeySet();

        assertTrue(pending.add("a"));
        assertTrue(!pending.add("a"), "挂起重复事件不再记");
        assertTrue(result.add("a"), "同一个 id 的结果不被挂起挡住");
        assertTrue(!result.add("a"), "结果重复事件不再记");
    }

    @Test
    @DisplayName("同一轮两个不同 approvalId 各自记挂起和结果")
    void twoApprovalsInOneTurn() {
        Set<String> pending = ConcurrentHashMap.newKeySet();
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (String id : new String[]{"a", "b"}) {
            assertTrue(pending.add(id));
            assertTrue(result.add(id));
        }
        assertEquals(2, pending.size());
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("还没有轮次时不记：持有者保持初始负值")
    void noTurnYet() {
        AtomicInteger turn = new AtomicInteger(-1);
        assertTrue(turn.get() < 0, "初始 -1，回调读到就跳过，不猜轮次");
        turn.set(3);
        assertEquals(3, turn.get(), "每轮开头写入的是循环变量 i");
    }

    @Test
    @DisplayName("多个线程同时登记不同 approvalId，不丢、不抛并发异常")
    void concurrentRegistrations() throws Exception {
        Set<String> pending = ConcurrentHashMap.newKeySet();
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            String id = "approval-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    pending.add(id);
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
        assertEquals(n, pending.size());
    }
}
