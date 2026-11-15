package com.example.jingjingai2.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * dispatch-v2 累计用量软提示：主循环工具结果累计 token 超阈值后，
 * 在下一轮 messages 末尾追加一条建议派 explore 的 SystemMessage（每请求最多一次）。
 * 不强制派发。仅 {@code dispatch-v2} + {@code dispatch-v2-soft-hint} 都开时生效。
 */
public final class DispatchSoftHint {

    public static final String HINT_TEXT =
            "【用量提示】本任务已读取较多内容，如还需继续大范围探索，建议派 explore 子代理。";

    private final int thresholdTokens;
    private boolean injected;

    public DispatchSoftHint(int thresholdTokens) {
        this.thresholdTokens = Math.max(0, thresholdTokens);
    }

    public boolean isInjected() { return injected; }

    public int getThresholdTokens() { return thresholdTokens; }

    /**
     * 若尚未注入且工具结果累计 ≥ 阈值，追加提示并返回 true。
     * {@code enabled=false} 时什么都不做。
     */
    public boolean maybeInject(List<Message> messages, boolean enabled) {
        if (!enabled || injected || messages == null) return false;
        int toolTok = MessageTokenBudget.toolResultTokens(messages);
        if (toolTok < thresholdTokens) return false;
        messages.add(new SystemMessage(HINT_TEXT));
        injected = true;
        return true;
    }

    /** 测试/观测：当前累计工具结果 token。 */
    public static int currentToolTokens(List<Message> messages) {
        return MessageTokenBudget.toolResultTokens(messages);
    }
}
