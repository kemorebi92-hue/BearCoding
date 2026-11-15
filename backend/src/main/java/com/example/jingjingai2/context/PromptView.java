package com.example.jingjingai2.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次模型请求的只读投影。列表不能增删，元素仍是原消息对象。
 * 不选择、不压缩、不改写正文。
 */
public final class PromptView {

    private final List<Message> messages;

    private PromptView(List<Message> messages) {
        this.messages = List.copyOf(messages == null ? List.of() : messages);
    }

    public static PromptView of(List<Message> messages) {
        return new PromptView(messages);
    }

    /** 不可修改。与 {@link #of} 时的元素是同一批对象。 */
    public List<Message> messages() {
        return messages;
    }
}
