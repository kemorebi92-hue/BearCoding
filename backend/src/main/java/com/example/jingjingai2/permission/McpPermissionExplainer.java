package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpListedTool;
import com.example.jingjingai2.mcp.McpNames;
import com.example.jingjingai2.mcp.McpToolAnnotations;
import com.example.jingjingai2.sandbox.CommandSafetyChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 工具的唯一判定。网关执行和「生效策略」接口都调 {@link #explain}，不要在别处再写一份。
 *
 * <p>顺序：URL 等硬拒绝 → 用户 DENY（定义变了也保留）/ 无单工具设置时的服务级 DENY →
 * 仍有效的单工具设置 → 服务级默认 → 未配置则 ASK → 收紧。
 * 服务自报的 {@code destructiveHint} 只在用户没有对该工具单独 ALLOW 时收成 ASK，
 * 服务级放行盖不过它。破坏性标注、名字词表、高危参数、跨服务污染把结果收成 ASK 时，
 * 都可以本会话允许：用户点过就表示这个会话里愿意承担这个工具的风险，下次不再问。
 * 高危参数的授权单独记，一次无害参数的允许盖不过后来的 {@code rm -rf}。
 * 定义哈希变了没有本会话允许：单独 ALLOW 变成 ASK，DENY 仍是 DENY，
 * ASK 仍是 ASK；在用户按新定义重新设置之前，生效结果最高是 ASK，服务级 ALLOW 不能放行。
 *
 * <p>硬拒绝只留给「用户同意也不应执行」的情况（已有的内网 / 回环 / 元数据 URL 规则）。
 * 其余危险操作一律询问，由用户判断。模型自主风险判断本轮不做。
 *
 * <p>标注是服务自报的，不可信，而且实际区分度很低（Playwright 25 个里 18 个都标破坏性）。
 * 所以默认谨慎，用户对单个工具明确放行可以盖过标注；名字、参数、跨服务污染盖不过单独 ALLOW，
 * 但仍可以本会话允许。定义变更必须重新确认，不能用本会话允许跳过。
 * {@code readOnlyHint=true} 只展示，绝不放行。名字词表是启发式兜底，有误报，接受误报。
 */
@Component
public class McpPermissionExplainer {

    public enum Effect { ALLOW, ASK, DENY }

    public static final class ExplainResult {
        public final Effect effect;
        public final String reason;
        /** 这次调用能不能提供「本会话允许」。 */
        public final boolean sessionGrant;
        /** 保存策略时的定义哈希和当前工具定义不一致，单工具设置已按未配置处理。 */
        public final boolean definitionChanged;
        /** 强制询问来自跨服务污染。卡片用它标「可能由网页内容诱发」。 */
        public final boolean crossServiceTaint;
        /** 这次询问是因为参数命中高危命令或 SQL。本会话授权单独记，不和普通调用混用。 */
        public final boolean argumentForce;

        ExplainResult(Effect effect, String reason, boolean sessionGrant,
                      boolean definitionChanged, boolean crossServiceTaint, boolean argumentForce) {
            this.effect = effect;
            this.reason = reason;
            this.sessionGrant = sessionGrant;
            this.definitionChanged = definitionChanged;
            this.crossServiceTaint = crossServiceTaint;
            this.argumentForce = argumentForce;
        }
    }

    /** 没有对该工具单独 ALLOW 时，破坏性标注收成这一句。服务级放行不算数。 */
    public static final String DESTRUCTIVE_NEEDS_TOOL_ALLOW =
            "服务自报为破坏性操作，请在面板中对该工具单独设置后才可放行";

    /**
     * 名字按单词切开后命中即强制询问，用户的单独 ALLOW 也盖不过。
     * 带下划线的项（run_code）看整名是否包含。
     * 其余项：单词相等，或长度 ≥ 3 且单词以其开头（evaluate 命中 eval）。
     * 这是启发式兜底，有误报，接受误报。
     */
    static final String[] FORCE_ASK_WORDS = {
            "delete", "remove", "drop", "destroy", "kill",
            "truncate", "purge", "wipe", "revoke",
            "unsafe", "eval", "exec", "shell", "run_code"
    };

    private static final Pattern[] ARG_PATTERNS = {
            Pattern.compile("(?i)\\bdrop\\s+table\\b"),
            Pattern.compile("(?i)\\bdrop\\s+database\\b"),
            Pattern.compile("(?i)\\btruncate\\b"),
            Pattern.compile("(?i)\\bdelete\\s+from\\b"),
            Pattern.compile("(?i)\\brm\\s+-rf\\b")
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpUserPolicyStore store;
    private final McpClientManager manager;

    public McpPermissionExplainer(McpUserPolicyStore store, McpClientManager manager) {
        this.store = store;
        this.manager = manager;
    }

    /**
     * @param arguments 本次调用参数；接口展示生效策略时传 null，参数类强制询问就不参加
     */
    public ExplainResult explain(Long userId, String server, String tool, String arguments) {
        String serverName = blankToNull(server);
        String toolName = blankToNull(tool);
        if (toolName != null && toolName.startsWith(McpNames.PREFIX)) {
            if (serverName == null) serverName = McpNames.serverOf(toolName);
            String original = McpNames.originalOf(toolName);
            if (original != null) toolName = original;
        }
        McpListedTool listed = find(userId, serverName, toolName);
        if (listed != null) {
            serverName = listed.server;
            toolName = listed.originalName;
        }

        String url = arguments == null ? null : UrlArgumentScanner.reject(arguments);
        if (url != null) {
            return new ExplainResult(Effect.DENY,
                    "地址不被允许（" + url + "）。用户设置不能覆盖硬拒绝",
                    false, false, false, false);
        }

        boolean changed = false;
        String toolEffect = null;
        String serverEffect = null;
        if (userId != null && serverName != null && store != null) {
            serverEffect = normalize(store.serverDefault(userId, serverName));
            if (toolName != null) {
                toolEffect = normalize(store.toolEffect(userId, serverName, toolName));
                String storedHash = store.toolHash(userId, serverName, toolName);
                String current = listed == null ? null : listed.definitionHash();
                changed = storedHash != null && current != null && !storedHash.equals(current);
                // 哈希变化只作废单独 ALLOW。DENY / ASK 留着，避免拒绝过的工具掉回服务级放行。
                if (changed && "ALLOW".equals(toolEffect)) toolEffect = null;
            }
        }

        // 优先级：单工具规则 > 服务级默认 > 未配置。
        // 第二个分支的 `toolEffect == null` 正是「服务级 DENY 只是默认值」的实现：
        // 单工具 ALLOW 时不会走到这里。去掉这个条件，服务级 DENY 就变成不可覆盖的封禁了。
        if ("DENY".equals(toolEffect)) {
            return decided(Effect.DENY, "该工具被设为拒绝", false, changed, false, false);
        }
        if (toolEffect == null && "DENY".equals(serverEffect)) {
            return decided(Effect.DENY, "服务默认拒绝，该工具没有单独设置", false, changed, false, false);
        }

        boolean perToolAllow = "ALLOW".equals(toolEffect);
        Effect effect;
        String reason;
        boolean grant;
        if (perToolAllow || "ASK".equals(toolEffect)) {
            effect = perToolAllow ? Effect.ALLOW : Effect.ASK;
            reason = perToolAllow ? "该工具单独设为允许" : "该工具单独设为询问";
            grant = effect == Effect.ASK;
        } else if (changed) {
            effect = Effect.ASK;
            reason = "工具定义已变更，需重新确认";
            grant = false;
        } else if (serverEffect != null) {
            effect = "ALLOW".equals(serverEffect) ? Effect.ALLOW : Effect.ASK;
            reason = effect == Effect.ALLOW ? "服务默认允许" : "服务默认询问";
            grant = effect == Effect.ASK;
        } else {
            effect = Effect.ASK;
            reason = "未配置，默认询问";
            grant = true;
        }

        McpToolAnnotations annotations = listed == null ? McpToolAnnotations.none() : listed.annotations;
        boolean destructive = annotations != null && Boolean.TRUE.equals(annotations.destructiveHint);
        if (destructive && !perToolAllow) {
            // 标注自己把 ALLOW 收成 ASK 时，这次询问可以本会话允许。
            // 原本已是 ASK 且允许会话授权的，不因标注关掉；定义变更留下的 grant=false 也不在这里打开。
            if (effect == Effect.ALLOW) {
                grant = true;
            }
            effect = Effect.ASK;
            reason = DESTRUCTIVE_NEEDS_TOOL_ALLOW;
        }

        String force = hardForce(toolName, arguments, serverName);
        boolean cross = force != null && force.startsWith("会话已被其他");
        boolean argForce = argHit(arguments) != null;
        if (force != null) {
            effect = Effect.ASK;
            // 单独 ALLOW 盖不过强制询问，但用户可以为本会话承担这个工具的风险。
            // 定义变更仍不能跳过，下面会把 grant 关掉。
            if (!changed) grant = true;
            reason = reason + "；强制询问：" + force;
        }
        if (changed && effect == Effect.ASK) {
            grant = false;
        }
        return decided(effect, reason, grant, changed, cross, argForce && effect == Effect.ASK);
    }

    /**
     * 面板上的「可能强制询问」：只表示<b>用户单独 ALLOW 也盖不过</b>的那一类，即名字命中词表。
     *
     * <p>破坏性标注不算：单独 ALLOW 能盖过它，此时 {@code explain} 给的就是 ALLOW，标签再写
     * 「可能强制询问」就与生效结果自相矛盾；没有单独 ALLOW 时 {@code explain} 自己给出 ASK
     * 并带上原因，面板照常显示，不需要这个「可能」。
     */
    public boolean maybeForceAsk(String toolName) {
        return nameHit(toolName) != null;
    }

    private McpListedTool find(Long userId, String server, String tool) {
        if (manager == null || server == null || tool == null) return null;
        // 先找这个用户自己的服务，找不到再看部署/共享级（manager 内部就是这么回退的）
        return manager.findListed(userId == null ? 0L : userId, server, tool);
    }

    /** 名字、参数、跨服务污染。不含 destructiveHint：那一项可以被单独 ALLOW 盖过。 */
    private String hardForce(String toolName, String arguments, String server) {
        List<String> why = new ArrayList<>();
        String word = nameHit(toolName);
        if (word != null) why.add("工具名命中启发式词表：" + word);
        String arg = argHit(arguments);
        if (arg != null) why.add(arg);
        if (crossService(server)) why.add("会话已被其他 MCP 服务的不可信内容污染");
        if (why.isEmpty()) return null;
        return String.join("、", why);
    }

    private boolean crossService(String server) {
        ToolCallContext ctx = ToolCallContext.current();
        if (ctx == null || !ctx.tainted() || manager == null) return false;
        Set<String> sources = manager.taintSources(ctx.conversationId);
        if (server != null && sources.contains(server)) return false;
        return true;
    }

    static String nameHit(String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        String raw = toolName.toLowerCase(Locale.ROOT);
        List<String> words = words(toolName);
        for (String key : FORCE_ASK_WORDS) {
            String k = key.toLowerCase(Locale.ROOT);
            if (k.indexOf('_') >= 0) {
                if (raw.contains(k)) return key;
                continue;
            }
            for (String w : words) {
                if (w.equals(k) || (k.length() >= 3 && w.startsWith(k))) return key;
            }
        }
        return null;
    }

    static List<String> words(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] parts = spaced.split("[^A-Za-z0-9]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) out.add(p.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String argHit(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        List<String> texts = new ArrayList<>();
        try {
            JsonNode n = MAPPER.readTree(arguments);
            if (n != null && (n.isObject() || n.isArray() || n.isTextual())) collect(n, texts);
            else texts.add(arguments);
        } catch (Exception e) {
            texts.add(arguments);
        }
        for (String t : texts) {
            if (t == null || t.isBlank()) continue;
            if (CommandSafetyChecker.isRisky(t)) return "参数命中高危命令";
            for (Pattern p : ARG_PATTERNS) {
                if (p.matcher(t).find()) return "参数命中危险语句";
            }
        }
        return null;
    }

    private static void collect(JsonNode node, List<String> texts) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            texts.add(node.asText());
            return;
        }
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) collect(it.next().getValue(), texts);
            return;
        }
        if (node.isArray()) {
            for (JsonNode c : node) collect(c, texts);
        }
    }

    private static ExplainResult decided(Effect effect, String reason, boolean grant,
                                         boolean changed, boolean cross, boolean argumentForce) {
        return new ExplainResult(effect, reason, grant && effect == Effect.ASK, changed, cross, argumentForce);
    }

    private static String normalize(String effect) {
        if (effect == null || effect.isBlank()) return null;
        String e = effect.trim().toUpperCase(Locale.ROOT);
        if ("ALLOW".equals(e) || "ASK".equals(e) || "DENY".equals(e)) return e;
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
