package com.example.jingjingai2.agent.reconcile;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.tool.ToolExecutionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求结束时的**只读**对账器。只读 {@link StepToolLedger} 和 {@link PlanStep}，
 * 不调用任何修改计划状态的方法，不写数据库。
 *
 * <p>告警只用于诊断；归因是启发式的，规则里会明确区分“确定矛盾”和“关联不确定”。
 */
public final class TaskReconciler {

    public record Alert(String conversationId, Integer turn, String stepId, String rule,
                        String attributionMode, String toolName, String executionStatus,
                        String businessOutcome, String detail) {

        public String line() {
            return "[TaskReconcile] conversation=" + safe(conversationId)
                    + " turn=" + (turn == null ? "-" : turn)
                    + " step=" + safe(stepId)
                    + " rule=" + safe(rule)
                    + " attributionMode=" + safe(attributionMode)
                    + " toolName=" + safe(toolName)
                    + " executionStatus=" + safe(executionStatus)
                    + " businessOutcome=" + safe(businessOutcome)
                    + " detail=" + safe(detail);
        }

        private static String safe(String s) {
            if (s == null || s.isBlank()) return "-";
            return s.replace('\n', ' ').replace('\r', ' ');
        }
    }

    private TaskReconciler() {}

    public static List<Alert> reconcile(StepToolLedger ledger, List<PlanStep> steps) {
        List<Alert> alerts = new ArrayList<>();
        if (ledger == null || steps == null || steps.isEmpty()) return alerts;

        Map<String, PlanStep> byId = new LinkedHashMap<>();
        for (PlanStep step : steps) {
            if (step != null && step.getStepId() != null) byId.put(step.getStepId(), step);
        }
        List<StepToolLedger.ToolCall> calls = ledger.toolCalls();
        List<StepToolLedger.StepReport> reports = ledger.stepReports();

        // 规则 4：updatePlanStep 指向不存在的步骤；解析失败只记诊断。
        for (StepToolLedger.StepReport report : reports) {
            if (report.parseError()) {
                alerts.add(new Alert(ledger.conversationId(), report.turnIndex(), report.stepId(),
                        "UNPARSEABLE_STEP_REPORT", "UNATTRIBUTED", "updatePlanStep", null, null,
                        "updatePlanStep arguments could not be parsed"));
            } else if (report.stepId() != null && !report.stepId().isBlank()
                    && !byId.containsKey(report.stepId())) {
                alerts.add(new Alert(ledger.conversationId(), report.turnIndex(), report.stepId(),
                        "UNKNOWN_STEP", "EXPLICIT", "updatePlanStep", null, null,
                        "stepId not found in current plan"));
            }
        }

        // 规则 5：多活动步骤导致的工作工具归因歧义。
        List<StepToolLedger.ToolCall> ambiguous = calls.stream()
                .filter(c -> c.attributionMode() == StepToolLedger.AttributionMode.AMBIGUOUS)
                .toList();
        if (!ambiguous.isEmpty()) {
            StepToolLedger.ToolCall first = ambiguous.get(0);
            alerts.add(new Alert(ledger.conversationId(), first.turnIndex(), null,
                    "AMBIGUOUS_ATTRIBUTION", "AMBIGUOUS", first.toolName(),
                    kind(first.executionStatus()), outcome(first.businessOutcome()),
                    ambiguous.size() + " tool call(s) could not be attributed to a single active step"));
        }

        for (PlanStep step : steps) {
            if (step == null || step.getStepId() == null) continue;
            String stepId = step.getStepId();

            if (PlanStep.DONE.equals(step.getStatus())) {
                // 规则 1：DONE 但 actualResult 为空。
                if (step.getActualResult() == null || step.getActualResult().isBlank()) {
                    Integer turn = lastReportTurn(reports, stepId);
                    alerts.add(new Alert(ledger.conversationId(), turn, stepId,
                            "DONE_WITHOUT_ACTUAL_RESULT", "EXPLICIT", null, null, null,
                            "step marked DONE but actualResult is blank"));
                }
                // 规则 2：明确归因的业务失败没有被后续同工具成功覆盖。
                for (StepToolLedger.ToolCall call : calls) {
                    if (!stepId.equals(call.attributedStepId())) continue;
                    if (call.attributionMode() != StepToolLedger.AttributionMode.HEURISTIC) continue;
                    if (isPlanTool(call.toolName())) continue;
                    if (!isFailure(call.businessOutcome())) continue;
                    boolean covered = calls.stream().anyMatch(later ->
                            later.order() > call.order()
                                    && later.attributedStepId() != null
                                    && later.attributedStepId().equals(stepId)
                                    && later.toolName().equals(call.toolName())
                                    && later.businessOutcome() == ToolBusinessOutcome.OK);
                    if (!covered) {
                        alerts.add(new Alert(ledger.conversationId(), call.turnIndex(), stepId,
                                "DONE_WITH_BUSINESS_FAILURE", "HEURISTIC", call.toolName(),
                                kind(call.executionStatus()), outcome(call.businessOutcome()),
                                "business failure without later " + call.toolName() + " OK"));
                    }
                }
                // 规则 7：最后一次归因的子代理是 PARTIAL/NONE，但步骤仍被报 DONE。
                StepToolLedger.ToolCall lastSpawn = calls.stream()
                        .filter(c -> stepId.equals(c.attributedStepId()))
                        .filter(c -> c.attributionMode() == StepToolLedger.AttributionMode.HEURISTIC)
                        .filter(c -> isSpawnTool(c.toolName()))
                        .reduce((a, b) -> b).orElse(null);
                if (lastSpawn != null
                        && (lastSpawn.businessOutcome() == ToolBusinessOutcome.PARTIAL
                        || lastSpawn.businessOutcome() == ToolBusinessOutcome.FAILED)) {
                    alerts.add(new Alert(ledger.conversationId(), lastSpawn.turnIndex(), stepId,
                            "DONE_WITH_INCOMPLETE_SUBAGENT", "HEURISTIC", lastSpawn.toolName(),
                            kind(lastSpawn.executionStatus()), outcome(lastSpawn.businessOutcome()),
                            "step marked DONE but last attributed subagent was not COMPLETE"));
                }
            } else if (PlanStep.RUNNING.equals(step.getStatus())) {
                // 规则 6：工具明确失败，但步骤仍 RUNNING。只诊断，不改状态。
                boolean failedReport = reports.stream()
                        .anyMatch(r -> stepId.equals(r.stepId()) && "failed".equals(r.status()));
                if (!failedReport) {
                    for (StepToolLedger.ToolCall call : calls) {
                        if (!stepId.equals(call.attributedStepId())) continue;
                        if (call.attributionMode() != StepToolLedger.AttributionMode.HEURISTIC) continue;
                        if (!isFailure(call.businessOutcome())) continue;
                        boolean covered = calls.stream().anyMatch(later ->
                                later.order() > call.order()
                                        && later.attributedStepId() != null
                                        && later.attributedStepId().equals(stepId)
                                        && later.toolName().equals(call.toolName())
                                        && later.businessOutcome() == ToolBusinessOutcome.OK);
                        if (!covered) {
                            alerts.add(new Alert(ledger.conversationId(), call.turnIndex(), stepId,
                                    "RUNNING_WITH_FAILED_TOOL", "HEURISTIC", call.toolName(),
                                    kind(call.executionStatus()), outcome(call.businessOutcome()),
                                    "tool failed but step is still RUNNING (diagnostic only)"));
                        }
                    }
                }
            } else if (PlanStep.FAILED.equals(step.getStatus())) {
                // 规则 8：父步骤 FAILED，但所有直接子步骤 DONE。按 parentStepId 判断，不看数组相邻。
                List<PlanStep> children = steps.stream()
                        .filter(c -> c != null && stepId.equals(c.getParentStepId()))
                        .toList();
                if (!children.isEmpty()
                        && children.stream().allMatch(c -> PlanStep.DONE.equals(c.getStatus()))) {
                    Integer turn = lastReportTurn(reports, stepId);
                    alerts.add(new Alert(ledger.conversationId(), turn, stepId,
                            "RESPLIT_PARENT_STUCK", "EXPLICIT", null, null, null,
                            "all " + children.size() + " children DONE but parent still FAILED"));
                }
            }
        }

        // 规则 3：done 之后同一请求仍有工作工具调用。
        for (StepToolLedger.StepReport report : reports) {
            if (!"done".equals(report.status())) continue;
            if (report.stepId() == null || report.stepId().isBlank()) continue;
            for (StepToolLedger.ToolCall call : calls) {
                if (call.order() <= report.order()) continue;
                if (isPlanTool(call.toolName())) continue;
                if (!report.stepId().equals(call.postCompletionAfterStepId())) continue;
                alerts.add(new Alert(ledger.conversationId(), call.turnIndex(), report.stepId(),
                        "TOOL_AFTER_DONE", "POST_COMPLETION", call.toolName(),
                        kind(call.executionStatus()), outcome(call.businessOutcome()),
                        "work tool executed after the step was reported DONE; "
                                + "this is an ordering anomaly, not proof that the tool belongs to the step"));
            }
        }

        return alerts;
    }

    private static Integer lastReportTurn(List<StepToolLedger.StepReport> reports, String stepId) {
        for (int i = reports.size() - 1; i >= 0; i--) {
            if (stepId.equals(reports.get(i).stepId())) return reports.get(i).turnIndex();
        }
        return null;
    }

    private static boolean isFailure(ToolBusinessOutcome outcome) {
        return outcome == ToolBusinessOutcome.BUSINESS_ERROR
                || outcome == ToolBusinessOutcome.FAILED
                || outcome == ToolBusinessOutcome.DENIED;
    }

    private static boolean isPlanTool(String name) {
        return name != null && name.replace("_", "").equalsIgnoreCase("updatePlanStep");
    }

    private static boolean isSpawnTool(String name) {
        return name != null && name.replace("_", "").equalsIgnoreCase("spawnSubagent");
    }

    private static String kind(ToolExecutionStatus.Kind kind) {
        return kind == null ? null : kind.name();
    }

    private static String outcome(ToolBusinessOutcome outcome) {
        return outcome == null ? null : outcome.name();
    }
}
