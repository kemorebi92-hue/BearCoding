package com.example.jingjingai2.agent;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 主循环的两项保护，纯函数，流式和非流式共用。
 *
 * <p>重复调用：同一组工具名+参数连续出现 3 次时先给模型一句提示，让它换思路或收尾。
 * 提示之后还是同一组，才停下。累计输入预算：超过配置就让模型用文字总结，不再给工具。
 */
public final class LoopGuard {

    public static final String REPEAT_REFUSAL =
            "未执行：这组参数已经连续出现。请换一种做法，或直接总结当前进展并结束，不要再用同一组参数重试。";

    public static final String REPEAT_HINT =
            "你已连续 3 次用相同参数调用同一批工具。请换一种做法，或者直接总结当前进展并结束。"
                    + "不要再用同一组参数重试。";

    public enum Action { NONE, HINT, STOP }

    public static final class State {
        public String lastSig;
        public int streak;
        public boolean hinted;
    }

    public static final class Decision {
        public final Action action;
        public final String lastSig;
        public final int streak;
        public final boolean hinted;

        public Decision(Action action, String lastSig, int streak, boolean hinted) {
            this.action = action;
            this.lastSig = lastSig;
            this.streak = streak;
            this.hinted = hinted;
        }
    }

    private LoopGuard() {}

    public static String signature(List<AssistantMessage.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AssistantMessage.ToolCall tc : calls) {
            if (tc == null) continue;
            sb.append(tc.name()).append('|').append(tc.arguments()).append(";;");
        }
        return sb.toString();
    }

    /**
     * 第三次相同（streak 从 0 起，连续两次相等后 streak 变成 2）且还没提示过 → HINT。
     * 提示过之后再次达到 2 → STOP。
     */
    public static Decision observe(State state, String sig) {
        State s = state == null ? new State() : state;
        boolean same = sig != null && sig.equals(s.lastSig);
        int streak = same ? s.streak + 1 : 0;
        String last = same ? s.lastSig : sig;
        Action action = Action.NONE;
        boolean hinted = s.hinted;
        if (streak >= 2 && !s.hinted) {
            action = Action.HINT;
            hinted = true;
        } else if (streak >= 2) {
            action = Action.STOP;
        }
        return new Decision(action, last, streak, hinted);
    }

    public static void apply(State state, Decision d) {
        if (state == null || d == null) return;
        state.lastSig = d.lastSig;
        state.streak = d.streak;
        state.hinted = d.hinted;
    }

    /** budget ≤ 0 表示关掉这项保护。 */
    public static boolean overInputBudget(long cumulativePromptTokens, long budget) {
        return budget > 0 && cumulativePromptTokens >= budget;
    }

    public static String inputBudgetNote(long cumulative, long budget) {
        return "累计输入 token 已达到本任务预算（" + cumulative + " / " + budget
                + "）。不要再调用工具。用文字总结当前进展、已经完成的部分，以及还没做完的部分。";
    }
}
