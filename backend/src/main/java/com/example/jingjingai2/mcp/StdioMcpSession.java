package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个 MCP Server 子进程 + 一行一条 JSON-RPC（stdio transport，规范 2024-11-05）。
 *
 * <p>stdout 只走协议；stderr 另线程排空，避免子进程把 pipe 写满后卡死。
 * 环境变量 = 最小 PATH（Windows 再加 SystemRoot / PATHEXT / ComSpec / TEMP）+ spec.env，
 * <strong>不</strong>继承父进程全量 env。
 */
public final class StdioMcpSession implements McpSession {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final Process process;
    private final BufferedWriter stdin;
    private final long requestTimeoutMs;
    private final int maxResultChars;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final StringBuilder stderrTail = new StringBuilder();
    private volatile List<McpRemoteTool> tools = List.of();
    /** initialize 的 result（含 protocolVersion / capabilities / serverInfo），探测与日志用。 */
    private volatile JsonNode initializeResult = MAPPER.createObjectNode();
    /** tools/list 的原始 result，消毒前；探测用。 */
    private volatile JsonNode rawToolsList = MAPPER.createObjectNode();

    private StdioMcpSession(String serverName, Process process, McpProperties props, McpServerSpec spec)
            throws IOException {
        this.serverName = serverName;
        this.process = process;
        this.requestTimeoutMs = props == null ? 30_000L : props.getRequestTimeoutMs();
        this.maxResultChars = spec == null
                ? (props == null ? 8000 : props.getMaxResultChars())
                : spec.effectiveMaxResultChars(props);
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread out = new Thread(this::readStdout, "mcp-stdout-" + serverName);
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(this::drainStderr, "mcp-stderr-" + serverName);
        err.setDaemon(true);
        err.start();
        process.onExit().thenRun(this::onProcessExit);
    }

    public static StdioMcpSession start(String serverName, McpServerSpec spec, McpProperties props) {
        if (spec == null || spec.getCommand() == null || spec.getCommand().isBlank()) {
            throw new McpException("MCP server '" + serverName + "' 未配置 command");
        }
        List<String> cmd = buildCommand(spec);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        applyMinimalEnv(pb.environment(), spec.getEnv());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new McpException("启动 MCP server '" + serverName + "' 失败: " + e.getMessage(), e);
        }
        StdioMcpSession session;
        try {
            session = new StdioMcpSession(serverName, process, props, spec);
        } catch (IOException e) {
            destroyTree(process, 1_000L);
            throw new McpException("打开 MCP server '" + serverName + "' 管道失败: " + e.getMessage(), e);
        }
        try {
            session.handshake(props, spec);
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    private void handshake(McpProperties props, McpServerSpec spec) {
        long initTimeout = spec == null
                ? (props == null ? 15_000L : props.getInitializeTimeoutMs())
                : spec.initializeTimeoutMs(props);
        String protocol = props == null || props.getProtocolVersion() == null
                ? "2024-11-05" : props.getProtocolVersion();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", protocol);
        params.set("capabilities", MAPPER.createObjectNode()); // 不声明 sampling / elicitation
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "jingjingAI2");
        info.put("version", "0.1.0");
        params.set("clientInfo", info);
        JsonNode init = request("initialize", params, initTimeout);
        this.initializeResult = init == null ? MAPPER.createObjectNode() : init;
        String negotiated = initializeResult.path("protocolVersion").asText(protocol);
        JsonNode caps = initializeResult.get("capabilities");
        System.out.println("[MCP] server=" + serverName + " 协议 " + negotiated
                + " capabilities=" + (caps == null ? "{}" : caps));
        notify("notifications/initialized", null);
        JsonNode listed = request("tools/list", MAPPER.createObjectNode(), requestTimeoutMs);
        this.rawToolsList = listed == null ? MAPPER.createObjectNode() : listed;
        this.tools = parseTools(listed);
        System.out.println("[MCP] server=" + serverName + " 已连接，工具 " + tools.size() + " 个");
    }

    /** initialize 的 result。测试/探测用。 */
    JsonNode initializeResult() {
        return initializeResult;
    }

    /** tools/list 原始 result（未消毒）。测试/探测用。 */
    JsonNode rawToolsList() {
        return rawToolsList;
    }

    long processPid() {
        try {
            return process.pid();
        } catch (Exception e) {
            return -1L;
        }
    }

    private List<McpRemoteTool> parseTools(JsonNode listed) {
        List<McpRemoteTool> out = new ArrayList<>();
        if (listed == null) return out;
        JsonNode arr = listed.get("tools");
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode t : arr) {
            if (t == null || !t.isObject()) continue;
            String name = t.path("name").asText("");
            if (name.isBlank()) continue;
            String desc = t.path("description").asText("");
            JsonNode schema = t.get("inputSchema");
            if (schema == null) schema = t.get("input_schema");
            String schemaJson;
            try {
                schemaJson = schema == null || schema.isNull()
                        ? McpSchemaSanitizer.FALLBACK
                        : MAPPER.writeValueAsString(schema);
            } catch (Exception e) {
                schemaJson = McpSchemaSanitizer.FALLBACK;
            }
            out.add(new McpRemoteTool(name, desc, McpSchemaSanitizer.sanitize(schemaJson),
                    McpToolAnnotations.from(t.get("annotations"))));
        }
        return List.copyOf(out);
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public List<McpRemoteTool> listTools() {
        return tools;
    }

