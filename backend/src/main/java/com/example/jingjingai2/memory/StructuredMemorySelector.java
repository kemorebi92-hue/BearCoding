package com.example.jingjingai2.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 结构化记忆检索原型（Phase 4-2）。独立选择层，不接入正式注入。
 *
 * <p>这不是语义检索，也不做自动任务理解。任务侧的项目与主题
 * （{@link TaskContext}）由调用方显式提供——离线评估里是人工标注的任务标签。
 * 选择器只做结构化字段的确定性匹配：
 * <ol>
 *   <li>硬隔离：userId、workspace 对不上的条目直接排除，不进评分；</li>
 *   <li>硬约束（{@code protectedItem}）先占预算，只受隔离约束；</li>
 *   <li>显式替代关系（{@code supersedesId}）指向的条目标 SUPERSEDED，不选；</li>
 *   <li>主题对得上才选；主题对不上但文本有词重合的标 UNCERTAIN（词面嫌疑，不判无关）；</li>
 *   <li>没有任何结构化信号的条目标 UNCERTAIN，不擅自淘汰也不擅自选中。</li>
 * </ol>
 *
 * <p>与 Phase 4-1 的 {@link MemoryShadowSelector} 并存，供离线对比；不替换它。
 */
public final class StructuredMemorySelector {

    private StructuredMemorySelector() {}

    /** 一条记忆的有效状态。UNKNOWN 表示数据里看不出来。 */
    public enum Status { ACTIVE, SUPERSEDED, UNKNOWN }

    /**
     * 当前任务的结构化描述。全部由调用方提供。
     *
     * @param userId         当前用户；null 表示不按用户过滤（只在评估里出现）
     * @param workspace      当前工作区标识；null 表示不按工作区过滤
     * @param topics         任务主题标签。空列表表示调用方未能判断主题
     * @param labelsSupplied true 表示 topics/workspace 是人工标注而非自动提取
     */
    public static final class TaskContext {
        public final Long userId;
        public final String workspace;
        public final List<String> topics;
        public final boolean labelsSupplied;

        public TaskContext(Long userId, String workspace, List<String> topics, boolean labelsSupplied) {
            this.userId = userId;
            this.workspace = workspace;
            this.topics = topics == null ? List.of() : List.copyOf(topics);
            this.labelsSupplied = labelsSupplied;
        }
    }

    /**
     * 带结构化属性的候选。现有 {@link MemoryItem} 没有这些字段，
     * 原型不改它，属性由评估数据（或未来的元数据）显式给出。
     *
     * @param ownerUserId  归属用户；null 表示工作区级、不按用户隔离
     * @param workspace    归属工作区；null 表示用户级、跨工作区通用
     * @param topic        主题标签；null 表示未知主题
     * @param status       有效状态
     * @param supersedesId 本条替代的旧条目 id；没有则为 null
     */
    public static final class Candidate {
        public final MemoryItem item;
        public final Long ownerUserId;
        public final String workspace;
        public final String topic;
        public final Status status;
        public final String supersedesId;

        public Candidate(MemoryItem item, Long ownerUserId, String workspace,
                         String topic, Status status, String supersedesId) {
            this.item = item;
            this.ownerUserId = ownerUserId;
            this.workspace = workspace;
            this.topic = topic;
            this.status = status == null ? Status.UNKNOWN : status;
            this.supersedesId = supersedesId;
        }
    }

    public enum Decision { SELECTED, EXCLUDED, UNCERTAIN, SUPERSEDED }

    public static final class Verdict {
        public final Candidate candidate;
        public final Decision decision;
        public final String reason;

        Verdict(Candidate candidate, Decision decision, String reason) {
            this.candidate = candidate;
            this.decision = decision;
            this.reason = reason;
        }
    }

    public static final class Result {
        public final List<Verdict> verdicts;
        public final int selectedChars;
        public final int selectedTokens;
        public final boolean labelsSupplied;

        Result(List<Verdict> verdicts, int selectedChars, int selectedTokens, boolean labelsSupplied) {
            this.verdicts = List.copyOf(verdicts);
            this.selectedChars = selectedChars;
            this.selectedTokens = selectedTokens;
            this.labelsSupplied = labelsSupplied;
        }
    }

    public static final String REASON_PROTECTED = "protected";
    public static final String REASON_TOPIC_MATCH = "topic-match";
    public static final String REASON_OTHER_WORKSPACE = "other-workspace";
    public static final String REASON_OTHER_USER = "other-user";
    public static final String REASON_SUPERSEDED = "superseded-by-newer";
    public static final String REASON_TOPIC_MISMATCH = "topic-mismatch-keyword-overlap";
    public static final String REASON_NO_SIGNAL = "no-structured-signal";
    public static final String REASON_NO_TASK_TOPIC = "task-topic-unknown";
    public static final String REASON_BUDGET = "over-budget";

