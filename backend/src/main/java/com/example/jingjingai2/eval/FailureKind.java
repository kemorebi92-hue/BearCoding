package com.example.jingjingai2.eval;

/** 失败归类。没有「模型不行」这一类。归不进去的用 {@link #UNCLASSIFIED}。 */
public enum FailureKind {
    WRONG_TOOL,
    ILLEGAL_ARGS,
    SANDBOX_OR_PERMISSION,
    CONSTRAINT_LOST_AFTER_COMPACT,
    EDIT_MISMATCH,
    HIT_ITERATION_CAP,
    COMPILE_UNFIXED,
    /**
     * 期望里有计划步骤要求（planStepDone 没通过），但这轮 tool_call_log 里没有 createPlan。
     * 也就是计划根本没建，而不是建了没做完。
     */
    NO_PLAN_CREATED,
    /**
     * 计划已经建了（tool_call_log 里有 createPlan），要求的步骤没做完，
     * 并且最后一轮是模型自己正常停止（end_reason 为 NORMAL_STOP）。
     */
    PLAN_INCOMPLETE,
    UNCLASSIFIED
}
