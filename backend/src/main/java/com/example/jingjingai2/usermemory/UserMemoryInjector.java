package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.memory.MemoryItemObservation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把跨会话用户记忆插进本轮 prompt。{@code /chat} 和 {@code /stream} 共用这一个方法。
 *
 * <p>输入是压缩<strong>之后</strong>的内存 {@code List<Message>}，不是 Compactor 吃的
 * {@code List<MessageRecord>}。Compactor 只压缩 history（库里的 user/assistant），
 * 本条 SystemMessage 不会被当成 history 再压一遍，也不会走 {@code addMessage}
 * 落 {@code conversation_message}。
 *
 * <p>fail-closed：开关关 / 没条目 / 读库失败 → 不插、不抛，本轮对话照常走。
 */
@Component
public class UserMemoryInjector {

    /** 注入文案固定开头。单测和幂等摘除都认这个前缀。 */
    public static final String PREFIX = "【用户记忆】";

    static final String HEADER = PREFIX
            + "\n以下是跨会话保留的事实，作参考；过时的不要沿用，与本轮冲突以本轮为准。\n";

    private final UserMemoryService service;
    private final UserMemoryProperties props;

    /** 最近一次注入的条目级观测。不含正文。测试和日志用，正式 prompt 不读它。 */
    private volatile List<MemoryItemObservation> lastObservations = List.of();

    public UserMemoryInjector(UserMemoryService service, UserMemoryProperties props) {
        this.service = service;
        this.props = props;
    }

    /**
     * 在 systemPrompt 那条之后、计划注入之前，插入至多一条用户记忆 SystemMessage。
     * 调用方负责先 {@code messages.add(0, systemPrompt)}。
     */
    public void injectUserMemory(List<Message> messages, Long userId) {
        if (messages == null) return;
        try {
            if (!props.isEnabled()) return;
            if (userId == null) return;
            Map<UserMemoryCategory, List<UserMemory>> grouped = service.listGrouped(userId);
            RenderResult rendered = renderObserved(grouped, props.getMaxInjectChars());
            lastObservations = rendered.observations;
            int injected = 0;
            int skipped = 0;
            int injectedChars = 0;
            for (MemoryItemObservation o : rendered.observations) {
                if (o.injected()) {
                    injected++;
                    injectedChars += o.chars();
                } else {
                    skipped++;
                }
                System.out.println("[UserMemoryItem] " + o.toLogLine());
            }
            System.out.println("[UserMemory] userId=" + userId
                    + " injected=" + injected + " skipped=" + skipped
                    + " itemChars=" + injectedChars);
            if (rendered.text == null || rendered.text.isBlank()) return;
            // 幂等：本轮只留一条（防止两个入口或测试重复调用叠两条）
            messages.removeIf(m -> m instanceof SystemMessage sm
                    && sm.getText() != null && sm.getText().startsWith(PREFIX));
            int at = (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) ? 1 : 0;
            messages.add(at, new SystemMessage(rendered.text));
            System.out.println("[UserMemory] 注入 userId=" + userId + " chars=" + rendered.text.length());
        } catch (Exception e) {
            lastObservations = List.of();
            System.err.println("[UserMemory] 注入失败，本轮跳过: " + e.getMessage());
        }
    }

    /** 最近一次 {@link #injectUserMemory} 的条目观测。失败或未注入时为空。 */
    public List<MemoryItemObservation> lastObservations() {
        return lastObservations;
    }

    /**
     * 按 constraint → preference → profile → project_context 渲染。
     * 超 {@code maxChars} 时丢掉后面的条目（整条丢，不截半句）。一条都放不下则返回 null。
     */
    public static String render(Map<UserMemoryCategory, List<UserMemory>> grouped, int maxChars) {
        return renderObserved(grouped, maxChars).text;
    }

    /** 渲染结果：正式文案与每条的去向。文案规则与改造前的 {@link #render} 一致。 */
    static final class RenderResult {
        final String text;
        final List<MemoryItemObservation> observations;

        RenderResult(String text, List<MemoryItemObservation> observations) {
            this.text = text;
            this.observations = List.copyOf(observations);
        }
    }

    static RenderResult renderObserved(Map<UserMemoryCategory, List<UserMemory>> grouped, int maxChars) {
        if (grouped == null || grouped.isEmpty()) return new RenderResult(null, List.of());
        int cap = maxChars <= 0 ? Integer.MAX_VALUE : maxChars;
        StringBuilder sb = new StringBuilder(HEADER);
        List<MemoryItemObservation> observations = new ArrayList<>();
        if (sb.length() > cap) return new RenderResult(null, observations);
        boolean anyItem = false;
        boolean budgetExhausted = false;
        for (UserMemoryCategory cat : UserMemoryCategory.values()) {
            List<UserMemory> items = grouped.get(cat);
            if (items == null || items.isEmpty()) continue;
            String heading = "\n" + cat.wire() + ":\n";
            boolean headingPending = true;
            for (UserMemory item : items) {
                if (item == null) continue;
                String key = item.getMemKey() == null ? "" : item.getMemKey();
                String content = item.getContent() == null ? "" : item.getContent();
                String line = "- " + key + ": " + content + "\n";
                int extra = (headingPending ? heading.length() : 0) + line.length();
                boolean kept = !budgetExhausted && sb.length() + extra <= cap;
                observations.add(observe(cat, key, content, kept,
                        kept ? "" : "over-budget"));
                if (!kept) {
                    budgetExhausted = true;
                    continue;
                }
                if (headingPending) {
                    sb.append(heading);
                    headingPending = false;
                }
                sb.append(line);
                anyItem = true;
            }
        }
        return new RenderResult(anyItem ? sb.toString() : null, observations);
    }

    private static MemoryItemObservation observe(UserMemoryCategory cat, String key,
                                                 String content, boolean injected, String skip) {
        return new MemoryItemObservation(
                "user", "user:" + cat.wire() + ":" + key, cat.wire(), key,
                content.length(), com.example.jingjingai2.context.TokenEstimator.estimate(content),
                injected, skip, cat == UserMemoryCategory.CONSTRAINT);
    }
}
