package com.example.jingjingai2.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试用的工具替身。
 *
 * 为什么不用真的 {@code FileTool}/{@code TerminalTool}：那需要 Spring 容器、Sandbox、工作区，
 * 而这里要钉死的是**白名单与记账**这两件事 —— 它们只依赖"工具名 + 被调了几次 + 返回什么"。
 */
public final class ToolStubs {

    public static ToolCallback of(String name, Function<String, String> body) {
        ToolDefinition def = new ToolDefinition() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub of " + name; }
            @Override public String inputSchema() { return "{}"; }
        };
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return body.apply(arguments); }
        };
    }

    static ToolCallback of(String name, String fixedResult, AtomicInteger counter) {
        return of(name, args -> {
            if (counter != null) counter.incrementAndGet();
            return fixedResult;
        });
    }

    static ToolCallback of(String name, String fixedResult) {
        return of(name, fixedResult, null);
    }

    /** 主循环那一套全量清单的"影子"：白名单测试要证明"列外的工具一个都进不了房间" */
    static ToolCallback[] fullSet(AtomicInteger readCounter, AtomicInteger writeCounter,
                                  AtomicInteger commandCounter, AtomicInteger spawnCounter) {
        return new ToolCallback[]{
                of("readFile", "文件内容", readCounter),
                of("listDirectory", "目录列表"),
                of("globFile", "**/*.java"),
                of("grepFile", "命中 3 处"),
                of("webSearch", "搜索结果"),
                of("writeFile", "写入失败: 子代理不该能写文件", writeCounter),
                of("createFile", "创建失败: 子代理不该能建文件", writeCounter),
                of("editFile", "编辑失败: 子代理不该能改文件", writeCounter),
                of("appendFile", "追加失败: 子代理不该能追加", writeCounter),
                of("deletePath", "删除失败: 子代理不该能删", writeCounter),
                of("createDirectory", "创建失败"),
                of("movePath", "移动失败"),
                of("copyPath", "复制失败"),
                of("executeCommand", "执行失败: 子代理不该能跑命令", commandCounter),
                of("createPlan", "（主 agent 才有的规划工具）"),
                of("updatePlanStep", "（主 agent 才有的规划工具）"),
                of("spawnSubagent", "（禁止套娃）", spawnCounter),
                of("saveUserMemory", "（主 agent 才有的用户记忆，子房间不该有）"),
                of("forgetUserMemory", "（主 agent 才有的用户记忆，子房间不该有）"),
                of("mcp__echo__echo", "（主循环的 MCP 工具，子房间第一期不该有）"),
                of("mcp__fs__read_file", "（主循环的 MCP 工具，子房间第一期不该有）"),
                of("mcpToolSearch", "（主循环的 MCP 搜索入口，子房间不该有）")
        };
    }
}
