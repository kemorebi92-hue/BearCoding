package com.example.jingjingai2.agent;

/**
 * 一次子代理派出的输入。
 *
 * 刻意**不带**聊天历史、计划清单、nudge 状态 —— 那些是主循环的事。
 * 子代理拿到的只有：要干什么 + 模式（决定工具白名单）+ 轮数预算。
 * 历史只在 **resume** 时由 {@link SubagentSessions} 提供，且只给同一个房间的续跑用。
 *
 * 第一版只有一个 {@code goal}，实测发现它太容易写成"找出所有和重试有关的逻辑"这种**宽泛目标** ——
 * 于是有了四个正交字段（对标 Claude Code 的委托契约）：
 * <ul>
 *   <li>{@code goal}：一句话目标（仍保留，它是"同一目标"的计数依据）</li>
 *   <li>{@code question}：**一个具体问题**（能一句话回答的那种）</li>
 *   <li>{@code scope}：路径或 glob（把搜索面钉死，防止"全项目扫一遍"）</li>
 *   <li>{@code doneWhen}：什么算查清（子代理据此自己判断"能不能收工了"）</li>
 * </ul>
 */
public final class SubagentSpec {

    /** 子代理的两种形态 */
    public enum Mode {
        /** 只读调研：可以并行，最多 max-concurrent 个同时跑 */
        EXPLORE,
        /** 动手改：**不允许并行**，同时只允许一个（写操作互斥） */
        IMPLEMENT;

        public boolean isReadOnly() { return this == EXPLORE; }

        /** 容错解析：模型可能写 explore/Explore/只读/impl/implement/write… */
        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) return EXPLORE;
            String s = raw.trim().toLowerCase();
            if (s.startsWith("impl") || s.startsWith("write") || s.contains("写") || s.contains("改")) {
                return IMPLEMENT;
            }
            return EXPLORE;
        }
    }

    private final String goal;
    private final String question;
    private final String scope;
    private final String doneWhen;
    private final Mode mode;
    private final int maxRounds;
    private final String conversationId;
    private final String thoroughness;
    /** 续跑凭据（空 = 新房间）。由 {@link SubagentSessions} 发，主 agent 原样抄回来 */
    private final String resumeTaskId;
    /** 归一化后的"同一目标" key（重派计数与上次笔记都按它索引） */
    private final String goalKey;

    /** 旧的四参构造器（保留：现有调用点与单测都按它写） */
    public SubagentSpec(String goal, Mode mode, int maxRounds, String conversationId) {
        this(goal, null, null, null, mode, maxRounds, conversationId, null, null);
    }

    public SubagentSpec(String goal, String question, String scope, String doneWhen,
                        Mode mode, int maxRounds, String conversationId,
                        String thoroughness, String resumeTaskId) {
        this.goal = clean(goal);
        this.question = clean(question);
        this.scope = clean(scope);
        this.doneWhen = clean(doneWhen);
        this.mode = mode == null ? Mode.EXPLORE : mode;
        this.maxRounds = maxRounds;
        this.conversationId = conversationId;
        this.thoroughness = clean(thoroughness);
        this.resumeTaskId = clean(resumeTaskId);
        // goal 为空时用 question 兜底：模型只填了 question 也该算一个目标
        String base = this.goal.isBlank() ? this.question : this.goal;
        this.goalKey = this.question.isBlank() || this.goal.isBlank()
                ? SubagentMemory.normalize(base)
                : SubagentMemory.normalize(base) + " | " + SubagentMemory.normalize(this.question);
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    public String goal() { return goal; }
    public String question() { return question; }
    public String scope() { return scope; }
    public String doneWhen() { return doneWhen; }
    public Mode mode() { return mode; }
    public int maxRounds() { return maxRounds; }
    public String conversationId() { return conversationId; }
    public String thoroughness() { return thoroughness; }
    public String resumeTaskId() { return resumeTaskId; }
    public String goalKey() { return goalKey; }

    /** 宽泛目标闸用的 key：**只看 goal**（同一个宽泛目标第二次不再拦，避免死循环） */
    public String vagueKey() { return SubagentMemory.normalize(goal.isBlank() ? question : goal); }

    /** 有没有填 scope（没填才需要过宽泛闸） */
    public boolean hasScope() { return !scope.isBlank(); }

    /** 目标是否"宽泛"（含"所有/全部/整个/彻底/find all"这类词）—— 纯函数，便于单测 */
    public static boolean looksBroad(String text) {
        if (text == null || text.isBlank()) return false;
        String s = text.toLowerCase();
        for (String w : BROAD_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    private static final String[] BROAD_WORDS = {
            "所有", "全部", "整个", "全项目", "全库", "彻底", "一切", "各个", "任意",
            "find all", "all of", "everything", "every ", "whole project", "entire"};

    /**
     * 房间里那段"这一件事"的完整交代（自包含：子代理看不到主对话，也**不知道主 agent 已经读过什么**）。
     * 四个字段按信息量排序，没填的就不出现 —— 不写"（未填）"这种空话去占 token。
     */
    public String roomTaskText(String modeHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(goal.isBlank() ? "(未填)" : goal).append('\n');
        if (!question.isBlank()) sb.append("要回答的具体问题：").append(question).append('\n');
        if (!scope.isBlank()) sb.append("范围（只看这里，不要越界）：").append(scope).append('\n');
        if (doneWhen.isBlank()) {
            sb.append("完成判据：能一句话回答上面的问题，并给出文件路径+行号作为依据。\n");
        } else {
            sb.append("完成判据：").append(doneWhen).append('\n');
        }
        sb.append("模式：").append(modeHint);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "SubagentSpec[" + mode.name().toLowerCase() + " goal=" + goal
                + " scope=" + scope + " rounds=" + maxRounds + "]";
    }
}
