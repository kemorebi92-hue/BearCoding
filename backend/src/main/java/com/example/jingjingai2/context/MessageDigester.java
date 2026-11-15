package com.example.jingjingai2.context;

import com.example.jingjingai2.tool.ToolArtifactRef;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不调模型的压缩：只改写将被压掉的旧消息。最近的原文保留由 {@link RoundCompactor} 负责。
 * 占位符再跑一遍不会变长，也不会把原文找回来。
 */
final class MessageDigester {

    static final String INDEX_PREFIX = "[压缩索引] ";
    private static final String CLEARED_PREFIX = "[已清除的工具结果：";
    private static final String TRUNCATED_MARK = "…[已截断，原长 ";
    private static final Pattern OMITTED = Pattern.compile("^\\[省略 (\\d+) 条用户消息\\]$");
    /** 单测模拟内部失败。生产路径保持 false。 */
    static volatile boolean failForTest = false;

    private MessageDigester() {}

    record Result(List<Message> messages, int clearedGroups, int truncatedAssistants, int omittedUsers,
                  String indexText) {}

    /**
     * @param keepToolGroups 在被压掉的这一段里，保留最近几组工具结果的原文。0 表示这一段里的工具结果全部换成占位符。
     *                       保留窗口里的最近消息不归这里管。
     */
    static Result digest(List<List<Message>> older, int messageBudget, int keepToolGroups) {
        if (failForTest) throw new IllegalStateException("digest-test-failure");
        Set<ToolResponseMessage> keepFull = recentToolResults(older, Math.max(0, keepToolGroups));
        List<Message> flat = new ArrayList<>();
        int cleared = 0;
        int truncated = 0;
        for (List<Message> group : older) {
            if (group.isEmpty() || isIndex(group.get(0))) continue;
            boolean toolGroup = isToolGroup(group);
            boolean protect = toolGroup && containsKeptResult(group, keepFull);
            boolean clearedThis = false;
            for (Message message : group) {
                if (toolGroup && !protect && message instanceof ToolResponseMessage tool) {
                    flat.add(clearToolResult(tool));
                    clearedThis = true;
                } else if (message instanceof AssistantMessage assistant && !hasToolCalls(assistant)) {
                    AssistantMessage shortened = truncateAssistant(assistant);
                    flat.add(shortened);
                    if (isTruncated(shortened.getText())) truncated++;
                } else {
                    flat.add(message);
                }
            }
            if (clearedThis) cleared++;
        }
        int omitted = capUserMessages(flat, Math.max(0, messageBudget / 2));
        String index = INDEX_PREFIX + "已清除的工具结果：" + cleared + " 组；已截断的助手回复："
                + truncated + " 条；已省略的用户消息：" + omitted + " 条";
        List<Message> out = new ArrayList<>();
        out.add(new SystemMessage(index));
        out.addAll(flat);
        return new Result(out, cleared, truncated, omitted, index);
    }

    /** 只看被压掉的这一段，从末尾数 keep 组工具调用。 */
    private static Set<ToolResponseMessage> recentToolResults(List<List<Message>> older, int keep) {
        Set<ToolResponseMessage> kept = new HashSet<>();
        int seen = 0;
        for (int i = older.size() - 1; i >= 0 && seen < keep; i--) {
            List<Message> group = older.get(i);
            if (group.isEmpty() || isIndex(group.get(0)) || !isToolGroup(group)) continue;
            for (Message message : group) {
                if (message instanceof ToolResponseMessage tool) kept.add(tool);
            }
            seen++;
        }
        return kept;
    }

    private static boolean isToolGroup(List<Message> group) {
        if (group.isEmpty()) return false;
        Message first = group.get(0);
        if (first instanceof AssistantMessage assistant && hasToolCalls(assistant)) return true;
        return group.size() == 1 && first instanceof ToolResponseMessage;
    }

