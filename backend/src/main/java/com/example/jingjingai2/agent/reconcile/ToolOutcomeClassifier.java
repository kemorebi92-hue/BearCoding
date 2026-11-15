package com.example.jingjingai2.agent.reconcile;

import com.example.jingjingai2.tool.ToolExecutionStatus;

/**
 * 保守的业务结果分类器。**不修改任何工具返回协议**，只对少数工具有明确返回格式时做严格判断。
 *
 * <p>原则：没有结构化结果、或文本不符合明确前缀/标记时，一律 {@link ToolBusinessOutcome#UNKNOWN}；
 * 不把“正常返回”“没有抛异常”“文本非空”当成成功。
 */
public final class ToolOutcomeClassifier {

    private ToolOutcomeClassifier() {}

    public static ToolBusinessOutcome classify(String toolName,
                                               ToolExecutionStatus.Kind executionStatus,
                                               String result) {
        if (executionStatus == ToolExecutionStatus.Kind.DENIED) return ToolBusinessOutcome.DENIED;
        if (executionStatus == ToolExecutionStatus.Kind.FAILED) return ToolBusinessOutcome.FAILED;
        if (executionStatus == ToolExecutionStatus.Kind.NOT_FOUND) return ToolBusinessOutcome.FAILED;
        if (executionStatus != ToolExecutionStatus.Kind.EXECUTED) return ToolBusinessOutcome.UNKNOWN;

        String name = toolName == null ? "" : toolName;
        String text = unquote(result == null ? "" : result);

        if (name.equalsIgnoreCase("executeCommand")) {
            // TerminalTool 把结果包在 [TC]{...}[/TC] 里；只认 result 字段的明确前缀。
            if (text.contains("\"result\":\"执行成功 (exit=0)") || text.contains("执行成功 (exit=0)")) {
                return ToolBusinessOutcome.OK;
            }
            if (text.contains("\"result\":\"执行失败 (exit=") || text.contains("执行失败 (exit=")) {
                return ToolBusinessOutcome.BUSINESS_ERROR;
            }
            if (text.contains("执行出错")) return ToolBusinessOutcome.FAILED;
            return ToolBusinessOutcome.UNKNOWN;
        }

        if (isFileWriteTool(name)) {
            if (text.startsWith("✅")) return ToolBusinessOutcome.OK;
            if (startsWithAny(text, "修改失败", "写入失败", "新建失败", "追加失败",
                    "删除失败", "移动失败", "复制失败", "创建目录失败")) {
                return ToolBusinessOutcome.BUSINESS_ERROR;
            }
            return ToolBusinessOutcome.UNKNOWN;
        }

        if (isSpawnTool(name)) {
            if (text.contains("完整性：COMPLETE")) return ToolBusinessOutcome.OK;
            if (text.contains("完整性：PARTIAL")) return ToolBusinessOutcome.PARTIAL;
            if (text.contains("完整性：NONE")) return ToolBusinessOutcome.FAILED;
            return ToolBusinessOutcome.UNKNOWN;
        }

        if (name.startsWith("mcp__")) {
            if (text.startsWith("MCP 工具调用失败:")) return ToolBusinessOutcome.FAILED;
            // 普通 MCP EXECUTED 不能直接提升成 OK；远端可能返回业务错误。
            return ToolBusinessOutcome.UNKNOWN;
        }

        // readFile / grepFile / listDirectory / updatePlanStep / verifyCompile 等：本阶段不猜。
        return ToolBusinessOutcome.UNKNOWN;
    }

    static boolean isFileWriteTool(String name) {
        return name.equalsIgnoreCase("editFile") || name.equalsIgnoreCase("writeFile")
                || name.equalsIgnoreCase("createFile") || name.equalsIgnoreCase("appendFile")
                || name.equalsIgnoreCase("deletePath") || name.equalsIgnoreCase("movePath")
                || name.equalsIgnoreCase("copyPath") || name.equalsIgnoreCase("createDirectory");
    }

    static boolean isSpawnTool(String name) {
        return name != null && name.replace("_", "").equalsIgnoreCase("spawnSubagent");
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Spring AI 的方法回调可能把字符串返回值再 JSON 编码一次，结果前后各多一个引号。
     * 只去掉成对的外层引号，不解析转义，避免改变工具原文语义。
     */
    static String unquote(String text) {
        if (text == null || text.length() < 2) return text;
        if (text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
