package com.example.jingjingai2.security;

import java.util.Map;

/**
 * 启动令牌只应存在于“Electron → Spring Boot”这一条受控通道里。
 *
 * <p>后端拉起子进程（用户审批过的终端命令等）时，默认会继承父进程的全部环境变量。
 * 若不处理，模型只要执行一条 {@code echo %BEARCODING_LOCAL_TOKEN%} 就能把令牌带进
 * 工具结果、对话历史和日志。这里在创建子进程前把令牌变量摘掉。
 *
 * <p>不修改父进程自己的环境；不影响 MCP stdio 的最小环境（本来就不继承全量 env）。
 */
public final class DesktopTokenSecrets {

    private DesktopTokenSecrets() {}

    /** 从子进程环境副本里移除启动令牌。null 安全。 */
    public static void scrub(Map<String, String> childEnvironment) {
        if (childEnvironment == null) return;
        childEnvironment.remove(DesktopAuthProperties.ENV_TOKEN);
    }
}
