package com.example.jingjingai2.agent.reconcile;

import com.example.jingjingai2.permission.SensitiveRedactor;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单次请求作用域的工具执行台账。**不持久化、不写数据库、不修改计划状态。**
 *
 * <p>它只记录元数据和脱敏摘要，不复制完整参数/结果。步骤归因是**启发式**的：
 * 只有“当前只有一个活动步骤”时才把工作工具归给它；多活动步骤一律 AMBIGUOUS，
 * 没有 running 报告时一律 UNATTRIBUTED。
 *
 * <p>本类不持有任何静态可变状态；每个请求创建自己的实例，请求结束即可被回收。
 */
public final class StepToolLedger {

    public enum AttributionMode {
        /** 工具参数里明确带 stepId（目前只有 updatePlanStep 本身）。 */
        EXPLICIT,
        /** 单活动步骤下的启发式归因。 */
        HEURISTIC,
        /** 存在多个可能的活动步骤，无法安全归因。 */
        AMBIGUOUS,
        /** 没有足够信息归因。 */
        UNATTRIBUTED,
        /** 某步骤已经 DONE 之后又出现的工作工具调用。 */
        POST_COMPLETION
    }

    public record ToolCall(
            String toolCallId,
            int turnIndex,
            int order,
            String toolName,
            ToolExecutionStatus.Kind executionStatus,
            ToolBusinessOutcome businessOutcome,
            String attributedStepId,
            AttributionMode attributionMode,
            String postCompletionAfterStepId,
            String argumentsSummary,
            String resultSummary) {}

    public record StepReport(
            String toolCallId,
            int turnIndex,
            int order,
            String stepId,
            String status,
            String actualResultSummary,
            boolean parseError) {}

    private static final int SUMMARY_CHARS = 160;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String conversationId;
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final List<StepReport> stepReports = new ArrayList<>();
    /** 已收到 running、尚未收到 done/failed 的步骤。 */
    private final Set<String> activeSteps = new LinkedHashSet<>();
    /** 最近一次 done 的步骤；之后的工具调用标 POST_COMPLETION，直到新的 running。 */
    private String postCompletionAfterStepId;
    private int sequence;

    public StepToolLedger(String conversationId) {
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return conversationId;
    }

    /**
     * 记录一次工具调用。{@code arguments}/{@code result} 只用于分类和生成限长摘要，
     * 不会原样保存。
     */
    public synchronized ToolCall recordTool(String toolCallId, int turnIndex, String toolName,
                                            String arguments, String result,
                                            ToolExecutionStatus.Kind executionStatus) {
        int order = ++sequence;
        String name = toolName == null ? "" : toolName;
        boolean planReport = isUpdatePlanStep(name);

        String stepId = null;
        String status = null;
        String actualResult = null;
        boolean parseError = false;
        if (planReport) {
            JsonNode args = parse(arguments);
            if (args == null) {
                parseError = true;
            } else {
                stepId = text(args, "stepId");
                status = text(args, "status");
                actualResult = text(args, "actualResult");
            }
        }

        Attribution attribution;
        if (planReport) {
            boolean explicit = stepId != null && !stepId.isBlank();
            attribution = new Attribution(explicit ? AttributionMode.EXPLICIT : AttributionMode.UNATTRIBUTED,
                    explicit ? stepId : null, null);
        } else {
            attribution = attributeWorkTool();
        }

        ToolCall call = new ToolCall(
                toolCallId, turnIndex, order, name,
                executionStatus,
                ToolOutcomeClassifier.classify(name, executionStatus, result),
                attribution.stepId, attribution.mode, attribution.postCompletionAfterStepId,
                summary(arguments), summary(result));
        toolCalls.add(call);

        if (planReport) {
            StepReport report = new StepReport(toolCallId, turnIndex, order,
                    stepId, normalizeStatus(status), summary(actualResult), parseError);
            stepReports.add(report);
            apply(report);
        }
        return call;
    }

    public synchronized List<ToolCall> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public synchronized List<StepReport> stepReports() {
        return List.copyOf(stepReports);
    }

    public synchronized Set<String> activeStepIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(activeSteps));
    }

    private Attribution attributeWorkTool() {
        if (postCompletionAfterStepId != null) {
            return new Attribution(AttributionMode.POST_COMPLETION, null, postCompletionAfterStepId);
        }
        if (activeSteps.size() == 1) {
            return new Attribution(AttributionMode.HEURISTIC, activeSteps.iterator().next(), null);
        }
        if (activeSteps.size() > 1) {
            return new Attribution(AttributionMode.AMBIGUOUS, null, null);
        }
        return new Attribution(AttributionMode.UNATTRIBUTED, null, null);
    }

    private void apply(StepReport report) {
        String step = report.stepId();
        if (step == null || step.isBlank()) return;
        String status = report.status();
        if ("running".equals(status)) {
            activeSteps.add(step);
            postCompletionAfterStepId = null;
            return;
        }
        if ("done".equals(status) || "failed".equals(status)) {
            activeSteps.remove(step);
            if ("done".equals(status)) {
                postCompletionAfterStepId = step;
            }
        }
        // 其他状态（unknown）不改变活动集合，避免猜测。
    }

    private static boolean isUpdatePlanStep(String name) {
        return name != null && name.replace("_", "").equalsIgnoreCase("updatePlanStep");
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "unknown";
        String s = status.trim().toLowerCase(Locale.ROOT);
        if ("running".equals(s) || "done".equals(s) || "failed".equals(s)) return s;
        return "unknown";
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 只保留脱敏后的限长摘要；非 JSON 文本只留长度，不落原文。 */
    private static String summary(String raw) {
        String redacted = SensitiveRedactor.redactForLog(raw);
        if (redacted == null || redacted.isEmpty()) return "";
        return redacted.length() <= SUMMARY_CHARS
                ? redacted
                : redacted.substring(0, SUMMARY_CHARS) + "…";
    }

    private record Attribution(AttributionMode mode, String stepId, String postCompletionAfterStepId) {}
}
