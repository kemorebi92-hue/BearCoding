package com.example.jingjingai2;

import com.example.jingjingai2.sandbox.CommandSafetyChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 危险命令识别测试：验证破坏性命令能被识别、普通命令不被误伤。
 */
class CommandSafetyCheckerTest {

    @Test
    @DisplayName("删除类命令识别为高危")
    void detectsDeletion() {
        assertTrue(CommandSafetyChecker.isRisky("rm -rf /"));
        assertTrue(CommandSafetyChecker.isRisky("rm -f a.txt"));
        assertTrue(CommandSafetyChecker.isRisky("del important.txt"));
        assertTrue(CommandSafetyChecker.isRisky("DEL /S /Q C:\\temp"));
        assertTrue(CommandSafetyChecker.isRisky("rd /s /q folder"));
    }

    @Test
    @DisplayName("改写历史的 git 命令识别为高危")
    void detectsGitHistoryRewrite() {
        assertTrue(CommandSafetyChecker.isRisky("git push --force"));
        assertTrue(CommandSafetyChecker.isRisky("git reset --hard HEAD~1"));
        assertTrue(CommandSafetyChecker.isRisky("git clean -fd"));
    }

    @Test
    @DisplayName("危险系统命令识别为高危")
    void detectsSystemCommands() {
        assertTrue(CommandSafetyChecker.isRisky("shutdown -s -t 0"));
        assertTrue(CommandSafetyChecker.isRisky("format c:"));
        assertTrue(CommandSafetyChecker.isRisky("powershell Remove-Item -Recurse -Force ."));
    }

    @Test
    @DisplayName("普通命令不误伤")
    void allowsSafeCommands() {
        assertFalse(CommandSafetyChecker.isRisky("dir"));
        assertFalse(CommandSafetyChecker.isRisky("java -version"));
        assertFalse(CommandSafetyChecker.isRisky("echo hello"));
        assertFalse(CommandSafetyChecker.isRisky("git status"));
        assertFalse(CommandSafetyChecker.isRisky("git log --oneline"));
        assertFalse(CommandSafetyChecker.isRisky(""));
        assertFalse(CommandSafetyChecker.isRisky(null));
    }

    @Test
    @DisplayName("deletePath 必须审批；普通读文件不用")
    void deletePathAlwaysNeedsApproval() {
        assertTrue(CommandSafetyChecker.requiresApproval("deletePath", null));
        assertTrue(CommandSafetyChecker.requiresApproval("delete_path", null));
        assertFalse(CommandSafetyChecker.requiresApproval("readFile", null));
        assertFalse(CommandSafetyChecker.requiresApproval("writeFile", null));
        assertFalse(CommandSafetyChecker.requiresApproval("executeCommand", "dir"));
        assertTrue(CommandSafetyChecker.requiresApproval("executeCommand", "del hello.txt"));
    }
}
