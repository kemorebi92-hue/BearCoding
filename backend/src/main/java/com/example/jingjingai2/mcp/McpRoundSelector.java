package com.example.jingjingai2.mcp;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算「本轮真正下发给模型」的工具清单。非流式和 SSE 共用这一处，不要再抄一份。
 *
 * <p>{@code eager} 原样返回 bind 出来的全量数组（引用不变），不会插入 {@code mcpToolSearch}。
 * {@code deferred}：原生工具在前，然后是 search，再按 tracker 顺序追加已激活的 mcp__。
 * {@code auto}（默认）：已暴露 MCP 工具个数超过 {@code auto-eager-max-tools}，
 * 或定义估算 token 达到上下文窗口的 {@code auto-threshold-percent}%（默认 10）才 deferred，否则 eager。
 * 个数闸 ≤0 时只看百分比。窗口大小取不到（≤0）且个数没超时保守走 eager，并打一行日志。
 *
 * <p>传入的 catalog 必须是「实际会下发」的那份：用户 DENY 和白名单挡住的工具
 * 由调用方先滤掉，这里不再计入。
 */
public final class McpRoundSelector {

    public enum Mode { EAGER, DEFERRED }

    private static final double CHARS_PER_TOKEN = 1.5;

    private McpRoundSelector() {}

    public static Mode resolveMode(McpProperties props, ToolCallback[] mcpCatalog) {
        if (props == null) return Mode.EAGER;
        int count = mcpCatalog == null ? 0 : mcpCatalog.length;
        // 总开关关掉且本轮没有 MCP 工具：保持 eager，工具 JSON 与加 MCP 之前一致。
        // 页面上已经连上的个人服务不看这个开关，否则 25 个工具会绕过 deferred 整包重发。
        if (!props.isEnabled() && count == 0) return Mode.EAGER;
        String loading = props.getToolLoading();
        if (loading == null || loading.isBlank() || "eager".equalsIgnoreCase(loading)) {
            return Mode.EAGER;
        }
        if ("deferred".equalsIgnoreCase(loading)) return Mode.DEFERRED;
        if ("auto".equalsIgnoreCase(loading)) {
            // 百分比是相对真实窗口的。1M 窗口的 10% 约 10 万 token，25 个 Playwright
            // 工具（大约 1 万 token）踩不中，却会每轮整包重发。个数超过上限就走同一条 deferred。
            int maxEager = props.getAutoEagerMaxTools();
            if (maxEager > 0 && count > maxEager) {
                System.out.println("[MCP] auto 工具数 " + count + " > " + maxEager + "，选择 deferred");
                return Mode.DEFERRED;
            }
            if (props.getContextWindowTokens() <= 0) {
                System.out.println("[MCP] auto 模式拿不到上下文窗口大小，保守走 eager");
                return Mode.EAGER;
            }
            long tokens = estimateSchemaTokens(mcpCatalog);
            int percent = props.getAutoThresholdPercent();
            if (percent <= 0) percent = 10;
            long threshold = (long) props.getContextWindowTokens() * percent / 100;
            Mode mode = tokens >= threshold ? Mode.DEFERRED : Mode.EAGER;
            System.out.println("[MCP] auto 估算 " + tokens + " token，窗口 "
                    + props.getContextWindowTokens() + " 的 " + percent + "% = " + threshold
                    + "，选择 " + mode.name().toLowerCase());
            return mode;
        }
        return Mode.EAGER;
    }

