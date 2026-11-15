package com.example.jingjingai2.agent.reconcile;

import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 业务结果分类：只认明确标记，不把正常返回当成功。 */
class ToolOutcomeClassifierTest {

    @Test
    @DisplayName("executeCommand exit=0 → OK")
    void executeCommandExitZeroIsOk() {
        assertEquals(ToolBusinessOutcome.OK, classify("executeCommand",
                "[TC]{\"tool\":\"executeCommand\",\"result\":\"执行成功 (exit=0):\\nok\"}[/TC]"));
    }

    @Test
    @DisplayName("executeCommand exit=1 → BUSINESS_ERROR")
    void executeCommandExitOneIsBusinessError() {
        assertEquals(ToolBusinessOutcome.BUSINESS_ERROR, classify("executeCommand",
                "[TC]{\"tool\":\"executeCommand\",\"result\":\"执行失败 (exit=1):\\nbad\"}[/TC]"));
    }

    @Test
    @DisplayName("executeCommand 执行出错 → FAILED")
    void executeCommandExceptionIsFailed() {
        assertEquals(ToolBusinessOutcome.FAILED,
                classify("executeCommand", "执行出错: boom"));
    }

    @Test
    @DisplayName("editFile 匹配失败 → BUSINESS_ERROR；成功 → OK")
    void editFileBusinessOutcome() {
        assertEquals(ToolBusinessOutcome.BUSINESS_ERROR,
                classify("editFile", "修改失败: oldStr 在文件中匹配到 0 处（未命中）"));
        assertEquals(ToolBusinessOutcome.OK,
                classify("editFile", "✅ 已修改文件: a.txt"));
    }

    @Test
    @DisplayName("editFile 路径越界失败也识别为 BUSINESS_ERROR")
    void editFilePathBlockedIsBusinessError() {
        assertEquals(ToolBusinessOutcome.BUSINESS_ERROR,
                classify("editFile",
                        "修改失败: 路径越界已拦截: missing.txt（只允许访问工作区 target\\test-ws）"));
    }

    @Test
    @DisplayName("Spring AI 回调多包一层 JSON 引号时仍能识别业务失败")
    void quotedResultIsUnwrapped() {
        assertEquals(ToolBusinessOutcome.BUSINESS_ERROR,
                classify("editFile", "\"修改失败: 路径越界已拦截: missing.txt\""));
        assertEquals(ToolBusinessOutcome.BUSINESS_ERROR,
                classify("executeCommand",
                        "\"[TC]{\\\"tool\\\":\\\"executeCommand\\\",\\\"result\\\":\\\"执行失败 (exit=1)\\\"}[/TC]\""));
    }

    @Test
    @DisplayName("spawnSubagent COMPLETE/PARTIAL/NONE")
    void subagentCompleteness() {
        assertEquals(ToolBusinessOutcome.OK, classify("spawnSubagent", "完整性：COMPLETE"));
        assertEquals(ToolBusinessOutcome.PARTIAL, classify("spawnSubagent", "完整性：PARTIAL"));
        assertEquals(ToolBusinessOutcome.FAILED, classify("spawnSubagent", "完整性：NONE"));
    }

    @Test
    @DisplayName("MCP 普通 EXECUTED 不能直接提升为 OK")
    void mcpExecutedIsUnknown() {
        assertEquals(ToolBusinessOutcome.UNKNOWN,
                classify("mcp__playwright__browser_snapshot", "page content"));
    }

    @Test
    @DisplayName("MCP 明确失败 → FAILED")
    void mcpErrorIsFailed() {
        assertEquals(ToolBusinessOutcome.FAILED,
                classify("mcp__playwright__browser_snapshot", "MCP 工具调用失败: timeout"));
    }

    @Test
    @DisplayName("拒绝/执行失败直接映射")
    void executionStatusMappings() {
        assertEquals(ToolBusinessOutcome.DENIED,
                ToolOutcomeClassifier.classify("editFile", ToolExecutionStatus.Kind.DENIED, "blocked"));
        assertEquals(ToolBusinessOutcome.FAILED,
                ToolOutcomeClassifier.classify("editFile", ToolExecutionStatus.Kind.FAILED, "boom"));
        assertEquals(ToolBusinessOutcome.UNKNOWN,
                ToolOutcomeClassifier.classify("editFile", ToolExecutionStatus.Kind.UNKNOWN, "?"));
    }

    @Test
    @DisplayName("readFile 等没有明确业务标记的工具保持 UNKNOWN")
    void readFileIsUnknown() {
        assertEquals(ToolBusinessOutcome.UNKNOWN,
                classify("readFile", "1\tline content"));
    }

    @Test
    @DisplayName("执行级 EXECUTED 与业务级 UNKNOWN 可以同时存在")
    void executedAndUnknownCoexist() {
        assertEquals(ToolExecutionStatus.Kind.EXECUTED, ToolExecutionStatus.Kind.EXECUTED);
        assertEquals(ToolBusinessOutcome.UNKNOWN,
                ToolOutcomeClassifier.classify("mcp__srv__tool",
                        ToolExecutionStatus.Kind.EXECUTED, "ok"));
    }

    private static ToolBusinessOutcome classify(String tool, String result) {
        return ToolOutcomeClassifier.classify(tool, ToolExecutionStatus.Kind.EXECUTED, result);
    }
}
