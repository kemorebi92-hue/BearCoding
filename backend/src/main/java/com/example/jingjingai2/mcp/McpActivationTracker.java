package com.example.jingjingai2.mcp;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 从本轮消息历史推导「已激活的 mcp__ 集合」。纯函数，不落库。
 *
 * <p>来源只有两处：mcpToolSearch 结果第一行 {@code ACTIVATED: a, b}，以及
 * <strong>成功</strong>执行过的 {@code mcp__} 工具。失败（{@link McpToolCallback#ERROR_PREFIX}）
 * 和 ToolGuard 拒绝都不算激活。压缩把搜索结果摘掉之后集合可以变空——那是预期，
 * 模型再调未激活的 mcp__ 会被守卫拦回去并提示先搜索。
 *
 * <p>顺序：未超上限时 = 首次出现顺序；超出 {@code maxActive} 时先留每条
 * {@code ACTIVATED:} 行的首个名字，其余名额按首次出现的倒序补，最近的优先。
 */
public final class McpActivationTracker {

    public static final String ACTIVATED_PREFIX = "ACTIVATED:";

    private McpActivationTracker() {}

    public static List<String> activated(List<Message> messages, int maxActive) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<List<String>> groups = new ArrayList<>();
        if (messages != null) {
            for (Message m : messages) {
                collect(m, seen, groups);
            }
        }
        int cap = maxActive < 1 ? 8 : maxActive;
        if (seen.size() <= cap) return List.copyOf(seen);
        return capByGroup(seen, groups, cap);
    }

    /**
     * 超出上限时，先留每条 {@code ACTIVATED:} 行的首个名字（搜索按相关度排序，
     * 首个就是这次搜索最想要的），剩下的名额按首次出现的倒序补，最近出现的优先。
     * 这样一次返回很多陪衬工具的搜索，挤不掉另一次搜索的首选。
     */
    private static List<String> capByGroup(LinkedHashSet<String> seen,
                                            List<List<String>> groups, int cap) {
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        List<String> heads = new ArrayList<>();
        for (List<String> g : groups) {
            if (!g.isEmpty()) heads.add(g.get(0));
        }
        for (int i = Math.max(0, heads.size() - cap); i < heads.size() && kept.size() < cap; i++) {
            kept.add(heads.get(i));
        }
        List<String> rest = new ArrayList<>(seen);
        for (int i = rest.size() - 1; i >= 0 && kept.size() < cap; i--) {
            kept.add(rest.get(i));
        }
        return List.copyOf(kept);
    }

    private static void collect(Message m, LinkedHashSet<String> seen, List<List<String>> groups) {
        if (m == null) return;
        if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                if (r == null) continue;
                String name = r.name();
                String data = r.responseData() == null ? "" : r.responseData();
                parseActivatedLines(data, seen, groups);
                if (McpNames.isMcp(name) && isSuccessfulMcpResult(data)) {
                    seen.add(name);
                }
            }
        }
        parseActivatedLines(m.getText(), seen, groups);
    }

    static void parseActivatedLines(String text, LinkedHashSet<String> seen, List<List<String>> groups) {
        if (text == null || text.isEmpty() || !text.contains(ACTIVATED_PREFIX)) return;
        int idx = 0;
        while (idx < text.length()) {
            int at = text.indexOf(ACTIVATED_PREFIX, idx);
            if (at < 0) break;
            int lineEnd = text.indexOf('\n', at);
            if (lineEnd < 0) lineEnd = text.length();
            String rest = text.substring(at + ACTIVATED_PREFIX.length(), lineEnd).trim();
            if (!rest.isEmpty()) {
                List<String> group = new ArrayList<>();
                for (String part : rest.split(",")) {
                    String n = part.trim();
                    if (McpNames.isMcp(n) && McpNames.isLegal(n)) {
                        seen.add(n);
                        group.add(n);
                    }
                }
                if (!group.isEmpty()) groups.add(group);
            }
            idx = lineEnd + 1;
        }
    }

    /** 失败串和守卫拒绝不算「成功调用过」。 */
    static boolean isSuccessfulMcpResult(String data) {
        if (data == null) return true;
        if (data.startsWith(McpToolCallback.ERROR_PREFIX)) return false;
        if (data.contains("本轮不可用（它不在本轮下发的工具清单里）")) return false;
        if (data.startsWith("未知的工具")) return false;
        return true;
    }
}
