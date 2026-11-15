package com.example.jingjingai2.permission;

import com.example.jingjingai2.sandbox.CommandSafetyChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内置工具名 → ALLOW / ASK，以及这次调用能不能「本会话允许」。
 * MCP 工具的用户策略不在这张表里，走 {@link McpPermissionExplainer}。
 * 表里留下的 {@code mcp__*} 只是网关没有 explainer 时的兜底（单测直接 dispatch）。
 */
public final class PermissionPolicy {

    public enum Effect { ALLOW, ASK }

    public static final class Match {
        public final Effect effect;
        /** 这次调用是否提供「本会话允许」。高危命令和 deletePath 为 false。 */
        public final boolean sessionGrant;
        public final boolean riskyCommand;

        Match(Effect effect, boolean sessionGrant, boolean riskyCommand) {
            this.effect = effect;
            this.sessionGrant = sessionGrant;
            this.riskyCommand = riskyCommand;
        }
    }

    /**
     * 内置表里「无条件免审批」的工具名：读类、计划类、子代理入口、web 搜索、上下文检索、
     * 会话级记忆写入（{@code saveMemory}）以及 MCP 延迟加载入口。
     *
     * <p>这一份清单是<b>唯一来源</b>，同时喂给两处：
     * <ol>
     *   <li>{@link #builtin()} 生成 ALLOW 规则（表顺序不变）；</li>
     *   <li>安全基础设施缺失时的兜底判定 —— 没有权限网关、或没有调用上下文时，
     *       只有这些工具允许照常执行（见 {@code ToolDispatcher#dispatch}
     *       与 {@code ToolPermissionGate#authorize}）。</li>
     * </ol>
     *
     * <p>为什么可以这样兜底：这些工具在策略表里本来就是无条件 ALLOW，装上网关也一样放行，
     * 所以「基础设施缺失时放行」不会给它们多出任何权限。写文件、终端执行、删除、MCP、
     * 用户记忆（{@code saveUserMemory} / {@code forgetUserMemory}）和一切未登记工具都不在这里，
     * 因此缺少网关或上下文时一律 fail-closed。
     */
    private static final List<String> APPROVAL_FREE_LIST = List.of(
            "readFile", "listDirectory", "globFile", "grepFile",
            "createPlan", "updatePlanStep", "spawnSubagent", "webSearch",
            "searchContext",
            "saveMemory",
            "mcpToolSearch");

    private static final Set<String> APPROVAL_FREE = Set.copyOf(APPROVAL_FREE_LIST);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<Compiled> rules;

    public PermissionPolicy(PermissionProperties props) {
        List<PermissionProperties.Rule> src = props == null ? null : props.getRules();
        if (src == null || src.isEmpty()) src = builtin();
        this.rules = compile(src);
    }

    public Match match(String toolName, String arguments) {
        String name = toolName == null ? "" : toolName;
        Compiled hit = null;
        for (Compiled c : rules) {
            if (c.pattern.matcher(name).matches()) {
                hit = c;
                break;
            }
        }
        Effect effect = hit == null ? Effect.ASK : hit.effect;
        boolean grant = hit != null && hit.sessionGrant && effect == Effect.ASK;
        boolean risky = false;
        if (isExecute(name)) {
            String cmd = commandOf(arguments);
            risky = cmd != null && CommandSafetyChecker.isRisky(cmd);
            if (risky) grant = false;
        }
        if (isDelete(name) || isUserMemory(name)) {
            grant = false;
        }
        return new Match(effect, grant, risky);
    }

    /** 未登记的 MCP 默认可会话授权；其余未登记工具只问这一次。由内置表的顺序保证。 */
    static List<PermissionProperties.Rule> builtin() {
        List<PermissionProperties.Rule> r = new ArrayList<>();
        // 无条件 ALLOW 的一组。清单本身见 APPROVAL_FREE_LIST（与 fail-closed 兜底共用一份）。
        // 延迟加载的入口 mcpToolSearch 也在这里：只按目录挑出工具名，不触碰任何 MCP 服务。
        // 不放行的话它会落到最后的「未登记一律 ASK」，每次搜索都要等一次审批，
        // 而审批超时后工具根本不执行，deferred 模式就永远激活不了任何工具。
        for (String n : APPROVAL_FREE_LIST) {
            r.add(rule(n, "ALLOW", false));
        }
        // Playwright 的 6 个「不问」行已删除。暴露出来的 MCP 工具走 explain：
        // 未配置默认 ASK，用户和服务级设置可以改，强制询问只能再收紧。
        // 长期记忆每次都要看清内容和键。不能「本会话允许」，也不能被工作区免审批档盖过。
        r.add(rule("saveUserMemory", "ASK", false));
        r.add(rule("forgetUserMemory", "ASK", false));
        r.add(rule("deletePath", "ASK", false));
        for (String n : new String[]{
                "writeFile", "editFile", "createFile", "appendFile",
                "createDirectory", "movePath", "copyPath", "executeCommand"}) {
            r.add(rule(n, "ASK", true));
        }
        r.add(rule("mcp__*", "ASK", true));
        r.add(rule("*", "ASK", false));
        return r;
    }

