package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具 schema 的 token 成本（本地估算，不打模型）。
 * 写入 docs/eval/mcp-phase1-token-eval.md，给报告用。
 */
class McpTokenEvalTest {

    private static final double CHARS_PER_TOKEN = 1.5;

    @Test
    @DisplayName("估算：MCP 关 / 2 个工具 / 20 个工具的 schema 体积")
    void writeEvalTable() throws Exception {
        String nativeJson = toolsJson(nativeTools());
        String two = toolsJson(concat(nativeTools(), fakeMcp(2)));
        String many = toolsJson(concat(nativeTools(), fakeMcp(20)));

        int nativeChars = nativeJson.length();
        int twoChars = two.length();
        int manyChars = many.length();
        long nativeTok = Math.round(nativeChars / CHARS_PER_TOKEN);
        long twoTok = Math.round(twoChars / CHARS_PER_TOKEN);
        long manyTok = Math.round(manyChars / CHARS_PER_TOKEN);

        String md = """
                # MCP Phase 1 token 成本（工具 schema）

                口径：把本轮下发的工具定义序列化成 JSON 后按「字符数 ÷ 1.5」估算 token，
                与 ChatController 的估算一致，**不是**服务商计费值。
                原生工具用一组固定 stub（readFile / writeFile / executeCommand / createPlan / spawnSubagent）；
                MCP 工具每条带一段中等 schema（3 个 string 字段）。

                | 条件 | 工具数 | schema 字符 | 估算 token | 相对「MCP 关」 |
                |---|---:|---:|---:|---:|
                | MCP 关 | %d | %d | %d | 0 |
                | 2 个 MCP 工具 | %d | %d | %d | +%d |
                | 20 个 MCP 工具 | %d | %d | %d | +%d |

                `app.context.max-tokens=8000`。20 个 MCP 工具大约再吃 %d token 的工具定义，
                会明显挤占对话预算。默认暴露 server 发现的所有工具（`enabled-tools` 已废弃，不再收窄），
                要收敛就用 `disabled-tools` 排除个别工具，或靠 `tool-loading: deferred` 只下发激活过的工具。

                3 问 × 3 次的在线评测见同目录 `mcp-phase1-live-eval.md`（无密钥或服务不可达时会标明跳过）。
                """.formatted(
                nativeTools().size(), nativeChars, nativeTok,
                nativeTools().size() + 2, twoChars, twoTok, twoTok - nativeTok,
                nativeTools().size() + 20, manyChars, manyTok, manyTok - nativeTok,
                manyTok - nativeTok);

        Path dir = Path.of("docs", "eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mcp-phase1-token-eval.md"), md, StandardCharsets.UTF_8);
        assertTrue(manyTok > twoTok);
        assertTrue(twoTok > nativeTok);
    }

    private static List<ToolCallback> nativeTools() {
        return List.of(
                named("readFile", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
                named("writeFile", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}"),
                named("executeCommand", "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}"),
                named("createPlan", "{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"string\"}}}"),
                named("spawnSubagent", "{\"type\":\"object\",\"properties\":{\"goal\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"}}}")
        );
    }

    private static List<ToolCallback> fakeMcp(int n) {
        List<ToolCallback> out = new ArrayList<>();
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"},\"c\":{\"type\":\"string\"}},"
                + "\"required\":[\"a\"]}";
        for (int i = 0; i < n; i++) {
            out.add(named("mcp__demo__tool_" + i, schema));
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

    private static ToolCallback named(String name, String schema) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("tool " + name)
                .inputSchema(schema)
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return ""; }
        };
    }
}
