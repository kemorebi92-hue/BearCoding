package com.example.jingjingai2.tool;

import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolPermissionGate;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具分发器：按名字在注册表（ToolCallback 数组）里找到对应的工具。
 *
 * 为什么单独抽出来：
 *  - 原来这段逻辑是 ChatController 里的 private 方法，无法单独测试；
 *    抽成纯静态工具类后不依赖 Spring 上下文，可以直接写单元测试。
 *  - 单一职责：Controller 负责编排 Agent 循环，"按名字找工具"是独立的映射逻辑。
 *
 * 为什么用遍历注册表而不是 if/else：
 *  - if/else 方案每新增一个工具都要改 Controller 源码，违反开闭原则；
 *    注册表方案下新增工具只需写一个 @Tool 类，分发逻辑零改动。
 */
public final class ToolDispatcher {

    /**
     * 权限网关没有安装时的拒绝文案。
     *
     * <p>旧实现里 {@code gate == null} 就直接 {@code cb.call(...)}，等于「没有网关 = 不需要授权」。
     * 那是结构性 fail-open：网关只在 Spring 的 {@code @PostConstruct} 里安装，一旦生命周期没走到、
     * 或者有人临时卸载了网关（{@code @PreDestroy} / 单测），写文件、终端、MCP 都会被真的执行。
     */
    public static final String NO_GATE =
            "工具未执行：权限网关未安装，无法确认该操作是否已被授权。这不是工具本身的问题，请向用户说明情况。";

    private static volatile ToolPermissionGate gate;

    private ToolDispatcher() {}

    /** 由 Spring 在启动时挂上。单测可临时替换，用完必须再传 null。 */
    public static void installGate(ToolPermissionGate next) {
        gate = next;
    }

    /** 当前是否已经挂上网关（只读观察，便于排障与测试）。 */
    public static boolean gateInstalled() {
        return gate != null;
    }

    /**
     * 在回调数组中查找指定名字的工具。
     *
     * @return 命中的 ToolCallback；未命中返回 null（由调用方决定降级文案）
     */
    public static ToolCallback find(ToolCallback[] callbacks, String toolName) {
        if (callbacks == null || toolName == null) {
            return null;
        }
        // MCP 名必须精确匹配：mcp__server__read_file 去下划线会撞 mcp__server__readfile
        if (toolName.startsWith("mcp__")) {
            for (ToolCallback cb : callbacks) {
                if (cb == null || cb.getToolDefinition() == null) continue;
                String registeredName = cb.getToolDefinition().name();
                if (toolName.equals(registeredName)) return cb;
            }
            return null;
        }
        // 兼容带下划线 / 不带下划线的命名（不同模型/版本生成规则略有差异）
        String normalized = toolName.replace("_", "");
        for (ToolCallback cb : callbacks) {
            if (cb == null || cb.getToolDefinition() == null) {
                continue;
            }
            String registeredName = cb.getToolDefinition().name();
            if (registeredName == null) {
                continue;
            }
            if (registeredName.equalsIgnoreCase(toolName)
                    || registeredName.replace("_", "").equalsIgnoreCase(normalized)) {
                return cb;
            }
        }
        return null;
    }

    /**
     * 查找并执行工具。
     *
     * <p>每条出口都会写下结构化执行结论（{@link ToolExecutionStatus}）：调用方据此判断
     * 「工具到底有没有被执行」，不再解析返回文案。四条"没执行"的出口各有不同结论：
     * 找不到工具 = {@code NOT_FOUND}，网关拒绝 = {@code DENIED}，网关缺失 = {@code DENIED}，
     * 工具本体抛异常 = {@code FAILED}。
     *
     * <p><b>fail-closed 边界</b>：网关缺失时没有生效策略可查，只能按内置表里无条件 ALLOW 的清单
     * （{@code PermissionPolicy#approvalFree}）兜底 —— 写文件、终端、删除、MCP、用户记忆和一切
     * 未登记工具一律拒绝。网关存在但调用上下文缺失时，由网关按<b>当前生效策略</b>判定
     * （{@code ToolPermissionGate#authorize}）：默认配置下同样只有那批 ALLOW 工具能执行，
     * 需要审批的工具因为没有 userId / SSE 通道而被拒绝。
     *
     * @return 工具执行结果；找不到工具时返回明确的错误文案（而不是抛异常，
     *         这样模型能"看到"错误并自行纠正，而不是让整个对话崩掉）
     */
    public static String dispatch(ToolCallback[] callbacks, String toolName, String arguments) {
        ToolCallback cb = find(callbacks, toolName);
        if (cb == null) {
            // 没有可执行的对象。这不是权限拒绝，但同样没有执行：
            // 审计里用 high_risk=0 / approved=0 与"网关拒绝"（high_risk=1）区分开。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.NOT_FOUND);
            markNotApproved();
            return "未知的工具: " + toolName;
        }
        try {
            ToolPermissionGate current = gate;
            if (current == null) {
                // 没有网关就没有任何授权判定，只能按「本来就不需要审批」的清单兜底。
                if (!PermissionPolicy.approvalFree(toolName)) {
                    ToolExecutionStatus.record(ToolExecutionStatus.Kind.DENIED);
                    markNotApproved(true);
                    return NO_GATE;
                }
            } else {
                String blocked = current.authorize(toolName, arguments);
                if (blocked != null) {
                    // 网关拒绝时 high_risk / approved 由网关自己写好（一律 approved=false）。
                    ToolExecutionStatus.record(ToolExecutionStatus.Kind.DENIED);
                    return blocked;
                }
            }
            String result = cb.call(arguments);
            // 回调自己可能已经写下结论（MCP 没发出去 / 结果未知）。先取走，
            // 没有结论才按「正常返回 = 执行过」记。返回文本是业务错误也照样是 EXECUTED。
            ToolExecutionStatus.Kind inner = ToolExecutionStatus.take();
            ToolExecutionStatus.record(inner == null ? ToolExecutionStatus.Kind.EXECUTED : inner);
            return result;
        } catch (Exception e) {
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
            return "工具调用失败: " + e.getMessage();
        }
    }

    /** 没有可执行对象时补一次结构化审计标记：没执行 = 没被批准执行。 */
    private static void markNotApproved() {
        markNotApproved(false);
    }

    /**
     * @param highRisk true 表示这是「权限基础设施缺失导致的拒绝」，与「工具名找不到」区分开
     */
    private static void markNotApproved(boolean highRisk) {
        ToolCallContext ctx = ToolCallContext.current();
        if (ctx != null) ctx.setAudit(highRisk, false);
    }
}
