package com.example.jingjingai2.permission;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 一次工具调用的调用方。挂在执行线程的 ThreadLocal 上：
 * 流式循环和子代理池线程互不看见对方的上下文。
 *
 * <p><b>没有上下文时不再"其余放行"。</b>旧行为（只做 DENY、其余一律执行）是结构性 fail-open：
 * 写文件、终端执行、删除、MCP 调用都会因为没人问过而被真的执行。现在缺上下文时，
 * 只有策略表里无条件 ALLOW 的工具（见 {@link PermissionPolicy#approvalFree}）照常执行，
 * 需要审批的工具一律不执行（{@link ToolPermissionGate#NO_CONTEXT}）。
 * 主循环和子代理房间必须先 {@link #open}，否则连 ASK 都不会发生。
 */
public final class ToolCallContext {

    /** 审批卡片和结果事件。没有它时，子代理里的 ASK 不能弹卡片。 */
    public interface Events {
        void approval(Map<String, Object> payload);
        void approvalResult(Map<String, Object> payload);

        /** MCP 结果超限落盘。默认忽略，老实现不用改。 */
        default void spilled(Map<String, Object> payload) {}
    }

    /**
     * 父线程交给子代理线程的最小透传：谁在审批、往哪条 SSE 推卡片、卡片上写的目标摘要。
     * 不是子代理的 EventSink，房间里的进度事件仍然走原来的通道。
     */
    public static final class ApprovalLink {
        public final Long userId;
        public final String conversationId;
        public final Events events;
        public final String subagentLabel;

        private ApprovalLink(Long userId, String conversationId, Events events, String subagentLabel) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.events = events;
            this.subagentLabel = subagentLabel;
        }

        private static final ThreadLocal<ApprovalLink> ATTACHED = new ThreadLocal<>();

        /** 在父线程调用。没有用户或没有 SSE 时返回 null，子代理保持「无法请求审批」。 */
        public static ApprovalLink capture(String subagentLabel) {
            ToolCallContext parent = current();
            if (parent == null || parent.userId == null || parent.events == null) return null;
            return new ApprovalLink(parent.userId, parent.conversationId, parent.events, subagentLabel);
        }

        public static Scope attach(ApprovalLink link) {
            ApprovalLink prev = ATTACHED.get();
            if (link == null) ATTACHED.remove();
            else ATTACHED.set(link);
            return () -> {
                if (prev == null) ATTACHED.remove();
                else ATTACHED.set(prev);
            };
        }

        public static ApprovalLink attached() {
            return ATTACHED.get();
        }
    }

    /** 写进 tool_call_log 的 high_risk / approved。 */
    public static final class Audit {
        public final boolean highRisk;
        public final boolean approved;

        public Audit(boolean highRisk, boolean approved) {
            this.highRisk = highRisk;
            this.approved = approved;
        }
    }

    private static final ThreadLocal<ToolCallContext> CURRENT = new ThreadLocal<>();

    public final Long userId;
    public final String conversationId;
    public final boolean streaming;
    public final boolean fromSubagent;
    public final Events events;
    /** 非空时卡片写「来自子代理：…」。 */
    public final String subagentLabel;
    private final BooleanSupplier taintedNow;
    private Audit audit;

    public ToolCallContext(Long userId, String conversationId, boolean streaming, boolean fromSubagent,
                           Events events, BooleanSupplier taintedNow) {
        this(userId, conversationId, streaming, fromSubagent, events, taintedNow, null);
    }

    public ToolCallContext(Long userId, String conversationId, boolean streaming, boolean fromSubagent,
                           Events events, BooleanSupplier taintedNow, String subagentLabel) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.streaming = streaming;
        this.fromSubagent = fromSubagent;
        this.events = events;
        this.taintedNow = taintedNow;
        this.subagentLabel = subagentLabel;
    }

    public static ToolCallContext forSubagent(String conversationId, BooleanSupplier taintedNow) {
        return new ToolCallContext(null, conversationId, false, true, null, taintedNow);
    }

    public boolean tainted() {
        return taintedNow != null && taintedNow.getAsBoolean();
    }

    public void setAudit(boolean highRisk, boolean approved) {
        this.audit = new Audit(highRisk, approved);
    }

    /** 取走最近一次网关结论，避免下一次工具误用。 */
    public static Audit takeAudit() {
        ToolCallContext ctx = CURRENT.get();
        if (ctx == null) return null;
        Audit a = ctx.audit;
        ctx.audit = null;
        return a;
    }

    public static ToolCallContext current() {
        return CURRENT.get();
    }

    public static Scope open(ToolCallContext ctx) {
        ToolCallContext prev = CURRENT.get();
        CURRENT.set(ctx);
        return () -> {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
