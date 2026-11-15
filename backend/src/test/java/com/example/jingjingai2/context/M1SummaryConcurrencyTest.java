package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 专项：摘要的跨请求复用槽与「最近一次摘要」观测在并发下的归属。
 *
 * <p>{@code ContextCompactor} 是 Spring 单例。生产里同一个用户的两个会话可以同时跑
 * （互斥锁只按 conversationId 生效），所以必须回答一个问题：
 * <strong>不同会话并发装配时，摘要正文、覆盖范围、用量会不会串到另一个请求上？</strong>
 *
 * <p>摘要正文本身是 {@code assemble} 的返回值，天然按请求隔离；但
 * {@code lastHistorySummary()} / {@code takeSummaryUsage()} 是「最近一次」观测，
 * 控制器在装配之后立刻读取它们来决定「哪些原文已被摘要覆盖」和「摘要失败时这一轮还能不能发」。
 * 如果它们跨请求共享，就会把 A 的用量记到 B 头上，或在 A 摘要失败时漏掉硬窗口拦截。
 */
class M1SummaryConcurrencyTest {

    private static final int TURNS = 20;
    private static final int CHARS = 1500;

    @Test
    @DisplayName("两个会话并发装配：摘要正文、覆盖范围、用量都必须各归各的")
    void concurrentAssembleKeepsEachRequestOwnSummary() throws Exception {
        ThreadLocal<String> perThreadSummary = new ThreadLocal<>();
        AtomicInteger calls = new AtomicInteger();
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            String text = perThreadSummary.get();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text == null ? "空" : text))));
        });
        ContextCompactor compactor = new ContextCompactor(model, 24_000);

        List<MessageRecord> history = longHistory();
        CountDownLatch aAssembled = new CountDownLatch(1);
        CountDownLatch bAssembled = new CountDownLatch(1);
        AtomicReference<List<Message>> aMessages = new AtomicReference<>();
        AtomicReference<List<Message>> bMessages = new AtomicReference<>();
        AtomicReference<ContextCompactor.SummaryUsage> aUsage = new AtomicReference<>();
        AtomicReference<ContextCompactor.SummaryUsage> bUsage = new AtomicReference<>();
        AtomicReference<ContextCompactor.HistorySummaryStats> aStats = new AtomicReference<>();
        AtomicReference<ContextCompactor.HistorySummaryStats> bStats = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try {
                perThreadSummary.set("会话A的摘要：" + "甲".repeat(200));
                aMessages.set(compactor.assemble(history, "当前问题A", "1|convA"));
                aAssembled.countDown();
                // B 在自己的线程上装配完成之后，A 才去取自己的观测值。
                assertTrue(bAssembled.await(5, TimeUnit.SECONDS), "B 没有在预期时间内装配完成");
                aStats.set(compactor.lastHistorySummary());
                aUsage.set(compactor.takeSummaryUsage());
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "m1-thread-A");

        Thread b = new Thread(() -> {
            try {
                assertTrue(aAssembled.await(5, TimeUnit.SECONDS), "A 没有在预期时间内装配完成");
                perThreadSummary.set("会话B的摘要：" + "乙".repeat(200));
                bMessages.set(compactor.assemble(history, "当前问题B", "1|convB"));
                bStats.set(compactor.lastHistorySummary());
                bUsage.set(compactor.takeSummaryUsage());
                bAssembled.countDown();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                bAssembled.countDown();
            }
        }, "m1-thread-B");

        a.start();
        b.start();
        a.join(10_000);
        b.join(10_000);
        if (failure.get() != null) throw new AssertionError(failure.get());

        // 摘要正文：返回的列表按请求隔离，这一点在并发下也必须成立。
        assertTrue(textOf(aMessages.get()).contains("会话A的摘要"), "A 的装配结果里必须是 A 的摘要");
        assertTrue(textOf(bMessages.get()).contains("会话B的摘要"), "B 的装配结果里必须是 B 的摘要");
        assertFalse(textOf(aMessages.get()).contains("会话B的摘要"), "A 的 Prompt 里不能出现 B 的摘要");
        assertFalse(textOf(bMessages.get()).contains("会话A的摘要"), "B 的 Prompt 里不能出现 A 的摘要");

        // 覆盖范围：A 读到的必须是 A 自己的统计，不能被 B 的装配覆盖。
        assertEquals("1|convA", aStats.get().scope(), "A 读到的覆盖范围必须属于 convA：" + aStats.get());
        assertEquals("1|convB", bStats.get().scope(), "B 读到的覆盖范围必须属于 convB：" + bStats.get());

        // 用量：两个请求都真的调用了摘要模型，各自都必须拿到自己的那一份。
        assertEquals(2, calls.get(), "两个不同会话各调用一次摘要模型，不能互相复用");
        assertNotNull(aUsage.get(), "A 必须能取到自己的摘要用量（不能被 B 清掉）");
        assertNotNull(bUsage.get(), "B 必须能取到自己的摘要用量");
        assertTrue(aUsage.get().text().contains("会话A的摘要"),
                "A 取到的用量必须属于 A，实际=" + aUsage.get().text());
        assertTrue(bUsage.get().text().contains("会话B的摘要"),
                "B 取到的用量必须属于 B，实际=" + bUsage.get().text());
    }

    @Test
    @DisplayName("同一会话范围复用摘要，不同会话范围不互相命中")
    void cacheIsKeyedByScope() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("摘要" + calls.get() + "：" + "丙".repeat(200)))));
        });
        ContextCompactor compactor = new ContextCompactor(model, 24_000);
        List<MessageRecord> history = longHistory();

        compactor.assemble(history, "当前问题", "1|convA");
        assertEquals(1, calls.get(), "第一次装配要调用一次摘要模型");
        ContextCompactor.HistorySummaryStats first = compactor.lastHistorySummary();
        assertTrue(first.applied());

        List<Message> again = compactor.assemble(history, "当前问题", "1|convA");
        assertEquals(1, calls.get(), "同一会话同一段历史不得重复调用摘要模型");
        assertTrue(compactor.lastHistorySummary().reused(), "第二次必须复用");
        assertEquals(0, compactor.lastHistorySummary().calls());
        assertTrue(textOf(again).contains("摘要1"), "复用的就是那一版摘要");

        compactor.assemble(history, "当前问题", "2|convB");
        assertEquals(2, calls.get(), "另一个会话不能命中 A 的摘要");
        assertFalse(compactor.lastHistorySummary().reused(), "另一个会话必须自己摘要一次");
    }

    @Test
    @DisplayName("摘要失败：用量标记为 fallback，原始历史原样保留")
    void failureKeepsOriginalHistoryAndReportsFallback() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型不可用"));
        ContextCompactor compactor = new ContextCompactor(model, 24_000);
        List<MessageRecord> history = longHistory();

        List<Message> messages = compactor.assemble(history, "当前问题", "1|convA");

        assertFalse(compactor.lastHistorySummary().applied(), "失败时不能产生覆盖");
        assertTrue(compactor.lastHistorySummary().triggered(), "触发条件确实达到了");
        assertTrue(messages.stream().noneMatch(m -> m.getText() != null && m.getText().startsWith("[历史摘要]")),
                "失败时不能插入半截摘要");
        assertTrue(textOf(messages).contains("第0轮用户约束"), "原始历史必须原样保留");
        ContextCompactor.SummaryUsage usage = compactor.takeSummaryUsage();
        assertNotNull(usage, "失败也要把状态交给硬窗口判断");
        assertTrue(usage.fallback(), "必须标记为 fallback");
    }

    // ===================== helpers =====================

    private static List<MessageRecord> longHistory() {
        List<MessageRecord> rows = new ArrayList<>();
        long id = 1;
        for (int turn = 0; turn < TURNS; turn++) {
            rows.add(row(id++, "user", "第" + turn + "轮用户约束：" + "约".repeat(CHARS)));
            rows.add(row(id++, "assistant", "第" + turn + "轮助手结论：" + "答".repeat(CHARS)));
        }
        return rows;
    }

    private static MessageRecord row(long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(id * 1000));
        return record;
    }

    private static String textOf(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        if (messages == null) return "";
        for (Message message : messages) sb.append(message.getText() == null ? "" : message.getText()).append('\n');
        return sb.toString();
    }
}
