package com.example.jingjingai2.mcp.testdouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 评测用 MCP Server：stdio 一行一条 JSON-RPC。
 *
 * <p>{@code --profile=tracker} 15 个 issue/PR 工具；{@code --profile=ops} 15 个
 * 日历/天气/文件元数据工具。故意放近义重复名，成功结果带唯一标记 {@code [T-&lt;name&gt;-OK]}。
 */
public final class FatMcpServer {

    private static final ObjectMapper M = new ObjectMapper();

    public static final class ToolSpec {
        public final String name;
        public final String description;
        public final String schemaJson;

        public ToolSpec(String name, String description, String schemaJson) {
            this.name = name;
            this.description = description;
            this.schemaJson = schemaJson;
        }
    }

    public static List<ToolSpec> catalog(String profile) {
        if ("ops".equalsIgnoreCase(profile)) return ops();
        return tracker();
    }

    public static List<ToolSpec> tracker() {
        List<ToolSpec> t = new ArrayList<>();
        t.add(spec("list_issues",
                "List issues in a repository. Filter by state (open/closed/all), optional label, and a page size. "
                        + "Use this to survey current work items before commenting or closing.",
                obj(p("repo", "string", "owner/name"),
                        p("state", "string", "open | closed | all"),
                        p("label", "string", "optional label filter"),
                        p("limit", "integer", "max items, default 20")),
                req("repo")));
        t.add(spec("list_open_issues",
                "List only currently open issues for a repository. Similar to list_issues with state=open, "
                        + "but returns a shorter card. Prefer this when you just need the open queue.",
                obj(p("repo", "string", "owner/name"),
                        p("limit", "integer", "max items")),
                req("repo")));
        t.add(spec("get_issue",
                "Fetch one issue by number: title, body, state, labels, assignees.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "issue number")),
                req("repo", "number")));
        t.add(spec("get_issue_status",
                "Return only the state of an issue (open/closed) plus the last-updated timestamp. "
                        + "Cheaper than get_issue when you do not need the body.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "issue number")),
                req("repo", "number")));
        t.add(spec("create_issue",
                "Open a new issue. Title is required; body and labels are optional.",
                obj(p("repo", "string", "owner/name"),
                        p("title", "string", "issue title"),
                        p("body", "string", "markdown body"),
                        p("labels", "string", "comma-separated labels")),
                req("repo", "title")));
        t.add(spec("close_issue",
                "Close an issue. Optional closing comment is appended first.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "issue number"),
                        p("comment", "string", "optional closing comment")),
                req("repo", "number")));
        t.add(spec("comment_issue",
                "Add a comment to an existing issue (markdown body).",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "issue number"),
                        p("body", "string", "comment markdown")),
                req("repo", "number", "body")));
        t.add(spec("add_issue_comment",
                "Post a short text comment on an issue. Near-duplicate of comment_issue; "
                        + "this one takes `text` instead of `body`.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "issue number"),
                        p("text", "string", "plain comment")),
                req("repo", "number", "text")));
        t.add(spec("list_issue_comments",
                "List comments already on an issue, oldest first.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "issue number")),
                req("repo", "number")));
        t.add(spec("search_issues",
                "Search issues with a free-text query, optionally scoped to one repo.",
                obj(p("query", "string", "search query"),
                        p("repo", "string", "optional owner/name"),
                        p("limit", "integer", "max hits")),
                req("query")));
        t.add(spec("find_issues",
                "Find issues by keyword `q`. Near-duplicate of search_issues with a shorter schema.",
                obj(p("q", "string", "keyword"),
                        p("repo", "string", "optional owner/name")),
                req("q")));
        t.add(spec("list_prs",
                "List pull requests. Filter by state (open/closed/merged/all).",
                obj(p("repo", "string", "owner/name"),
                        p("state", "string", "open | closed | merged | all"),
                        p("limit", "integer", "max items")),
                req("repo")));
        t.add(spec("get_pr",
                "Fetch one pull request: title, body, head, base, mergeable.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "PR number")),
                req("repo", "number")));
        t.add(spec("create_pr",
                "Open a pull request from head branch into base branch.",
                obj(p("repo", "string", "owner/name"),
                        p("title", "string", "PR title"),
                        p("head", "string", "source branch"),
                        p("base", "string", "target branch, default main"),
                        p("body", "string", "markdown body")),
                req("repo", "title", "head")));
        t.add(spec("merge_pr",
                "Merge a pull request. method = merge | squash | rebase.",
                obj(p("repo", "string", "owner/name"),
                        p("number", "integer", "PR number"),
                        p("method", "string", "merge | squash | rebase")),
                req("repo", "number")));
        return t;
    }

    public static List<ToolSpec> ops() {
        List<ToolSpec> t = new ArrayList<>();
        t.add(spec("list_calendar",
                "List calendar events starting at `date` (YYYY-MM-DD) for `days` days. "
                        + "Returns title, start, end, location.",
                obj(p("date", "string", "YYYY-MM-DD"),
                        p("days", "integer", "range length, default 1")),
                req("date")));
        t.add(spec("list_calendar_events",
                "List events on a single date. Near-duplicate of list_calendar with days fixed at 1.",
                obj(p("date", "string", "YYYY-MM-DD")),
                req("date")));
        t.add(spec("get_calendar_event",
                "Fetch one calendar event by id.",
                obj(p("event_id", "string", "event id")),
                req("event_id")));
        t.add(spec("create_calendar_event",
                "Create a calendar event with title, start, optional end and location.",
                obj(p("title", "string", "event title"),
                        p("start", "string", "ISO-8601 start"),
                        p("end", "string", "ISO-8601 end"),
                        p("location", "string", "optional location")),
                req("title", "start")));
        t.add(spec("get_weather",
                "Current weather for a city: temperature C, condition, humidity, wind.",
                obj(p("city", "string", "city name")),
                req("city")));
        t.add(spec("get_today_weather",
                "Today's weather summary for a city. Near-duplicate of get_weather, shorter payload.",
                obj(p("city", "string", "city name")),
                req("city")));
        t.add(spec("get_weather_report",
                "Weather report for a city with units (metric/imperial). Near-duplicate of get_weather.",
                obj(p("city", "string", "city name"),
                        p("units", "string", "metric | imperial")),
                req("city")));
        t.add(spec("get_forecast",
                "Multi-day forecast for a city.",
                obj(p("city", "string", "city name"),
                        p("days", "integer", "1-7, default 3")),
                req("city")));
        t.add(spec("list_file_metadata",
                "List file metadata under a path matching optional glob (size, mtime, mime).",
                obj(p("path", "string", "directory"),
                        p("glob", "string", "optional glob")),
                req("path")));
        t.add(spec("get_file_metadata",
                "Metadata for a single file: size, mtime, mime, checksum.",
                obj(p("path", "string", "file path")),
                req("path")));
        t.add(spec("stat_file",
                "POSIX-like stat for a path (size, mode, mtime). Near-duplicate of get_file_metadata.",
                obj(p("path", "string", "file path")),
                req("path")));
        t.add(spec("search_files",
                "Search file names under root by query substring.",
                obj(p("query", "string", "substring"),
                        p("root", "string", "search root")),
                req("query")));
        t.add(spec("get_disk_usage",
                "Disk usage for a path: used, free, percent.",
                obj(p("path", "string", "mount or directory")),
                req("path")));
        t.add(spec("list_alerts",
                "List ops alerts. Optional severity filter: info | warn | error.",
                obj(p("severity", "string", "info | warn | error | all")),
                req()));
        t.add(spec("get_alert",
                "Fetch one alert by id: title, severity, opened_at, detail.",
                obj(p("alert_id", "string", "alert id")),
                req("alert_id")));
        return t;
    }

    public static void main(String[] args) throws Exception {
        String profile = "tracker";
        if (args != null) {
            for (String a : args) {
                if (a != null && a.startsWith("--profile=")) {
                    profile = a.substring("--profile=".length()).trim();
                }
            }
        }
        List<ToolSpec> tools = catalog(profile);
        Map<String, ToolSpec> byName = new LinkedHashMap<>();
        for (ToolSpec t : tools) byName.put(t.name, t);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = M.readTree(line);
            } catch (Exception e) {
                continue;
            }
            String method = msg.path("method").asText("");
            JsonNode id = msg.get("id");
            boolean hasId = id != null && !id.isNull();
            if ("notifications/initialized".equals(method)) continue;
            if (!hasId) continue;

            if ("initialize".equals(method)) {
                ObjectNode result = M.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                result.set("capabilities", M.createObjectNode().set("tools", M.createObjectNode()));
                ObjectNode info = M.createObjectNode();
                info.put("name", "fat-" + profile);
                info.put("version", "1.0.0");
                result.set("serverInfo", info);
                reply(out, id, result);
                continue;
            }
            if ("tools/list".equals(method)) {
                ObjectNode result = M.createObjectNode();
                ArrayNode arr = M.createArrayNode();
                for (ToolSpec t : tools) {
                    arr.add(toolNode(t));
                }
                result.set("tools", arr);
                reply(out, id, result);
                continue;
            }
            if ("tools/call".equals(method)) {
                JsonNode params = msg.path("params");
                String name = params.path("name").asText("");
                JsonNode arguments = params.path("arguments");
                ObjectNode result = M.createObjectNode();
                ArrayNode content = M.createArrayNode();
                if (!byName.containsKey(name)) {
                    content.add(text("unknown tool: " + name));
                    result.put("isError", true);
                } else {
                    content.add(text(call(name, arguments)));
                    result.put("isError", false);
                }
                result.set("content", content);
                reply(out, id, result);
            }
        }
    }

    public static String call(String name, JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            args = M.createObjectNode();
        }
        String marker = "[T-" + name + "-OK]";
        return switch (name) {
            case "list_issues", "list_open_issues" -> marker + " repo="
                    + args.path("repo").asText("jingjingAI2")
                    + " issues=#12 title=\"deferred loading overflows 8k\"; #7 title=\"stdio handshake timeout\"";
            case "get_issue" -> marker + " #" + args.path("number").asInt(12)
                    + " title=\"deferred loading overflows 8k\" state=open";
            case "get_issue_status" -> marker + " #" + args.path("number").asInt(12) + " state=open";
            case "create_issue" -> marker + " created #" + (40 + args.path("title").asText("").length())
                    + " title=" + args.path("title").asText("");
            case "close_issue" -> marker + " closed #" + args.path("number").asInt(0);
            case "comment_issue", "add_issue_comment" -> marker + " commented #"
                    + args.path("number").asInt(0);
            case "list_issue_comments" -> marker + " #" + args.path("number").asInt(0)
                    + " comments=2";
            case "search_issues", "find_issues" -> marker + " hits=#12,#7 q="
                    + args.path("query").asText(args.path("q").asText(""));
            case "list_prs" -> marker + " repo=" + args.path("repo").asText("jingjingAI2")
                    + " prs=#3 title=\"mcp stdio client\"";
            case "get_pr" -> marker + " #" + args.path("number").asInt(3)
                    + " title=\"mcp stdio client\" mergeable=true";
            case "create_pr" -> marker + " created PR #9 title=" + args.path("title").asText("")
                    + " head=" + args.path("head").asText("")
                    + " base=" + args.path("base").asText("main");
            case "merge_pr" -> marker + " merged #" + args.path("number").asInt(0)
                    + " method=" + args.path("method").asText("squash");
            case "list_calendar", "list_calendar_events" -> marker + " date="
                    + args.path("date").asText("2026-09-30")
                    + " events=\"standup 09:30\"; \"eval review 14:00\"";
            case "get_calendar_event" -> marker + " id=" + args.path("event_id").asText("")
                    + " title=\"standup\"";
            case "create_calendar_event" -> marker + " created id=evt-77 title="
                    + args.path("title").asText("");
            case "get_weather", "get_today_weather", "get_weather_report" -> marker + " city="
                    + args.path("city").asText("Beijing")
                    + " temp_c=18 condition=cloudy humidity=46";
            case "get_forecast" -> marker + " city=" + args.path("city").asText("Beijing")
                    + " days=" + args.path("days").asInt(3)
                    + " 18/20/16 cloudy";
            case "list_file_metadata" -> marker + " path=" + args.path("path").asText(".")
                    + " files=README.md,src/";
            case "get_file_metadata", "stat_file" -> marker + " path="
                    + args.path("path").asText("")
                    + " size=1024 mtime=2026-09-29";
            case "search_files" -> marker + " query=" + args.path("query").asText("")
                    + " hits=README.md";
            case "get_disk_usage" -> marker + " path=" + args.path("path").asText("/")
                    + " used=42G free=80G";
            case "list_alerts" -> marker + " alerts=al-1 warn disk; al-2 info deploy";
            case "get_alert" -> marker + " id=" + args.path("alert_id").asText("al-1")
                    + " severity=warn title=\"disk 80%\"";
            default -> marker + " ok";
        };
    }

    private static ToolSpec spec(String name, String desc, String schema) {
        return new ToolSpec(name, desc, schema);
    }

    private static String obj(String[]... propsAndReq) {
        // last array if named __required is handled by req(); here props only
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < propsAndReq.length; i++) {
            if (i > 0) sb.append(',');
            String[] p = propsAndReq[i];
            sb.append('"').append(p[0]).append("\":{\"type\":\"").append(p[1])
                    .append("\",\"description\":\"").append(p[2].replace("\"", "\\\"")).append("\"}");
        }
        sb.append('}');
        return sb.toString();
    }

    private static String obj(String propertiesJson, String requiredJson) {
        if (requiredJson == null || requiredJson.isBlank()) {
            return propertiesJson + "}";
        }
        return propertiesJson + ",\"required\":" + requiredJson + "}";
    }

    private static String[] p(String name, String type, String desc) {
        return new String[]{name, type, desc};
    }

    private static String req(String... names) {
        if (names == null || names.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(names[i]).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static ToolSpec spec(String name, String desc, String propertiesJson, String requiredJson) {
        return new ToolSpec(name, desc, obj(propertiesJson, requiredJson));
    }

    private static ObjectNode toolNode(ToolSpec t) throws Exception {
        ObjectNode n = M.createObjectNode();
        n.put("name", t.name);
        n.put("description", t.description);
        n.set("inputSchema", M.readTree(t.schemaJson));
        return n;
    }

    private static ObjectNode text(String s) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "text");
        n.put("text", s);
        return n;
    }

    private static void reply(PrintWriter out, JsonNode id, JsonNode result) {
        ObjectNode msg = M.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result);
        try {
            out.println(M.writeValueAsString(msg));
        } catch (Exception e) {
            out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32603,\"message\":\"encode\"}}");
        }
        out.flush();
    }
}
