package com.example.jingjingai2.DTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份执行计划（会话级状态，随 conversation.plan_json 持久化）。
 *
 * maxIterations 由步数动态算出（max(12, 步数×4)）并存在这里，
 * 这样"预算"也成为计划的一部分：模型每轮能看到"剩余 X / 共 Y 轮"，
 * 长任务就不会被固定的轮次上限硬切断。
 */
public class TaskPlan {

    /**
     * 动态预算下限（18 轮）。
     * 原来写 12：实测"写 3 个文件 + 编译 + 修错 + 逐步上报"这种任务，12 轮连一半都做不完，
     * 所以下限抬高到 18。
     */
    public static final int MIN_ITERATIONS = 18;
    /**
     * 每步大致需要的轮数（估算系数，6 轮/步）。
     * 原来写 4：实测每步真实开销是 4~6 轮（写文件、编译、修错、上报各占一轮），
     * 系数太小会让长任务在最后一步前就"没钱了"。
     */
    public static final int ITERATIONS_PER_STEP = 6;

    private boolean deliveryReturned;
    private String deliveryReceipt;
    public String getDeliveryReceipt() { return deliveryReceipt; }
    public void setDeliveryReceipt(String value) { deliveryReceipt = value; }
    public boolean isDeliveryReturned() { return deliveryReturned; }
    public void setDeliveryReturned(boolean value) { deliveryReturned = value; }
    private String planId;
    private List<PlanStep> steps = new ArrayList<>();
    private int maxIterations = MIN_ITERATIONS;
    private int currentIteration;
    /** 已废弃的续预算计数。保留字段，避免旧计划 JSON 反序列化丢字段。 */
    private int extensions;

    /**
     * 计划版本号：**任何步骤状态变化都 +1**。
     *
     * 用途（改动 3 的 slim 注入策略）：每轮都注入完整清单很费 token，
     * 而清单内容只在"步骤状态变化"时才变 —— 用版本号判断"要不要重新注入全量"，
     * 状态没变时只注入一行摘要，省下来的就是每轮重复的清单文本。
     *
     * 注意：累加动作由 {@link com.example.jingjingai2.service.TaskPlanService} 在
     * 每次真正修改步骤状态的路径上显式调用 {@link #bumpVersion()}（不在 PlanStep 里做，
     * 因为步骤不知道自己属于哪个计划）。
     */
    private int version = 1;

    public TaskPlan() {}

    public TaskPlan(String planId) { this.planId = planId; }

    /** 计划进度尺度，不是模型调用上限。执行次数看请求账本。 */
    public static int budgetFor(int stepCount) {
        return Math.max(MIN_ITERATIONS, stepCount * ITERATIONS_PER_STEP);
    }

    /** 当前是否存在 blocked 步骤 */
    public boolean hasBlockedStep() {
        for (PlanStep s : steps) if (PlanStep.BLOCKED.equals(s.getStatus())) return true;
        return false;
    }

    /** 是否还有未完成的步骤（含 failed，需要继续重试的） */
    public boolean hasOpenSteps() {
        for (PlanStep s : steps) if (s.open()) return true;
        return false;
    }

    /**
     * 是否**所有步骤都已完成**（每一步都是 done，且至少有一个步骤）。
     * 用在"该收尾了"的判定上：一旦为 true，harness 会禁用工具、强制模型输出总结，
     * 避免它"再做最后一件事"没完没了地多跳几轮。
     */
    public boolean allDone() {
        if (steps.isEmpty()) return false;
        for (PlanStep s : steps) {
            if (!PlanStep.DONE.equals(s.getStatus())) return false;
        }
        return true;
    }

    /** 是否所有步骤都已结束（done / skipped 之外只剩 blocked） */
    public boolean allSettled() {
        for (PlanStep s : steps) {
            if (PlanStep.PENDING.equals(s.getStatus()) || PlanStep.RUNNING.equals(s.getStatus())) return false;
        }
        return true;
    }

    public PlanStep findStep(String stepId) {
        for (PlanStep s : steps) if (s.getStepId().equals(stepId)) return s;
        return null;
    }

    /** 某个步骤在列表里的下标（重拆时要插在它后面） */
    public int indexOf(String stepId) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).getStepId().equals(stepId)) return i;
        return -1;
    }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public int getCurrentIteration() { return currentIteration; }
    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

    public int getExtensions() { return extensions; }
    public void setExtensions(int extensions) { this.extensions = extensions; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    /** 步骤状态发生变化时累加版本号（全量注入的触发依据） */
    public void bumpVersion() { this.version++; }
}
