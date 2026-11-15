package com.example.jingjingai2.agent.reconcile;

import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ledger 的记录、归因边界、隐私摘要。 */
class StepToolLedgerTest {

    @Test
    @DisplayName("不同请求的 Ledger 相互独立")
    void differentRequestsHaveIndependentLedgers() {
        StepToolLedger a = new StepToolLedger("conv-a");
        StepToolLedger b = new StepToolLedger("conv-b");
        a.recordTool("c1", 0, "editFile", "{}", "✅ 已修改文件: a", ToolExecutionStatus.Kind.EXECUTED);
        assertTrue(b.toolCalls().isEmpty());
        assertEquals(1, a.toolCalls().size());
    }

    @Test
    @DisplayName("同请求内多工具：toolCallId、turnIndex、执行顺序都保留")
    void recordsMultipleCallsWithToolCallIdTurnAndOrder() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        ledger.recordTool("c1", 0, "readFile", "{}", "content", ToolExecutionStatus.Kind.EXECUTED);
        ledger.recordTool("c2", 1, "editFile", "{}", "✅ 已修改文件", ToolExecutionStatus.Kind.EXECUTED);

        List<StepToolLedger.ToolCall> calls = ledger.toolCalls();
        assertEquals(2, calls.size());
        assertEquals("c1", calls.get(0).toolCallId());
        assertEquals(0, calls.get(0).turnIndex());
        assertEquals(1, calls.get(0).order());
        assertEquals("c2", calls.get(1).toolCallId());
        assertEquals(1, calls.get(1).turnIndex());
        assertEquals(2, calls.get(1).order());
    }

    @Test
    @DisplayName("不保存完整敏感 arguments/result，只留脱敏限长摘要")
    void doesNotStoreFullSensitiveArgumentsOrResult() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        StepToolLedger.ToolCall call = ledger.recordTool("c1", 0, "mcp__srv__call",
                "{\"api_key\":\"super-secret-value\",\"path\":\"a.txt\"}",
                "password=super-secret-value " + "x".repeat(500),
                ToolExecutionStatus.Kind.EXECUTED);

        assertFalse(call.argumentsSummary().contains("super-secret-value"), call.argumentsSummary());
        assertTrue(call.argumentsSummary().contains("***"), call.argumentsSummary());
        assertFalse(call.resultSummary().contains("super-secret-value"), call.resultSummary());
        assertTrue(call.resultSummary().length() <= 161, "摘要必须限长: " + call.resultSummary().length());
    }

    @Test
    @DisplayName("单活动步骤：running 之后的工作工具标记 HEURISTIC")
    void singleActiveStepHeuristicAttribution() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        report(ledger, "r1", 0, "s1", "running", "");
        StepToolLedger.ToolCall call = work(ledger, "t1", 0, "editFile", "✅ 已修改文件");

        assertEquals("s1", call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.HEURISTIC, call.attributionMode());
    }

    @Test
    @DisplayName("没有 running 报告：工作工具保持 UNATTRIBUTED")
    void noRunningMeansUnattributed() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        StepToolLedger.ToolCall call = work(ledger, "t1", 0, "editFile", "✅ 已修改文件");
        assertNull(call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.UNATTRIBUTED, call.attributionMode());
    }

    @Test
    @DisplayName("多活动步骤：工作工具标记 AMBIGUOUS，不归入任何一步")
    void multipleActiveStepsAmbiguous() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        report(ledger, "r1", 0, "s1", "running", "");
        report(ledger, "r2", 0, "s2", "running", "");
        StepToolLedger.ToolCall call = work(ledger, "t1", 0, "editFile", "✅ 已修改文件");

        assertNull(call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.AMBIGUOUS, call.attributionMode());
    }

    @Test
    @DisplayName("DONE 之后的工作工具标记 POST_COMPLETION，不归入其他活动步骤")
    void doneThenToolIsPostCompletion() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        report(ledger, "r1", 0, "s1", "running", "");
        report(ledger, "r2", 0, "s1", "done", "done");
        StepToolLedger.ToolCall call = work(ledger, "t1", 0, "editFile", "✅ 已修改文件");

        assertNull(call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.POST_COMPLETION, call.attributionMode());
        assertEquals("s1", call.postCompletionAfterStepId());
    }

    @Test
    @DisplayName("DONE 之后即使还有另一个活动步骤，也不自动归入它")
    void doneThenToolDoesNotFallIntoOtherActiveStep() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        report(ledger, "r1", 0, "s1", "running", "");
        report(ledger, "r2", 0, "s2", "running", "");
        report(ledger, "r3", 0, "s1", "done", "done");
        StepToolLedger.ToolCall call = work(ledger, "t1", 0, "editFile", "✅ 已修改文件");

        assertNull(call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.POST_COMPLETION, call.attributionMode());
        assertEquals("s1", call.postCompletionAfterStepId());
    }

    @Test
    @DisplayName("updatePlanStep 自身是 EXPLICIT，并生成 StepReport")
    void updatePlanStepIsExplicitAndCreatesReport() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        StepToolLedger.ToolCall call = report(ledger, "r1", 0, "s1", "running", "");

        assertEquals("s1", call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.EXPLICIT, call.attributionMode());
        assertEquals(1, ledger.stepReports().size());
        assertEquals("s1", ledger.stepReports().get(0).stepId());
        assertEquals("running", ledger.stepReports().get(0).status());
    }

    @Test
    @DisplayName("updatePlanStep 参数解析失败：StepReport 标记 parseError，不猜步骤")
    void unparseableStepReportIsMarked() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        StepToolLedger.ToolCall call = ledger.recordTool("r1", 0, "updatePlanStep",
                "not-json", "ok", ToolExecutionStatus.Kind.EXECUTED);

        assertNull(call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.UNATTRIBUTED, call.attributionMode());
        assertEquals(1, ledger.stepReports().size());
        assertTrue(ledger.stepReports().get(0).parseError());
    }

    @Test
    @DisplayName("FAILED 关闭步骤：之后的工作工具不再归入该步骤")
    void failedClosesStep() {
        StepToolLedger ledger = new StepToolLedger("conv-1");
        report(ledger, "r1", 0, "s1", "running", "");
        report(ledger, "r2", 0, "s1", "failed", "bad");
        StepToolLedger.ToolCall call = work(ledger, "t1", 0, "editFile", "✅ 已修改文件");

        assertNull(call.attributedStepId());
        assertEquals(StepToolLedger.AttributionMode.UNATTRIBUTED, call.attributionMode());
    }

    private static StepToolLedger.ToolCall report(StepToolLedger ledger, String toolCallId, int turn,
                                                   String stepId, String status, String actual) {
        return ledger.recordTool(toolCallId, turn, "updatePlanStep",
                "{\"stepId\":\"" + stepId + "\",\"status\":\"" + status
                        + "\",\"actualResult\":\"" + actual + "\"}",
                "ok", ToolExecutionStatus.Kind.EXECUTED);
    }

    private static StepToolLedger.ToolCall work(StepToolLedger ledger, String toolCallId, int turn,
                                                String toolName, String result) {
        return ledger.recordTool(toolCallId, turn, toolName,
                "{\"path\":\"a.txt\"}", result, ToolExecutionStatus.Kind.EXECUTED);
    }
}
