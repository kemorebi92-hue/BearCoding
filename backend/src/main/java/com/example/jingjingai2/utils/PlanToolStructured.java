package com.example.jingjingai2.utils;

import com.example.jingjingai2.DTO.StepInput;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * createPlan 的 **structured 形态**：入参保持原来的嵌套对象数组 {@code List<StepInput>}。
 *
 * 存在的意义：作为 A/B 对照的基线形态 —— 行为与改动前完全一致，
 * 用于验证"flat 形态是不是真的提高了调用率"。日常默认走 flat。
 *
 * 生效条件：app.plan.steps.mode = structured。
 */
@Component
@ConditionalOnProperty(name = "app.plan.steps.mode", havingValue = "structured")
public class PlanToolStructured implements PlanCreateTool {

    private final PlanTool planTool;

    public PlanToolStructured(PlanTool planTool) {
        this.planTool = planTool;
    }

    @Tool(description = PlanTool.CREATE_PLAN_DESCRIPTION
            + "\n【steps 参数怎么填】有序的步骤数组，每项包含 description（这一步做什么）"
            + "与 expected（做完应该达到什么状态，用于自动验收核对）。"
            + "步骤数量控制在 3~8 个，每步都要是一次能做完的具体动作。")
    public String createPlan(
            @ToolParam(description = "有序的步骤列表，每项包含 description（做什么）和 expected（做完的预期状态）")
            List<StepInput> steps) {
        return planTool.createPlanFromSteps(steps, false);
    }
}
