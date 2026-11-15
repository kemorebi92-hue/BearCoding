package com.example.jingjingai2.eval;

import java.util.List;

/** 判定器的可选输入。不连数据库：计划、工具日志、轮次、最终回复都由调用方导出后传进来。 */
public record JudgeContext(
        List<PlanStepView> planSteps,
        List<ToolCallView> toolCalls,
        List<TurnView> turns,
        String assistantReply) {

    public JudgeContext {
        planSteps = planSteps == null ? List.of() : List.copyOf(planSteps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public JudgeContext(List<PlanStepView> planSteps, List<ToolCallView> toolCalls, List<TurnView> turns) {
        this(planSteps, toolCalls, turns, null);
    }

    public JudgeContext(List<PlanStepView> planSteps) {
        this(planSteps, List.of(), List.of(), null);
    }

    public static JudgeContext empty() {
        return new JudgeContext(List.of());
    }

    public record PlanStepView(String stepId, String status) {}

    public record ToolCallView(String toolName, String arguments) {
        public ToolCallView(String toolName) {
            this(toolName, null);
        }
    }

    public record TurnView(String compactMode, String endReason, String phase, Integer turnIndex) {
        public TurnView(String compactMode) {
            this(compactMode, null, null, null);
        }
    }
}
