package com.example.jingjingai2.context;

/**
 * 「当前会话」的线程上下文。
 *
 * 为什么需要它：
 *  - 工具（@Tool）是单例 Bean，方法签名里只有业务参数（模型不该知道 conversationId）；
 *    但计划类工具（createPlan / updatePlanStep）必须知道"这是哪个会话的计划"。
 *  - 于是和 WorkspaceContext 一样，用 ThreadLocal 把"当前请求属于哪个用户/会话"传下去。
 *
 * ⚠️ 与 WorkspaceContext 同一个坑：ThreadLocal **不跨线程**。
 *    /ai/stream 在 streamExecutor 的独立线程里跑，所以必须在 doStream 内部重新 set、finally clear。
 */
public final class ConversationContext {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private ConversationContext() {}

    public static void set(Long userId, String conversationId) {
        USER_ID.set(userId);
        CONVERSATION_ID.set(conversationId);
    }

    public static String conversationId() { return CONVERSATION_ID.get(); }

    public static Long userId() { return USER_ID.get(); }

    public static void clear() {
        USER_ID.remove();
        CONVERSATION_ID.remove();
        ContextAssetTool.endRequest();
    }
}
