package com.example.jingjingai2.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * 子代理房间专用的 {@link ChatModel} 包装：同步 {@code call()} 遇到瞬时 I/O 时最多试 3 次。
 *
 * <p>只包房间路径，不包父代理自己的请求（父代理走 SSE {@code stream()}，本类对 stream 原样转交）。
 *
 * <p><b>退避选择</b>：第 2 次前等 1s，第 3 次前等 2s（指数，底 1s）。
 * Connection reset 是 TCP 抖动，秒级退避够让对端回收半开连接；
 * 三次都失败时额外只等 1+2=3 秒，不会把挂掉的端点拖成十几秒
 * （后面还有「首轮空失败再开一次房间」兜底，不必在死连接上把第三次再等 4s）。
 * 不重试 4xx：那是业务错误，重试只会放大。
 */
final class RetryingChatModel implements ChatModel {

    static final int MAX_ATTEMPTS = 3;

    /** 第 2、第 3 次尝试前的等待（毫秒） */
    static final long[] BACKOFF_MS = {1000L, 2000L};

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 测试可替换，避免单测真的睡 1s/2s。生产默认 {@link Thread#sleep(long)}。 */
    static volatile Sleeper sleeper = Thread::sleep;

    private final ChatModel delegate;

    private RetryingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    static ChatModel wrap(ChatModel delegate) {
        if (delegate == null || delegate instanceof RetryingChatModel) return delegate;
        return new RetryingChatModel(delegate);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return com.example.jingjingai2.agent.TaskModelCalls.call(delegate, prompt);
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) throw e;
                long wait = BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)];
                SubagentLog.line("房间请求重试 " + (attempt + 1) + "/" + MAX_ATTEMPTS
                        + " backoffMs=" + wait
                        + " cause=" + e.getClass().getSimpleName()
                        + " msg=" + String.valueOf(e.getMessage()));
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    /**
     * 瞬时 I/O：{@link ResourceAccessException}、Socket reset/timeout/connect，以及它们的 cause 链。
     * 4xx / 普通 RuntimeException 不重试。
     */
    static boolean isRetryable(Throwable e) {
        while (e != null) {
            if (e instanceof ResourceAccessException) return true;
            if (e instanceof SocketException) return true;
            if (e instanceof SocketTimeoutException) return true;
            if (e instanceof ConnectException) return true;
            if (e instanceof IOException) return true;
            e = e.getCause();
        }
        return false;
    }
}