    private static boolean containsKeptResult(List<Message> group, Set<ToolResponseMessage> keepFull) {
        for (Message message : group) {
            if (message instanceof ToolResponseMessage tool && keepFull.contains(tool)) return true;
        }
        return false;
    }

    private static ToolResponseMessage clearToolResult(ToolResponseMessage tool) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (tool.getResponses() != null) {
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response == null) continue;
                String data = response.responseData() == null ? "" : response.responseData();
                if (!ToolArtifactRef.isOmissionMarker(data)) {
                    ToolArtifactRef.Ref ref = ToolArtifactRef.parse(data);
                    int original = ref != null && ref.chars() >= 0 ? ref.chars() : data.length();
                    data = CLEARED_PREFIX + response.name() + "，原长 " + original + " 字。"
                            + ToolArtifactRef.recoverySuffix(ref, data.length()) + "]";
                }
                responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), data));
            }
        }
        return new ToolResponseMessage(responses);
    }

    private static AssistantMessage truncateAssistant(AssistantMessage assistant) {
        String text = assistant.getText() == null ? "" : assistant.getText();
        if (text.length() <= 200 || isTruncated(text)) return assistant;
        return new AssistantMessage(text.substring(0, 200) + TRUNCATED_MARK + text.length() + " 字]",
                assistant.getMetadata(), List.of());
    }

    private static boolean isTruncated(String text) {
        return text != null && text.contains(TRUNCATED_MARK);
    }

    private static boolean hasToolCalls(AssistantMessage assistant) {
        return assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty();
    }

    private static boolean isIndex(Message message) {
        return message instanceof SystemMessage system && system.getText() != null
                && system.getText().startsWith(INDEX_PREFIX);
    }

    /** 用户原文的估算 token 超过预算一半时，只留最早一条和末尾装得下的若干条。返回省略条数。 */
    private static int capUserMessages(List<Message> flat, int tokenBudget) {
        List<Integer> real = new ArrayList<>();
        List<Integer> markers = new ArrayList<>();
        for (int i = 0; i < flat.size(); i++) {
            if (!(flat.get(i) instanceof UserMessage user)) continue;
            if (omittedCount(user.getText()) > 0) markers.add(i);
            else real.add(i);
        }
        int used = 0;
        for (int index : real) used += TokenEstimator.estimate(flat.get(index).getText());
        if (used <= tokenBudget) {
            int already = 0;
            for (int index : markers) already += omittedCount(flat.get(index).getText());
            return already;
        }
        Set<Integer> keep = new HashSet<>();
        if (!real.isEmpty()) {
            keep.add(real.get(0));
            int keptTokens = TokenEstimator.estimate(flat.get(real.get(0)).getText());
            for (int k = real.size() - 1; k >= 1; k--) {
                int cost = TokenEstimator.estimate(flat.get(real.get(k)).getText());
                if (keptTokens + cost > tokenBudget) break;
                keep.add(real.get(k));
                keptTokens += cost;
            }
        }
        int omitted = 0;
        List<Integer> remove = new ArrayList<>();
        int insertAt = -1;
        for (int index : real) {
            if (keep.contains(index)) continue;
            omitted++;
            remove.add(index);
            if (insertAt < 0) insertAt = index;
        }
        for (int index : markers) {
            omitted += omittedCount(flat.get(index).getText());
            remove.add(index);
            if (insertAt < 0 || index < insertAt) insertAt = index;
        }
        remove.sort((a, b) -> Integer.compare(b, a));
        int shift = 0;
        if (insertAt >= 0) {
            for (int index : remove) if (index < insertAt) shift++;
        }
        for (int index : remove) flat.remove(index);
        if (omitted > 0 && insertAt >= 0) {
            flat.add(insertAt - shift, new UserMessage("[省略 " + omitted + " 条用户消息]"));
        }
        return omitted;
    }

    private static int omittedCount(String text) {
        if (text == null) return 0;
        Matcher matcher = OMITTED.matcher(text.trim());
        if (!matcher.matches()) return 0;
        return Integer.parseInt(matcher.group(1));
    }
}
