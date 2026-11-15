package com.example.jingjingai2.utils;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import com.example.jingjingai2.sandbox.ApprovedCommandLauncher;
import com.example.jingjingai2.sandbox.CommandPathValidator;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

@Component
public class TerminalTool {
    static final int MAX_OUTPUT_CHARS = 6000;
    /** 进程原始输出的字节上限（解码前的保护，约 200KB 足够放 6000 字中文） */
    private static final int MAX_OUTPUT_BYTES = 200_000;
    private static final int EXECUTE_TIMEOUT_SECONDS = 90;

    private final PathSandbox sandbox;

    public TerminalTool(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * 真正启动进程的那一步。默认是 {@link ApprovedCommandLauncher#REAL}（与原来内联的
     * {@link ProcessBuilder} 完全等价）；测试替换成替身，用来断言"命令被沙箱拒绝时一个子进程都没创建"。
     * 校验顺序不变：先 {@link CommandPathValidator}，再交给它启动。
     */
    private ApprovedCommandLauncher commandLauncher = ApprovedCommandLauncher.REAL;

    /** 可选注入：没有该 bean 时退回旧的 6000 字截断，单测与无 Spring 场景零变化。 */
    private ToolOutputStore toolOutputStore;

    /** 可选注入：没有该 bean 时保持真实实现，生产零配置变化。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCommandLauncher(ApprovedCommandLauncher launcher) {
        if (launcher != null) this.commandLauncher = launcher;
    }

    /** 可选注入：超限输出落盘。没有时保持旧截断行为。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setToolOutputStore(ToolOutputStore store) {
        if (store != null) this.toolOutputStore = store;
    }

    @Tool(description = "在终端执行命令并返回输出结果。适用于：执行脚本、运行代码、编译项目、跑 git、查看系统信息等。"
            + "【沙箱规则，必须遵守】命令的工作目录被固定在工作区根目录，且："
            + "① 命令中不允许出现 cd / chdir / pushd / popd（想换目录？直接用工作区内的相对路径，例如 `dir src`、`java -cp target/classes com.demo.Application`）；"
            + "② 命令里出现的任何路径都必须在工作区内，引用工作区之外的路径（如 C:\\Windows\\win.ini、../..、D:\\other）会被整条拒绝。"
            + "Windows 示例：'dir /s /b src'、'java -version'、'javac -d out src/main/java/com/demo/App.java'。"
            + "注意：命令执行超时为90秒，输出超过6000字符会截断。执行完成后，结果会以 [TC]{\"tool\":\"executeCommand\",\"input\":\"...\",\"result\":\"...\",\"success\":true,\"elapsed\":0}[/TC] 格式嵌入。")
    public String executeCommand(@ToolParam(description = "要执行的终端命令，例如：java -version 或 dir src（不允许 cd 切换目录，路径必须都在工作区内）") String command) {
        long start = System.currentTimeMillis();
        // 沙箱守卫（白名单式）：禁止改目录 + 所有像路径的参数都必须落在工作区内；
        // 当前会话为 FULL_ACCESS 时只放开「工作区外路径参数」这一条，其余检查不变。
        CommandPathValidator.Decision decision =
                CommandPathValidator.validate(command, sandbox.root(), sandbox.allowsOutsideWorkspace());
        if (!decision.allowed()) {
            return decision.reason();
        }
        try {
            String[] cmd = isWindows() ? new String[]{"cmd.exe", "/c", command} : new String[]{"/bin/sh", "-c", command};
            // 沙箱：命令固定在工作区根目录内执行（配合上面的守卫，杜绝"切目录到工作区外"）
            Process process = commandLauncher.start(cmd, sandbox.root());
            // 先把字节读全（有上限），再智能解码 —— 见 decodeSmart 的说明
            byte[] raw = readLimited(process.getInputStream(), MAX_OUTPUT_BYTES);
            String decoded = decodeSmart(raw);
            StringBuilder sb = new StringBuilder(decoded);
            boolean finished = process.waitFor(EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String output = sb.toString().trim();
            if (!finished) { output += "\n[命令执行超时（90秒限制）]"; process.destroyForcibly(); }
            // 超过阈值才落盘；恰好等于阈值保持原来的「截断标记」行为。
            output = limitOutput(output, full -> spillOrTruncate(command, full));
            int exitCode = finished ? process.exitValue() : -1;
            long elapsed = System.currentTimeMillis() - start;
            // 成功路径也带真实 exitValue。拿不到（超时走 -1、异常走「执行出错」）就不写 exit=0。
            String result;
            if (exitCode == 0) {
                result = "执行成功 (exit=" + exitCode + "):\n" + output;
            } else {
                result = "执行失败 (exit=" + exitCode + "):\n"
                        + (output.isEmpty() ? "(无输出)" : output);
            }
            return formatToolMarker("executeCommand", command, result, exitCode == 0, elapsed);
        } catch (Exception e) {
            return "执行出错: " + e.getMessage();
        }
    }

    private String formatToolMarker(String toolName, String input, String result, boolean success, long elapsed) {
        String safeInput = input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n");
        String safeResult = result.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n");
        return "[TC]{\"tool\":\"" + toolName + "\",\"input\":\"" + safeInput + "\",\"result\":\"" + safeResult + "\",\"success\":" + success + ",\"elapsed\":" + elapsed + "}[/TC]";
    }

    /**
     * 输出长度决策。{@code >} 阈值才调用落盘；{@code ==} 阈值保持原来的截断标记行为；
     * {@code <} 阈值原样返回。抽成包内静态方法，便于离线测试三个边界，不必真的跑 6000 字命令。
     */
    static String limitOutput(String output, java.util.function.Function<String, String> spill) {
        String text = output == null ? "" : output;
        if (text.length() > MAX_OUTPUT_CHARS) {
            return spill == null ? legacyTruncate(text) : spill.apply(text);
        }
        if (text.length() == MAX_OUTPUT_CHARS) {
            return text + "\n[输出过长已截断]";
        }
        return text;
    }

    /** 超限落盘；没有 store 或落盘失败时退回旧截断。命令退出码与成功/失败语义不受影响。 */
    private String spillOrTruncate(String command, String fullOutput) {
        if (toolOutputStore == null) return legacyTruncate(fullOutput);
        try {
            ToolOutputStore.Stored stored = toolOutputStore.store("executeCommand", fullOutput);
            if (stored == null) return legacyTruncate(fullOutput);
            return previewWithNotice(fullOutput, stored.path().toString(), stored.totalChars(), stored.sha256());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("[Terminal] 输出落盘失败，退回截断: " + reason);
            return legacyTruncate(fullOutput) + "\n（完整内容保存失败：" + reason + "）";
        }
    }

    private static String legacyTruncate(String output) {
        String text = output == null ? "" : output;
        return text.length() <= MAX_OUTPUT_CHARS
                ? text : text.substring(0, MAX_OUTPUT_CHARS) + "\n[输出过长已截断]";
    }

    /** 头 70% + 说明 + 尾 30% + 系统签名引用，总长不超过 MAX_OUTPUT_CHARS。 */
    private static String previewWithNotice(String full, String path, int total, String sha256) {
        String text = full == null ? "" : full;
        String marker = ToolArtifactRef.marker(path, total, "executeCommand", sha256);
        int reserve = marker.isEmpty() ? 0 : marker.length() + 1;
        int budget = Math.max(0, MAX_OUTPUT_CHARS - reserve);
        String note = notice(0, 0, total, path);
        int head = 0;
        int tail = 0;
        for (int i = 0; i < 4; i++) {
            int room = Math.max(0, budget - note.length());
            if (room >= text.length()) {
                head = text.length();
                tail = 0;
            } else {
                head = (int) Math.round(room * 0.7);
                tail = room - head;
                if (head + tail > text.length()) {
                    head = text.length();
                    tail = 0;
                }
            }
            note = notice(head, tail, total, path);
        }
        if (head >= text.length() && marker.isEmpty()) return text;
        StringBuilder out = new StringBuilder(head + tail + note.length() + reserve);
        out.append(text, 0, head);
        out.append(note);
        if (tail > 0) out.append(text, text.length() - tail, text.length());
        if (!marker.isEmpty()) out.append('\n').append(marker);
        return out.toString();
    }

    private static String notice(int head, int tail, int total, String path) {
        return "\n【命令输出过长，完整内容已保存。总字符数 " + total
                + "；已显示前 " + head + " 个字符和末尾 " + tail + " 个字符。"
                + "需要中间的内容时，请用 readFile 按 offset/limit 分段读取"
                + "（offset 是起始行，从 1 开始）。"
                + "请原样使用下面这个完整路径（绝对路径），不要改写或使用相对路径：\n" + path + "】";
    }

    /**
     * 进程输出编码：**先试 UTF-8，失败再退回系统代码页（GBK）**。
     *
     * 为什么不能固定用一种：
     *  - cmd 内建命令（dir/echo 等）的输出是系统代码页（中文 Windows = GBK）；
     *  - 但 Java/Python 这类程序在现代 JDK 下默认按 UTF-8 输出（尤其带了
     *    -Dstdout.encoding=UTF-8 之后），用 GBK 解码就会得到"锟斤拷"；
     *  - 两种都可能出现，所以按"严格 UTF-8 解码能否成功"来判断：
     *    GBK 编码的中文字节几乎不可能是合法的 UTF-8 序列，所以这个判据很可靠。
     */
    static String decodeSmart(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(raw, legacyCharset());
        }
    }

    /** 系统代码页（中文 Windows = GBK），拿不到就用 JVM 默认 */
    private static java.nio.charset.Charset legacyCharset() {
        try {
            return java.nio.charset.Charset.forName("GBK");
        } catch (Exception e) {
            return java.nio.charset.Charset.defaultCharset();
        }
    }

    /** 读取进程输出（带上限，防止超大输出把内存吃满） */
    private static byte[] readLimited(java.io.InputStream in, int limit) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
            if (buf.size() >= limit) break;
        }
        return buf.toByteArray();
    }

    private boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
