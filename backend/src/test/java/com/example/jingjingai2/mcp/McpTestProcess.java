package com.example.jingjingai2.mcp;

import com.example.jingjingai2.mcp.testdouble.EchoMcpServer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.File;
import java.net.URI;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 测 stdio 子进程时拼 java -cp。
 *
 * <p>{@code test.sh} 用 {@code java -jar junit-platform-console-standalone ... --class-path ...}，
 * JVM 的 {@code java.class.path} 只有那一个 jar，子进程拿它会找不到 EchoMcpServer / Jackson。
 * 所以从本进程已经加载的 class 的 CodeSource 拼一份最小 classpath。
 */
final class McpTestProcess {

    private McpTestProcess() {}

    static String childClasspath() {
        Set<String> parts = new LinkedHashSet<>();
        addClassLocation(parts, EchoMcpServer.class);
        addClassLocation(parts, StdioMcpSession.class);
        addClassLocation(parts, ObjectMapper.class);
        addClassLocation(parts, JsonParser.class);
        addClassLocation(parts, JsonProperty.class);
        File testClasses = new File("target/test-classes");
        File classes = new File("target/classes");
        if (testClasses.isDirectory()) parts.add(testClasses.getAbsolutePath());
        if (classes.isDirectory()) parts.add(classes.getAbsolutePath());
        String jcp = System.getProperty("java.class.path");
        if (jcp != null && !jcp.isBlank()) {
            for (String p : jcp.split(File.pathSeparator)) {
                if (!p.isBlank()) parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("拼不出 EchoMcpServer 子进程 classpath");
        }
        return String.join(File.pathSeparator, parts);
    }

    private static void addClassLocation(Set<String> parts, Class<?> type) {
        try {
            CodeSource cs = type.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return;
            URI uri = cs.getLocation().toURI();
            parts.add(new File(uri).getAbsolutePath());
        } catch (Exception ignored) {
            // 模块 path / 无 CodeSource 时跳过，靠 target/ 目录兜底
        }
    }

    static List<String> javaCommand(String mainClass, String... extraArgs) {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        if (StdioMcpSession.isWindows() && !new File(java).isFile()) {
            java = java + ".exe";
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-cp");
        cmd.add(childClasspath());
        cmd.add(mainClass);
        if (extraArgs != null) {
            for (String a : extraArgs) cmd.add(a);
        }
        return cmd;
    }

    static McpServerSpec echoSpec(String... extraArgs) {
        List<String> cmd = javaCommand("com.example.jingjingai2.mcp.testdouble.EchoMcpServer", extraArgs);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand(cmd.get(0));
        spec.setArgs(cmd.subList(1, cmd.size()));
        return spec;
    }

    static McpProperties shortTimeoutProps() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setInitializeTimeoutMs(8_000);
        p.setRequestTimeoutMs(2_000);
        p.setMaxResultChars(8000);
        return p;
    }

    static ToolCallback stub(String name) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("stub")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return "ok:" + name; }
        };
    }
}
