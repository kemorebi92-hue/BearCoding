package com.example.jingjingai2.DTO;

/**
 * createPlan 工具的单步入参（规划阶段由 LLM 填）。
 * 只保留两个字段：做什么（description）+ 做完应该是什么样（expected）。
 * expected 后面会被 PlanVerifier 用来和实际结果做轻量核对。
 */
public class StepInput {
    private String description;
    private String expected;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
}
