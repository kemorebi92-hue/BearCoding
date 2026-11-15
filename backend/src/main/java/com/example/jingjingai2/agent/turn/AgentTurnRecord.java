package com.example.jingjingai2.agent.turn;

/**
 * 由 {@link TurnState} 生成的一行 {@code agent_turn} 记录。纯数据：
 * 五个估算列和哈希都在 {@link #from} 里算好，持久化层不再做任何推导。
 *
 * <p>end_reason 取值：
 * {@code TOOL_CALLING} 模型返回了工具调用、
 * {@code NORMAL_STOP} 模型给出最终回答、
 * {@code PLAN_GATE_RETURNED} 本阶段不产生，枚举先留着、
 * {@code MAX_ITERATIONS} 轮数用尽、
 * {@code APPROVAL_PENDING} 本轮挂起等审批、
 * {@code APPROVED_EXECUTED} 审批补行：用户批准并已执行、
 * {@code USER_REJECTED} 审批补行：用户拒绝。
 *
 * <p>审批补行的 token 列全部为 null，避免和挂起那一轮重复统计。
 */
public record AgentTurnRecord(
        String conversationId,
        Long userId,
        int turnIndex,
        String phase,
        String modelName,
        String systemPromptSha256,
        String systemDynamicSha256,
        String toolsSha256,
        String compactMode,
        Integer tokensBeforeCompactEst,
        Integer tokensAfterCompactEst,
        Integer compactedMessageCount,
        Integer inputTokens,
        Integer outputTokens,
        Integer estTokensSystem,
        Integer estTokensInjected,
        Integer estTokensTools,
        Integer estTokensHistory,
        Integer estTokensToolResults,
        String endReason) {

    public static final String END_TOOL_CALLING = "TOOL_CALLING";
    public static final String END_NORMAL_STOP = "NORMAL_STOP";
    public static final String END_PLAN_GATE_RETURNED = "PLAN_GATE_RETURNED";
    public static final String END_MAX_ITERATIONS = "MAX_ITERATIONS";
    public static final String END_APPROVAL_PENDING = "APPROVAL_PENDING";
    public static final String END_APPROVED_EXECUTED = "APPROVED_EXECUTED";
    /** 用户已批准。批准后仍会复查权限，不代表工具已经执行。 */
    public static final String END_APPROVED = "APPROVED";
    public static final String END_USER_REJECTED = "USER_REJECTED";
    public static final String END_APPROVAL_EXPIRED = "APPROVAL_EXPIRED";
    public static final String END_APPROVAL_CANCELLED = "APPROVAL_CANCELLED";
    /** 摘要调用拿到了服务商 usage。 */
    public static final String END_COMPACTION = "COMPACTION";
    /** 摘要调用没有 usage，token 是估算，不是 0。 */
    public static final String END_COMPACTION_ESTIMATED = "COMPACTION_ESTIMATED";
    /** 摘要调用失败，退回截断。token 为 0。 */
    public static final String END_COMPACTION_FALLBACK = "COMPACTION_FALLBACK";
    /** 压完输入仍高于阈值，本请求不再重试。 */
    public static final String END_COMPACTION_INEFFECTIVE = "COMPACTION_INEFFECTIVE";
    /** 固定部分把消息预算挤到下限以下，本请求不压缩。 */
    public static final String END_COMPACTION_SKIPPED_FIXED_OVERHEAD = "COMPACTION_SKIPPED_FIXED_OVERHEAD";

    /** 由一轮状态生成一行记录。审批补行不带消息和用量，token 列保持 null。 */
    public static AgentTurnRecord from(TurnState s) {
        boolean approvalRow = TurnState.PHASE_APPROVAL_RESOLVED.equals(s.phase());
        TurnState.EstimateSplit est = approvalRow
                ? null
                : TurnState.split(s.systemPrompt(), s.messages(), s.toolsCanonicalJson());
        String dynamic = approvalRow ? null : s.dynamicSystemText();
        return new AgentTurnRecord(
                s.conversationId(),
                s.userId(),
                s.turnIndex(),
                s.phase(),
                s.modelName(),
                approvalRow ? null : PromptCanonical.sha256(s.systemPrompt()),
                dynamic == null ? null : PromptCanonical.sha256(dynamic),
                approvalRow ? null : PromptCanonical.sha256(s.toolsCanonicalJson()),
                s.compactMode(),
                approvalRow ? null : s.tokensBeforeCompactEst(),
                approvalRow ? null : s.tokensAfterCompactEst(),
                approvalRow ? null : s.compactedMessageCount(),
                approvalRow ? null : s.inputTokens(),
                approvalRow ? null : s.outputTokens(),
                est == null ? null : est.system(),
                est == null ? null : est.injected(),
                est == null ? null : est.tools(),
                est == null ? null : est.history(),
                est == null ? null : est.toolResults(),
                s.endReason());
    }
}
