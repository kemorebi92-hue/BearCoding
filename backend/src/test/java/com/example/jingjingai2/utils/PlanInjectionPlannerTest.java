package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「何时全量注入、何时只给一行」的裁决规则测试。
 *
 * 为什么重点测它：这条规则两头都会出错 ——
 *  判太松（该全量却给一行）→ 模型拿到过期进度，基于错误坐标继续干活；
 *  判太紧（每轮都全量）→ 又回到"每轮重发整张清单"的老开销，改动 3 就白做了。
 * 纯函数测试，不依赖 Spring、不联网、不烧 token。
 */
class PlanInjectionPlannerTest {

    /** 默认兜底刷新间隔，与 application.yml 的 app.plan.injection.full-refresh-every 默认值一致 */
    private static final int EVERY = 6;

    @Test
    @DisplayName("版本没变、也没到兜底轮次 → 只给一行摘要（这是省 token 的常态路径）")
    void steadyStateGivesOneLine() {
        assertEquals(PlanInjectionPlanner.Mode.ONE_LINE,
                PlanInjectionPlanner.decide(3, 3, 0, EVERY, false));
        assertEquals(PlanInjectionPlanner.Mode.ONE_LINE,
                PlanInjectionPlanner.decide(3, 3, EVERY - 1, EVERY, false), "差一轮到兜底，仍是一行");
    }

    @Test
    @DisplayName("版本变了（步骤状态变化）→ 必须全量，否则模型看到的是过期清单")
    void versionChangeForcesFull() {
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(4, 3, 0, EVERY, false), "☑ 之后版本 +1 → 全量");
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(7, 4, 1, EVERY, false));
    }

    @Test
    @DisplayName("首次注入（从未注入过，lastVersion=-1）→ 全量，先把清单交底")
    void firstInjectionIsFull() {
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(1, -1, 0, EVERY, false));
    }

    @Test
    @DisplayName("距上次全量满 fullRefreshEvery 轮 → 兜底全量刷新")
    void periodicRefreshForcesFull() {
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(3, 3, EVERY, EVERY, false));
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(3, 3, EVERY + 5, EVERY, false));
    }

    @Test
    @DisplayName("刚发生过上下文压缩 → 一律全量（清单可能已被压掉），优先级最高")
    void compactionWinsOverEverything() {
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(3, 3, 0, EVERY, true), "版本没变、也没到兜底，但刚压缩过");
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(3, 3, 1, 100, true));
    }

    @Test
    @DisplayName("fullRefreshEvery<=0 → 视为关闭优化，永远全量（等价旧行为，便于对照）")
    void nonPositiveRefreshDisablesOptimization() {
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(3, 3, 0, 0, false));
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(3, 3, 0, -1, false));
    }

    @Test
    @DisplayName("默认间隔 6 轮：第 5 轮仍是一行，第 6 轮转全量（边界）")
    void defaultIntervalBoundary() {
        assertEquals(PlanInjectionPlanner.Mode.ONE_LINE,
                PlanInjectionPlanner.decide(9, 9, 5, 6, false));
        assertEquals(PlanInjectionPlanner.Mode.FULL,
                PlanInjectionPlanner.decide(9, 9, 6, 6, false));
    }
}
