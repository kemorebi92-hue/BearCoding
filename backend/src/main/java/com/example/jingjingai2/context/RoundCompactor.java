package com.example.jingjingai2.context;

import com.example.jingjingai2.agent.turn.PromptCanonical;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * total 口径：按「这一轮真正要发给模型的完整输入」决定是否压缩。
 * history 口径不走这里，仍由 {@link ContextCompactor#buildMessages} 在进循环前做一次。
 *
 * <p>带工具调用的助手消息和紧跟的工具结果是一组，只能整组留下或整组压掉。
 */
public final class RoundCompactor {

    private static final int MAX_PRESERVED_ARTIFACT_REFS = 20;
    /** 引用段超出消息预算的这个比例时，不再硬塞进摘要，改为放弃本次压缩。 */
    private static final double REF_BUDGET_RATIO = 0.5;

    private RoundCompactor() {}

    /** 一次请求内的反复压缩保护。不能放在单例上。 */
    public static final class Guard {
        public int lastRound = -1;
        public Integer lastAfter = null;
        public boolean ineffective = false;
        /** 固定部分把消息预算挤到下限以下时，本请求只记一次。 */
        public boolean skippedOverhead = false;
        /** 本轮压缩写成 SUMMARY 还是 DIGEST。没压过是 null。 */
        public String lastMode = null;
    }

    public record Decision(String scope, int value, int threshold, boolean trigger, String reason) {}

    public record Summary(String text, int inputTokens, int outputTokens, boolean estimated, boolean fallback) {}

    public interface Summarizer {
        Summary summarize(String olderText);
    }

    public record Outcome(Decision decision, int after, int compactedCount, Summary summary, boolean acted,
                          int fixedTokens, int messageBudget, String compactMode) {
        public boolean compacted() { return acted && summary != null && decision.trigger(); }
    }

    public static int estimate(List<Message> messages, ToolCallback[] tools) {
        int n = 0;
        if (messages != null) {
            for (Message message : messages) n += TokenEstimator.estimate(textOf(message));
        }
        n += TokenEstimator.estimate(PromptCanonical.toolsJson(tools));
        return n;
    }

    /**
     * @param growthRatio 相对阈值的增长比例，0.30 表示要比上次压缩后至少多出阈值的 30%
     */
    public static Decision decide(int round, int value, int threshold, int minGap, double growthRatio, Guard guard) {
        if (guard != null && guard.ineffective) {
            return new Decision("total", value, threshold, false, "COMPACTION_INEFFECTIVE");
        }
        if (guard != null && guard.lastRound >= 0 && round - guard.lastRound < minGap) {
            return new Decision("total", value, threshold, false, "GAP");
        }
        if (guard != null && guard.lastAfter != null) {
            int need = guard.lastAfter + (int) Math.round(threshold * growthRatio);
            if (value < need) {
                return new Decision("total", value, threshold, false, "GROWTH");
            }
        }
        if (value <= threshold) {
            return new Decision("total", value, threshold, false, "UNDER");
        }
        return new Decision("total", value, threshold, true, null);
    }

    /**
     * 可能就地改 {@code messages}。没触发时列表不动。
     * 压缩后仍高于阈值则把 guard 标成不再重试。
     */
    public static Outcome apply(List<Message> messages, ToolCallback[] tools, int round,
                                int threshold, int minGap, double growthRatio, int minMessageBudget,
                                Guard guard, Summarizer summarizer) {
        return apply(messages, tools, round, threshold, minGap, growthRatio, minMessageBudget,
                guard, summarizer, "summary");
    }

    /**
     * @param strategy {@code summary} 走模型摘要；{@code digest} 只改写旧消息，不调用 summarizer。
     *                 digest 内部失败时退回 summary，并在日志里留下原因。
     *                 digest 在被压段里保留的工具结果组数默认是 0。
     */
    public static Outcome apply(List<Message> messages, ToolCallback[] tools, int round,
                                int threshold, int minGap, double growthRatio, int minMessageBudget,
                                Guard guard, Summarizer summarizer, String strategy) {
        return apply(messages, tools, round, threshold, minGap, growthRatio, minMessageBudget,
                guard, summarizer, strategy, 0);
    }

    /**
     * @param digestKeepToolGroups 只在走 digest 时生效：被压段里保留最近几组工具结果原文。
     */
    public static Outcome apply(List<Message> messages, ToolCallback[] tools, int round,
                                int threshold, int minGap, double growthRatio, int minMessageBudget,
                                Guard guard, Summarizer summarizer, String strategy, int digestKeepToolGroups) {
        return apply(messages, tools, round, threshold, minGap, growthRatio, minMessageBudget,
                guard, summarizer, strategy, digestKeepToolGroups, 0.9);
    }

    /**
     * @param tieredAcceptRatio strategy 为 tiered 时，digest 后的估算不超过阈值的这个比例就采用 digest，否则改走摘要。
     */
    public static Outcome apply(List<Message> messages, ToolCallback[] tools, int round,
                                int threshold, int minGap, double growthRatio, int minMessageBudget,
                                Guard guard, Summarizer summarizer, String strategy,
                                int digestKeepToolGroups, double tieredAcceptRatio) {
        int value = estimate(messages, tools);
        int fixed = fixedTokens(messages, tools);
        int messageBudget = threshold - fixed;
        Decision decision = decide(round, value, threshold, minGap, growthRatio, guard);
        if (!decision.trigger()) {
            return new Outcome(decision, value, 0, null, false, fixed, messageBudget, null);
        }
        if (messageBudget < minMessageBudget) {
            boolean first = guard == null || !guard.skippedOverhead;
            if (guard != null) guard.skippedOverhead = true;
            Decision skip = new Decision("total", value, threshold, false, "COMPACTION_SKIPPED_FIXED_OVERHEAD");
            return new Outcome(skip, value, 0, null, first, fixed, messageBudget, null);
        }
        List<List<Message>> groups = groupsOf(messages);
        int sticky = stickyCount(groups);
        int reserve = messageBudget * 2 / 3;
        List<List<Message>> older = new ArrayList<>();
        List<List<Message>> recent = new ArrayList<>();
        int acc = 0;
        for (int i = groups.size() - 1; i >= sticky; i--) {
            List<Message> group = groups.get(i);
            if (isPriorSummary(group)) {
                older.add(0, group);
                continue;
            }
            int cost = groupTokens(group);
            if (acc + cost <= reserve) {
                recent.add(0, group);
                acc += cost;
            } else {
                older.add(0, group);
            }
        }
        Summary summary = null;
        int compactedCount = 0;
        String compactMode = null;
        if (!older.isEmpty()) {
            for (List<Message> group : older) compactedCount += group.size();
            boolean tiered = strategy != null && "tiered".equalsIgnoreCase(strategy.trim());
            boolean wantDigest = tiered || (strategy != null && "digest".equalsIgnoreCase(strategy.trim()));
            double ratio = tieredAcceptRatio > 0 && tieredAcceptRatio <= 1 ? tieredAcceptRatio : 0.9;
            List<Message> snapshot = new ArrayList<>(messages);
            if (wantDigest) {
                try {
                    MessageDigester.Result digested = MessageDigester.digest(
                            older, messageBudget, digestKeepToolGroups);
                    replaceCompacted(messages, groups, sticky, digested.messages(), recent);
                    int digestedAfter = estimate(messages, tools);
                    int accept = (int) Math.floor(threshold * ratio);
                    if (!tiered || digestedAfter <= accept) {
                        summary = new Summary(digested.indexText(), 0, 0, false, false);
                        compactMode = "DIGEST";
                        System.out.println("[Compactor] DIGEST 已清除工具结果 " + digested.clearedGroups()
                                + " 组，截断助手 " + digested.truncatedAssistants()
                                + " 条，省略用户 " + digested.omittedUsers() + " 条");
                    } else {
                        System.out.println("[Compactor] TIER_FALLBACK digestAfter=" + digestedAfter
                                + " threshold=" + threshold);
                        summary = summarizeOlder(older, summarizer, messageBudget);
                        if (summary != null) {
                            replaceCompacted(messages, groups, sticky, List.of(summaryMessage(summary)), recent);
                            compactMode = "SUMMARY";
                        } else {
                            // 摘要失败时保留已经做好的 digest，并明确标成 fallback，不回退成空摘要。
                            summary = new Summary(digested.indexText(), 0, 0, false, true);
                            compactMode = "SUMMARY";
                            System.err.println("[Compactor] 回退摘要失败，保留 digest 结果并标记 fallback");
                        }
                    }
                } catch (RuntimeException e) {
                    String why = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    System.err.println("[Compactor] DIGEST 失败，退回摘要: " + why);
                    messages.clear();
                    messages.addAll(snapshot);
                    try {
                        summary = summarizeOlder(older, summarizer, messageBudget);
                    } catch (RuntimeException again) {
                        System.err.println("[Compactor] 回退摘要也失败，保留原消息: " + again.getMessage());
                        summary = null;
                    }
                    if (summary != null) {
                        replaceCompacted(messages, groups, sticky, List.of(summaryMessage(summary)), recent);
                        compactMode = "SUMMARY";
                    }
                }
            } else {
                try {
                    summary = summarizeOlder(older, summarizer, messageBudget);
                } catch (RuntimeException e) {
                    String why = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    System.err.println("[Compactor] 摘要失败，保留原消息: " + why);
                    summary = null;
                }
                if (summary != null) {
                    replaceCompacted(messages, groups, sticky, List.of(summaryMessage(summary)), recent);
                    compactMode = "SUMMARY";
                }
            }
            if (summary == null) {
                // 摘要无效：放弃本次压缩，原消息逐项不变。已发生的模型调用由调用方记账。
                messages.clear();
                messages.addAll(snapshot);
                compactedCount = 0;
                System.err.println("[Compactor] 摘要无效，放弃本次压缩并保留原消息 " + messages.size() + " 条");
            }
            if (guard != null && compactMode != null) guard.lastMode = compactMode;
        }
        int after = estimate(messages, tools);
        boolean ineffective = summary != null && after > threshold;
        if (guard != null && summary != null) {
            guard.lastRound = round;
            guard.lastAfter = after;
            if (ineffective) guard.ineffective = true;
        }
        String reason = summary == null ? "SUMMARY_UNAVAILABLE"
                : ineffective ? "COMPACTION_INEFFECTIVE" : null;
        Decision done = new Decision("total", value, threshold, summary != null, reason);
        return new Outcome(done, after, compactedCount, summary, summary != null, fixed, messageBudget, compactMode);
    }

    private static Summary summarizeOlder(List<List<Message>> older, Summarizer summarizer, int messageBudget) {
        StringBuilder body = new StringBuilder();
        for (List<Message> group : older) {
            for (Message message : group) body.append(lineOf(message)).append('\n');
        }
        Summary summary = summarizer == null ? null : summarizer.summarize(body.toString());
        if (summary == null || summary.fallback() || summary.text() == null || summary.text().isBlank()) return null;
        // 摘要会替换整段 older；合法签名引用必须作为独立恢复信息保留下来。
        String refs = preservedArtifactRefs(older, messageBudget);
        if (refs == null) return null;
        if (refs.isEmpty()) return summary;
        String text = summary.text() == null ? "" : summary.text();
        String merged = text.isEmpty() ? refs : text + "\n\n" + refs;
        return new Summary(merged, summary.inputTokens(), summary.outputTokens(),
                summary.estimated(), summary.fallback());
    }

    /**
     * 从待压缩的所有消息里取签名有效的引用，包括此前生成的摘要和索引消息。
     * 一条消息里可以有多个引用；同一 path/chars/tool 只留一条。
     * 伪造、缺签名和篡改的引用不保留。
     *
     * @return null 表示引用太多或引用段超过消息预算的一半，调用方必须放弃本次压缩，
     *         不能静默丢掉任何一个唯一恢复定位
     */
    private static String preservedArtifactRefs(List<List<Message>> older, int messageBudget) {
        Map<String, ToolArtifactRef.Ref> refs = new LinkedHashMap<>();
        for (List<Message> group : older) {
            for (Message message : group) {
                List<ToolArtifactRef.Ref> found = ToolArtifactRef.parseAll(textOf(message));
                if (found == null) {
                    System.err.println("[Compactor] 单条消息的可信引用超过 "
                            + ToolArtifactRef.MAX_REFS_PER_MESSAGE + " 个，放弃本次压缩");
                    return null;
                }
                for (ToolArtifactRef.Ref ref : found) {
                    refs.putIfAbsent(ref.path() + "\u0000" + ref.chars() + "\u0000" + ref.tool(), ref);
                }
            }
        }
        if (refs.isEmpty()) return "";
        if (refs.size() > MAX_PRESERVED_ARTIFACT_REFS) {
            System.err.println("[Compactor] 可信引用 " + refs.size() + " 个，超过 "
                    + MAX_PRESERVED_ARTIFACT_REFS + " 个上限，放弃本次压缩并保留原消息");
            return null;
        }
        StringBuilder sb = new StringBuilder("【可恢复的工具结果】\n");
        for (ToolArtifactRef.Ref ref : refs.values()) {
            String tool = ref.tool() == null || ref.tool().isBlank() ? "工具" : ref.tool();
            sb.append("- ").append(tool).append("：").append(recoveryLine(ref)).append('\n');
        }
        String text = sb.toString().trim();
        int budget = (int) Math.floor(Math.max(0, messageBudget) * REF_BUDGET_RATIO);
        if (TokenEstimator.estimate(text) > budget) {
            System.err.println("[Compactor] 可信引用段超过消息预算的一半，放弃本次压缩并保留原消息");
            return null;
        }
        return text;
    }

    private static String recoveryLine(ToolArtifactRef.Ref ref) {
        if (ref.chars() >= 0) return ToolArtifactRef.recoverySuffix(ref, ref.chars());
        return "完整内容已落盘（快照），路径：" + ref.path()
                + "。用 readFile 按 offset/limit 读取；若文件已被清理，请重新调用工具。 "
                + ToolArtifactRef.marker(ref.path(), -1, ref.tool(), ref.sha256());
    }

    private static SystemMessage summaryMessage(Summary summary) {
        String text = summary == null || summary.text() == null ? "" : summary.text();
        return new SystemMessage("[历史摘要] 以下是更早对话的摘要，请结合其继续回答：\n" + text);
    }

    private static void replaceCompacted(List<Message> messages, List<List<Message>> groups, int sticky,
                                         List<Message> compacted, List<List<Message>> recent) {
        List<Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < sticky; i++) rebuilt.addAll(groups.get(i));
        rebuilt.addAll(compacted);
        for (List<Message> group : recent) rebuilt.addAll(group);
        messages.clear();
        messages.addAll(rebuilt);
    }

    /** 压不掉的部分：开头的系统消息，加上工具定义。后面的对话、工具结果、计划提醒都算可压缩。 */
    static int fixedTokens(List<Message> messages, ToolCallback[] tools) {
        List<List<Message>> groups = groupsOf(messages == null ? List.of() : messages);
        int sticky = stickyCount(groups);
        int n = 0;
        for (int i = 0; i < sticky; i++) n += groupTokens(groups.get(i));
        n += TokenEstimator.estimate(PromptCanonical.toolsJson(tools));
        return n;
    }

    static List<List<Message>> groupsOf(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant && hasToolCalls(assistant)) {
                List<Message> group = new ArrayList<>();
                group.add(assistant);
                while (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                    i++;
                    group.add(messages.get(i));
                }
                groups.add(group);
            } else {
                groups.add(List.of(message));
            }
        }
        return groups;
    }

    private static int stickyCount(List<List<Message>> groups) {
        int n = 0;
        for (List<Message> group : groups) {
            if (group.size() == 1 && group.get(0) instanceof SystemMessage system
                    && !isSummaryText(system.getText())) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    private static boolean isPriorSummary(List<Message> group) {
        return group.size() == 1 && group.get(0) instanceof SystemMessage system
                && isSummaryText(system.getText());
    }

    private static boolean isSummaryText(String text) {
        return text != null && (text.startsWith("[历史摘要]") || text.startsWith(MessageDigester.INDEX_PREFIX));
    }

    private static boolean hasToolCalls(AssistantMessage assistant) {
        return assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty();
    }

    private static int groupTokens(List<Message> group) {
        int n = 0;
        for (Message message : group) n += TokenEstimator.estimate(textOf(message));
        return n;
    }

    static String textOf(Message message) {
        if (message == null) return "";
        if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && response.responseData() != null) sb.append(response.responseData());
            }
            return sb.toString();
        }
        String text = message.getText();
        return text == null ? "" : text;
    }

    private static String lineOf(Message message) {
        if (message instanceof UserMessage) return "用户: " + textOf(message);
        if (message instanceof ToolResponseMessage) return "助手: " + textOf(message);
        return "助手: " + textOf(message);
    }
}
