package com.example.jingjingai2.mcp;

import com.example.jingjingai2.tool.ToolArtifactRef;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旧页面内容从 messages 里摘掉，只留同一 server 最新一份完整结果。
 *
 * <p>就地改 ToolResponse 文本，不动 assistant 的 tool_call id，配对仍然合法。
 * 已经换成省略标记的条目不再还原 —— 前缀对 DeepSeek cache 稳定。
 * 审计日志在写入 messages 之前已经落库，不受这里影响。
 */
public final class McpHistoryPruner {

    public static final int DEFAULT_MIN_CHARS = 500;

    private McpHistoryPruner() {}

    public static String omitMarker(String exposedName, int originalChars) {
        return omitMarker(exposedName, originalChars, null);
    }

    /**
     * 省略标记。{@code ref} 非空时带完整内容路径；为空时明确写“未落盘，无法恢复”。
     * 已落盘时同时保留机器引用，后续压缩器可以继续把它带下去。
     */
    static String omitMarker(String exposedName, int originalChars, ToolArtifactRef.Ref ref) {
        int chars = ref != null && ref.chars() >= 0 ? ref.chars() : originalChars;
        return "[旧页面内容已省略：" + exposedName + "，原 " + chars + " 字符。"
                + ToolArtifactRef.recoverySuffix(ref, originalChars) + "]";
    }

    public static boolean isOmitMarker(String text) {
        return text != null && text.startsWith("[旧页面内容已省略：");
    }

    /**
     * 按配置把同一 server 的旧页面结果换成省略标记。{@code history-prune-tools} 为空则什么都不做。
     */
    public static void pruneInPlace(List<Message> messages, McpProperties props) {
        if (messages == null || messages.isEmpty() || props == null) return;
        Map<String, McpServerSpec> servers = props.getServers();
        if (servers == null || servers.isEmpty()) return;
        pruneInPlace(messages, servers);
    }

    public static void pruneInPlace(List<Message> messages, Map<String, McpServerSpec> servers) {
        if (messages == null || messages.isEmpty() || servers == null || servers.isEmpty()) return;

        Map<String, List<Hit>> byServer = new LinkedHashMap<>();
        for (int mi = 0; mi < messages.size(); mi++) {
            Message m = messages.get(mi);
            if (!(m instanceof ToolResponseMessage trm)) continue;
            List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
            if (responses == null) continue;
            for (int ri = 0; ri < responses.size(); ri++) {
                ToolResponseMessage.ToolResponse r = responses.get(ri);
                if (r == null) continue;
                String exposed = r.name();
                String server = McpNames.serverOf(exposed);
                if (server == null) continue;
                McpServerSpec spec = servers.get(server);
                if (spec == null) continue;
                List<String> prune = spec.getHistoryPruneTools();
                if (prune == null || prune.isEmpty()) continue;
                if (!isPruneClass(exposed, prune)) continue;
                String data = r.responseData() == null ? "" : r.responseData();
                int chars = data.length();
                int min = spec.effectiveHistoryPruneMinChars();
                if (chars < min) continue;
                if (ToolArtifactRef.isOmissionMarker(data)) continue;
                byServer.computeIfAbsent(server, k -> new ArrayList<>())
                        .add(new Hit(mi, ri, exposed, chars, ToolArtifactRef.parse(data)));
            }
        }

        for (List<Hit> hits : byServer.values()) {
            if (hits.size() < 2) continue;
            for (int i = 0; i < hits.size() - 1; i++) {
                Hit h = hits.get(i);
                replace(messages, h, omitMarker(h.exposed, h.chars, h.ref));
            }
        }
    }

    static boolean isPruneClass(String exposed, List<String> pruneTools) {
        if (exposed == null || pruneTools == null) return false;
        String original = McpNames.originalOf(exposed);
        for (String p : pruneTools) {
            if (p == null || p.isBlank()) continue;
            if (p.equals(exposed) || p.equals(original)) return true;
            if (original != null && original.equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    private static void replace(List<Message> messages, Hit hit, String newData) {
        Message m = messages.get(hit.messageIndex);
        if (!(m instanceof ToolResponseMessage trm)) return;
        List<ToolResponseMessage.ToolResponse> cur = trm.getResponses();
        if (cur == null || hit.responseIndex >= cur.size()) return;
        List<ToolResponseMessage.ToolResponse> next = new ArrayList<>(cur.size());
        for (int i = 0; i < cur.size(); i++) {
            ToolResponseMessage.ToolResponse r = cur.get(i);
            if (i == hit.responseIndex) {
                next.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), newData));
            } else {
                next.add(r);
            }
        }
        messages.set(hit.messageIndex, new ToolResponseMessage(next));
    }

    private record Hit(int messageIndex, int responseIndex, String exposed, int chars,
                       ToolArtifactRef.Ref ref) {}

    /** 测试用：判断某条 tool 名是否落在 prune 集合里。 */
    static boolean matches(String exposed, Set<String> pruneTools) {
        return pruneTools != null && isPruneClass(exposed, List.copyOf(pruneTools));
    }
}
