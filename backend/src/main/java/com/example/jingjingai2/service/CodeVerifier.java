package com.example.jingjingai2.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 编译校验器：只对工作区里的 Java 源码跑 javac，把结果回灌给模型，
 * 形成「编辑 → 校验 → 修正」的自愈闭环（而不是改完就默认成功）。
 *
 * <p><strong>产品边界：仅 Java。</strong>不检测、不调用 Python/Node/Go/C++ 等其它工具链。
 * 非 Java 项目不会被本类自动验证。
 *
 * 设计取舍（都是有意为之）：
 *  1. **沿用本项目既有的"javac + 手拼 classpath"方式**（与 test.sh / run.sh 同款），不引入 Maven 等新构建依赖 ——
 *     本机 Maven 环境不可用，引入不可靠的构建工具只会让闭环跑不起来。
 *  2. **classpath 直接取当前 JVM 的 {@code java.class.path}**：本项目启动时已经把本地 m2 的全量 jar 拼好，
 *     所以工作区里的代码若依赖这些 jar 也能编过。**已知局限**：不会去解析工作区自带的 pom.xml/build.gradle，
 *     因此"工作区项目有自己独立的第三方依赖"时可能报找不到符号。
 *  3. **白名单源码根**：只编译 {@code src/main/java} 以及需要联编的 {@code src/test/java}，
 *     不对工作区做发现式全扫描。scripts/eval 夹具里故意同名的 {@code Main.java} 不会进 javac，
 *     也就不会把一次正常改动判成假失败。这两个目录都不存在或里面没有 .java 时 skipped。
 *  4. **不污染工作区**：class 产物输出到系统临时目录，编译完即删除。
 */
@Service
public class CodeVerifier {

    /** 单次校验最多编译的源文件数，防止误对超大目录执行时卡死 */
    private static final int MAX_FILES = 800;

    /** javac 超时（秒） */
    private static final int TIMEOUT_SECONDS = 90;

    /** 回灌给模型的编译输出上限（保留前 N 个字符，javac 的报错通常集中在前部） */
    private static final int MAX_OUTPUT_CHARS = 6000;

