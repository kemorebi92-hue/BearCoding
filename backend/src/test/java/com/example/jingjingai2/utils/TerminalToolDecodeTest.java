package com.example.jingjingai2.utils;

import com.example.jingjingai2.agent.HandoffResult;
import com.example.jingjingai2.sandbox.PathSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端输出解码测试。
 *
 * 背景：Windows 上同一条命令的输出可能是两种编码 ——
 *  cmd 内建命令是系统代码页（GBK），而 java/python 默认按 UTF-8 输出。
 * 固定用一种就必然有一边乱码（实测就是这么踩的：模型看到 ??? 还得额外写测试去验证），
 * 所以改成"严格 UTF-8 解码失败 → 回退 GBK"。
 */
class TerminalToolDecodeTest {

    @Test
    @DisplayName("UTF-8 中文输出正常解码（java -Dstdout.encoding=UTF-8 的场景）")
    void utf8Decoded() {
        byte[] utf8 = "添加成功！学生：张三".getBytes(StandardCharsets.UTF_8);
        assertEquals("添加成功！学生：张三", TerminalTool.decodeSmart(utf8));
    }

    @Test
    @DisplayName("GBK 中文输出回退解码（cmd 内建命令的场景）")
    void gbkFallback() {
        byte[] gbk = "编译通过，无错误".getBytes(Charset.forName("GBK"));
        assertEquals("编译通过，无错误", TerminalTool.decodeSmart(gbk));
    }

    @Test
    @DisplayName("纯 ASCII 两种编码都一样，不能出问题")
    void asciiStable() {
        byte[] ascii = "javac -encoding UTF-8 ok".getBytes(StandardCharsets.US_ASCII);
        assertEquals("javac -encoding UTF-8 ok", TerminalTool.decodeSmart(ascii));
        assertEquals(0, TerminalTool.decodeSmart(new byte[0]).length());
        assertEquals("", TerminalTool.decodeSmart(null));
    }

    @Test
    @DisplayName("混合内容（英文命令 + 中文结果）不丢内容")
    void mixedContent() {
        String text = "exit=0\n已完成 40 个断言\n第 4 轮 工具调用 2 个";
        assertEquals(text, TerminalTool.decodeSmart(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(text, TerminalTool.decodeSmart(text.getBytes(Charset.forName("GBK"))));
    }

    @Test
    @DisplayName("V1：成功路径带真实 (exit=0)，失败路径带真实非 0；不发明 0")
    void executeCommandPrefixesRealExitCode() throws Exception {
        Path root = Files.createTempDirectory("term-exit-");
        try {
            TerminalTool tool = new TerminalTool(new PathSandbox(root.toString()));
            String ok = tool.executeCommand("echo v1-exit-ok");
            assertTrue(ok.contains("执行成功 (exit=0)"), ok);
            assertTrue(ok.contains("v1-exit-ok"), ok);
            assertEquals(0, HandoffResult.parseExitCode(ok), ok);

            String fail = tool.executeCommand("exit 7");
            assertTrue(fail.contains("执行失败 (exit=7)"), fail);
            assertFalse(fail.contains("执行成功 (exit=0)"), fail);
            assertEquals(7, HandoffResult.parseExitCode(fail), fail);
        } finally {
            Files.deleteIfExists(root);
        }
    }
}
