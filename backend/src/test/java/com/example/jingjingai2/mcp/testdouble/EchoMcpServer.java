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

/**
 * 测试用 MCP Server：stdio 上一行一条 JSON-RPC。
 *
 * <p>工具：{@code echo}（回显 text）、{@code add}（a+b）。
 * 模式（第一个参数）：
 * <ul>
 *   <li>（默认）正常握手 + 调工具</li>
 *   <li>{@code --hang-on-call} 收到 tools/call 后不再回包</li>
 *   <li>{@code --crash-after-init} 收到 notifications/initialized 后立刻退出</li>
 *   <li>{@code --malformed} tools/call 回一行非 JSON</li>
 *   <li>{@code --spawn-child} initialize 时再拉一个长期存活的子孙进程</li>
 * </ul>
 */
public final class EchoMcpServer {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String mode = args != null && args.length > 0 ? args[0] : "";
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

            if ("notifications/initialized".equals(method)) {
                if ("--crash-after-init".equals(mode)) {
                    System.exit(1);
                }
                continue;
            }
            if (!hasId) continue;

            if ("initialize".equals(method)) {
                if ("--spawn-child".equals(mode)) {
                    spawnChild();
                }
                ObjectNode result = M.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                result.set("capabilities", M.createObjectNode().set("tools", M.createObjectNode()));
                ObjectNode info = M.createObjectNode();
                info.put("name", "echo-test");
                info.put("version", "1.0.0");
                result.set("serverInfo", info);
                reply(out, id, result);
                continue;
            }
            if ("tools/list".equals(method)) {
                ObjectNode result = M.createObjectNode();
                ArrayNode tools = M.createArrayNode();
                tools.add(tool("echo", "Echo text back",
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
                tools.add(tool("add", "Add two integers",
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"]}"));
                result.set("tools", tools);
                reply(out, id, result);
                continue;
            }
            if ("tools/call".equals(method)) {
                if ("--hang-on-call".equals(mode)) {
                    Thread.sleep(3_600_000L);
                    continue;
                }
                if ("--malformed".equals(mode)) {
                    out.println("this is not json {{{");
                    out.flush();
                    continue;
                }
                JsonNode params = msg.path("params");
                String name = params.path("name").asText("");
                JsonNode arguments = params.path("arguments");
                ObjectNode result = M.createObjectNode();
                ArrayNode content = M.createArrayNode();
                if ("echo".equals(name)) {
                    content.add(text(arguments.path("text").asText("")));
                    result.put("isError", false);
                } else if ("add".equals(name)) {
                    int a = arguments.path("a").asInt(0);
                    int b = arguments.path("b").asInt(0);
                    content.add(text(Integer.toString(a + b)));
                    result.put("isError", false);
                } else {
                    content.add(text("unknown tool: " + name));
                    result.put("isError", true);
                }
                result.set("content", content);
                reply(out, id, result);
            }
        }
    }

    private static volatile Process spawnedChild;

    /** 再拉一个长期存活的子孙，用来测进程树 close。 */
    private static void spawnChild() {
        try {
            java.io.File javaBin = new java.io.File(System.getProperty("java.home"),
                    "bin" + java.io.File.separator + "java");
            String javaExe = javaBin.getPath();
            if (java.io.File.separatorChar == '\\' && !javaBin.isFile()) {
                javaExe = javaExe + ".exe";
            }
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-cp",
                    System.getProperty("java.class.path"),
                    "com.example.jingjingai2.mcp.testdouble.ChildSleeper");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            spawnedChild = pb.start();
        } catch (Exception e) {
            System.err.println("spawn-child failed: " + e.getMessage());
        }
    }

    private static ObjectNode tool(String name, String desc, String schemaJson) throws Exception {
        ObjectNode t = M.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        t.set("inputSchema", M.readTree(schemaJson));
        return t;
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