    /**
     * 安全基础设施缺失时，这个工具还能不能照常执行。
     *
     * <p>只回答一个问题：它是否属于策略表里<b>无条件 ALLOW</b> 的那一组。
     * 用于「没有安装权限网关」与「没有调用上下文」两种结构性异常场景 ——
     * 这两种场景以前会直接放行<b>任何</b>工具（包括写文件、终端、MCP），属于 fail-open。
     *
     * <p>判定是严格的：{@code mcp__*} 永远不算（MCP 走用户/服务级策略，必须真的问过网关），
     * 名称必须精确匹配（不做下划线/大小写归一），拿不准就按需要审批处理。
     */
    public static boolean approvalFree(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        if (toolName.startsWith("mcp__")) return false;
        return APPROVAL_FREE.contains(toolName);
    }

    private static PermissionProperties.Rule rule(String pattern, String effect, boolean grant) {
        PermissionProperties.Rule rule = new PermissionProperties.Rule();
        rule.setPattern(pattern);
        rule.setEffect(effect);
        rule.setSessionGrant(grant);
        return rule;
    }

    private static List<Compiled> compile(List<PermissionProperties.Rule> src) {
        List<Compiled> out = new ArrayList<>();
        for (PermissionProperties.Rule rule : src) {
            if (rule == null || rule.getPattern() == null || rule.getPattern().isBlank()) continue;
            Effect effect = "ALLOW".equalsIgnoreCase(rule.getEffect()) ? Effect.ALLOW : Effect.ASK;
            out.add(new Compiled(glob(rule.getPattern().trim()), effect, rule.isSessionGrant()));
        }
        return out;
    }

    /** `*` 只匹配通配，其余按字面。Pattern.quote 会把星号包进 \\Q，不能事后替换。 */
    private static Pattern glob(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            int star = pattern.indexOf('*', i);
            if (star < 0) {
                sb.append(Pattern.quote(pattern.substring(i)));
                break;
            }
            sb.append(Pattern.quote(pattern.substring(i, star))).append(".*");
            i = star + 1;
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    private static boolean isExecute(String name) {
        return "executeCommand".equalsIgnoreCase(name) || "execute_command".equalsIgnoreCase(name);
    }

    private static boolean isDelete(String name) {
        return "deletePath".equalsIgnoreCase(name) || "delete_path".equalsIgnoreCase(name);
    }

    private static boolean isUserMemory(String name) {
        return "saveUserMemory".equalsIgnoreCase(name) || "forgetUserMemory".equalsIgnoreCase(name);
    }

    private static String commandOf(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        try {
            JsonNode n = MAPPER.readTree(arguments);
            JsonNode c = n.get("command");
            if (c != null && c.isTextual()) return c.asText();
        } catch (Exception ignored) {
            // 参数不是 JSON 时不当成高危命令，仍走工具级 ASK
        }
        return null;
    }

    public static String riskText(String toolName, Match match, boolean taintedUpgrade) {
        String base;
        if (isDelete(toolName)) {
            base = "将删除文件或目录，不能用「本会话允许」跳过";
        } else if ("saveUserMemory".equalsIgnoreCase(toolName)) {
            base = "将写入当前用户的长期记忆。请核对类别、键名和内容；不批准不会保存";
        } else if ("forgetUserMemory".equalsIgnoreCase(toolName)) {
            base = "将删除当前用户的一条长期记忆。请核对类别和键名；不批准不会删除";
        } else if (match.riskyCommand) {
            base = "高危命令，每次都需要单独确认";
        } else if (isExecute(toolName)) {
            base = "将执行终端命令";
        } else {
            base = "此操作可能修改工作区或调用外部能力";
        }
        if (!taintedUpgrade) return base;
        return "此操作可能由网页内容诱发。" + base;
    }

    public static String sourceOf(String toolName) {
        if (toolName != null && toolName.startsWith("mcp__")) {
            String rest = toolName.substring("mcp__".length());
            int i = rest.indexOf("__");
            if (i > 0) return rest.substring(0, i);
            return rest;
        }
        return "原生";
    }

    private static final class Compiled {
        final Pattern pattern;
        final Effect effect;
        final boolean sessionGrant;

        Compiled(Pattern pattern, Effect effect, boolean sessionGrant) {
            this.pattern = pattern;
            this.effect = effect;
            this.sessionGrant = sessionGrant;
        }
    }
}
