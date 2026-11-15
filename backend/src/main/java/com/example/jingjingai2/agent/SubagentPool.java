package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子代理的**并发闸门**：同时最多 N 个、写操作互斥、ThreadLocal 跨线程搬运。
 *
 * 三条规则（写进代码，不靠提示词自觉）：
 *  1. **上限**：{@code app.subagent.max-concurrent}（默认 4）。**超出的直接拒绝**（返回 null，
 *     调用方翻译成"并发已满，稍后再派或收窄目标"的错误工具结果）。
 *     第一版是让多余的 explore 在池里**排队** —— 实测发现这会让主 agent 以为"已经派出去了"，
 *     其实还在等，成本与收益都算不清；而且排队期间它已经又在读别的文件了。明确拒绝更好。
 *  2. **写操作互斥**：任一 implement 在跑时，**拒绝**再启动 implement（不是排队）。
 *     这与计划层的"失败 3 次才重拆"同一哲学：宁可明确拒绝让主 agent 自己决定，也不要静默串行。
 *     explore 不受影响，仍可并行（只读）。
 *  3. **ThreadLocal 搬运**：{@code ConversationContext} / {@code WorkspaceContext} 都是 ThreadLocal，
 *     **不跨线程**。SSE 已经踩过这个坑（doStream 里必须重新 set）；这里在提交线程抓快照、
 *     工作线程里 set、finally 里 clear，避免线程复用导致串工作区。
 *
 * 为什么不复用 {@code ChatController.streamExecutor}：那个池 8 个线程还要推 SSE，
 * 子代理一多就会把流式输出饿死。独立的池，大小 = max-concurrent。
 */
@Component
public class SubagentPool implements DisposableBean {

    /** 提交线程上的上下文快照 */
    private static final class Ctx {
        final Long userId;
        final String conversationId;
        final Path workspace;

        Ctx(Long userId, String conversationId, Path workspace) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.workspace = workspace;
        }

        static Ctx capture() {
            return new Ctx(ConversationContext.userId(), ConversationContext.conversationId(),
                    WorkspaceContext.get());
        }

        void install() {
            ConversationContext.set(userId, conversationId);
            if (workspace != null) WorkspaceContext.set(workspace);
        }

        void clear() {
            ConversationContext.clear();
            WorkspaceContext.clear();
        }
    }

    private final int maxConcurrent;
    private final Semaphore permits;
    private final ExecutorService workers;
    private final boolean ownsWorkers;
    /** true = 已经有一个 implement 在跑 */
    private final AtomicBoolean implementRunning = new AtomicBoolean(false);

    /**
     * Spring 用的构造器。
     *
     * 这里**必须**有 @Autowired：本类有第二个（测试用的）构造器，Spring 在"多个构造器且没有一个被标注"时
     * 会退回去找无参构造器，然后直接启动失败（NoSuchMethodException: <init>()）。
     * 单测发现不了这个 —— 它只 new 不装配，只有真正启动容器才会暴露。
     */
    @Autowired
    public SubagentPool(SubagentProperties props) {
        this(props.getMaxConcurrent(), null);
    }

    /** 测试用：可注入自己的线程池 */
    public SubagentPool(int maxConcurrent, ExecutorService workers) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.permits = new Semaphore(this.maxConcurrent, true);
        this.ownsWorkers = (workers == null);
        this.workers = ownsWorkers
                ? Executors.newFixedThreadPool(this.maxConcurrent, r -> {
                    Thread t = new Thread(r, "subagent-" + System.nanoTime() % 100000);
                    t.setDaemon(true);
                    return t;
                })
                : workers;
    }

    public int maxConcurrent() { return maxConcurrent; }

    /** 当前是否已有 implement 在跑（测试/诊断用） */
    public boolean isImplementRunning() { return implementRunning.get(); }

    /** 当前可用的 explore 许可数（测试/诊断用） */
    public int availablePermits() { return permits.availablePermits(); }

    /**
     * 尝试占住"写操作"这一把锁。成功返回 true（调用方**必须**在任务结束时 releaseImplement）。
     * 失败说明已有 implement 在跑 → 调用方应当直接拒绝本次派出（failureReason=REJECTED）。
     */
    public boolean tryAcquireImplement() {
        return implementRunning.compareAndSet(false, true);
    }

    public void releaseImplement() {
        implementRunning.set(false);
    }

    /**
     * 提交一个子代理任务。**立即返回**：
     *  - 许可在**调用线程**里尝试获取，拿到就交给工作线程跑（工作线程结束时释放）；
     *  - 拿不到（并发已满）→ 返回 {@code null}，调用方拒绝本次派出。
     *
     * 这样"派出去没有"是**当场就知道**的事实，不会出现"提交成功但其实在排队"。
     */
    public Future<SubagentResult> trySubmit(SubagentSpec spec, Callable<SubagentResult> body) {
        if (!permits.tryAcquire()) return null;
        final Ctx ctx = Ctx.capture();
        final TaskExecution task = TaskExecution.current();
        try {
            return workers.submit(() -> {
                try {
                    ctx.install();
                    try (TaskExecution.Scope scope = TaskExecution.attach(task);
                         TaskExecution.Scope auxiliary = TaskModelCalls.auxiliaryScope()) {
                        return body.call();
                    } finally {
                        ctx.clear();
                    }
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            permits.release();   // 线程池已关：把许可还回去，别把闸门永久焊死
            throw e;
        }
    }

    @Override
    public void destroy() {
        if (ownsWorkers) {
            workers.shutdownNow();
        }
    }
}
