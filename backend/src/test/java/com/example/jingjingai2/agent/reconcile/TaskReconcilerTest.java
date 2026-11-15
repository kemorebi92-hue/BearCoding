package com.example.jingjingai2.agent.reconcile;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ledger + 真实 PlanStep 的组件组合对账：告警、保守边界、绝不修改计划。 */
class TaskReconcilerTest {

    @Test
    @DisplayName("真实 FileTool 的缺失文件返回能被识别为 BUSINESS_ERROR")
    void realEditFileFailureIsClassified() {
        com.example.jingjingai2.utils.FileTool fileTool =
                new com.example.jingjingai2.utils.FileTool(
                        new com.example.jingjingai2.sandbox.PathSandbox("target/test-ws"));
        String result = fileTool.editFile("missing-reconcile.txt", "a", "b");

        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        StepToolLedger.ToolCall call = l.recordTool("t1", 0, "editFile",
                "{\"path\":\"missing-reconcile.txt\"}", result, ToolExecutionStatus.Kind.EXECUTED);
        report(l, "r2", 0, "s1", "done", "done");

        assertEquals(ToolBusinessOutcome.BUSINESS_ERROR, call.businessOutcome(), result);
        assertTrue(rules(TaskReconciler.reconcile(l, List.of(step("s1", PlanStep.DONE, "done"))))
                .contains("DONE_WITH_BUSINESS_FAILURE"), result);
    }

    @Test
    @DisplayName("DONE + 明确业务失败且无成功重试 → DONE_WITH_BUSINESS_FAILURE")
    void doneWithBusinessFailureAlerts() {
        PlanStep s = step("s1", PlanStep.DONE, "done");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        work(l, "t1", 0, "editFile", "修改失败: oldStr 在文件中匹配到 0 处（未命中）");
        report(l, "r2", 0, "s1", "done", "done");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertTrue(rules(alerts).contains("DONE_WITH_BUSINESS_FAILURE"), alerts.toString());
    }

    @Test
    @DisplayName("DONE + 业务 UNKNOWN 不产生确定性失败结论")
    void doneWithUnknownDoesNotConcludeFailure() {
        PlanStep s = step("s1", PlanStep.DONE, "done");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        work(l, "t1", 0, "mcp__srv__tool", "page content");
        report(l, "r2", 0, "s1", "done", "done");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertFalse(rules(alerts).contains("DONE_WITH_BUSINESS_FAILURE"), alerts.toString());
    }

    @Test
    @DisplayName("DONE 但 actualResult 为空 → 告警")
    void doneWithoutActualResultAlerts() {
        PlanStep s = step("s1", PlanStep.DONE, "");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        report(l, "r2", 0, "s1", "done", "");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertTrue(rules(alerts).contains("DONE_WITHOUT_ACTUAL_RESULT"), alerts.toString());
    }

    @Test
    @DisplayName("done 之后仍有工作工具调用 → TOOL_AFTER_DONE")
    void toolAfterDoneAlerts() {
        PlanStep s = step("s1", PlanStep.DONE, "done");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        report(l, "r2", 0, "s1", "done", "done");
        work(l, "t1", 0, "executeCommand",
                "[TC]{\"tool\":\"executeCommand\",\"result\":\"执行成功 (exit=0):\\nok\"}[/TC]");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertTrue(rules(alerts).contains("TOOL_AFTER_DONE"), alerts.toString());
    }

    @Test
    @DisplayName("updatePlanStep 指向不存在的步骤 → UNKNOWN_STEP")
    void unknownStepAlerts() {
        PlanStep s = step("s1", PlanStep.RUNNING, "");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s2", "done", "done");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertTrue(rules(alerts).contains("UNKNOWN_STEP"), alerts.toString());
    }

