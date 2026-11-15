package com.example.jingjingai2.controller;

import com.example.jingjingai2.sandbox.ApprovedCommandLauncher;
import com.example.jingjingai2.sandbox.CommandPathValidator;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import com.example.jingjingai2.utils.TerminalTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归：审批通过后的终端命令只能打在会话工作区里。
 *
 * <p><b>迁移说明（阶段 C-2B）。</b>旧审批旁路（{@code POST /ai/approve} 与
 * {@code ChatController.executeCommand(String, Path)}）已删除，本测试不再反射那个私有方法，
 * 改为验证新链路真正的执行边界：
 * {@link TerminalTool} → {@link CommandPathValidator} → {@link ApprovedCommandLauncher}。
 * 启动器在本测试里换成替身，所以<b>不会真的启动任何进程</b>，也不执行删除/覆盖。
 *
 * <p><b>安全目标重新落点（不是"原样保留旧语义"）。</b>原测试断言"工作区根取不到 → 拒绝且零进程"；
 * 新链路上不存在这种状态：{@link PathSandbox} 拿不到请求级上下文时会回退到 {@code app.sandbox.root}。
 * 因此同一安全目标（越界命令不得启动进程）改为落在两处：
 * ① 允许的命令，启动器拿到的工作目录必须是会话工作区根（而不是 JVM 当前目录/仓库根）；
 * ② 引用工作区之外路径的命令，整条被拒绝，且启动器<b>一次都没有被调用</b>（另有文件未被创建的旁证）。
 */
class ApprovedCommandWorkdirTest {

    /** 文件名带阶段标记，避免和仓库里任何已有文件重名。 */
    private static final String TARGET_NAME = "stage0-approved-del.txt";

    @Test
    @DisplayName("工作区内的相对路径命令：启动器的工作目录是工作区根，仓库根的同名文件不受影响")
    void approvedRelativeCommandStaysInsideWorkspace(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("proj-a"));
        // 用一个"仓库根"替身目录，避免测试往真实仓库目录里写文件
        Path repoLike = Files.createDirectories(temp.resolve("repo-root"));
        Path workspaceFile = workspace.resolve(TARGET_NAME);
        Files.writeString(workspaceFile, "在工作区里");
        Path decoy = repoLike.resolve(TARGET_NAME);
        Files.writeString(decoy, "在仓库根里，批准后绝不能被改动");

        String command = "cmd /c del /q " + TARGET_NAME;
        // 前置：这条相对路径命令本身是"被允许的"，否则下面的断言就退化成"被拒绝"而不是"允许后仍在界内"
        CommandPathValidator.Decision decision = CommandPathValidator.validate(command, workspace);
        assertTrue(decision.allowed(), "相对路径命令应被允许：" + decision.reason());

        PathSandbox sandbox = new PathSandbox(workspace.toString());
        assertEquals(workspace.resolve(TARGET_NAME).toAbsolutePath().normalize(),
                sandbox.resolveWithin(TARGET_NAME).toAbsolutePath().normalize(),
                "相对路径必须解析到工作区内，而不是仓库根的同名文件");

        RecordingLauncher launcher = new RecordingLauncher();
        TerminalTool tool = new TerminalTool(sandbox);
        tool.setCommandLauncher(launcher);

        WorkspaceContext.set(workspace);
        try {
            assertEquals(workspace.toAbsolutePath().normalize(),
                    sandbox.root().toAbsolutePath().normalize(), "沙箱根必须是本会话工作区");
            String output = tool.executeCommand(command);
            assertTrue(output.contains("执行成功 (exit=0)"), output);
        } finally {
            WorkspaceContext.clear();
        }

