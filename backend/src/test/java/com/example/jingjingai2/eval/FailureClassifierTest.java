package com.example.jingjingai2.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailureClassifierTest {

    private static Check check(String type, String command) {
        return new Check(type, "a.txt", "x", command, "s3", "abc");
    }

    private static JudgeResult failed(Check... checks) {
        return new JudgeResult(false, List.of(
                new JudgeResult.Item(checks[0], false, "no")));
    }

    private static EvalTrace trace(List<EvalTrace.Turn> turns, List<EvalTrace.ToolLog> logs, Check failedCheck) {
        return new EvalTrace(turns, logs, failed(failedCheck));
    }

    @Test
    @DisplayName("撞上轮数上限")
    void iterationCap() {
        EvalTrace trace = trace(
                List.of(new EvalTrace.Turn("MAX_ITERATIONS", "NONE", 0)),
                List.of(),
                check("fileContains", null));
        assertEquals(FailureKind.HIT_ITERATION_CAP, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("沙箱拒绝")
    void sandbox() {
        EvalTrace trace = trace(List.of(),
                List.of(new EvalTrace.ToolLog("readFile", "{}", "路径越界已拦截: ../x", false)),
                check("fileContains", null));
        assertEquals(FailureKind.SANDBOX_OR_PERMISSION, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("编辑对不上原文")
    void editMismatch() {
        EvalTrace trace = trace(List.of(),
                List.of(new EvalTrace.ToolLog("editFile", "{}",
                        "修改失败: oldStr 在文件中匹配到 0 处（未命中），未做任何修改。", false)),
                check("fileContains", null));
        assertEquals(FailureKind.EDIT_MISMATCH, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("参数不是合法 JSON")
    void illegalArgs() {
        EvalTrace trace = trace(List.of(),
                List.of(new EvalTrace.ToolLog("editFile", "{", "工具执行异常", false)),
                check("fileContains", null));
        assertEquals(FailureKind.ILLEGAL_ARGS, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("D-02 修复后：被拒绝的调用 success=false，但参数是合法 JSON → 不误判成参数不合法")
    void deniedCallIsNotIllegalArgs() {
        // 修复前这类行是 success=true（假成功）；修复后变 false。分类器仍必须看参数本身，
        // 不能因为 success=false 就把"权限拒绝"归成 ILLEGAL_ARGS。
        EvalTrace trace = trace(List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("writeFile",
                        "{\"path\":\"a.txt\",\"content\":\"v\"}",
                        "用户拒绝了该操作，请不要原样重试，向用户说明或改用其他方案。", false)),
                check("fileContains", null));
        assertEquals(FailureKind.UNCLASSIFIED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("D-02 修复后：白名单拒绝的调用同样不因 success=false 被判成参数不合法")
    void whitelistDeniedCallIsNotIllegalArgs() {
        EvalTrace trace = trace(List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("executeCommand", "{\"command\":\"echo hi\"}",
                        "工具 executeCommand 本轮不可用（它不在本轮下发的工具清单里），已拒绝执行。", false)),
                check("fileContains", null));
        assertEquals(FailureKind.UNCLASSIFIED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("编译期望没过")
    void compileUnfixed() {
        Check javac = check("commandOutputContains", "javac -encoding UTF-8 src/App.java && echo COMPILE_OK");
        EvalTrace trace = new EvalTrace(List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(), failed(javac));
        assertEquals(FailureKind.COMPILE_UNFIXED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("压缩之后受保护文件的哈希变了")
    void constraintLost() {
        Check hash = check("fileHashUnchanged", null);
        EvalTrace trace = new EvalTrace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "SUMMARY", 17)),
                List.of(),
                failed(hash));
        assertEquals(FailureKind.CONSTRAINT_LOST_AFTER_COMPACT, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("没调 createPlan，期望的计划步骤不存在")
    void noPlanCreated() {
        Check step = check("planStepDone", null);
        EvalTrace trace = new EvalTrace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("createFile", "{}", "已写入", true)),
                failed(step));
        assertEquals(FailureKind.NO_PLAN_CREATED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("计划已创建，步骤没做完，模型自己停了")
    void planIncomplete() {
        Check step = check("planStepDone", null);
        EvalTrace trace = new EvalTrace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("createPlan", "{\"goal\":\"x\"}", "已创建", true)),
                failed(step));
        assertEquals(FailureKind.PLAN_INCOMPLETE, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("计划步骤已通过时，不归没建计划或计划未完成")
    void finishedPlanIsNeither() {
        Check step = check("planStepDone", null);
        Check file = check("fileContains", null);
        JudgeResult judged = new JudgeResult(false, List.of(
                new JudgeResult.Item(step, true, "ok"),
                new JudgeResult.Item(file, false, "no")));
        EvalTrace trace = new EvalTrace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("createPlan", "{\"goal\":\"x\"}", "已创建", true)),
                judged);
        assertEquals(FailureKind.UNCLASSIFIED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("调用了该任务声明的禁用工具")
    void forbiddenToolIsWrongTool() {
        Check hash = check("fileHashUnchanged", null);
        EvalTrace trace = new EvalTrace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("createPlan", "{\"goal\":\"x\"}", "已创建", true)),
                failed(hash),
                List.of("createPlan"));
        assertEquals(FailureKind.WRONG_TOOL, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("选错工具：没有声明禁用工具时，归为 UNCLASSIFIED")
    void wrongToolCannotBeDecided() {
        EvalTrace trace = trace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(new EvalTrace.ToolLog("deletePath", "{\"path\":\"a.txt\"}", "已删除", true)),
                check("fileContains", null));
        assertEquals(FailureKind.UNCLASSIFIED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("什么规则都没撞上")
    void unclassified() {
        EvalTrace trace = trace(
                List.of(new EvalTrace.Turn("NORMAL_STOP", "NONE", 0)),
                List.of(),
                check("fileContains", null));
        assertEquals(FailureKind.UNCLASSIFIED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("基线 T05-1 没建计划，当前代码归为 NO_PLAN_CREATED")
    void baselineT05IsNoPlanCreated() throws Exception {
        com.fasterxml.jackson.databind.JsonNode node;
        try (InputStream in = getClass().getResourceAsStream("/eval/t05-1-min.json")) {
            node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
        }
        List<EvalTrace.Turn> turns = new java.util.ArrayList<>();
        node.get("turns").forEach(t -> turns.add(new EvalTrace.Turn(t.get("endReason").asText(), "NONE", 0)));
        List<EvalTrace.ToolLog> logs = new java.util.ArrayList<>();
        node.get("toolNames").forEach(t -> logs.add(new EvalTrace.ToolLog(t.asText(), "{}", "ok", true)));
        EvalTrace trace = new EvalTrace(turns, logs, failed(check("planStepDone", null)));
        assertEquals(FailureKind.NO_PLAN_CREATED, FailureClassifier.classify(trace));
    }

    @Test
    @DisplayName("通过的运行不归类")
    void passedRunIsNotClassified() {
        EvalTrace trace = new EvalTrace(List.of(), List.of(), new JudgeResult(true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> FailureClassifier.classify(trace));
    }
}
