package com.example.jingjingai2;

import com.example.jingjingai2.sandbox.CommandPathValidator;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.utils.TerminalTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端命令沙箱守卫测试（白名单式）。
 *
 * 重点回归 E2E 里实际踩到的绕过手法：
 *   - `cd /d D:\... && dir`（切目录绕过）
 *   - pushd / chdir / popd
 *   - `..\..` 相对路径拼接绕过
 *   - 符号链接指到工作区之外
 * 同时确保**正常命令不被误伤**（dir /s /b src、javac、java -version 等）。
 */
class CommandPathValidatorTest {

    private Path root;
    private Path outside;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("guard-ws");
        outside = Files.createTempDirectory("guard-outside");
        Files.writeString(root.resolve("App.java"), "public class App {}\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/main/java/com/demo"));
        Files.writeString(root.resolve("src/main/java/com/demo/App.java"), "package com.demo;\npublic class App {}\n",
                StandardCharsets.UTF_8);
        Files.writeString(outside.resolve("secret.txt"), "TOP_SECRET\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(root);
        deleteRecursively(outside);
    }

    private void deleteRecursively(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (Exception ignored) {}
            });
        }
    }

    private CommandPathValidator.Decision check(String command) {
        return CommandPathValidator.validate(command, root);
    }

    private void assertDenied(String command) {
        CommandPathValidator.Decision d = check(command);
        assertFalse(d.allowed(), "本应拒绝却放行了: " + command);
        assertNotNull(d.reason());
    }

    private void assertAllowed(String command) {
        CommandPathValidator.Decision d = check(command);
        assertTrue(d.allowed(), "本应放行却被拒绝: " + command + " -> " + d.reason());
    }

    // ==================== 回归：本次踩到的坑 ====================

    @Test
    @DisplayName("回归：cd /d 切换盘符+目录绕过 → 拒绝")
    void blocksCdWithDriveSwitch() {
        assertDenied("cd /d D:\\java\\practice\\jingjingAI2 && dir /s /b src");
        assertDenied("cd /d " + outside);
        assertDenied("cd " + outside);
    }

    @Test
    @DisplayName("回归：cd 的拒绝文案包含要求的话术")
    void cdRejectionMessageIsActionable() {
        String reason = check("cd ..").reason();
        assertTrue(reason.contains("不允许在命令中切换目录，请使用相对路径操作"), reason);
    }

    @Test
    @DisplayName("回归：pushd / popd / chdir 绕过 → 拒绝")
    void blocksPushdPopdChdir() {
        assertDenied("pushd " + outside + " && dir");
        assertDenied("popd");
        assertDenied("chdir " + outside);
        assertDenied("pushd ..\\..");
    }

    @Test
    @DisplayName("回归：嵌套 shell 里改目录（cmd /c cd ...）→ 拒绝")
    void blocksCdInsideNestedShell() {
        assertDenied("cmd /c cd /d " + outside + " && dir");
        assertDenied("cmd.exe /c pushd " + outside);
    }

    @Test
    @DisplayName("回归：连写的 cd.. 也拒绝；但 'abcd' 这类词不误伤")
    void handlesCdWordBoundaries() {
        assertDenied("cd.. && dir");
        assertAllowed("echo abcd");
        assertAllowed("dir abcde");
    }

    // ==================== 路径越界 ====================

    @Test
    @DisplayName("..\\.. 相对路径拼接越过工作区 → 拒绝")
    void blocksParentTraversal() {
        assertDenied("type ..\\..\\secret.txt");
        assertDenied("type ../../etc/passwd");
        assertDenied("dir ..");
        assertDenied("cat ../guard-outside/secret.txt");
    }

    @Test
    @DisplayName("工作区之外的绝对路径 → 拒绝（且不只看命令开头）")
    void blocksAbsolutePathsOutside() {
        assertDenied("type C:\\Windows\\win.ini");
        assertDenied("type " + outside.resolve("secret.txt"));
        assertDenied("dir /s /b D:\\java");
        // 关键：越界路径出现在命令**中间**也要拦
        assertDenied("echo hello && type " + outside.resolve("secret.txt"));
    }

    @Test
    @DisplayName("-C 参数指定工作区之外 → 拒绝；-C 工作区内的相对路径 → 放行")
    void validatesDashCCArgument() {
        assertDenied("git -C " + outside + " status");
        assertAllowed("git -C src status");
    }

    @Test
    @DisplayName("含义不确定的驱动器相对路径（D:foo）→ 拒绝")
    void blocksDriveRelativePath() {
        assertDenied("type D:secret.txt");
    }

    @Test
    @DisplayName("符号链接/junction 指向工作区之外 → 拒绝（环境不支持建链接时自动跳过）")
    void blocksSymlinkEscape() throws Exception {
        Path link = root.resolve("escape-link");
        Assumptions.assumeTrue(createLink(link, outside),
                "当前环境无法创建符号链接/junction（Windows 需管理员或开发者模式），跳过该用例");

        assertDenied("type escape-link/secret.txt");
        assertDenied("type escape-link\\secret.txt");
        // 工作区内的真实文件仍然放行
        assertAllowed("type App.java");
    }

    /**
     * 建一个"从工作区内指向工作区外"的链接。
     *
     * 踩过的坑（都实测过）：
     *  - Windows 上普通用户建符号链接需要特权；本机更诡异：`Files.createSymbolicLink` **不抛异常，
     *    却只创建了一个空目录**（isSymbolicLink=false、透过它读不到目标文件）——所以不能只信"没抛异常"，
     *    必须验证链接**真的通**（能透过它读到目标里的文件）。
     *  - 目录 junction（mklink /J）不需要管理员权限，实测可用，因此 Windows 上优先用它。
     */
    private boolean createLink(Path link, Path target) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (createJunction(link, target)) return true;
        }
        try {
            Files.createSymbolicLink(link, target);
            if (isWorkingLink(link)) return true;
            Files.deleteIfExists(link);
        } catch (Exception ignored) {
            // 环境不支持符号链接
        }
        return false;
    }

    private boolean createJunction(Path link, Path target) {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            p.waitFor(20, TimeUnit.SECONDS);
            return isWorkingLink(link);
        } catch (Exception e) {
            return false;
        }
    }

    /** 链接是否真的"通"：能透过它读到目标目录里的 secret.txt */
    private boolean isWorkingLink(Path link) {
        try {
            return Files.exists(link.resolve("secret.txt"));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 远程 URL 与误伤 ====================

    @Test
    @DisplayName("远程 URL 不算路径：curl/git clone 带网址能放行；file:// 仍要校验")
    void allowsRemoteUrlsButNotFileUrls() {
        assertAllowed("curl -s https://api.github.com/repos/openai/codex/releases/latest");
        assertAllowed("curl \"https://example.com/a/b?x=1\"");
        assertAllowed("git clone https://github.com/openai/codex.git");
        assertAllowed("npm view @openai/codex version");
        // file:// 指向本地文件，必须走路径校验 → 越界要拒
        assertDenied("curl file:///C:/Windows/win.ini");
        // 网址后面跟一个越界的输出路径，照样拦
        assertDenied("curl -o ../escaped.html https://example.com");
    }

    // ==================== 正常命令不能被误伤 ====================

    @Test
    @DisplayName("正常命令放行：dir /s /b src、javac、java -version、git status…")
    void allowsLegitimateCommands() {
        assertAllowed("dir");
        assertAllowed("dir /s /b src");
        assertAllowed("dir /s /b src\\main\\java");
        assertAllowed("java -version");
        assertAllowed("javac -d out src/main/java/com/demo/App.java");
        assertAllowed("javac -encoding UTF-8 -d target/classes App.java");
        assertAllowed("type App.java");
        assertAllowed("type src/main/java/com/demo/App.java");
        assertAllowed("git status");
        assertAllowed("mvnw clean package");
        assertAllowed("echo hello world");
        assertAllowed("echo 中文测试");
        assertAllowed("findstr class App.java");
        assertAllowed("java -cp target/classes com.demo.App");
        assertAllowed("");
        assertAllowed(null);
    }

    @Test
    @DisplayName("工具层联通：TerminalTool 对 cd 命令直接返回拒绝文案，不执行")
    void terminalToolRejectsBeforeExecuting() {
        TerminalTool tool = new TerminalTool(new PathSandbox(root.toString()));
        String r = tool.executeCommand("cd /d " + outside + " && dir");
        assertTrue(r.contains("不允许在命令中切换目录"), r);
        // 越界路径同样在工具层被拦
        String r2 = tool.executeCommand("type " + outside.resolve("secret.txt"));
        assertTrue(r2.contains("工作区之外的路径"), r2);
    }
}
