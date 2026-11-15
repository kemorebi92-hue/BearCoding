package com.example.jingjingai2.context;

import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;
import com.example.jingjingai2.tool.ToolSnapshotIntegrity;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本轮循环里的工具结果太大时，把更早的结果换成一行省略标记。
 *
 * <p>不调用模型。没有另一份逐字相同正文，或没有本次进程保留且内容校验通过的快照时，
 * 保留原文。预算因此仍超出时，结果里的 {@code complete} 为 false。
 * 标记换掉正文，tool_call id 不动，配对仍然合法。
 */
public final class ToolHistoryCompactor {

    public static final String MARKER_PREFIX = "[旧工具结果已省略：";
    private static final Pattern RETIRED_KEEP = Pattern.compile("保留结果 tool_call_id=([^。\\]\\s]+)");

    private ToolHistoryCompactor() {}

    /** 一次压缩的结果。{@code complete=false} 表示没有更多安全候选，但预算仍超出。 */
    public record Result(int replaced, boolean complete, int blocked) {}

    public static boolean isMarker(String text) {
        return text != null && text.startsWith(MARKER_PREFIX);
    }

    /**
     * @return 换成标记的条数。历史已经不超过 budget，或没有安全候选时返回对应条数。
     */
    public static int compactInPlace(List<Message> messages, int historyBudgetTokens) {
        return compact(messages, historyBudgetTokens).replaced();
    }

    public static Result compact(List<Message> messages, int historyBudgetTokens) {
        if (messages == null || messages.isEmpty()) return new Result(0, true, 0);
        int budget = historyBudgetTokens <= 0 ? 24000 : historyBudgetTokens;
        int replaced = 0;
        int blocked = 0;
        int lastTool = lastToolIndex(messages);
        List<Message> snapshot = new ArrayList<>(messages);
        try {
            while (ContextBudget.historyTokens(messages) > budget) {
                int[] hit = oldestSafe(messages, lastTool);
                if (hit == null) {
                    blocked = unsafeCount(messages, lastTool);
                    System.out.println("[ToolHistoryCompactor] compaction_incomplete replaced="
                            + replaced + " blocked=" + blocked);
                    return new Result(replaced, false, blocked);
                }
                replace(messages, hit[0], hit[1]);
                MessageProtocolValidator.Result check = MessageProtocolValidator.check(messages);
                if (!check.ok()) {
                    restore(messages, snapshot);
                    System.err.println("[ToolHistoryCompactor] protocol_failed skipped issues="
                            + check.summary());
                    return new Result(replaced, false, blocked);
                }
                snapshot = new ArrayList<>(messages);
                replaced++;
                if (replaced > 10000) return new Result(replaced, false, blocked);
            }
            return new Result(replaced, true, 0);
        } catch (RuntimeException e) {
            restore(messages, snapshot);
            System.err.println("[ToolHistoryCompactor] 异常，保留原文: " + e.getClass().getSimpleName());
            return new Result(0, false, blocked);
        }
    }

    private static int lastToolIndex(List<Message> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) last = i;
        }
        return last;
    }

    /** 最早的安全候选：另一份正文逐字相同，或带内容哈希且本次进程保留的快照校验通过。 */
    private static int[] oldestSafe(List<Message> messages, int lastTool) {
        for (int mi = 0; mi < messages.size(); mi++) {
            if (mi == lastTool) continue;
            Message m = messages.get(mi);
            if (!(m instanceof ToolResponseMessage trm) || trm.getResponses() == null) continue;
            List<ToolResponseMessage.ToolResponse> rs = trm.getResponses();
            for (int ri = 0; ri < rs.size(); ri++) {
                ToolResponseMessage.ToolResponse r = rs.get(ri);
                if (r == null || !eligible(r, messages)) continue;
                if (hasExactDuplicate(r, messages) || hasVerifiedSnapshot(r.responseData())) {
                    return new int[]{mi, ri};
                }
            }
        }
        return null;
    }

    private static boolean eligible(ToolResponseMessage.ToolResponse response, List<Message> messages) {
        String data = response.responseData() == null ? "" : response.responseData();
        if (ToolArtifactRef.isOmissionMarker(data) || data.length() < 80) return false;
        return !protectedKeepIds(messages).contains(response.id());
    }

    private static int unsafeCount(List<Message> messages, int lastTool) {
        int count = 0;
        for (int mi = 0; mi < messages.size(); mi++) {
            if (mi == lastTool || !(messages.get(mi) instanceof ToolResponseMessage trm)
                    || trm.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
                if (response != null && eligible(response, messages)) count++;
            }
        }
        return count;
    }

    private static boolean hasExactDuplicate(ToolResponseMessage.ToolResponse candidate, List<Message> messages) {
        String data = candidate.responseData() == null ? "" : candidate.responseData();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response == null || response == candidate || response.id().equals(candidate.id())) continue;
                String other = response.responseData() == null ? "" : response.responseData();
                if (data.equals(other) && !ToolArtifactRef.isOmissionMarker(other)) return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> protectedKeepIds(List<Message> messages) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response == null || response.responseData() == null) continue;
                Matcher matcher = RETIRED_KEEP.matcher(response.responseData());
                if (matcher.find() && !hasVerifiedSnapshot(dataOf(messages, matcher.group(1)))) {
                    ids.add(matcher.group(1));
                }
            }
        }
        return ids;
    }

    private static String dataOf(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && id.equals(response.id())) return response.responseData();
            }
        }
        return "";
    }

    /**
     * 旧引用没有 sha256，直接不可靠。新引用必须对同一次读取的字节校验通过。
     * 校验通过后才保留快照，避免容量或 TTL 清理在原文刚被替换后删除它。
     * 校验本身复用 {@link ToolSnapshotIntegrity}，与退休闸门、readFile 用同一套判断。
     */
    private static boolean hasVerifiedSnapshot(String data) {
        ToolSnapshotIntegrity.Check check = ToolSnapshotIntegrity.verify(ToolArtifactRef.parse(data), null);
        if (!check.ok()) return false;
        ToolOutputStore.retain(check.path());
        return true;
    }

    private static void restore(List<Message> messages, List<Message> snapshot) {
        messages.clear();
        messages.addAll(snapshot);
    }

    private static void replace(List<Message> messages, int mi, int ri) {
        Message m = messages.get(mi);
        if (!(m instanceof ToolResponseMessage trm) || trm.getResponses() == null) return;
        List<ToolResponseMessage.ToolResponse> cur = trm.getResponses();
        if (ri >= cur.size()) return;
        List<ToolResponseMessage.ToolResponse> next = new ArrayList<>(cur.size());
        for (int i = 0; i < cur.size(); i++) {
            ToolResponseMessage.ToolResponse r = cur.get(i);
            if (i == ri && r != null) {
                String data = r.responseData() == null ? "" : r.responseData();
                ToolArtifactRef.Ref ref = ToolArtifactRef.parse(data);
                int original = ref != null && ref.chars() >= 0 ? ref.chars() : data.length();
                String marker = MARKER_PREFIX + r.name() + "，原 " + original
                        + " 字符。" + ToolArtifactRef.recoverySuffix(ref, data.length()) + "]";
                next.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), marker));
            } else {
                next.add(r);
            }
        }
        messages.set(mi, new ToolResponseMessage(next));
    }
}