    /**
     * 与评测脚本同一口径：把工具定义序列化成 JSON 后字符数 ÷ 1.5。
     * auto 阈值只看 MCP catalog（manager.callbacks()），不含原生工具。
     */
    public static long estimateSchemaTokens(ToolCallback[] cbs) {
        if (cbs == null || cbs.length == 0) return 0;
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (ToolCallback cb : cbs) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            ToolDefinition d = cb.getToolDefinition();
            if (!first) sb.append(',');
            first = false;
            String desc = d.description() == null ? "" : d.description().replace("\"", "\\\"");
            String schema = d.inputSchema() == null ? "{}" : d.inputSchema();
            sb.append("{\"name\":\"").append(d.name())
                    .append("\",\"description\":\"").append(desc)
                    .append("\",\"parameters\":").append(schema).append('}');
        }
        sb.append(']');
        return Math.round(sb.length() / CHARS_PER_TOKEN);
    }

    /**
     * 主循环入口：eager 返回 {@code fullBind} 原引用；deferred 才拼 search + 已激活。
     * MCP 关掉时同样原样返回。
     */
    /** 系统/部署级视图（单测用）。 */
    public static ToolCallback[] forRound(ToolCallback[] fullBind, McpClientManager manager,
                                          List<Message> messages) {
        return forRound(fullBind, manager, messages, 0L);
    }

    /**
     * @param userId 当前登录用户：搜索目录与名字映射都只用他可见的那份，
     *               否则同名服务会显示成别人的描述
     */
    public static ToolCallback[] forRound(ToolCallback[] fullBind, McpClientManager manager,
                                          List<Message> messages, long userId) {
        if (fullBind == null) fullBind = new ToolCallback[0];
        if (manager == null) return fullBind;
        ToolCallback[] catalog = mcpCatalogOf(fullBind);
        if (catalog.length == 0) return fullBind;
        Mode mode = resolveMode(manager.properties(), catalog);
        if (mode != Mode.DEFERRED) return fullBind;
        McpProperties props = manager.properties();
        int maxActive = props == null ? 8 : props.effectiveMaxActiveTools();
        int maxResults = props == null ? 5 : props.effectiveSearchMaxResults();
        List<String> activated = McpActivationTracker.activated(messages, maxActive);
        ToolCallback search = new McpToolSearchCallback(
                mcpCatalogOf(fullBind),
                manager.nameMap(userId),
                maxResults);
        return select(fullBind, search, activated, Mode.DEFERRED);
    }

    /** 搜索目录用本轮 fullBind 里的 mcp__，这样 DENY 过滤之后的名单不会被 manager.callbacks() 加回去。 */
    public static ToolCallback[] mcpCatalogOf(ToolCallback[] fullBind) {
        if (fullBind == null || fullBind.length == 0) return new ToolCallback[0];
        List<ToolCallback> mcp = new ArrayList<>();
        for (ToolCallback cb : fullBind) {
            String n = nameOf(cb);
            if (McpNames.isMcp(n)) mcp.add(cb);
        }
        return mcp.toArray(new ToolCallback[0]);
    }

    /**
     * {@code eager}：原样返回 {@code fullBind}（不插入 search）。
     * {@code deferred}：原生（非 mcp__、非 search）+ search + 已激活（按 tracker 顺序，且必须在 catalog 里）。
     */
    public static ToolCallback[] select(ToolCallback[] fullBind, ToolCallback searchCb,
                                        List<String> activatedNames, Mode mode) {
        if (mode != Mode.DEFERRED) {
            return fullBind == null ? new ToolCallback[0] : fullBind;
        }
        List<ToolCallback> nativeCbs = new ArrayList<>();
        Map<String, ToolCallback> mcpByName = new LinkedHashMap<>();
        if (fullBind != null) {
            for (ToolCallback cb : fullBind) {
                String n = nameOf(cb);
                if (n == null) continue;
                if (McpNames.isMcp(n)) {
                    mcpByName.put(n, cb);
                } else if (!McpToolSearchCallback.NAME.equals(n)) {
                    nativeCbs.add(cb);
                }
            }
        }
        List<ToolCallback> out = new ArrayList<>(nativeCbs);
        if (searchCb != null) out.add(searchCb);
        if (activatedNames != null) {
            for (String n : activatedNames) {
                ToolCallback cb = mcpByName.get(n);
                if (cb != null) out.add(cb);
            }
        }
        return out.toArray(new ToolCallback[0]);
    }

    public static String joinNames(ToolCallback[] cbs) {
        if (cbs == null || cbs.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolCallback cb : cbs) {
            String n = nameOf(cb);
            if (n == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(n);
        }
        return sb.toString();
    }

    private static String nameOf(ToolCallback cb) {
        if (cb == null || cb.getToolDefinition() == null) return null;
        return cb.getToolDefinition().name();
    }
}
