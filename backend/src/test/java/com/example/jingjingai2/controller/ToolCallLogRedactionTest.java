package com.example.jingjingai2.controller;

import com.example.jingjingai2.agent.ToolStubs;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 控制台日志不得带出密钥。走的是真实的 {@code ChatController.callTool}，
 * 不是只测脱敏工具类；工具本体是桩，不执行任何真实操作。
 */
class ToolCallLogRedactionTest {

    private final PrintStream originalOut = System.out;

    @BeforeEach
    void noGate() {
        ToolDispatcher.installGate(null);
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        ToolDispatcher.installGate(null);
    }

    @Test
    @DisplayName("[ToolCall] 打印的是脱敏参数：token/password/apiKey/Cookie 不进标准输出")
    void toolCallLogIsRedacted() throws Exception {
        String args = "{\"url\":\"https://example.com/a\",\"token\":\"tok-value\","
                + "\"password\":\"pw-value\",\"apiKey\":\"ak-value\",\"Cookie\":\"sid-value\"}";

        String out = capture(() -> callTool("browser_click", args));

        assertTrue(out.contains("name=browser_click"), out);
        assertTrue(out.contains("***"), out);
        for (String secret : new String[]{"tok-value", "pw-value", "ak-value", "sid-value"}) {
            assertFalse(out.contains(secret), "日志里出现了密钥：" + out);
        }
    }

    @Test
    @DisplayName("非 JSON 参数也不能打印原文")
    void nonJsonArgsAreNotPrinted() throws Exception {
        String out = capture(() -> callTool("run_shell", "sk-live-abcdef"));

        assertFalse(out.contains("sk-live"), out);
        assertTrue(out.contains("已省略"), out);
        assertTrue(out.contains("name=run_shell"), out);
    }

    private String callTool(String name, String args) throws Exception {
        Method callTool = ChatController.class.getDeclaredMethod(
                "callTool", ToolCallback[].class, String.class, String.class);
        callTool.setAccessible(true);
        ToolCallback[] cbs = new ToolCallback[]{ToolStubs.of(name, a -> "ok")};
        try {
            return String.valueOf(callTool.invoke(newChatController(), cbs, name, args));
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    /** 构造器只做字段赋值，其余依赖给 null 不影响 callTool（只打日志 + 派发）。 */
    private static ChatController newChatController() throws Exception {
        Constructor<?> ctor = ChatController.class.getConstructors()[0];
        return (ChatController) ctor.newInstance(new Object[ctor.getParameterCount()]);
    }

    private String capture(Callable<?> action) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(ps);
        try {
            action.call();
            System.out.flush();
        } finally {
            System.setOut(originalOut);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
