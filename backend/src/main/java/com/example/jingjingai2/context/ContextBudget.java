package com.example.jingjingai2.context;

import com.example.jingjingai2.mcp.McpRoundSelector;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 上下文预算：历史和固定开销分开算，压缩只看「本轮预计总输入」相对真实窗口的比例。
 *
 * <p>固定开销 = 系统提示（含用户记忆）+ 本轮工具定义。历史 = 其余消息。
 * 工具定义用 {@link McpRoundSelector#estimateSchemaTokens}（字符数 ÷ 1.5），
 * 消息用 {@link TokenEstimator}。两套都是估算，只用来决定要不要压，不是账单。
 */
public final class ContextBudget {

    private ContextBudget() {}

    /**
     * @param historyTokens  历史消息估算
     * @param fixedTokens    系统提示 + 工具定义估算
     * @param windowTokens   模型真实窗口；≤0 表示未知，退回「历史超过 historyBudget」
     * @param percent        窗口百分比，≤0 当 65
     * @param historyBudget  窗口未知时的退路，也是压完后打算留下的历史量
     */
    public static boolean shouldCompact(long historyTokens, long fixedTokens,
                                        int windowTokens, int percent, int historyBudget) {
        if (windowTokens > 0) {
            int p = percent <= 0 ? 65 : percent;
            long line = (long) windowTokens * p / 100;
            return historyTokens + Math.max(0, fixedTokens) > line;
        }
        int budget = historyBudget <= 0 ? 24000 : historyBudget;
        return historyTokens > budget;
    }

    /** 配置窗口的 100%。与 {@link #shouldCompact} 的压缩触发线分开。 */
    public static boolean exceedsWindow(long requestTokens, int windowTokens) {
        return windowTokens > 0 && requestTokens > windowTokens;
    }

    /** 当前消息加本轮工具定义。输出 token 没有单独配置，不在这里预留。 */
    public static long requestTokens(List<Message> messages, ToolCallback[] tools) {
        return historyTokens(messages) + fixedTokens(messages, tools);
    }

    public static long historyTokens(List<Message> messages) {
        if (messages == null) return 0;
        long n = 0;
        for (Message m : messages) {
            if (m == null || m instanceof SystemMessage) continue;
            if (m instanceof ToolResponseMessage trm) {
                if (trm.getResponses() == null) continue;
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r == null) continue;
                    n += TokenEstimator.estimate(r.responseData());
                }
            } else {
                n += TokenEstimator.estimate(m.getText());
            }
        }
        return n;
    }

    /** 系统提示 + 本轮工具定义。 */
    public static long fixedTokens(List<Message> messages, ToolCallback[] tools) {
        long n = 0;
        if (messages != null) {
            for (Message m : messages) {
                if (m instanceof SystemMessage) n += TokenEstimator.estimate(m.getText());
            }
        }
        n += McpRoundSelector.estimateSchemaTokens(tools);
        return n;
    }
}
