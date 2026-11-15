package com.example.jingjingai2.utils;

import java.util.Collection;

/**
 * 执行层的"工具白名单"守卫。
 *
 * 为什么需要它（真实漏洞）：
 *   HARD 的逃生舱 {@code stripWriteTools} 只把"能改变工作区"的工具从**下发给模型的清单**里摘掉，
 *   但工具执行时仍然拿**全量** callbacks 去派发。于是模型凭上下文记忆喊出一个已经被摘掉的工具名
 *   （实测：`stripWriteTools` 那一轮模型仍然请求 `executeCommand`），harness 照样把它执行了 ——
 *   摘除等于没摘。
 *
 *   更普遍地说：任何"模型幻觉出一个不存在的工具名"的情况，都应该在执行前被明确拒绝，
 *   而不是靠"找不到就当未知工具"这种隐式兜底。
 *
 * 设计：把判断做成**纯函数**，只依赖"本轮实际下发的工具名集合"，
 * 这样单测可以直接覆盖"清单内放行 / 清单外拒绝 / 被摘掉的写类工具被拒绝"。
 */
public final class ToolGuard {

    private ToolGuard() {}

    /**
     * 该不该拒绝这次工具调用。
     *
     * @param toolName     模型请求的工具名
     * @param allowedNames 本轮**实际下发给模型**的工具名集合
     * @return null = 放行；否则返回给模型看的拒绝说明
     */
    public static String rejectReason(String toolName, Collection<String> allowedNames) {
        return rejectReason(toolName, allowedNames, false);
    }

    /**
     * @param tainted 本会话已读过不可信外部内容时，写类内置工具即使还在清单里也拒绝
     */
    public static String rejectReason(String toolName, Collection<String> allowedNames, boolean tainted) {
        if (tainted && PlanNudgePolicy.isDangerousBuiltin(toolName)) {
            return com.example.jingjingai2.mcp.McpUntrustedGuard.REJECT_MESSAGE;
        }
        if (toolName == null || toolName.isBlank()) {
            return "工具名称为空，已拒绝执行。本轮可用工具：" + join(allowedNames);
        }
        if (allowedNames != null && allowedNames.contains(toolName)) {
            return null;
        }
        String msg = "工具 " + toolName + " 本轮不可用（它不在本轮下发的工具清单里），已拒绝执行。";
        if (toolName.startsWith("mcp__")) {
            msg += "该工具尚未加载，请先调用 mcpToolSearch。";
        }
        return msg + "请只使用本轮可用的工具，或改用 createPlan 说明你打算怎么做。本轮可用工具：" + join(allowedNames);
    }

    private static String join(Collection<String> names) {
        if (names == null || names.isEmpty()) return "(无)";
        return String.join("、", names);
    }
}
