package com.example.jingjingai2.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MCP 工具对外名字：{@code mcp__{server}__{tool}}。
 *
 * <p>OpenAI 兼容协议（DeepSeek 走这条）对 function name 的限制是
 * {@code ^[A-Za-z0-9_-]{1,64}$}。超长时截断并接 6 位稳定哈希，避免两个长名撞车。
 * 哈希输入是「配置里的 server 名 + 服务端原始 tool 名」，不是截断后的片段。
 */
public final class McpNames {

    public static final String PREFIX = "mcp__";
    public static final int MAX_LEN = 64;

    private McpNames() {}

    public static boolean isMcp(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    /**
     * {@code mcp__server__tool} → server。截断带哈希的名字解析不到时返回 null。
     */
    public static String serverOf(String exposed) {
        if (!isMcp(exposed)) return null;
        String rest = exposed.substring(PREFIX.length());
        int i = rest.indexOf("__");
        if (i <= 0) return null;
        String s = rest.substring(0, i);
        return s.isBlank() ? null : s;
    }

    /** {@code mcp__server__tool} → tool 段。 */
    public static String originalOf(String exposed) {
        if (!isMcp(exposed)) return null;
        String rest = exposed.substring(PREFIX.length());
        int i = rest.indexOf("__");
        if (i < 0 || i + 2 >= rest.length()) return null;
        String t = rest.substring(i + 2);
        return t.isBlank() ? null : t;
    }

    /**
     * 生成下发给模型的名字。server / tool 里的非法字符会被换成 {@code _}。
     */
    public static String exposed(String server, String tool) {
        String s = sanitize(server);
        String t = sanitize(tool);
        String full = PREFIX + s + "__" + t;
        if (full.length() <= MAX_LEN && isLegal(full)) {
            return full;
        }
        String hash = stableHash6(nullToEmpty(server) + "\0" + nullToEmpty(tool));
        // mcp__{server}__{prefix}_{hash6}
        int budget = MAX_LEN - PREFIX.length() - 2 /* __ */ - 1 /* _ */ - hash.length();
        // budget 分给 server + toolPrefix。server 至少留 1，其余给 tool。
        int serverBudget = Math.min(s.length(), Math.max(1, budget / 2));
        int toolBudget = Math.max(0, budget - serverBudget);
        String sPart = s.substring(0, Math.min(s.length(), serverBudget));
        String tPart = t.substring(0, Math.min(t.length(), toolBudget));
        String cut = PREFIX + sPart + "__" + tPart + "_" + hash;
        if (cut.length() > MAX_LEN) {
            cut = PREFIX + hash;
        }
        return cut;
    }

    /** 只保留 [A-Za-z0-9_-]；空则 unnamed。 */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        return out.isBlank() ? "unnamed" : out;
    }

    public static boolean isLegal(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LEN) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** SHA-256 前 3 字节 → 6 位小写 hex，同样输入永远同样输出。 */
    public static String stableHash6(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(nullToEmpty(input).getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x", d[0], d[1], d[2]);
        } catch (NoSuchAlgorithmException e) {
            // Java 17 必有 SHA-256；落到这里就用长度凑一个稳定值
            int h = nullToEmpty(input).hashCode();
            return String.format("%06x", h & 0xffffff);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
