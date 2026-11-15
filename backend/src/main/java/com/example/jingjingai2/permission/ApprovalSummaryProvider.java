package com.example.jingjingai2.permission;

/**
 * 审批卡可信摘要的可选扩展点。
 *
 * <p>默认不提供：现有工具的审批卡行为不变。只有需要在卡上展示「由后端可信配置生成的摘要」
 * （而不是通用风险文案）的工具才注册实现。
 *
 * <p>摘要必须由后端可信代码生成，数据来源是已审核的模板与本次可信调用参数，
 * 不采信模型传入的自由文本。返回 null = 该工具不提供摘要，卡片按原有方式显示。
 */
public interface ApprovalSummaryProvider {

    /**
     * @param toolName  工具名（如 {@code addMcpServerFromTemplate}）
     * @param arguments 本次调用的原始参数 JSON（与工具执行时同一份）
     * @return 可信摘要；该工具不提供时返回 null。管理保存工具校验失败时返回「不会保存：」说明，不返回 null。
     */
    String summaryFor(String toolName, String arguments);

    /**
     * 批准之后、真正执行工具之前再问一次。默认 true，普通工具和 {@code mcp__} 工具保持原样。
     * 管理保存工具在 {@code prepare} 失败时返回 false，调用方不得放行工具方法。
     */
    default boolean executable(String toolName, String arguments) {
        return true;
    }
}
