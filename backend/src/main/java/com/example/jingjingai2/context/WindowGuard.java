package com.example.jingjingai2.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 摘要失败后的硬窗口判断。软压缩阈值（窗口的百分比）和模型硬窗口（100%）是两个概念：
 * 前者只决定要不要压缩，后者决定摘要失败、原文保留后还能不能发给主模型。
 */
public final class WindowGuard {

    private WindowGuard() {}

    /**
     * @param totalScope true 时看本轮 total 压缩的结果，false 时看进循环前的 history 摘要
     * @return true 表示原消息已保留且完整请求超过硬窗口，本轮不能发送主模型
     */
    public static boolean reject(List<Message> messages, ToolCallback[] tools,
                                 ContextCompactor.SummaryUsage historySummary,
                                 ContextCompactor.CompactStats totalRound,
                                 boolean totalScope, int windowTokens) {
        boolean failed = totalScope
                ? totalRound != null && "SUMMARY_UNAVAILABLE".equals(totalRound.mode())
                : historySummary != null && historySummary.fallback();
        if (!failed) return false;
        return ContextBudget.exceedsWindow(ContextBudget.requestTokens(messages, tools), windowTokens);
    }
}
