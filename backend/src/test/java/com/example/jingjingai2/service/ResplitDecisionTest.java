package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.PlanStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.jingjingai2.service.TaskPlanService.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 「失败重拆」的决策规则测试 —— 这是整个 Plan 模式里**唯一允许机器自己"拆任务"的地方**，
 * 也是规格里最硬的约束（只允许拆一层，再失败必须交给人）。用纯函数 + 单测把它钉死，
 * 避免依赖模型行为去验证（模型可能自己提前收手，路径就测不到了）。
 */
class ResplitDecisionTest {

    @Test
    @DisplayName("核对通过 → DONE（不管之前失败过几次）")
    void passedAlwaysDone() {
        assertEquals(DONE, TaskPlanService.decide(true, 0, 0));
        assertEquals(DONE, TaskPlanService.decide(true, 2, 0));
        assertEquals(DONE, TaskPlanService.decide(true, 5, 1));
    }

    @Test
    @DisplayName("失败但没到 3 次 → 继续重试，不拆")
    void belowThresholdRetries() {
        assertEquals(FAILED, TaskPlanService.decide(false, 1, 0));
        assertEquals(FAILED, TaskPlanService.decide(false, 2, 0));
        assertEquals(FAILED, TaskPlanService.decide(false, 2, 1), "已拆过的步骤没到阈值也只是重试");
    }

    @Test
    @DisplayName("失败满 3 次且从未拆过（depth=0）→ 允许重拆一次")
    void thirdFailureTriggersResplit() {
        assertEquals(RESPLIT, TaskPlanService.decide(false, 3, 0));
        assertEquals(RESPLIT, TaskPlanService.decide(false, 4, 0));
    }

    @Test
    @DisplayName("失败满 3 次但已经拆过一层（depth>=1）→ blocked，禁止再拆（不许套娃）")
    void resplitDepthLimitBlocks() {
        assertEquals(BLOCK, TaskPlanService.decide(false, 3, 1));
        assertEquals(BLOCK, TaskPlanService.decide(false, 9, 1));
        assertEquals(BLOCK, TaskPlanService.decide(false, 3, 2));
    }

    @Test
    @DisplayName("阈值常量就是 3，改动它会同时影响重拆与 blocked 的时点")
    void thresholdConstant() {
        assertEquals(3, PlanStep.RESPLIT_THRESHOLD);
        assertEquals(FAILED, TaskPlanService.decide(false, PlanStep.RESPLIT_THRESHOLD - 1, 0));
        assertEquals(RESPLIT, TaskPlanService.decide(false, PlanStep.RESPLIT_THRESHOLD, 0));
    }
}
