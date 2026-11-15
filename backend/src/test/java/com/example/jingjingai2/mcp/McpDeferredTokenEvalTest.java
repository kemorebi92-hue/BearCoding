package com.example.jingjingai2.mcp;

import com.example.jingjingai2.mcp.testdouble.FatMcpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 30 个真实评测桩 schema 的 token 估算（不打模型）。
 * 写入 docs/eval/mcp-deferred-schema-tokens.md，给基线报告引用。
 */
class McpDeferredTokenEvalTest {

    private static final double CHARS_PER_TOKEN = 1.5;

    @Test
    @DisplayName("估算：MCP 关 / 2 工具 / 30 个 FatMcp 工具的 schema 体积")
    void writeSchemaTable() throws Exception {
        List<ToolCallback> nativeCbs = nativeTools();
        List<ToolCallback> two = concat(nativeCbs, fakeEcho());
        List<ToolCallback> fat = concat(nativeCbs, fatMcp());

        String nativeJson = toolsJson(nativeCbs);
        String twoJson = toolsJson(two);
        String fatJson = toolsJson(fat);

        int nativeChars = nativeJson.length();
        int twoChars = twoJson.length();
        int fatChars = fatJson.length();
        long nativeTok = Math.round(nativeChars / CHARS_PER_TOKEN);
        long twoTok = Math.round(twoChars / CHARS_PER_TOKEN);
        long fatTok = Math.round(fatChars / CHARS_PER_TOKEN);

        String md = """
                口径：把工具定义序列化成 JSON 后按「字符数 ÷ 1.5」估算 token，与 ChatController 一致，**不是**服务商计费。
                原生工具用 5 个 stub（readFile / writeFile / executeCommand / createPlan / spawnSubagent）。
                MCP 侧用 `FatMcpServer` 的真实 description + schema（tracker 15 + ops 15）。

                | 条件 | 工具数 | schema 字符 | 估算 token | 相对「MCP 关」 |
                |---|---:|---:|---:|---:|
                | MCP 关 | %d | %d | %d | 0 |
                | 2 个 echo 工具 | %d | %d | %d | +%d |
                | 30 个 FatMcp 工具 | %d | %d | %d | +%d |

                `app.context.max-tokens=8000` 只约束**历史压缩**，不硬截本轮工具 schema。
                30 个真实 schema 大约再吃 %d token 的工具定义（比 Phase 1 的 20 个中等 stub 更肥）。
                在线对话会不会被服务商 context 打爆，见 `mcp-deferred-baseline.md`。
                """.formatted(
                nativeCbs.size(), nativeChars, nativeTok,
                two.size(), twoChars, twoTok, twoTok - nativeTok,
                fat.size(), fatChars, fatTok, fatTok - nativeTok,
                fatTok - nativeTok);

        Path dir = Path.of("docs", "eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mcp-deferred-schema-tokens.md"), md, StandardCharsets.UTF_8);
        assertEquals(5 + 30, fat.size());
        assertTrue(fatTok > twoTok);
        assertTrue(twoTok > nativeTok);
    }

    private static List<ToolCallback> nativeTools() {
        return List.of(
                named("readFile", "read a file", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
                named("writeFile", "write a file", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}"),
                named("executeCommand", "run a command", "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}"),
                named("createPlan", "create a plan", "{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"string\"}}}"),
                named("spawnSubagent", "spawn a subagent", "{\"type\":\"object\",\"properties\":{\"goal\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"}}}")
        );
    }

    private static List<ToolCallback> fakeEcho() {
        String schema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
        return List.of(
                named("mcp__echo__echo", "Echo text back", schema),
                named("mcp__echo__add", "Add two integers",
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"]}")
        );
    }

    private static List<ToolCallback> fatMcp() {
        List<ToolCallback> out = new ArrayList<>();
        for (FatMcpServer.ToolSpec t : FatMcpServer.tracker()) {
            out.add(named("mcp__tracker__" + t.name, t.description, t.schemaJson));
        }
        for (FatMcpServer.ToolSpec t : FatMcpServer.ops()) {
            out.add(named("mcp__ops__" + t.name, t.description, t.schemaJson));
        }
        return out;
    }

    private static List<ToolCallback> concat(List<ToolCallback> a, List<ToolCallback> b) {
        List<ToolCallback> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String toolsJson(List<ToolCallback> cbs) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < cbs.size(); i++) {
            if (i > 0) sb.append(',');
            ToolDefinition d = cbs.get(i).getToolDefinition();
            sb.append("{\"name\":\"").append(d.name())
                    .append("\",\"description\":\"").append(d.description().replace("\"", "\\\""))
                    .append("\",\"parameters\":").append(d.inputSchema()).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static ToolCallback named(String name, String description, String schema) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return ""; }
        };
    }
}
