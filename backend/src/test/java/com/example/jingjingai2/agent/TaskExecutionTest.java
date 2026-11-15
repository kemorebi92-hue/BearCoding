package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.ContextCompactor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TaskExecutionTest {
    private static ChatResponse answer() { return new ChatResponse(List.of(new Generation(new AssistantMessage("正文")))); }
    @Test void summaryAndFailedAttemptCannotSpendTheReservedFinalCall() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(answer()).thenThrow(new IllegalStateException("failed")).thenReturn(answer());
        TaskExecution task = new TaskExecution(4, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            new ContextCompactor(model, 24000).summarizeTracked("历史事实");
            assertThrows(IllegalStateException.class, () -> TaskModelCalls.call(model, new Prompt("执行")));
            TaskModelCalls.call(model, new Prompt("回退"));
            assertTrue(task.mustFinalize());
            assertThrows(TaskExecution.BudgetExceeded.class, () -> TaskModelCalls.call(model, new Prompt("不能再执行")));
            task.beginFinal("预算");
            assertNotNull(TaskModelCalls.call(model, new Prompt("正文")));
            assertThrows(TaskExecution.BudgetExceeded.class, () -> TaskModelCalls.call(model, new Prompt("不能重试收尾")));
            assertEquals(4, task.calls());
        }
        verify(model, times(4)).call(any(Prompt.class));
        assertNull(TaskExecution.current());
    }
    @Test void streamSubscriptionsAreChargedAndClosedTasksCannotResume() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(answer()));
        TaskExecution task = new TaskExecution(3, 10000, 10000);
        Flux<ChatResponse> stream;
        try (var scope = TaskExecution.attach(task)) { stream = TaskModelCalls.stream(model, new Prompt("正文")); }
        stream.blockLast(); stream.blockLast();
        assertThrows(TaskExecution.BudgetExceeded.class, stream::blockLast);
        task.close();
        assertThrows(TaskExecution.BudgetExceeded.class, stream::blockLast);
        verify(model, times(2)).stream(any(Prompt.class));
    }
    @Test void workersShareAtomicBudgetAndCannotStealFinalSlot() throws Exception {
        TaskExecution task = new TaskExecution(4, 10000, 10000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 10).mapToObj(i -> (Callable<Boolean>) () -> {
                try (var scope = TaskExecution.attach(task)) {
                    try { task.acquire(1); return true; } catch (TaskExecution.BudgetExceeded e) { return false; }
                }
            }).toList();
            int admitted = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) if (f.get()) admitted++;
            assertEquals(3, admitted);
            task.beginFinal("finish");
            assertFalse(pool.submit(() -> { try { task.acquire(1); return true; } catch (TaskExecution.BudgetExceeded e) { return false; } }).get());
            task.acquire(1); assertEquals(4, task.calls());
        } finally { pool.shutdownNow(); }
    }
    @Test void windowAndInputLimitsDoNotGetAFreeFinalRequest() {
        TaskExecution task = new TaskExecution(4, 10, 8);
        assertThrows(TaskExecution.BudgetExceeded.class, () -> task.acquire(9));
        task.acquire(8); task.beginFinal("finish");
        assertThrows(TaskExecution.BudgetExceeded.class, () -> task.acquire(3));
        assertEquals(1, task.calls());
        assertTrue(task.fallback("预算不足").contains("未确认完整交付"));
    }
    @Test void actualSubagentPoolCarriesAndClearsTheSharedLedger() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        SubagentPool pool = new SubagentPool(1, workers);
        TaskExecution task = new TaskExecution(3, 10000, 10000);
        try {
            Future<SubagentResult> job;
            try (var scope = TaskExecution.attach(task)) {
                job = pool.trySubmit(null, () -> {
                    assertSame(task, TaskExecution.current());
                    TaskExecution.current().acquire(1);
                    return null;
                });
            }
            job.get(3, TimeUnit.SECONDS);
            assertEquals(1, task.calls());
            assertNull(workers.submit(TaskExecution::current).get());
        } finally { workers.shutdownNow(); pool.destroy(); }
    }
    @Test void retriesSpendIndividualSlotsAndSdkDoesNotRetryInvisibly() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenThrow(new org.springframework.web.client.ResourceAccessException("offline"));
        TaskExecution task = new TaskExecution(3, 10000, 10000);
        var previous = RetryingChatModel.sleeper;
        RetryingChatModel.sleeper = millis -> {};
        try (var scope = TaskExecution.attach(task)) {
            assertThrows(TaskExecution.BudgetExceeded.class, () -> TaskModelCalls.call(RetryingChatModel.wrap(delegate), new Prompt("执行")));
            verify(delegate, times(2)).call(any(Prompt.class));
            assertEquals(2, task.calls());
        } finally { RetryingChatModel.sleeper = previous; }
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var sdkRetry = new com.example.jingjingai2.config.AiConfig().retryTemplate();
        assertThrows(IllegalStateException.class, () -> sdkRetry.execute(ctx -> {
            attempts.incrementAndGet(); throw new IllegalStateException("failure");
        }));
        assertEquals(1, attempts.get());
    }
}