    /**
     * 生产源码根（相对工作区）。VerifyCompile 只 walk 这些目录，
     * 不再对整个 workspace 做发现式全扫描。
     */
    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java",
            "src/test/java");

    /** 源码根内部仍跳过的目录：构建产物 / 依赖 / 版本控制 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "build", "out", "bin", "classes",
            "dist", ".idea", ".vscode", "__pycache__", ".gradle");

    /**
     * 会「改动工作区文件」的工具名。
     * 只要一轮里出现过其中任意一个，就认为这轮需要做编译校验。
     * 同时兼容下划线写法（不同模型生成的名字可能带下划线）。
     */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "createFile", "create_file", "editFile", "edit_file",
            "writeFile", "write_file", "appendFile", "append_file",
            "deletePath", "delete_path", "movePath", "move_path",
            "copyPath", "copy_path");

    /**
     * 校验结果。
     *
     * @param ran     是否真的执行了编译（false = 跳过，例如工作区没有 Java 源码 / 找不到 javac）
     * @param success 是否通过（ran=false 时视为"无需因此阻塞"，返回 true）
     * @param output  编译输出原文（给模型看的，也是给用户看的）
     */
    public record Result(boolean ran, boolean success, String output) {
        public static Result skipped(String reason) {
            return new Result(false, true, reason);
        }

        /** 没真正跑成编译。不能当成通过。 */
        public static Result unverified(String reason) {
            return new Result(false, false, reason);
        }
    }

    /** 判断某个工具是否属于"会改文件"的类别 */
    public static boolean isWriteTool(String toolName) {
        return toolName != null && WRITE_TOOLS.contains(toolName);
    }

    /**
     * 对工作区执行一次编译校验。
     *
     * @param root 工作区根目录（已由 WorkspaceService 解析好的绝对路径）
     */
    public Result verify(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Result.skipped("工作区不可用，跳过编译校验");
        }

        // 1) 只收集生产源码根下的 Java（白名单，不是「除黑名单外全编」）
        List<Path> javaFiles;
        try {
            javaFiles = collectProductionSources(root);
        } catch (IOException e) {
            return Result.skipped("扫描源码失败，跳过编译校验: " + e.getMessage());
        }
        if (javaFiles.isEmpty()) {
            return Result.skipped("工作区内未发现生产源码（src/main/java、src/test/java），跳过编译校验");
        }

        // 2) 定位 javac（用当前运行 JDK 自带的，保证与本项目编译环境一致）
        Path javac = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "javac.exe" : "javac");
        if (!Files.exists(javac)) {
            return Result.unverified("未验证：未找到 javac（" + javac + "）");
        }

        boolean externalBuild = Files.isRegularFile(root.resolve("pom.xml"))
                || Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"));

        Path outDir = null;
        Path argFile = null;
        try {
            outDir = Files.createTempDirectory("jingjing-verify-out");
            argFile = Files.createTempFile("jingjing-javac-", ".args");

            // 3) 用 @argfile 传参（classpath 很长，会超过 Windows 命令行长度上限）
            StringBuilder args = new StringBuilder();
            args.append("-encoding\nUTF-8\n");
            args.append("-nowarn\n");
            args.append("-proc:none\n");                       // 关闭注解处理：本项目无 Lombok，避免第三方处理器干扰
            args.append("-d\n").append(argPath(outDir.toAbsolutePath().toString())).append('\n');
            // 不用本应用的 classpath 冒充目标项目的依赖。有自己的构建文件时，编不过记为未验证。
            for (Path f : javaFiles) {
                args.append(argPath(f.toAbsolutePath().toString())).append('\n');
            }
            writeArgFile(argFile, args.toString());

            ProcessBuilder pb = new ProcessBuilder(javac.toString(), "@" + argFile.toAbsolutePath());
            pb.redirectErrorStream(true);
            pb.directory(root.toFile());
            com.example.jingjingai2.security.DesktopTokenSecrets.scrub(pb.environment());
            com.example.jingjingai2.agent.TaskExecution task = com.example.jingjingai2.agent.TaskExecution.current();
            if (task != null && task.cancelled()) return Result.unverified("请求已停止，未开始编译");
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            int[] overflowLines = new int[1];
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), outputCharset()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            if (out.length() < MAX_OUTPUT_CHARS) out.append(line).append('\n');
                            else overflowLines[0]++;
                        }
                    }
                } catch (IOException ignored) {
                    // 超时或取消关掉流时，读线程在这里结束。
                }
            }, "javac-output");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                if (task != null && task.cancelled()) {
                    proc.destroyForcibly();
                    proc.getInputStream().close();
                    reader.join(2000);
                    return Result.unverified("请求已停止，编译进程已终止");
                }
                if (proc.waitFor(200, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }
            if (!finished) {
                proc.destroyForcibly();
                proc.getInputStream().close();
                reader.join(2000);
                return new Result(true, false,
                        "编译超时（>" + TIMEOUT_SECONDS + " 秒），已强制终止。\n" + out);
            }
            reader.join(2000);
            if (overflowLines[0] > 0) {
                out.append("...（另有 ").append(overflowLines[0]).append(" 行输出已截断）\n");
            }

            int code = proc.exitValue();
            String text = out.toString().trim();
            if (code == 0) {
                return new Result(true, true,
                        "编译通过（javac 退出码 0，编译了 " + javaFiles.size() + " 个 .java 文件）"
                                + (text.isEmpty() ? "" : "\n" + text));
            }
            if (externalBuild) {
                return Result.unverified("未验证：目标项目自带构建文件，没有解析它的依赖，"
                        + "不能把本应用的 classpath 当成它的依赖。javac 退出码 " + code
                        + (text.isEmpty() ? "" : "\n" + text));
            }
            return new Result(true, false,
                    "编译失败（javac 退出码 " + code + "）：\n" + text);
        } catch (Exception e) {
            return Result.unverified("未验证：编译进程未能正常结束: " + e.getMessage());
        } finally {
            deleteQuietly(argFile);
            deleteQuietly(outDir);
        }
    }

    /** 只 walk 白名单源码根；每个根内部仍跳过构建产物目录。 */
    private List<Path> collectProductionSources(Path root) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        for (String rel : SOURCE_ROOTS) {
            Path dir = root.resolve(rel);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !underSkippedDir(p, root))
                        .forEach(javaFiles::add);
            }
            if (javaFiles.size() > MAX_FILES) {
                return new ArrayList<>(javaFiles.subList(0, MAX_FILES));
            }
        }
        return javaFiles;
    }

    /** 相对 base 判断路径中是否含有需要跳过的目录（这样显式指定某目录时仍能被扫描） */
    private boolean underSkippedDir(Path file, Path base) {
        Path rel;
        try {
            rel = base.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path seg : rel) {
            if (SKIP_DIRS.contains(seg.toString())) return true;
        }
        return false;
    }

    /**
     * 生成 @argfile 里的一个参数。
     *
     * 【坑 1｜反斜杠被当转义符】javac 解析 @argfile 时会把「反斜杠」当作转义字符处理（在双引号内尤其明显），
     * 于是 Windows 路径 {@code D:\java\x.java} 会被吃成 {@code D:javax.java} 而报
     * "无效文件名"。因此这里统一把 {@code \} 换成 {@code /}（javac 在 Windows 上同样接受正斜杠），
     * 再用双引号包住以兼容含空格的路径。
     * 注意：转成正斜杠后，路径里**不会再有反斜杠**，所以也就不会出现
     * "路径以 \ 结尾 + 紧跟收尾引号" 这类经典转义陷阱；含空格靠引号解决。
     */
    private String argPath(String p) {
        return "\"" + p.replace('\\', '/') + "\"";
    }

    /**
     * 写 @argfile。
     *
     * 【坑 2｜编码】@argfile 是由 javac 启动器按**系统 ANSI 代码页**读取的（中文 Windows = GBK），
     * **不是 UTF-8**。曾实测：把含中文路径的 argfile 按 UTF-8 写入，javac 报
     * "找不到文件: ...涓 鏂 鐩 褰..."（乱码）；改成按 GBK 写入才编译通过。
     * 所以这里按平台编码写；若某些字符连平台编码都表示不了，才退回 UTF-8。
     */
    private void writeArgFile(Path argFile, String content) throws IOException {
        Charset cs = argFileCharset();
        if (!cs.newEncoder().canEncode(content)) {
            cs = StandardCharsets.UTF_8;
        }
        Files.write(argFile, content.getBytes(cs));
    }

    /** @argfile 的读写编码：Windows 用系统 ANSI 代码页（sun.jnu.encoding，通常 GBK），其他平台用默认编码 */
    private Charset argFileCharset() {
        if (isWindows()) {
            try {
                return Charset.forName(System.getProperty("sun.jnu.encoding", "GBK"));
            } catch (Exception ignored) {
                // 继续尝试 GBK
            }
            try {
                return Charset.forName("GBK");
            } catch (Exception ignored) {
                // 退回默认
            }
        }
        return Charset.defaultCharset();
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** Windows 下进程输出为系统代码页（GBK/CP936），其它平台按 UTF-8 */
    private Charset outputCharset() {
        if (isWindows()) {
            try {
                return Charset.forName("GBK");
            } catch (Exception ignored) {
                // 退回 UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }
}
