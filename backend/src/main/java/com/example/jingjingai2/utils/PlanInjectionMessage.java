package com.example.jingjingai2.utils;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 计划注入消息。身份是内部字段，不写进 {@link #getText()}，因此不会多占 Token，
 * 也不会出现在发给模型的正文里。
 *
 * <p>列表下标和对象引用都不能当长期身份：插入消息会让下标错位，
 * 摘要压缩会丢掉原来的对象。身份跟着这个实例走；实例还在，就能找到；
 * 实例被换成普通 {@link SystemMessage} 之后，按身份删除会安全地什么都不做。
 */
public final class PlanInjectionMessage extends SystemMessage {

    private final String planMessageId;

    public PlanInjectionMessage(String planMessageId, String text) {
        super(text);
        if (planMessageId == null || planMessageId.isBlank()) {
            throw new IllegalArgumentException("planMessageId 不能为空");
        }
        this.planMessageId = planMessageId;
    }

    public String planMessageId() {
        return planMessageId;
    }

    /** 框架若复制消息，身份跟着走，而不是变回一条没有身份的系统消息。 */
    @Override
    public SystemMessage copy() {
        return new PlanInjectionMessage(planMessageId, getText());
    }

    public static String idOf(Message message) {
        if (message instanceof PlanInjectionMessage plan) {
            return plan.planMessageId;
        }
        return null;
    }

    /** 找不到返回 -1。相同正文的普通系统消息不算。 */
    public static int indexOf(List<Message> messages, String planMessageId) {
        if (messages == null || planMessageId == null) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (planMessageId.equals(idOf(messages.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 按身份摘掉计划消息。已经不在列表里时返回 false，不删除任何其他消息。
     */
    public static boolean remove(List<Message> messages, String planMessageId) {
        int index = indexOf(messages, planMessageId);
        if (index < 0) {
            return false;
        }
        messages.remove(index);
        return true;
    }
}
