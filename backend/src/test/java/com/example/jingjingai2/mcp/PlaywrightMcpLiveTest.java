package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 真实 {@code @playwright/mcp} 协议探测。默认跳过；
 * {@code MCP_LIVE_PLAYWRIGHT=true}（环境变量或系统属性）才跑。
 *
 * <p>不进主循环，只用 {@link StdioMcpSession}。产物写入
 * {@code docs/eval/playwright-mcp-tools.md}。
 */
class PlaywrightMcpLiveTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path TOOLS_MD = Path.of("docs", "eval", "playwright-mcp-tools.md");
    private static final Path OUT_DIR = Path.of("target", "playwright-mcp-output");

    static boolean liveEnabled() {
        return flag("MCP_LIVE_PLAYWRIGHT");
    }

    private static boolean flag(String name) {
        String p = System.getProperty(name);
        if (p != null && !p.isBlank()) return Boolean.parseBoolean(p.trim());
        String e = System.getenv(name);
        return e != null && Boolean.parseBoolean(e.trim());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("Playwright MCP：handshake + tools/list + navigate/snapshot + 无残留进程")
    void probeProtocolNavigateSnapshotAndCleanup() throws Exception {
        Assumptions.assumeTrue(liveEnabled(),
                "跳过：设置 MCP_LIVE_PLAYWRIGHT=true（环境变量或系统属性）才跑真实 Playwright");
        Files.createDirectories(OUT_DIR);
        Files.createDirectories(TOOLS_MD.getParent());
        String browser = detectBrowser();
        McpServerSpec spec = playwrightSpec(browser);
        McpProperties props = new McpProperties();
        props.setInitializeTimeoutMs(60_000);
        props.setRequestTimeoutMs(60_000);
        props.setMaxResultChars(8000);

        StringBuilder md = new StringBuilder();
        md.append("# Playwright MCP tools/list 探测\n\n");
        md.append("- 时间：").append(Instant.now()).append('\n');
        md.append("- 包：`@playwright/mcp@latest`（help 采集时 0.0.83）\n");
        md.append("- 浏览器渠道：`").append(browser).append("`\n");
        md.append("- 命令：`npx -y @playwright/mcp@latest --headless --isolated --browser ")
                .append(browser).append(" --output-dir ").append(OUT_DIR.toString().replace('\\', '/'))
                .append("`\n");
        md.append("- 未传 `--caps`：0.0.83 的合法值是 vision/pdf/devtools，不是 tabs,wait\n");
        md.append("- spec.env 含 ProgramFiles / USERPROFILE 等（最小 PATH 不够找 msedge）\n\n");

        List<Long> leftover = List.of();
        try (StdioMcpSession session = StdioMcpSession.start("playwright", spec, props)) {
            JsonNode init = session.initializeResult();
            String protocol = init.path("protocolVersion").asText("");
            JsonNode caps = init.get("capabilities");
            JsonNode info = init.get("serverInfo");
            md.append("## initialize\n\n");
            md.append("- protocolVersion：`").append(protocol).append("`\n");
            md.append("- capabilities：`").append(caps == null ? "{}" : caps.toString()).append("`\n");
            md.append("- serverInfo：`").append(info == null ? "{}" : info.toString()).append("`\n\n");

            org.junit.jupiter.api.Assertions.assertEquals("2024-11-05", protocol,
                    "本期只谈 2024-11-05，server 若不接受应在报告里写明");

            JsonNode rawList = session.rawToolsList();
            JsonNode arr = rawList.get("tools");
            Map<String, JsonNode> rawByName = new LinkedHashMap<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode t : arr) {
                    String n = t.path("name").asText("");
                    if (!n.isBlank()) rawByName.put(n, t);
                }
            }

            md.append("## tools/list（").append(rawByName.size()).append("）\n\n");
            md.append("| 工具 | 描述字符 | 原始 schema 字符 | 清洗后 schema 字符 | 清洗后是否空 object | 备注 |\n");
            md.append("|---|---:|---:|---:|---|---|\n");

            List<McpRemoteTool> tools = session.listTools();
            org.junit.jupiter.api.Assertions.assertFalse(tools.isEmpty(), "tools/list 不应为空");
            int emptied = 0;
            for (McpRemoteTool tool : tools) {
                JsonNode rawTool = rawByName.get(tool.originalName());
                String rawSchema = "";
                if (rawTool != null) {
                    JsonNode schema = rawTool.get("inputSchema");
                    if (schema == null) schema = rawTool.get("input_schema");
                    rawSchema = schema == null ? "" : schema.toString();
                }
                String sanitized = tool.inputSchemaJson();
                boolean emptyObj = isEmptyObjectSchema(sanitized);
                boolean hadStructure = rawSchema.contains("\"properties\"")
                        || rawSchema.contains("anyOf")
                        || rawSchema.contains("oneOf")
                        || rawSchema.contains("required");
                String note;
                if (emptyObj && hadStructure) {
                    emptied++;
                    note = "**剥空**（原始有字段）";
                } else {
                    List<String> bits = new ArrayList<>();
                    if (rawSchema.contains("$schema")) bits.add("剥 $schema");
                    if (rawSchema.contains("anyOf")) bits.add("剥 anyOf");
                    if (rawSchema.contains("oneOf")) bits.add("剥 oneOf");
                    if (rawSchema.contains("$ref")) bits.add("剥 $ref");
                    note = bits.isEmpty() ? "—" : String.join("；", bits);
                }
                md.append("| `").append(tool.originalName()).append("` | ")
                        .append(tool.description() == null ? 0 : tool.description().length()).append(" | ")
                        .append(rawSchema.length()).append(" | ")
                        .append(sanitized == null ? 0 : sanitized.length()).append(" | ")
                        .append(emptyObj ? "是" : "否").append(" | ")
                        .append(note).append(" |\n");
            }
            md.append("\n剥空（原始有结构但清洗后只剩 `{type:object}`）：**")
                    .append(emptied).append("** 个。\n\n");

            md.append("### 清洗后 schema 摘录\n\n");
            for (String name : List.of("browser_navigate", "browser_snapshot", "browser_click",
                    "browser_type", "browser_emulate_media")) {
                tools.stream().filter(t -> name.equals(t.originalName())).findFirst().ifPresent(t -> {
                    md.append("#### `").append(name).append("`\n\n```json\n")
                            .append(prettyOrRaw(t.inputSchemaJson())).append("\n```\n\n");
                });
            }

            CallProbe nav = call(session, "browser_navigate", "{\"url\":\"https://example.com\"}");
            md.append("## browser_navigate https://example.com\n\n");
            appendCall(md, nav);
            org.junit.jupiter.api.Assertions.assertFalse(nav.error, nav.text);
            org.junit.jupiter.api.Assertions.assertFalse(nav.text.isBlank(), "navigate 应有文本");

            CallProbe snap = call(session, "browser_snapshot", "{}");
            md.append("## browser_snapshot\n\n");
            appendCall(md, snap);
            org.junit.jupiter.api.Assertions.assertFalse(snap.error, snap.text);
            org.junit.jupiter.api.Assertions.assertTrue(snap.chars > 0, "snapshot 应有内容");
            boolean hasRef = snap.text.contains("[ref=") || snap.text.contains("ref=");
            md.append("- 含 `[ref=…]`：").append(hasRef ? "是" : "否").append('\n');
            md.append("- 含图片占位 `[binary content omitted]`：")
                    .append(snap.text.contains(McpContentMapper.BINARY_PLACEHOLDER) ? "是" : "否").append('\n');
            md.append("- 含截断标记：")
                    .append(snap.text.contains("截断") ? "是" : "否").append('\n');
            md.append("- 相对 6000 字符上限：")
                    .append(snap.chars > 6000 ? "会截断" : "不会截断").append('\n');
            md.append("\n### snapshot 原文（前 800 字符）\n\n```\n");
            md.append(clip(snap.text, 800)).append("\n```\n\n");

            long pid = session.processPid();
            List<Long> tree = descendantPids(pid);
            md.append("## 进程树（关闭前）\n\n");
            md.append("- session pid：").append(pid).append('\n');
            md.append("- 子孙 pid：").append(tree).append('\n');
            for (long p : tree) {
                md.append("  - ").append(p).append(" ").append(commandOf(p)).append('\n');
            }
            md.append('\n');

            session.close();
            leftover = waitDead(tree, 5_000);
            md.append("## 关闭后残留\n\n");
            if (leftover.isEmpty()) {
                md.append("无残留（stdin.close + destroy 后子孙均退出）。\n");
            } else {
                md.append("**有残留**，需要销毁进程树：\n");
                for (long p : leftover) {
                    md.append("- ").append(p).append(" ").append(commandOf(p)).append('\n');
                }
            }
        }

        Files.writeString(TOOLS_MD, md.toString(), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(leftover.isEmpty(),
                "关闭后仍有残留进程 " + leftover + "，需要修 process-tree destroy");
    }

    static McpServerSpec playwrightSpec(String browser) throws Exception {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("npx");
        spec.setArgs(List.of(
                "-y", "@playwright/mcp@latest",
                "--headless", "--isolated",
                "--browser", browser,
                "--output-dir", OUT_DIR.toAbsolutePath().toString()));
        spec.setEnv(playwrightEnv());
        spec.setConnectTimeoutSeconds(60);
        spec.setMaxResultChars(6000);
        spec.setDescription("Playwright 浏览器（有状态，第一期 server 全用户共享，禁止多用户部署时开启）");
        return spec;
    }

    static Map<String, String> playwrightEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : new String[]{
                "USERPROFILE", "APPDATA", "LOCALAPPDATA",
                "HOMEDRIVE", "HOMEPATH", "USERNAME",
                "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"
        }) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) env.put(key, v);
        }
        return env;
    }

    static String detectBrowser() {
        for (String p : new String[]{
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
        }) {
            if (new File(p).isFile()) return "chrome";
        }
        for (String p : new String[]{
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
        }) {
            if (new File(p).isFile()) return "msedge";
        }
        throw new IllegalStateException("未找到 Chrome 或 Edge，无法跑 Playwright 探测");
    }

    private static CallProbe call(StdioMcpSession session, String name, String args) {
        long t0 = System.nanoTime();
        McpCallResult r = session.callTool(name, args);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        String text = r.text() == null ? "" : r.text();
        return new CallProbe(name, r.isError(), text, text.length(), ms);
    }

    private static void appendCall(StringBuilder md, CallProbe c) {
        md.append("- isError：").append(c.error).append('\n');
        md.append("- 耗时：").append(c.ms).append(" ms\n");
        md.append("- 字符数：").append(c.chars).append('\n');
        md.append("- 映射后类型：text（image/audio 会换成 `[binary content omitted]`）\n");
        md.append("- 前 240 字符：`").append(clip(c.text, 240).replace("\n", "\\n")).append("`\n\n");
    }

    private static boolean isEmptyObjectSchema(String json) {
        if (json == null) return true;
        String t = json.replaceAll("\\s+", "");
        return t.equals(McpSchemaSanitizer.FALLBACK) || t.equals("{\"type\":\"object\"}");
    }

    private static String prettyOrRaw(String json) {
        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(M.readTree(json));
        } catch (Exception e) {
            return json == null ? "" : json;
        }
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static List<Long> descendantPids(long pid) {
        if (pid <= 0) return List.of();
        return ProcessHandle.of(pid)
                .map(h -> h.descendants().map(ProcessHandle::pid).collect(Collectors.toList()))
                .orElse(List.of());
    }

    private static List<Long> waitDead(List<Long> pids, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<Long> alive = new ArrayList<>(pids);
        while (System.currentTimeMillis() < deadline) {
            alive = alive.stream().filter(PlaywrightMcpLiveTest::alive).collect(Collectors.toList());
            if (alive.isEmpty()) return List.of();
            Thread.sleep(200);
        }
        return alive.stream().filter(PlaywrightMcpLiveTest::alive).collect(Collectors.toList());
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String commandOf(long pid) {
        return ProcessHandle.of(pid)
                .flatMap(h -> h.info().command())
                .orElse("?");
    }

    private record CallProbe(String name, boolean error, String text, int chars, long ms) {}
}
