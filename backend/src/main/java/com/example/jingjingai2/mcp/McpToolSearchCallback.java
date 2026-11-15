package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内建搜索工具，名字固定 {@code mcpToolSearch}（不是 mcp__ 前缀）。
 *
 * <p>只在 deferred / auto-deferred 时进入本轮清单。目录来自
 * {@link McpClientManager#callbacks()}（已经过 enabled-tools / disabled-tools）。
 * 查询：关键词按 名字×3 + server×2 + 说明×1 打分；或以 {@code select:name1,name2} 精确加载。
 * 返回第一行永远是 {@code ACTIVATED: ...}，给 {@link McpActivationTracker} 认。
 */
public final class McpToolSearchCallback implements ToolCallback {

    public static final String NAME = "mcpToolSearch";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]+|[\\u4e00-\\u9fff]+");
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":"
            + "\"关键词（按工具名/服务名/说明打分），或 select:名字1,名字2 精确加载已连接的 mcp__ 工具\"}"
            + "},\"required\":[\"query\"]}";

    private final ToolCallback[] catalog;
    private final Map<String, String[]> nameMap;
    private final int maxResults;
    private final ToolDefinition def;

    public McpToolSearchCallback(McpClientManager manager, int maxResults) {
        this(manager == null ? new ToolCallback[0] : manager.callbacks(),
                manager == null ? Map.of() : manager.nameMap(),
                maxResults);
    }

    public McpToolSearchCallback(ToolCallback[] catalog, Map<String, String[]> nameMap, int maxResults) {
        this.catalog = catalog == null ? new ToolCallback[0] : catalog;
        this.nameMap = nameMap == null ? Map.of() : nameMap;
        int n = maxResults < 1 ? 5 : maxResults;
        this.maxResults = Math.min(n, 8);
        this.def = ToolDefinition.builder()
                .name(NAME)
                .description("搜索并激活已连接 MCP Server 上的工具。"
                        + "query 为关键词，或 select:mcp__server__tool,... 精确加载。"
                        + "返回第一行 ACTIVATED: 已激活的名字；下一轮才能调用这些 mcp__ 工具。"
                        + "未激活的 mcp__ 本轮不可用。读写工作区仍用内建文件工具。")
                .inputSchema(SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return def;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String arguments) {
        String query = extractQuery(arguments);
        List<Hit> hits;
        if (query != null && query.regionMatches(true, 0, "select:", 0, 7)) {
            hits = selectExact(query.substring(7));
        } else {
            hits = search(query);
        }
        return format(hits);
    }

    @Override
    public String call(String arguments, ToolContext context) {
        return call(arguments);
    }

    static String extractQuery(String arguments) {
        if (arguments == null) return "";
        String raw = arguments.trim();
        if (raw.isEmpty()) return "";
        if (raw.startsWith("{")) {
            try {
                JsonNode n = MAPPER.readTree(raw);
                if (n != null && n.has("query")) {
                    return n.path("query").asText("");
                }
            } catch (Exception ignored) {
                // 模型偶发塞了非 JSON，当整段当 query
            }
        }
        return raw;
    }

    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static int score(String name, String server, String description, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return 0;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String s = server == null ? "" : server.toLowerCase(Locale.ROOT);
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        int sc = 0;
        for (String t : tokens) {
            if (n.contains(t)) sc += 3;
            if (s.contains(t)) sc += 2;
            if (d.contains(t)) sc += 1;
        }
        return sc;
    }

    private List<Hit> search(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty() || catalog.length == 0) return List.of();
        List<Hit> scored = new ArrayList<>();
        for (int i = 0; i < catalog.length; i++) {
            ToolCallback cb = catalog[i];
            if (cb == null || cb.getToolDefinition() == null) continue;
            String name = cb.getToolDefinition().name();
            if (!McpNames.isMcp(name)) continue;
            String server = serverOf(name);
            String desc = cb.getToolDefinition().description();
            int sc = score(name, server, desc, tokens);
            if (sc > 0) scored.add(new Hit(name, server, desc, sc, i));
        }
        scored.sort(Comparator.comparingInt((Hit h) -> h.score).reversed()
                .thenComparingInt(h -> h.index));
        if (scored.size() > maxResults) {
            return new ArrayList<>(scored.subList(0, maxResults));
        }
        return scored;
    }

    private List<Hit> selectExact(String csv) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String n = part.trim();
                if (!n.isEmpty()) wanted.add(n);
            }
        }
        List<Hit> hits = new ArrayList<>();
        LinkedHashSet<String> taken = new LinkedHashSet<>();
        for (String want : wanted) {
            for (int i = 0; i < catalog.length && hits.size() < maxResults; i++) {
                ToolCallback cb = catalog[i];
                if (cb == null || cb.getToolDefinition() == null) continue;
                String name = cb.getToolDefinition().name();
                if (!McpNames.isMcp(name) || taken.contains(name)) continue;
                if (matchesSelect(want, name)) {
                    taken.add(name);
                    hits.add(new Hit(name, serverOf(name), cb.getToolDefinition().description(), 0, i));
                }
            }
        }
        return hits;
    }

    private boolean matchesSelect(String want, String exposed) {
        if (want.equals(exposed)) return true;
        String[] pair = nameMap.get(exposed);
        if (pair != null && pair.length >= 2 && want.equals(pair[1])) return true;
        return exposed.endsWith("__" + want);
    }

    private String serverOf(String exposed) {
        String[] pair = nameMap.get(exposed);
        if (pair != null && pair.length >= 1 && pair[0] != null) return pair[0];
        if (exposed != null && exposed.startsWith(McpNames.PREFIX)) {
            String rest = exposed.substring(McpNames.PREFIX.length());
            int cut = rest.indexOf("__");
            if (cut > 0) return rest.substring(0, cut);
        }
        return "";
    }

    private static String format(List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append(McpActivationTracker.ACTIVATED_PREFIX);
        if (hits == null || hits.isEmpty()) {
            sb.append(" \n未找到匹配的 MCP 工具。请换关键词，或用 select:mcp__server__tool 精确加载。");
            return sb.toString();
        }
        sb.append(' ');
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(hits.get(i).name);
        }
        sb.append('\n');
        for (Hit h : hits) {
            sb.append(h.name);
            if (h.server != null && !h.server.isBlank()) {
                sb.append(" [").append(h.server).append(']');
            }
            if (h.description != null && !h.description.isBlank()) {
                sb.append(' ').append(h.description);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final class Hit {
        final String name;
        final String server;
        final String description;
        final int score;
        final int index;

        Hit(String name, String server, String description, int score, int index) {
            this.name = name;
            this.server = server;
            this.description = description;
            this.score = score;
            this.index = index;
        }
    }
}
