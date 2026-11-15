package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdioMcpSessionEnvTest {

    @Test
    @DisplayName("Windows：无扩展名的命令按 PATHEXT 解析成 .cmd")
    void resolvesNpxCmdOnWindows() throws Exception {
        File dir = File.createTempFile("mcp-path-", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        File cmd = new File(dir, "npx.cmd");
        assertTrue(cmd.createNewFile());
        try {
            String resolved = StdioMcpSession.resolveCommand(
                    "npx", dir.getAbsolutePath(), ".COM;.EXE;.BAT;.CMD", true);
            assertEquals(cmd.getCanonicalPath(), resolved);
            assertTrue(new File(resolved).getName().equalsIgnoreCase("npx.cmd"), resolved);
        } finally {
            cmd.delete();
            dir.delete();
        }
    }

    @Test
    @DisplayName("已经是路径或带扩展名：原样返回，不用 cmd /c")
    void leavesPathsAlone() {
        assertEquals("C:\\bin\\npx.cmd",
                StdioMcpSession.resolveCommand("C:\\bin\\npx.cmd", "", ".CMD", true));
        assertEquals("java", StdioMcpSession.resolveCommand("java", "", "", false));
        List<String> cmd = StdioMcpSession.buildCommand(spec("java", "-version"));
        String exe = cmd.get(0);
        assertTrue("java".equals(exe) || exe.toLowerCase(java.util.Locale.ROOT).endsWith("java.exe"),
                "Windows 上无扩展名的 java 应解析成 java.exe，且绝不用 cmd /c: " + exe);
        assertEquals("-version", cmd.get(1));
        assertFalse(cmd.contains("cmd.exe"));
        assertFalse(cmd.contains("/c"));
    }

    @Test
    @DisplayName("环境变量：清空后只留 PATH 等最小集合，再叠 spec.env")
    void minimalEnvDoesNotInheritEverything() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SECRET_SHOULD_NOT_STAY", "xxx");
        env.put("JAVA_TOOL_OPTIONS", "-evil");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("FOO", "bar");
        StdioMcpSession.applyMinimalEnv(env, extra);
        assertEquals("bar", env.get("FOO"));
        assertFalse(env.containsKey("SECRET_SHOULD_NOT_STAY"), env.keySet().toString());
        assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"), env.keySet().toString());
        boolean hasPath = env.containsKey("PATH") || env.containsKey("Path");
        assertTrue(hasPath, "必须保留 PATH: " + env.keySet());
        assertFalse(env.containsKey("ProgramFiles"),
                "全局最小 env 不得放行 ProgramFiles（Playwright 走 spec.env）: " + env.keySet());
        assertFalse(env.containsKey("USERPROFILE"),
                "全局最小 env 不得放行 USERPROFILE: " + env.keySet());
    }

    private static McpServerSpec spec(String command, String... args) {
        McpServerSpec s = new McpServerSpec();
        s.setCommand(command);
        s.setArgs(List.of(args));
        return s;
    }
}
