package com.example.jingjingai2.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 任务主题的确定性识别（Phase 4-3A）。不调用模型。
 *
 * <p>只做一件事：拿调用方给的<b>封闭词表</b>去当前用户消息里找整词命中。
 * 词表来自已有数据里能直接读到的东西（用户记忆的 memKey、工作区记忆的文件名），
 * 不是从正文猜出来的主题。
 *
 * <p>三种结果互斥：
 * <ul>
 *   <li>{@link Kind#IDENTIFIED}：恰好命中一个词，主题确定；</li>
 *   <li>{@link Kind#AMBIGUOUS}：命中两个及以上，<b>不挑一个</b>，交给调用方标不确定；</li>
 *   <li>{@link Kind#UNKNOWN}：一个都没命中，明确表示无法判断。</li>
 * </ul>
 *
 * <p>人工标签不走这里。本类不产出 {@code labelsSupplied=true} 的结果。
 */
public final class TaskTopicIdentifier {

    private TaskTopicIdentifier() {}

    public enum Kind { IDENTIFIED, AMBIGUOUS, UNKNOWN }

    /**
     * @param kind   识别结果的性质
     * @param topics 命中的词。IDENTIFIED 恰好一个，AMBIGUOUS 至少两个，UNKNOWN 为空
     */
    public record Identification(Kind kind, List<String> topics) {

        public Identification {
            topics = topics == null ? List.of() : List.copyOf(topics);
        }

        /** 转成选择器的任务上下文。识别不出或有歧义时 topics 留空，选择器会走 task-topic-unknown。 */
        public StructuredMemorySelector.TaskContext toContext(Long userId, String workspace) {
            List<String> usable = kind == Kind.IDENTIFIED ? topics : List.of();
            return new StructuredMemorySelector.TaskContext(userId, workspace, usable, false);
        }
    }

    /**
     * @param message  当前用户消息。null 或空白视为无法判断
     * @param lexicon  封闭词表。null 或空视为没有可识别的主题
     */
    public static Identification identify(String message, List<String> lexicon) {
        if (message == null || message.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return new Identification(Kind.UNKNOWN, List.of());
        }
        String haystack = message.toLowerCase(Locale.ROOT);
        Set<String> hits = new LinkedHashSet<>();
        for (String term : lexicon) {
            if (term == null) continue;
            String t = term.trim().toLowerCase(Locale.ROOT);
            if (t.length() < 2) continue;
            if (haystack.contains(t)) hits.add(t);
        }
        if (hits.isEmpty()) return new Identification(Kind.UNKNOWN, List.of());
        if (hits.size() == 1) return new Identification(Kind.IDENTIFIED, new ArrayList<>(hits));
        return new Identification(Kind.AMBIGUOUS, new ArrayList<>(hits));
    }
}
