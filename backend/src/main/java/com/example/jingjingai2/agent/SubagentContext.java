package com.example.jingjingai2.agent;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 「当前这一轮用哪个模型」的线程上下文。
 *
 * 为什么需要它：模型可以在 /ai/chat 和 /ai/stream 里被用户切换（chatModelFactory），
 * 而 {@code @Tool} 方法的签名里只有业务参数 —— 工具拿不到 ChatModel，也就没法把"本次选定的模型"
 * 传给子代理房间。和 {@code ConversationContext} 同一个套路：ThreadLocal 传下去。
 *
 * ⚠️ 同样不跨线程：{@link SubagentPool} 只搬运 ConversationContext / WorkspaceContext，
 *    这个只在**主循环线程**里被 SubagentTool 读到 —— 房间要用的 model 是派发时就当参数传进去的
 *    （随 spec 一起进闭包），所以工作线程不需要它。这是刻意的：模型是"这次派发的输入"，
 *    不是"线程的环境"，用参数传递比 ThreadLocal 更不容易串。
 *
 * {@link #inRoom()}：子代理房间工作线程执行工具时为 true，主循环为 false。
 * FileTool 用它选择主/子各自的 grep 命中阈值。
 */
public final class SubagentContext {

    private static final ThreadLocal<ChatModel> MODEL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IN_ROOM = ThreadLocal.withInitial(() -> false);

    private SubagentContext() {}

    public static void set(ChatModel model) { MODEL.set(model); }

    public static ChatModel model() { return MODEL.get(); }

    public static void clear() {
        MODEL.remove();
        IN_ROOM.remove();
    }

    public static void enterRoom() { IN_ROOM.set(true); }

    public static void exitRoom() { IN_ROOM.set(false); }

    public static boolean inRoom() { return Boolean.TRUE.equals(IN_ROOM.get()); }
}
