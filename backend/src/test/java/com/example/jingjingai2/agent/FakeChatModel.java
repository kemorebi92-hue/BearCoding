package com.example.jingjingai2.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 子代理测试用的假模型：**按脚本逐轮返回**，同时把每一轮收到的 Prompt 记下来。
 *
 * 记 Prompt 是关键 —— "房间是干净的"这条约束只能靠"模型实际收到了什么"来证明，
 * 光看 SubagentRunner 的代码是证明不了的（未来有人在上面加历史注入，测试要能红）。
 */
final class FakeChatModel implements ChatModel {

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private final List<Function<Prompt, ChatResponse>> script = new ArrayList<>();
    private int cursor = 0;

    /** 追加一轮脚本（脚本用完后一直重复最后一轮） */
    FakeChatModel then(Function<Prompt, ChatResponse> step) {
        script.add(step);
        return this;
    }

    /** 追加 n 轮"只说话、不调工具" */
    FakeChatModel thenReply(String text, int n) {
        for (int i = 0; i < n; i++) then(p -> reply(text));
        return this;
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        // **必须快照**：SubagentRunner 每轮复用同一个 messages 列表（就地追加助手消息、摘掉上一轮的
        // 预算消息），Spring AI 的 Prompt 也只是把这个列表存起来（不拷贝）。
        // 不做快照的话，测试读到的永远是"整趟跑完之后"的最终状态 ——
        // "这一轮发出去的末尾是不是预算消息"这类断言就恒为真，等于没测。
        prompts.add(new Prompt(new ArrayList<>(prompt.getInstructions()), prompt.getOptions()));
        if (script.isEmpty()) return reply("(空脚本)");
        int idx = Math.min(cursor, script.size() - 1);
        cursor++;
        return script.get(idx).apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("子代理内核只用 call()，不该走流式");
    }

    List<Prompt> prompts() { return new ArrayList<>(prompts); }

    int callCount() { return prompts.size(); }

    // ==================== 构造回复 ====================

    static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage("", Map.of(), List.of(calls), List.of()))));
    }

    static AssistantMessage.ToolCall call(String id, String name, String argsJson) {
        return new AssistantMessage.ToolCall(id, "function", name, argsJson);
    }
}
