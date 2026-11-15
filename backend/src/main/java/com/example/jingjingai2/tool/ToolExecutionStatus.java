package com.example.jingjingai2.tool;

/**
 * 一次工具派发的**结构化执行结论**。
 *
 * <p>为什么需要它：{@link ToolDispatcher#dispatch} 只能返回一个字符串。调用方过去只能靠
 * 「结果文本以什么开头」猜工具到底有没有被执行（例如 {@code startsWith("未知的工具")}），
 * 而流式链路干脆把 {@code success} 固定成 {@code true}。于是「被权限拒绝、根本没有执行」
 * 和「真的执行了」在审计表（{@code tool_call_log.success}）和前端工具卡片上完全一样。
 *
 * <p>这里把结论记在派发线程的 ThreadLocal 上，由**唯一知道真相的那个点**写入：
 * 白名单拒绝在 {@code ChatController.callToolChecked}，
 * 网关拒绝 / 工具不存在 / 抛异常 / 正常返回在 {@link ToolDispatcher#dispatch}。
 * 调用方 {@link #take()} 取走，不再解析任何结果文案。
 *
 * <p>关键语义：工具**成功执行但返回合法的业务错误文本**（例如 editFile 返回
 * 「修改失败: 匹配到 0 处」）仍然算 {@link Kind#EXECUTED} —— 工具确实跑了，
 * 只是业务上没成功。是否执行过由控制流决定，不由返回文本决定。
 *
 * <p>本类只描述「发生了什么」，不参与任何权限判定，也不改变授权优先级与审批行为。
 */
public final class ToolExecutionStatus {

    public enum Kind {
        /** 工具本体被真正调用并正常返回（返回业务错误文本也算：工具确实执行了）。 */
        EXECUTED,
        /** 权限网关 / 白名单拒绝：工具本体没有被调用。 */
        DENIED,
        /** 本轮工具清单里没有这个名字：没有可执行的对象。 */
        NOT_FOUND,
        /** 工具本体被调用后抛异常，或已放行但能证明请求没有发出去。不是权限拒绝。 */
        FAILED,
        /**
         * 请求可能已经发出，无法证明远端没有执行。
         * {@code success=false} 只表示不能记成成功，不能读成「确定没执行」。
         */
        UNKNOWN
    }

    private static final ThreadLocal<Kind> LAST = new ThreadLocal<>();

    private ToolExecutionStatus() {}

    public static void record(Kind kind) {
        if (kind == null) {
            LAST.remove();
        } else {
            LAST.set(kind);
        }
    }

    /**
     * 取走最近一次结论并清空。
     *
     * @return null 表示这一层没有记录 —— 调用方**不得**据此推断成功
     */
    public static Kind take() {
        Kind kind = LAST.get();
        LAST.remove();
        return kind;
    }

    /** 只有「确实执行且正常返回」才算成功。{@link Kind#UNKNOWN} 与 {@link Kind#FAILED} 都不是。 */
    public static boolean succeeded(Kind kind) {
        return kind == Kind.EXECUTED;
    }
}