        assertEquals(1, launcher.calls.get(), "允许的命令必须交给启动器一次");
        Path cwd = launcher.workingDirectory.get();
        assertNotNull(cwd, "启动器必须收到工作目录");
        assertEquals(workspace.toAbsolutePath().normalize(), cwd.toAbsolutePath().normalize(),
                "启动器的工作目录必须是会话工作区根");
        assertNotEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                cwd.toAbsolutePath().normalize(),
                "绝不能回退到 Java 进程的当前目录（旧 bug 就是从这里误删仓库文件）");

        assertEquals("在仓库根里，批准后绝不能被改动", Files.readString(decoy),
                "仓库根的同名文件不能被改动");
        assertEquals("在工作区里", Files.readString(workspaceFile),
                "替身启动器不真跑进程，工作区文件也不会被删——本用例断言的是执行边界，不是真的删除");
    }

    @Test
    @DisplayName("引用工作区外路径（..）的命令：整条拒绝，启动器零调用，且没有产生任何文件")
    void outsideRelativePathIsRejectedBeforeLaunch(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("proj-a"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path proof = outside.resolve("proof.txt");
        assertFalse(Files.exists(proof), "前置：副作用文件本来不存在");

        ForbiddenLauncher launcher = new ForbiddenLauncher();
        TerminalTool tool = new TerminalTool(new PathSandbox(workspace.toString()));
        tool.setCommandLauncher(launcher);

        String command = "cmd /c echo pwn > ..\\outside\\proof.txt";
        WorkspaceContext.set(workspace);
        String output;
        try {
            output = tool.executeCommand(command);
        } finally {
            WorkspaceContext.clear();
        }

        assertTrue(output.contains("工作区之外的路径"), "应明确拒绝越界路径，实际：" + output);
        assertEquals(0, launcher.calls.get(), "被拒绝的命令不得启动进程");
        assertFalse(Files.exists(proof), "被拒绝的命令不得产生任何副作用文件");
    }

    @Test
    @DisplayName("绝对路径指向工作区外：整条拒绝，启动器零调用，目标文件没被删除")
    void absoluteOutsidePathIsRejectedBeforeLaunch(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("proj-a"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path victim = outside.resolve("keep.txt");
        Files.writeString(victim, "工作区外，绝不能被删");

        ForbiddenLauncher launcher = new ForbiddenLauncher();
        TerminalTool tool = new TerminalTool(new PathSandbox(workspace.toString()));
        tool.setCommandLauncher(launcher);

        // 这正是旧 /ai/approve 会直接执行的那类命令：批准后不经过路径校验就起进程
        String command = "cmd /c del /q " + victim.toAbsolutePath();
        WorkspaceContext.set(workspace);
        String output;
        try {
            output = tool.executeCommand(command);
        } finally {
            WorkspaceContext.clear();
        }

        assertTrue(output.contains("工作区之外的路径"), "应明确拒绝越界路径，实际：" + output);
        assertEquals(0, launcher.calls.get(), "被拒绝的命令不得启动进程");
        assertEquals("工作区外，绝不能被删", Files.readString(victim), "目标文件不能被删除");
    }

    /** 允许场景用：记录被调用次数与工作目录，返回一个"空输出、退出码 0"的进程替身。 */
    private static final class RecordingLauncher implements ApprovedCommandLauncher {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Path> workingDirectory = new AtomicReference<>();

        @Override
        public Process start(String[] command, Path dir) {
            calls.incrementAndGet();
            workingDirectory.set(dir);
            return new SilentProcess();
        }
    }

    /** 拒绝场景用：一旦被调用就炸，证明"根本没走到启动进程这一步"。 */
    private static final class ForbiddenLauncher implements ApprovedCommandLauncher {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Process start(String[] command, Path dir) {
            calls.incrementAndGet();
            throw new AssertionError("命令已被沙箱拒绝，却仍然尝试启动进程");
        }
    }

    /** 只实现 {@link TerminalTool} 用到的那几个方法：空 stdout、立即结束、exit=0。 */
    private static final class SilentProcess extends Process {
        private static final InputStream EMPTY = new ByteArrayInputStream(new byte[0]);

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return EMPTY; }
        @Override public InputStream getErrorStream() { return EMPTY; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
