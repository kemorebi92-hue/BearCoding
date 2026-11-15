package com.example.jingjingai2.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 失败归类。只对判定未通过的运行调用。命中第一条就返回，否则 {@link FailureKind#UNCLASSIFIED}。
 *
 * <p>选错工具只认该任务声明的禁用工具。没声明时不能从工具名猜，仍是 UNCLASSIFIED。
 */
public final class FailureClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FailureClassifier() {}

    public static FailureKind classify(EvalTrace trace) {
        if (trace.judge() == null || trace.judge().passed()) {
            throw new IllegalArgumentException("只对未通过的运行归类");
        }
        // 撞上轮数上限：某一轮 end_reason 为 MAX_ITERATIONS。
        if (trace.turns().stream().anyMatch(t -> "MAX_ITERATIONS".equals(t.endReason()))) {
            return FailureKind.HIT_ITERATION_CAP;
        }
        // 权限或沙箱拒绝：工具结果含沙箱/越界/工作区根取不到的原文。
        if (trace.toolLogs().stream().anyMatch(FailureClassifier::sandboxRejected)) {
            return FailureKind.SANDBOX_OR_PERMISSION;
        }
        // 编辑对不上原文：editFile 返回「匹配到 0 处」或「不唯一」，文件未改。
        if (trace.toolLogs().stream().anyMatch(t -> t.result() != null
                && t.result().contains("修改失败: oldStr 在文件中匹配到"))) {
            return FailureKind.EDIT_MISMATCH;
        }
        // 参数不合法：这次调用失败，且 arguments 不是一个 JSON 对象。
        if (trace.toolLogs().stream().anyMatch(FailureClassifier::illegalArgs)) {
            return FailureKind.ILLEGAL_ARGS;
        }
        // 选错工具：调用了该任务声明的禁用工具。没声明的任务不会走到这里。
        if (calledForbidden(trace)) {
            return FailureKind.WRONG_TOOL;
        }
        // 编译失败后修不好：javac 或 mvn 的命令期望没有通过。
        if (trace.judge().items().stream().anyMatch(item -> !item.passed()
                && "commandOutputContains".equals(item.check().type())
                && isCompileCommand(item.check().command()))) {
            return FailureKind.COMPILE_UNFIXED;
        }
        // 压缩后约束不见了：有一轮 SUMMARY（或压过消息），且「哈希不变」没过。
        boolean compacted = trace.turns().stream().anyMatch(t -> "SUMMARY".equals(t.compactMode())
                || (t.compactedMessageCount() != null && t.compactedMessageCount() > 0));
        if (compacted && trace.judge().items().stream().anyMatch(item -> !item.passed()
                && "fileHashUnchanged".equals(item.check().type()))) {
            return FailureKind.CONSTRAINT_LOST_AFTER_COMPACT;
        }
        // 计划步骤没过，按有没有建计划分成两种，次序不能颠倒：
        // NO_PLAN_CREATED：期望里有 planStepDone 且没通过，但这轮 tool_call_log 里没有 createPlan。
        //   计划根本没建。2026-10-17 的基线就是这种情况，当时还没有这个分支，被标成了 PLAN_INCOMPLETE。
        // PLAN_INCOMPLETE：计划已经建了（有 createPlan），要求的步骤仍没过，且最后一轮是 NORMAL_STOP。
        //   建过但没做完、模型自己停了。最后一轮不是正常停止（比如轮数用满）时不归这里。
        boolean planMissed = trace.judge().items().stream().anyMatch(item -> !item.passed()
                && "planStepDone".equals(item.check().type()));
        boolean createdPlan = trace.toolLogs().stream().anyMatch(t ->
                t.toolName() != null && "createPlan".equalsIgnoreCase(t.toolName()));
        if (planMissed && !createdPlan) {
            return FailureKind.NO_PLAN_CREATED;
        }
        String lastEnd = trace.turns().isEmpty()
                ? null
                : trace.turns().get(trace.turns().size() - 1).endReason();
        if (planMissed && "NORMAL_STOP".equals(lastEnd)) {
            return FailureKind.PLAN_INCOMPLETE;
        }
        return FailureKind.UNCLASSIFIED;
    }

    private static boolean sandboxRejected(EvalTrace.ToolLog log) {
        String result = log.result();
        if (result == null) return false;
        return result.contains("路径越界已拦截")
                || result.contains("命令已被沙箱拦截")
                || result.contains("沙箱未就绪")
                || result.contains("执行已拒绝：没有会话或工作区根");
    }

    private static boolean illegalArgs(EvalTrace.ToolLog log) {
        if (log.success()) return false;
        String args = log.arguments();
        if (args == null || args.isBlank()) return false;
        try {
            JSON.readTree(args);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean calledForbidden(EvalTrace trace) {
        for (String forbidden : trace.forbiddenTools()) {
            for (EvalTrace.ToolLog log : trace.toolLogs()) {
                if (log.toolName() != null && log.toolName().equalsIgnoreCase(forbidden)) return true;
            }
        }
        return false;
    }

    private static boolean isCompileCommand(String command) {
        if (command == null) return false;
        String c = command.toLowerCase();
        return c.contains("javac") || c.contains("mvn ");
    }
}