    @Test
    @DisplayName("多活动步骤导致工作工具 AMBIGUOUS → 告警")
    void ambiguousAttributionAlerts() {
        PlanStep s1 = step("s1", PlanStep.RUNNING, "");
        PlanStep s2 = step("s2", PlanStep.RUNNING, "");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        report(l, "r2", 0, "s2", "running", "");
        work(l, "t1", 0, "editFile", "✅ 已修改文件");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s1, s2));
        assertTrue(rules(alerts).contains("AMBIGUOUS_ATTRIBUTION"), alerts.toString());
    }

    @Test
    @DisplayName("工具失败但步骤仍 RUNNING：只诊断，不改任何 PlanStep 字段")
    void runningWithFailedToolIsDiagnosticAndNoMutation() {
        PlanStep s = step("s1", PlanStep.RUNNING, "");
        List<String> before = snapshot(s);
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        work(l, "t1", 0, "editFile", "修改失败: oldStr 在文件中匹配到 0 处（未命中）");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertTrue(rules(alerts).contains("RUNNING_WITH_FAILED_TOOL"), alerts.toString());
        assertEquals(before, snapshot(s), "对账绝不能修改 PlanStep");
    }

    @Test
    @DisplayName("resplit 父步骤 FAILED、直接子步骤全 DONE → RESPLIT_PARENT_STUCK")
    void resplitParentStuck() {
        PlanStep parent = step("p", PlanStep.FAILED, "failed");
        PlanStep c1 = child("c1", "p", PlanStep.DONE);
        PlanStep c2 = child("c2", "p", PlanStep.DONE);
        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(
                new StepToolLedger("conv-1"), List.of(parent, c1, c2));
        assertTrue(rules(alerts).contains("RESPLIT_PARENT_STUCK"), alerts.toString());
    }

    @Test
    @DisplayName("规则 8 按 parentStepId 判断：子步骤未全 DONE 或只有相邻步骤 DONE 都不告警")
    void resplitRuleUsesRealParentRelation() {
        PlanStep parent = step("p", PlanStep.FAILED, "failed");
        PlanStep c1 = child("c1", "p", PlanStep.DONE);
        PlanStep c2 = child("c2", "p", PlanStep.FAILED);
        PlanStep adjacent = step("s9", PlanStep.DONE, "done");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(
                new StepToolLedger("conv-1"), List.of(parent, c1, c2, adjacent));
        assertFalse(rules(alerts).contains("RESPLIT_PARENT_STUCK"), alerts.toString());
    }

    @Test
    @DisplayName("spawnSubagent PARTIAL 但步骤 DONE → DONE_WITH_INCOMPLETE_SUBAGENT")
    void doneWithPartialSubagentAlerts() {
        PlanStep s = step("s1", PlanStep.DONE, "done");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        work(l, "t1", 0, "spawnSubagent", "完整性：PARTIAL");
        report(l, "r2", 0, "s1", "done", "done");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertTrue(rules(alerts).contains("DONE_WITH_INCOMPLETE_SUBAGENT"), alerts.toString());
    }

    @Test
    @DisplayName("同工具失败后重试成功：不产生 DONE_WITH_BUSINESS_FAILURE")
    void retrySuccessDoesNotAlert() {
        PlanStep s = step("s1", PlanStep.DONE, "done");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        work(l, "t1", 0, "executeCommand",
                "[TC]{\"tool\":\"executeCommand\",\"result\":\"执行失败 (exit=1):\\nbad\"}[/TC]");
        work(l, "t2", 0, "executeCommand",
                "[TC]{\"tool\":\"executeCommand\",\"result\":\"执行成功 (exit=0):\\nok\"}[/TC]");
        report(l, "r2", 0, "s1", "done", "done");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertFalse(rules(alerts).contains("DONE_WITH_BUSINESS_FAILURE"), alerts.toString());
    }

    @Test
    @DisplayName("对账前后 PlanStep 所有字段保持一致")
    void noMutationAcrossRules() {
        PlanStep done = step("s1", PlanStep.DONE, "");
        PlanStep running = step("s2", PlanStep.RUNNING, "");
        PlanStep parent = step("p", PlanStep.FAILED, "failed");
        PlanStep c1 = child("c1", "p", PlanStep.DONE);
        PlanStep c2 = child("c2", "p", PlanStep.DONE);
        List<PlanStep> steps = List.of(done, running, parent, c1, c2);
        List<List<String>> before = steps.stream().map(TaskReconcilerTest::snapshot).toList();

        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        work(l, "t1", 0, "editFile", "修改失败: oldStr 在文件中匹配到 0 处（未命中）");
        report(l, "r2", 0, "s1", "done", "");
        report(l, "r3", 0, "s2", "running", "");
        work(l, "t2", 0, "editFile", "修改失败: oldStr 在文件中匹配到 0 处（未命中）");

        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, steps);
        assertFalse(alerts.isEmpty());
        assertEquals(before, steps.stream().map(TaskReconcilerTest::snapshot).toList(),
                "对账绝不能修改 PlanStep");
    }

    @Test
    @DisplayName("告警行统一带 [TaskReconcile] 前缀")
    void alertLinePrefix() {
        PlanStep s = step("s1", PlanStep.DONE, "");
        StepToolLedger l = new StepToolLedger("conv-1");
        report(l, "r1", 0, "s1", "running", "");
        report(l, "r2", 0, "s1", "done", "");
        List<TaskReconciler.Alert> alerts = TaskReconciler.reconcile(l, List.of(s));
        assertFalse(alerts.isEmpty());
        for (TaskReconciler.Alert a : alerts) {
            assertTrue(a.line().startsWith("[TaskReconcile]"), a.line());
        }
    }

    private static PlanStep step(String id, String status, String actual) {
        PlanStep s = new PlanStep(id, "desc-" + id, "expected-" + id);
        s.setStatus(status);
        s.setActualResult(actual);
        return s;
    }

    private static PlanStep child(String id, String parent, String status) {
        PlanStep s = step(id, status, PlanStep.DONE.equals(status) ? "done" : "");
        s.setParentStepId(parent);
        s.setResplitDepth(1);
        return s;
    }

    private static void report(StepToolLedger ledger, String toolCallId, int turn,
                               String stepId, String status, String actual) {
        ledger.recordTool(toolCallId, turn, "updatePlanStep",
                "{\"stepId\":\"" + stepId + "\",\"status\":\"" + status
                        + "\",\"actualResult\":\"" + actual + "\"}",
                "ok", ToolExecutionStatus.Kind.EXECUTED);
    }

    private static StepToolLedger.ToolCall work(StepToolLedger ledger, String toolCallId, int turn,
                                                String toolName, String result) {
        return ledger.recordTool(toolCallId, turn, toolName,
                "{\"path\":\"a.txt\"}", result, ToolExecutionStatus.Kind.EXECUTED);
    }

    private static List<String> rules(List<TaskReconciler.Alert> alerts) {
        List<String> out = new ArrayList<>();
        for (TaskReconciler.Alert a : alerts) out.add(a.rule());
        return out;
    }

    private static List<String> snapshot(PlanStep s) {
        return List.of(
                String.valueOf(s.getStepId()),
                String.valueOf(s.getDescription()),
                String.valueOf(s.getExpected()),
                String.valueOf(s.getActualResult()),
                String.valueOf(s.getStatus()),
                String.valueOf(s.getFailCount()),
                String.valueOf(s.getResplitDepth()),
                String.valueOf(s.getParentStepId()));
    }
}