    /**
     * @param budgetChars 选中条目正文字符上限；≤0 视为不设上限
     */
    public static Result select(TaskContext task, List<Candidate> candidates, int budgetChars) {
        List<Candidate> safe = candidates == null ? List.of() : candidates;
        int cap = budgetChars <= 0 ? Integer.MAX_VALUE : budgetChars;

        List<String> supersededIds = new ArrayList<>();
        for (Candidate c : safe) {
            if (c != null && c.supersedesId != null && !c.supersedesId.isBlank() && inScope(task, c)) {
                supersededIds.add(c.supersedesId);
            }
        }

        List<Verdict> verdicts = new ArrayList<>();
        int used = 0;
        List<Candidate> rest = new ArrayList<>();
        for (Candidate c : safe) {
            if (c == null || c.item == null || c.item.content().isBlank()) continue;
            if (c.item.protectedItem() && inScope(task, c) && !supersededIds.contains(c.item.id())) {
                Verdict v = place(c, Decision.SELECTED, REASON_PROTECTED, used, cap);
                if (v.decision == Decision.SELECTED) used += c.item.chars();
                verdicts.add(v);
            } else {
                rest.add(c);
            }
        }

        for (Candidate c : rest) {
            if (!inScope(task, c)) {
                String reason = otherUser(task, c) ? REASON_OTHER_USER : REASON_OTHER_WORKSPACE;
                verdicts.add(new Verdict(c, Decision.EXCLUDED, reason));
                continue;
            }
            if (c.status == Status.SUPERSEDED || supersededIds.contains(c.item.id())) {
                verdicts.add(new Verdict(c, Decision.SUPERSEDED, REASON_SUPERSEDED));
                continue;
            }
            if (task.topics.isEmpty()) {
                verdicts.add(new Verdict(c, Decision.UNCERTAIN, REASON_NO_TASK_TOPIC));
                continue;
            }
            if (c.topic == null || c.topic.isBlank()) {
                verdicts.add(new Verdict(c, Decision.UNCERTAIN, REASON_NO_SIGNAL));
                continue;
            }
            if (topicMatches(task, c)) {
                Verdict v = place(c, Decision.SELECTED, REASON_TOPIC_MATCH, used, cap);
                if (v.decision == Decision.SELECTED) used += c.item.chars();
                verdicts.add(v);
                continue;
            }
            if (keywordOverlap(task, c) > 0) {
                verdicts.add(new Verdict(c, Decision.UNCERTAIN, REASON_TOPIC_MISMATCH));
            } else {
                verdicts.add(new Verdict(c, Decision.EXCLUDED, REASON_TOPIC_MISMATCH));
            }
        }

        int tokens = 0;
        for (Verdict v : verdicts) if (v.decision == Decision.SELECTED) tokens += v.candidate.item.tokens();
        return new Result(verdicts, used == Integer.MAX_VALUE ? 0 : used, tokens, task.labelsSupplied);
    }

    /** 用户级条目（workspace 为空）跟着用户走，不算跨工作区。 */
    private static boolean inScope(TaskContext task, Candidate c) {
        if (otherUser(task, c)) return false;
        if (task.workspace == null || c.workspace == null) return true;
        return task.workspace.equals(c.workspace);
    }

    private static boolean otherUser(TaskContext task, Candidate c) {
        return task.userId != null && c.ownerUserId != null && !task.userId.equals(c.ownerUserId);
    }

    private static boolean topicMatches(TaskContext task, Candidate c) {
        for (String t : task.topics) if (t.equalsIgnoreCase(c.topic)) return true;
        return false;
    }

    /** 只用于「主题不符时是否标不确定」，不作为选中依据。 */
    private static int keywordOverlap(TaskContext task, Candidate c) {
        String haystack = (c.item.ref() + "\n" + c.item.content()).toLowerCase(Locale.ROOT);
        int n = 0;
        for (String topic : task.topics) {
            if (topic.length() >= 2 && haystack.contains(topic.toLowerCase(Locale.ROOT))) n++;
        }
        return n;
    }

    private static Verdict place(Candidate c, Decision wanted, String reason, int used, int cap) {
        if ((long) used + c.item.chars() > cap) {
            return new Verdict(c, Decision.EXCLUDED, REASON_BUDGET);
        }
        return new Verdict(c, wanted, reason);
    }
}
