package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.TokenEstimator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 本轮即将发给模型的 messages 的 token 粗分（{@link TokenEstimator}）。
 * 只读观测：回答「峰值是基线还是任务内容」时用，不参与计费。
 */
public final class MessageTokenBudget {

    public record Breakdown(int system, int user, int assistant, int tool, int other, int total) {
        @Override
        public String toString() {
            return "system=" + system + " user=" + user + " assistant=" + assistant
                    + " tool=" + tool + " other=" + other + " total=" + total;
        }
    }

    private MessageTokenBudget() {}

    public static Breakdown of(List<Message> messages) {
        int system = 0, user = 0, assistant = 0, tool = 0, other = 0;
        if (messages == null) return new Breakdown(0, 0, 0, 0, 0, 0);
        for (Message m : messages) {
            int n = TokenEstimator.estimate(textOf(m));
            if (m instanceof SystemMessage) system += n;
            else if (m instanceof UserMessage) user += n;
            else if (m instanceof AssistantMessage) assistant += n;
            else if (m instanceof ToolResponseMessage) tool += n;
            else other += n;
        }
        return new Breakdown(system, user, assistant, tool, other,
                system + user + assistant + tool + other);
    }

    /** 当前请求里工具结果累计估算 token（软提示阈值用这个，不用 history）。 */
    public static int toolResultTokens(List<Message> messages) {
        if (messages == null) return 0;
        int n = 0;
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage) n += TokenEstimator.estimate(textOf(m));
        }
        return n;
    }

    static String textOf(Message m) {
        if (m == null) return "";
        if (m instanceof ToolResponseMessage tr && tr.getResponses() != null) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
                if (r != null && r.responseData() != null) sb.append(r.responseData());
            }
            return sb.toString();
        }
        String t = m.getText();
        return t == null ? "" : t;
    }
}
