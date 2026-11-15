package com.example.jingjingai2.sandbox;

import com.example.jingjingai2.security.DesktopTokenSecrets;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 审批通过后的终端命令怎么真正启动进程。
 *
 * <p>抽出来只为一个目的：测试要能断言「工作区根取不到时，一个子进程都没创建」。
 * 生产实现就是 {@link ProcessBuilder}，测试里换成计数替身。
 */
public interface ApprovedCommandLauncher {

    /** 在 {@code workingDirectory} 下启动命令，返回已启动的进程。 */
    Process start(String[] command, Path workingDirectory) throws IOException;

    /** 生产实现：{@link ProcessBuilder}，工作目录固定为传入的工作区根。 */
    ApprovedCommandLauncher REAL = (command, workingDirectory) -> {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        // 启动令牌不能跟着用户命令/MCP 子进程跑出去（否则模型能把它读进对话或日志）。
        DesktopTokenSecrets.scrub(builder.environment());
        return builder.start();
    };
}
