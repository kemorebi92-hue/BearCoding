package com.example.jingjingai2.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "停止生成"的取消登记表。
 *
 * 为什么需要它：
 *  - Agent 的一轮问答是一个跑在后台线程里的循环（最多 12 轮工具调用），
 *    用户点"停止"时不能直接杀线程（JVM 里强杀线程是危险动作），
 *    正确做法是**协作式取消**：循环自己在安全点（每轮开头、每个工具执行前）检查一个标志位，
 *    看到标志就主动收尾退出。
 *  - 前端断开 SSE 连接 ≠ 后端停止工作：后端还在傻跑，还会继续调模型烧 token。
 *    所以需要一个独立于 HTTP 连接的取消信号 —— 这就是这张表存在的原因。
 *
 * 线程安全：ConcurrentHashMap + AtomicBoolean（取消信号会从 Tomcat 线程写入、
 * 从 streamExecutor 线程读取，必须保证可见性）。
 */
@Component
public class AgentCancellationRegistry {

    private record RequestKey(Long userId, String conversationId) {}
    private final Map<RequestKey, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /** 开始新一轮问答前登记（会覆盖上一次遗留的标志，保证是新的一次"未取消"状态） */
    public void arm(String conversationId) {
        if (conversationId == null) return;
        flags.put(new RequestKey(null, conversationId), new AtomicBoolean(false));
    }

    /** The chat entry points share an exclusive execution slot per conversation. */
    public boolean tryArm(Long userId, String conversationId) {
        return flags.putIfAbsent(new RequestKey(userId, conversationId), new AtomicBoolean(false)) == null;
    }

    /** 请求取消（用户点"停止"） */
    public boolean cancel(String conversationId) { return cancel(null, conversationId); }

    public boolean cancel(Long userId, String conversationId) {
        return cancel(userId, conversationId, () -> {});
    }

    public boolean cancel(Long userId, String conversationId, Runnable onCancelled) {
        if (conversationId == null) return false;
        if (cancel(new RequestKey(userId, conversationId), onCancelled)) return true;
        return userId != null && cancel(new RequestKey(null, conversationId), onCancelled);
    }

    private boolean cancel(RequestKey key, Runnable onCancelled) {
        // Complete the approval cancellation before this request's slot can be removed.
        return flags.computeIfPresent(key, (ignored, flag) -> {
            flag.set(true);
            onCancelled.run();
            return flag;
        }) != null;
    }

    /** 循环在安全点轮询这个（无锁读，开销可忽略） */
    public boolean isCancelled(String conversationId) { return isCancelled(null, conversationId); }

    public boolean isCancelled(Long userId, String conversationId) {
        if (conversationId == null) return false;
        AtomicBoolean flag = flags.get(new RequestKey(userId, conversationId));
        return flag != null && flag.get();
    }

    /** 本轮结束清理，避免长跑的进程里堆积无用的标志位 */
    public void clear(String conversationId) { clear(null, conversationId); }

    public void clear(Long userId, String conversationId) {
        if (conversationId == null) return;
        flags.remove(new RequestKey(userId, conversationId));
    }

    /** 当前登记数（测试/诊断用） */
    public int size() {
        return flags.size();
    }
}
