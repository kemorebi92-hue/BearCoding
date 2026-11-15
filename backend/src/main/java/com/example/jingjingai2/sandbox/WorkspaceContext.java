package com.example.jingjingai2.sandbox;

import java.nio.file.Path;

/**
 * 当前请求的工作区上下文（ThreadLocal）。
 *
 * 作用：让"每个会话一个独立工作区"成为可能——{@link PathSandbox} 从这里取当前会话的工作区根，
 * 取不到时再回退到全局配置根。这样工具方法签名、给模型看的 schema 都不用改。
 *
 * 重要：SSE 流式输出在独立线程池里执行，ThreadLocal 不会自动传递到子线程，
 * 所以必须在使用工作区的线程里显式 set()，并在 finally 里 clear()，避免线程复用导致串工作区。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<Path> CURRENT = new ThreadLocal<>();

    public static void set(Path root) {
        CURRENT.set(root);
    }

    public static Path get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private WorkspaceContext() {}
}
