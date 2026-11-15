package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发闸门：**上限（超出直接拒绝）、写互斥、ThreadLocal 搬运**。
 *
 * 为什么必须真并发地测：这三条都是"只在同时发生时才暴露"的语义。
 * 单线程跑一百遍也测不出"两个 explore 其实在排队"或者"工作线程读错了工作区"。
 *
 * 第二轮改造点：**超限从"排队"改成"当场拒绝"**（trySubmit 返回 null）。
 * 排队会让主 agent 以为"已经派出去了"，其实还在等 —— 成本与收益都算不清。
 */
class SubagentPoolTest {

    private static SubagentSpec spec(String goal, SubagentSpec.Mode mode) {
        return new SubagentSpec(goal, mode, 0, "conv-pool");
    }

    /** 参数既是 goal 也是摘要 —— 摘要必须是**每个任务自己的**，否则"顺序对不对"就测不出来了 */
    private static SubagentResult ok(String goalAndSummary) {
        return SubagentResult.success(goalAndSummary, SubagentSpec.Mode.EXPLORE, goalAndSummary,
                1, 1, 1, java.util.Set.of());
    }

    @Test
    @DisplayName("maxConcurrent=4 时 4 个 explore 真的同时在跑（不是排队）")
    void fourExploresRunConcurrently() throws Exception {
        SubagentProperties props = new SubagentProperties();   // max-concurrent 默认 4
        SubagentPool pool = new SubagentPool(props);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Future<SubagentResult>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String g = "调研 " + i;
                Future<SubagentResult> f = pool.trySubmit(spec(g, SubagentSpec.Mode.EXPLORE), () -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return ok(g);
                });
                assertNotNull(f, "第 " + (i + 1) + " 个（上限之内）必须被接受");
                futures.add(f);
            }
            assertTrue(started.await(3, TimeUnit.SECONDS),
                    "4 个 explore 应当同时进入任务体；没有同时到齐说明池把并发串行化了");
            release.countDown();
            for (Future<SubagentResult> f : futures) {
                assertEquals(SubagentResult.FailureReason.SUCCESS, f.get(5, TimeUnit.SECONDS).failureReason());
            }
        } finally {
            release.countDown();
            pool.destroy();
        }
    }

    @Test
    @DisplayName("trySubmit 不阻塞调用方：提交一个会卡住的任务后，还能立刻继续提交")
    void trySubmitDoesNotBlockCaller() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        SubagentPool pool = new SubagentPool(4, workers);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertNotNull(pool.trySubmit(spec("卡住的任务", SubagentSpec.Mode.EXPLORE), () -> {
                release.await(5, TimeUnit.SECONDS);
                return ok("卡住的任务");
            }));
            long t0 = System.currentTimeMillis();
            assertNotNull(pool.trySubmit(spec("第二个任务", SubagentSpec.Mode.EXPLORE),
                    () -> ok("第二个任务")));
            long cost = System.currentTimeMillis() - t0;
            assertTrue(cost < 500, "第二次提交不该等第一个跑完，实际等了 " + cost + "ms");
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("超出上限的 explore **当场被拒绝**（返回 null），不是排队等着")
    void excessExploresAreRejectedNotQueued() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        SubagentPool pool = new SubagentPool(1, workers);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        try {
            assertNotNull(pool.trySubmit(spec("任务 0", SubagentSpec.Mode.EXPLORE), () -> {
                entered.incrementAndGet();
                firstStarted.countDown();
                release.await(5, TimeUnit.SECONDS);
                return ok("任务 0");
            }), "第一个应当被接受");
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));

            // 许可只有 1 个且已被占住 → 后面两个必须**立刻**拿到 null，而不是排进队列
            assertNull(pool.trySubmit(spec("任务 1", SubagentSpec.Mode.EXPLORE),
                    () -> ok("任务 1")), "并发已满时必须直接拒绝（返回 null），让主 agent 知道没派出去");
            assertNull(pool.trySubmit(spec("任务 2", SubagentSpec.Mode.EXPLORE),
                    () -> ok("任务 2")), "同上");

            Thread.sleep(200);   // 给"本该被拦住的那两个"足够的时间去违规
            assertEquals(1, entered.get(),
                    "被拒绝的任务绝不能进入任务体（拒绝不是延迟执行）");
            assertEquals(0, pool.availablePermits());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("implement 写互斥：第二个直接被【拒绝】，不是排队等待")
    void secondImplementIsRejected() {
        SubagentProperties props = new SubagentProperties();
        SubagentPool pool = new SubagentPool(props);
        try {
            assertTrue(pool.tryAcquireImplement(), "第一个 implement 应该能拿到写锁");
            assertTrue(pool.isImplementRunning());
            assertFalse(pool.tryAcquireImplement(),
                    "第二个 implement 必须被拒绝 —— 写操作不允许并行（与计划层'宁可明确拒绝'同一哲学）");
            pool.releaseImplement();
            assertFalse(pool.isImplementRunning());
            assertTrue(pool.tryAcquireImplement(), "前一个结束后应当能再拿");
        } finally {
            pool.releaseImplement();
            pool.destroy();
        }
    }

    @Test
    @DisplayName("ThreadLocal 被搬进工作线程：显式 set / 用完 clear（否则线程复用会串工作区）")
    void threadLocalsAreCarriedAndCleared() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();   // 故意只有一个线程，复用才暴露问题
        SubagentPool pool = new SubagentPool(1, workers);
        Path ws = Path.of("build", "ws-1").toAbsolutePath();
        AtomicReference<String> firstConv = new AtomicReference<>();
        AtomicReference<Path> firstWs = new AtomicReference<>();
        AtomicReference<String> secondConv = new AtomicReference<>("UNSET");
        try {
            ConversationContext.set(7L, "conv-A");
            WorkspaceContext.set(ws);
            pool.trySubmit(spec("A", SubagentSpec.Mode.EXPLORE), () -> {
                firstConv.set(ConversationContext.conversationId());
                firstWs.set(WorkspaceContext.get());
                return ok("A");
            }).get(5, TimeUnit.SECONDS);

            // 提交线程这边把上下文清掉，再从同一个工作线程跑第二个任务
            ConversationContext.clear();
            WorkspaceContext.clear();
            pool.trySubmit(spec("B", SubagentSpec.Mode.EXPLORE), () -> {
                secondConv.set(ConversationContext.conversationId());
                return ok("B");
            }).get(5, TimeUnit.SECONDS);

            assertEquals("conv-A", firstConv.get(), "工作线程必须能读到提交线程的会话上下文");
            assertEquals(ws, firstWs.get(), "工作区同理（文件工具靠它做沙箱）");
            assertNull(secondConv.get(), "上一个任务结束后必须 clear，否则线程复用会串到别的会话");
        } finally {
            ConversationContext.clear();
            WorkspaceContext.clear();
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("一轮里连续提交多个：每个都拿到自己的 Future，顺序与提交顺序一致")
    void batchOfSubmitsKeepsOrder() throws Exception {
        SubagentProperties props = new SubagentProperties();
        SubagentPool pool = new SubagentPool(props);
        try {
            List<String> goals = List.of("一", "二", "三");
            List<Future<SubagentResult>> futures = new ArrayList<>();
            for (String g : goals) {
                Future<SubagentResult> f = pool.trySubmit(spec(g, SubagentSpec.Mode.EXPLORE),
                        () -> ok(g + " 的结论"));
                assertNotNull(f);
                futures.add(f);
            }
            List<String> summaries = new ArrayList<>();
            for (Future<SubagentResult> f : futures) {
                summaries.add(f.get(5, TimeUnit.SECONDS).summary());
            }
            assertEquals(List.of("一 的结论", "二 的结论", "三 的结论"), summaries);
        } finally {
            pool.destroy();
        }
    }

    @Test
    @DisplayName("任务体抛异常 → 不吞掉，由 Future.get 如实抛出（调用方负责转成 crashed 结果）")
    void bodyExceptionPropagates() {
        SubagentProperties props = new SubagentProperties();
        SubagentPool pool = new SubagentPool(props);
        try {
            Future<SubagentResult> f = pool.trySubmit(spec("炸", SubagentSpec.Mode.EXPLORE), () -> {
                throw new IllegalStateException("房间内部炸了");
            });
            Exception e = assertThrows(Exception.class, () -> f.get(5, TimeUnit.SECONDS));
            assertNotNull(e);
            // 许可必须被还回去，否则一次异常就把池永久堵死
            assertEquals(props.getMaxConcurrent(), pool.availablePermits());
        } finally {
            pool.destroy();
        }
    }

    @Test
    @DisplayName("并发压力下许可数守恒：N 个许可跑 12 个任务后必须回到 N")
    void permitsAreReleasedUnderLoad() throws Exception {
        SubagentPool pool = new SubagentPool(12, null);
        try {
            List<Future<SubagentResult>> fs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String g = "任务 " + i;
                Future<SubagentResult> f = pool.trySubmit(spec(g, SubagentSpec.Mode.EXPLORE), () -> {
                    Thread.sleep(5);
                    return ok(g);
                });
                assertNotNull(f);
                fs.add(f);
            }
            for (Future<SubagentResult> f : fs) f.get(10, TimeUnit.SECONDS);
            assertEquals(pool.maxConcurrent(), pool.availablePermits(), "许可泄漏会让池慢慢饿死");
        } finally {
            pool.destroy();
        }
    }
}
