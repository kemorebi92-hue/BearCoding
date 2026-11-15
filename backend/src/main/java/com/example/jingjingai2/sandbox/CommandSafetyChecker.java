package com.example.jingjingai2.sandbox;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 危险命令识别器（human-in-the-loop 审批 + 终端工具黑名单共用的纯静态逻辑）。
 *
 * 从 ChatController 原 private isRiskyCommand() 抽出，好处：
 *  - 单一职责：安全判定独立于 Controller 编排；
 *  - 可复用：ChatController 用于"高危命令先问用户"，TerminalTool 用于"直接拒绝执行"；
 *  - 可测试：纯静态方法，不依赖 Spring 上下文。
 */
public final class CommandSafetyChecker {

    private CommandSafetyChecker() {}

    /** 判定一条命令是否危险（破坏性、不可逆、影响系统/改写历史）。 */
    public static boolean isRisky(String command) {
        if (command == null || command.isBlank()) return false;
        String c = command.toLowerCase(Locale.ROOT);

        // A. 明确的高危子串（破坏性、不可逆、影响系统）
        String[] exactPatterns = {
                "rm -rf", "rm -fr", "rm -r ", "rm -f ",
                "del /f", "del /s", "del /q",
                "rd /s", "rd /q", "rmdir /s", "rmdir /q",
                "format ", "mkfs", "fdisk",
                "git push --force", "git push -f",
                "git reset --hard", "git clean -f", "git clean -fd",
                "git checkout -f", "git branch -d", "git revert",
                "dd if=", "shutdown", ":(){",
                "remove-item", "remove-item -recurse", "remove-item -force",
                "clear-content", "invoke-expression", "iex "
        };
        for (String p : exactPatterns) {
            if (c.contains(p)) return true;
        }

        // B. 任何以 rm / rmdir / del 开头的文件删除命令都算高危
        if (Pattern.compile("\\brm\\s+").matcher(c).find()) return true;
        if (Pattern.compile("\\brmdir\\s+").matcher(c).find()) return true;
        if (Pattern.compile("\\bdel\\s+").matcher(c).find()) return true;

        // C. git push / pull / reset / rebase / merge / checkout 等可能改写历史的操作
        if (Pattern.compile("\\bgit\\s+(push|pull|reset|rebase|merge|checkout)\\b").matcher(c).find()) return true;

        // D. 管道到 shell、下载并执行、重定向写设备等高危模式
        if (Pattern.compile(".*\\|\\s*(sh|bash|cmd|powershell)").matcher(c).find()) return true;
        if (Pattern.compile("(curl|wget)\\s+.*\\|").matcher(c).find()) return true;
        if (Pattern.compile(">\\s*/dev/(sd[a-z]|hd[a-z]|mmcblk|disk)").matcher(c).find()) return true;

        // E. PowerShell 高危 cmdlet：删除、清空内容、远程执行表达式
        if (Pattern.compile("\\bremove-item\\b").matcher(c).find()) return true;
        if (Pattern.compile("\\bclear-content\\b").matcher(c).find()) return true;
        if (Pattern.compile("\\binvoke-expression\\b").matcher(c).find()) return true;
        if (Pattern.compile("\\biex\\s+").matcher(c).find()) return true;

        return false;
    }

    /** 终端执行工具（模型可能吐 execute_command） */
    public static boolean isExecuteCommandTool(String name) {
        return "executeCommand".equalsIgnoreCase(name) || "execute_command".equalsIgnoreCase(name);
    }

    /** 结构化删文件/目录：不走终端，但也是不可逆破坏，必须审批。 */
    public static boolean isDeletePathTool(String name) {
        return "deletePath".equalsIgnoreCase(name) || "delete_path".equalsIgnoreCase(name);
    }

    /**
     * 主循环在真正执行工具前是否要暂停等人确认。
     * @param command executeCommand 的 command 字段；其它工具可传 null
     */
    public static boolean requiresApproval(String toolName, String command) {
        if (isDeletePathTool(toolName)) return true;
        if (isExecuteCommandTool(toolName)) return isRisky(command);
        return false;
    }
}