    @Override
    public boolean isAlive() {
        return !closed.get() && process != null && process.isAlive();
    }

    @Override
    public McpCallResult callTool(String originalName, String argumentsJson) {
        McpCallResult raw = callToolRaw(originalName, argumentsJson);
        // 截断只发生在返回给模型的这一层；落盘由调用方决定。
        // 保留 delivery：isError 的完成调用不能被收成 UNKNOWN。
        String text = McpContentMapper.truncate(raw.text(), maxResultChars);
        return new McpCallResult(raw.isError(), text, raw.delivery());
    }

    /** 不截断，给超限落盘用。 */
    @Override
    public McpCallResult callToolRaw(String originalName, String argumentsJson) {
        if (closed.get()) return McpCallResult.notSent("MCP 会话已关闭");
        if (!process.isAlive()) return McpCallResult.notSent("MCP 进程已退出" + stderrHint());
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", originalName);
        params.set("arguments", parseArgs(argumentsJson));
        try {
            JsonNode result = request("tools/call", params, requestTimeoutMs);
            boolean isError = result != null && result.path("isError").asBoolean(false);
            String text = McpContentMapper.toFullText(result);
            if (isError) return McpCallResult.failedAtTool(text.isBlank() ? "isError=true" : text);
            return McpCallResult.ok(text);
        } catch (McpException e) {
            // 写入抛 IOException 时字节可能已经进了管道，证明不了「一个字节都没出去」，
            // 所以和超时、stdout 提前结束一样按结果未知处理，不自动重发。
            return McpCallResult.fail(e.getMessage());
        } catch (Exception e) {
            String m = e.getMessage();
            return McpCallResult.fail(m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
        }
    }

    private JsonNode parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return MAPPER.createObjectNode();
        try {
            JsonNode n = MAPPER.readTree(argumentsJson);
            return n != null && n.isObject() ? n : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private JsonNode request(String method, JsonNode params, long timeoutMs) {
        int id = nextId.getAndIncrement();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        write(msg);
        JsonNode resp;
        try {
            resp = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            // 卡住的进程不要继续往里塞请求：整棵进程树关掉，交给 manager 重连。
            close();
            throw new McpException("等待 " + method + " 超时 (" + timeoutMs + "ms)" + stderrHint());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new McpException("等待 " + method + " 被中断");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            String m = c.getMessage();
            throw new McpException(m == null || m.isBlank() ? c.getClass().getSimpleName() : m, c);
        }
        if (resp != null && resp.has("error")) {
            JsonNode err = resp.get("error");
            String m = err.path("message").asText("JSON-RPC error");
            int code = err.path("code").asInt(0);
            throw new McpException("JSON-RPC " + code + ": " + m);
        }
        return resp == null ? MAPPER.createObjectNode() : resp.get("result");
    }

    private void notify(String method, JsonNode params) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        write(msg);
    }

    private synchronized void write(JsonNode msg) {
        if (closed.get()) throw new McpException("MCP 会话已关闭");
        try {
            stdin.write(MAPPER.writeValueAsString(msg));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new McpException("写入 MCP stdin 失败: " + e.getMessage() + stderrHint(), e);
        }
    }

    private void readStdout() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg;
                try {
                    msg = MAPPER.readTree(line);
                } catch (Exception e) {
                    failAll("MCP 返回了无法解析的 JSON: " + clip(line, 200));
                    break;
                }
                if (msg == null || !msg.has("id") || msg.get("id").isNull()) {
                    continue; // 通知，第一期忽略
                }
                int id = msg.get("id").asInt(-1);
                CompletableFuture<JsonNode> f = pending.remove(id);
                if (f != null) f.complete(msg);
            }
        } catch (IOException e) {
            if (!closed.get()) failAll("MCP stdout 关闭: " + e.getMessage());
        } finally {
            if (!closed.get()) failAll("MCP 进程 stdout 结束" + stderrHint());
        }
    }

    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrTail) {
                    if (stderrTail.length() > 4000) {
                        stderrTail.delete(0, stderrTail.length() - 2000);
                    }
                    if (stderrTail.length() > 0) stderrTail.append('\n');
                    stderrTail.append(line);
                }
            }
        } catch (IOException ignored) {
            // 进程退出时 stream 关闭是正常的
        }
    }

    private void onProcessExit() {
        if (closed.get()) return;
        failAll("MCP 进程已退出" + stderrHint());
    }

    private void failAll(String reason) {
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.completeExceptionally(new McpException(reason));
        }
        pending.clear();
    }

    private String stderrHint() {
        String s;
        synchronized (stderrTail) {
            s = stderrTail.toString();
        }
        if (s.isBlank()) return "";
        return "；stderr: " + clip(s, 400);
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        failAll("MCP 会话已关闭");
        try { stdin.close(); } catch (Exception ignored) {}
        destroyTree(process, 3_000L);
    }

    /**
     * 先对子孙 + 自己 graceful destroy，约 3s 后仍活着的再 destroyForcibly。
     * npx → node → browser 这种树必须整棵拔掉，否则会留下 node/msedge。
     */
    static void destroyTree(Process process, long graceMs) {
        if (process == null) return;
        ProcessHandle handle;
        try {
            handle = process.toHandle();
        } catch (Exception e) {
            try { process.destroyForcibly(); } catch (Exception ignored) {}
            return;
        }
        List<ProcessHandle> first = new ArrayList<>();
        try {
            handle.descendants().forEach(first::add);
        } catch (Exception ignored) {}
        for (ProcessHandle h : first) {
            try { h.destroy(); } catch (Exception ignored) {}
        }
        try { handle.destroy(); } catch (Exception ignored) {}
        try {
            process.waitFor(Math.max(0L, graceMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<ProcessHandle> leftover = new ArrayList<>(first);
        try {
            handle.descendants().forEach(leftover::add);
        } catch (Exception ignored) {}
        leftover.add(handle);
        for (ProcessHandle h : leftover) {
            try {
                if (h.isAlive()) h.destroyForcibly();
            } catch (Exception ignored) {}
        }
        if (process.isAlive()) {
            try { process.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    // ==================== 启动辅助（单测可直接调） ====================

    static List<String> buildCommand(McpServerSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveCommand(spec.getCommand()));
        if (spec.getArgs() != null) cmd.addAll(spec.getArgs());
        return cmd;
    }

    /**
     * Windows 上无扩展名的命令（npx / npm）按 PATHEXT 在 PATH 里找 .cmd / .exe。
     * 已经是路径或带点的原样返回。不用 {@code cmd /c} 拼字符串。
     */
    static String resolveCommand(String command) {
        return resolveCommand(command, System.getenv("PATH"), System.getenv("PATHEXT"), isWindows());
    }

    static String resolveCommand(String command, String path, String pathext, boolean windows) {
        if (command == null || command.isBlank()) return command;
        if (!windows) return command;
        if (command.contains("/") || command.contains("\\") || command.contains(".")) return command;
        String extList = (pathext == null || pathext.isBlank()) ? ".COM;.EXE;.BAT;.CMD" : pathext;
        String[] exts = extList.split(";");
        if (path == null) path = "";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String ext : exts) {
                if (ext == null || ext.isBlank()) continue;
                File f = new File(dir, command + ext);
                if (f.isFile()) return existingPath(f);
            }
            File bare = new File(dir, command);
            if (bare.isFile()) return existingPath(bare);
        }
        return command;
    }

    /**
     * Windows 大小写不敏感：PATHEXT 是 {@code .CMD}，磁盘上常是 {@code .cmd}。
     * {@code getCanonicalPath()} 仍可能保留构造时的大小写，所以对着父目录真实文件名匹配。
     */
    private static String existingPath(File f) {
        File parent = f.getParentFile();
        String want = f.getName();
        if (parent != null) {
            String[] names = parent.list();
            if (names != null) {
                for (String n : names) {
                    if (n != null && n.equalsIgnoreCase(want)) {
                        return new File(parent, n).getAbsolutePath();
                    }
                }
            }
        }
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 清空继承的 env，只放行跑子进程必需的那几项，再叠 spec.env。
     */
    static void applyMinimalEnv(Map<String, String> env, Map<String, String> extra) {
        Map<String, String> keep = new LinkedHashMap<>();
        copyEnv(keep, "PATH");
        copyEnv(keep, "Path");
        if (isWindows()) {
            copyEnv(keep, "PATHEXT");
            copyEnv(keep, "SystemRoot");
            copyEnv(keep, "SYSTEMROOT");
            copyEnv(keep, "WINDIR");
            copyEnv(keep, "ComSpec");
            copyEnv(keep, "COMSPEC");
            copyEnv(keep, "TEMP");
            copyEnv(keep, "TMP");
            copyEnv(keep, "SystemDrive");
            copyEnv(keep, "SYSTEMDRIVE");
        } else {
            copyEnv(keep, "HOME");
            copyEnv(keep, "TMPDIR");
            copyEnv(keep, "LANG");
            copyEnv(keep, "LC_ALL");
        }
        env.clear();
        env.putAll(keep);
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) env.put(e.getKey(), e.getValue());
            }
        }
    }

    private static void copyEnv(Map<String, String> into, String key) {
        String v = System.getenv(key);
        if (v != null) into.put(key, v);
    }
}
