package com.example.jingjingai2.utils;

/**
 * 决定"这一轮该注入完整清单、还是只注入一行摘要"。
 *
 * 为什么单独抽出来：这是改动 3 的核心规则，也是最容易写错的地方
 * （写错就会导致"清单不刷新 → 模型拿到过期进度"或"每轮都全量 → 省不下 token"）。
 * 抽成**纯函数**后可以直接用单测把规则钉死，不必靠跑真实对话去验证。
 *
 * 三条"必须给全量"的理由：
 * <ol>
 *   <li><b>版本变了</b>：某个步骤状态发生了变化（☐→▶→☑ / 失败 / 阻塞 / 重拆），
 *       清单内容真的不一样了，必须整张重发，否则模型看到的是过期进度。</li>
 *   <li><b>距上次全量满 N 轮</b>：兜底刷新。哪怕状态一直没变，也隔一段时间重发一次，
 *       避免长时间只给一行摘要导致模型丢掉全局坐标（N = app.plan.injection.full-refresh-every）。</li>
 *   <li><b>刚发生过上下文压缩</b>：历史被压成摘要后，原来注入在上下文里的清单可能已被压缩掉，
 *       此时必须立刻重新给一份完整的。</li>
 * </ol>
 * 其余情况一律只给一行摘要 —— 这才是省 token 的地方。
 */
public final class PlanInjectionPlanner {

    /** 注入形态 */
    public enum Mode {
        /** 注入完整清单（进度行 + 每步一行 + 预算提醒） */
        FULL,
        /** 只注入一行摘要（进度 x/y | 当前步骤 | 剩余预算） */
        ONE_LINE
    }

    private PlanInjectionPlanner() {}

    /**
     * @param planVersion        当前计划版本号（步骤状态每变一次 +1）
     * @param lastInjectedVersion 上一次注入时用的版本号（从未注入过传 -1）
     * @param roundsSinceFull    距离上一次全量注入已经过了多少轮（从未全量过传一个很大的数）
     * @param fullRefreshEvery   兜底刷新间隔（来自配置 app.plan.injection.full-refresh-every）
     * @param contextCompacted   本轮是否刚刚发生过上下文压缩/截断
     */
    public static Mode decide(int planVersion,
                              int lastInjectedVersion,
                              int roundsSinceFull,
                              int fullRefreshEvery,
                              boolean contextCompacted) {
        if (contextCompacted) return Mode.FULL;                          // ① 刚压缩过
        if (fullRefreshEvery <= 0) return Mode.FULL;                     // 配置为 0/负数 → 关闭优化，退回"每轮全量"
        if (planVersion != lastInjectedVersion) return Mode.FULL;        // ② 清单内容变了
        if (roundsSinceFull >= fullRefreshEvery) return Mode.FULL;       // ③ 兜底刷新
        return Mode.ONE_LINE;
    }
}
