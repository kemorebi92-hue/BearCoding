package com.example.jingjingai2.eval;

import java.util.List;

/**
 * 一次未通过运行的归类输入。只含 agent_turn、tool_call_log 和判定器结果。
 */
public record EvalTrace(
        List<Turn> turns,
        List<ToolLog> toolLogs,
        JudgeResult judge,
        List<String> forbiddenTools) {

    public EvalTrace {
        turns = turns == null ? List.of() : List.copyOf(turns);
        toolLogs = toolLogs == null ? List.of() : List.copyOf(toolLogs);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
    }

    public EvalTrace(List<Turn> turns, List<ToolLog> toolLogs, JudgeResult judge) {
        this(turns, toolLogs, judge, List.of());
    }

    public record Turn(String endReason, String compactMode, Integer compactedMessageCount) {}

    public record ToolLog(String toolName, String arguments, String result, boolean success) {}
}
