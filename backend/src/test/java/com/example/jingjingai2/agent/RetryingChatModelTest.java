package com.example.jingjingai2.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子房间同步 call() 的瞬时 I/O 重试：3 次、1s/2s 退避，不碰 stream()。
 */
class RetryingChatModelTest {

    @AfterEach
    void restoreSleeper() {
        RetryingChatModel.sleeper = Thread::sleep;
    }

    @Test
    @DisplayName("I/O 失败两次后成功：共 3 次 call，退避 1000ms 然后 2000ms")
    void retriesIoThreeTimesWithExponentialBackoff() {
        List<Long> waits = new ArrayList<>();
        RetryingChatModel.sleeper = waits::add;
        FakeChatModel fake = new FakeChatModel()
                .then(p -> { throw reset(); })
                .then(p -> { throw reset(); })
                .thenReply("ok", 1);

        ChatResponse r = RetryingChatModel.wrap(fake).call(new Prompt("hi"));

        assertEquals(3, fake.callCount());
        assertEquals(List.of(1000L, 2000L), waits);
        assertEquals("ok", r.getResult().getOutput().getText());
    }

    @Test
    @DisplayName("连续 3 次 I/O 失败：抛最后一次，不再第 4 次")
    void givesUpAfterThreeIoFailures() {
        RetryingChatModel.sleeper = ms -> {};
        FakeChatModel fake = new FakeChatModel()
                .then(p -> { throw reset(); });

        assertThrows(ResourceAccessException.class,
                () -> RetryingChatModel.wrap(fake).call(new Prompt("hi")));
        assertEquals(3, fake.callCount());
    }

    @Test
    @DisplayName("业务异常（非 I/O）不重试")
    void doesNotRetryBusinessErrors() {
        List<Long> waits = new ArrayList<>();
        RetryingChatModel.sleeper = waits::add;
        FakeChatModel fake = new FakeChatModel()
                .then(p -> { throw new IllegalArgumentException("bad request"); })
                .thenReply("should not run", 1);

        assertThrows(IllegalArgumentException.class,
                () -> RetryingChatModel.wrap(fake).call(new Prompt("hi")));
        assertEquals(1, fake.callCount());
        assertTrue(waits.isEmpty());
    }

    @Test
    @DisplayName("stream() 原样转交，不套重试（父代理路径）")
    void streamIsNotRetried() {
        ChatModel wrapped = RetryingChatModel.wrap(new FakeChatModel());
        assertThrows(UnsupportedOperationException.class, () -> wrapped.stream(new Prompt("hi")));
    }

    @Test
    @DisplayName("wrap 幂等，不会套两层")
    void wrapIsIdempotent() {
        FakeChatModel fake = new FakeChatModel().thenReply("ok", 1);
        ChatModel once = RetryingChatModel.wrap(fake);
        assertSame(once, RetryingChatModel.wrap(once));
    }

    private static ResourceAccessException reset() {
        return new ResourceAccessException("I/O error on GET", new SocketException("Connection reset"));
    }
}
