package com.example.jingjingai2.DTO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划模型（TaskPlan / PlanStep）的纯逻辑测试：预算计算、状态图标、增删查位置、完结判定。
 * 这些是所有 Plan 逻辑的地基，跑错会直接影响"预算够不够、重拆插哪儿"。
 */
class TaskPlanModelTest {

    @Test
    @DisplayName("计划尺度 = max(18, 步数 × 6)，不再用 60 轮冒充执行预算")
    void budgetFormula() {
        assertEquals(18, TaskPlan.budgetFor(1));
        assertEquals(18, TaskPlan.budgetFor(3), "3 步 → 18，取下限");
        assertEquals(24, TaskPlan.budgetFor(4));
        assertEquals(48, TaskPlan.budgetFor(8));
        assertEquals(72, TaskPlan.budgetFor(12));
        assertEquals(180, TaskPlan.budgetFor(30));
    }

    @Test
    @DisplayName("计划版本号：默认 1，bumpVersion 每次 +1（slim 注入据此判断要不要重发全量清单）")
    void planVersion() {
        TaskPlan plan = new TaskPlan("p-v");
        assertEquals(1, plan.getVersion(), "新建计划版本号从 1 开始");
        plan.bumpVersion();
        assertEquals(2, plan.getVersion());
        plan.bumpVersion();
        plan.bumpVersion();
        assertEquals(4, plan.getVersion(), "每次状态变化累加 1");
    }

    @Test
    @DisplayName("hasBlockedStep：重拆过一层仍失败才算卡死")
    void blockedDetection() {
        TaskPlan plan = new TaskPlan("p-x");
        PlanStep a = new PlanStep("s1", "a", "");
        PlanStep b = new PlanStep("s2", "b", "");
        plan.getSteps().add(a);
        plan.getSteps().add(b);
        assertFalse(plan.hasBlockedStep());
        b.setStatus(PlanStep.BLOCKED);
        assertTrue(plan.hasBlockedStep());
    }

    @Test
    @DisplayName("状态图标：☐ 待执行 / ▶ 进行中 / ☑ 已完成 / ✗ 失败 / ⛔ 阻塞")
    void statusIcons() {
        PlanStep s = new PlanStep("s1", "写文件", "文件存在");
        assertEquals("☐", s.icon());
        s.setStatus(PlanStep.RUNNING);
        assertEquals("▶", s.icon());
        s.setStatus(PlanStep.DONE);
        assertEquals("☑", s.icon());
        s.setStatus(PlanStep.FAILED);
        assertEquals("✗", s.icon());
        s.setStatus(PlanStep.BLOCKED);
        assertEquals("⛔", s.icon());
    }

    @Test
    @DisplayName("open()：未完成的步骤才需要继续做；done/blocked 不算")
    void openPredicate() {
        PlanStep s = new PlanStep("s1", "x", "");
        assertTrue(s.open());
        s.setStatus(PlanStep.RUNNING);
        assertTrue(s.open());
        s.setStatus(PlanStep.FAILED);
        assertTrue(s.open(), "失败但未阻塞的步骤仍需重试");
        s.setStatus(PlanStep.DONE);
        assertFalse(s.open());
        s.setStatus(PlanStep.BLOCKED);
        assertFalse(s.open(), "已阻塞的步骤不再自动尝试");
    }

    @Test
    @DisplayName("findStep / indexOf：重拆时要靠它定位插入位置")
    void lookup() {
        TaskPlan plan = new TaskPlan("p-1");
        plan.getSteps().add(new PlanStep("s1", "a", ""));
        plan.getSteps().add(new PlanStep("s2", "b", ""));
        plan.getSteps().add(new PlanStep("s3", "c", ""));

        assertEquals("b", plan.findStep("s2").getDescription());
        assertNull(plan.findStep("s9"));
        assertEquals(1, plan.indexOf("s2"));
        assertEquals(-1, plan.indexOf("s9"));
    }

    @Test
    @DisplayName("allSettled：还有 pending/running 就没结束")
    void settled() {
        TaskPlan plan = new TaskPlan("p-1");
        PlanStep a = new PlanStep("s1", "a", "");
        PlanStep b = new PlanStep("s2", "b", "");
        plan.getSteps().add(a);
        plan.getSteps().add(b);

        assertFalse(plan.allSettled());
        assertTrue(plan.hasOpenSteps());

        a.setStatus(PlanStep.DONE);
        b.setStatus(PlanStep.RUNNING);
        assertFalse(plan.allSettled(), "running 视作未结束");

        b.setStatus(PlanStep.BLOCKED);
        assertTrue(plan.allSettled(), "blocked 已终止，不再算未结束");
        assertFalse(plan.hasOpenSteps());
    }
}
