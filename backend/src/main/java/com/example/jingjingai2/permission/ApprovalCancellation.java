package com.example.jingjingai2.permission;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 结束、超时、出错时取消该会话还在等的审批。
 * 回调本身只打日志，不取消 Agent 循环；循环靠 future 被完成来脱身。
 */
public final class ApprovalCancellation {

    private ApprovalCancellation() {}

    public static void bind(SseEmitter emitter, String conversationId, ApprovalService approvals) {
        if (emitter == null || approvals == null) return;
        Runnable cancel = () -> {
            System.out.println("[SSE] 连接结束，取消未决审批 conversation=" + conversationId);
            approvals.cancelConversation(conversationId);
        };
        emitter.onTimeout(() -> {
            System.out.println("[SSE] 连接超时回调触发（不应发生，已设 0=不超时）");
            cancel.run();
        });
        emitter.onError(t -> {
            System.out.println("[SSE] 连接错误回调: " + (t == null ? "" : t.getMessage()));
            cancel.run();
        });
        emitter.onCompletion(cancel);
    }
}
