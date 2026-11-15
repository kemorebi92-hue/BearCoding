package com.example.jingjingai2.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 子代理跑完交回的东西。
 *
 * 关键设计：**交回的是坐标与结论，不是子房间里的原文**。
 * 主 agent 看不到子代理读过的文件正文 —— 这是隔离省 token 的全部意义。
 *
 * {@code ok / truncated / cancelled} 三个布尔是给**人**看的；
 * {@link FailureReason} 是给**主 agent 和日志**用的：它要据此决定
 * 「再派 / 换个目标 / 自己做 / 标 failed」，靠解析一段中文不可靠。
 * 这和计划层的 {@code TaskPlanService.Decision}（DONE/FAILED/RESPLIT/BLOCK）是同一哲学。
 *
 * 第二轮改造加了三样：{@link #completeness()}（交出来的东西能不能用）、
 * {@link #report()}（结构化：结论/依据/缺口/下一步）、{@link #resumeId()}（能不能接着跑）。
 */
public final class SubagentResult {

    /**
     * 停因。**六个值，不再加。**
     *
     * 刻意不设 {@code GUARD_REJECT} 当整趟停因：单次 ToolGuard 拒绝只是该轮工具结果里的一句话，
     * 子循环继续跑（和主循环现在的行为一致）；把它升级成"整趟失败"是过拟合。
     */
    public enum FailureReason {
        /** 模型自然收尾 */
        SUCCESS,
        /** 轮数用尽（truncated=true）：**结论可能是 PARTIAL 的**，先看完整性再决定要不要续跑 */
        ROUNDS_EXHAUSTED,
        /** 同一个工具连续失败 3 次：不要原样再派，换路径 / 换工具 / 自己做 */
        TOOL_FAILURES,
        /** 用户点了停止：停，不要重试 */
        CANCELLED,
        /** 同一 goal 第 4 次派出，根本没进房间：自己做或标 failed */
        RETRY_LIMIT,
        /** 池拒绝（并发满 / 第二个 implement 在跑 / 功能关闭）：主 agent 稍后再派或自己做 */
        REJECTED
    }

    private final String goal;
    private final SubagentSpec.Mode mode;
    private final FailureReason failureReason;
    private final boolean truncated;
    private final boolean cancelled;
    private final String summary;
    private final int roundsUsed;
    private final int maxRounds;
    private final int toolCalls;
    private final Set<String> filesRead;
    /** 让主 agent 一眼看到"卡在哪"（连失败的工具名 / 最后一次报错） */
    private final String detail;
    // ===== 第二轮新增 =====
    private final SubagentReport report;
    private final SubagentUsage usage;
    /** 是否走了收尾轮（最后一轮不给工具、强制出报告） */
    private final boolean wrapUp;
    /** 这一趟是不是**续跑**（接着上次保留的历史跑） */
    private final boolean resumed;
    /** 可续跑时给主 agent 的凭据；null/空 = 不可续跑 */
    private final String resumeId;
    /** 子代理的固定开销估算（system + 工具定义），用来算"派一次最少要花多少" */
    private final int fixedOverheadTokens;
    /** 续跑方式的说明（正常续跑 / 降级为新房间 + 注入摘要），如实写进工具结果 */
    private final String resumeNote;
    /** general-purpose 写子代理的完工单；explore / 关开关时为 null */
    private final HandoffResult handoff;
    /** 本趟实际发出的 executeCommand（落盘/日志用；与核对共用同一份原始数据） */
    private final List<HandoffResult.ExecutedCommand> executedCommands;

    private SubagentResult(String goal, SubagentSpec.Mode mode, FailureReason reason,
                           boolean truncated, boolean cancelled, String summary,
                           int roundsUsed, int maxRounds, int toolCalls,
                           Set<String> filesRead, String detail,
                           SubagentReport report, SubagentUsage usage,
                           boolean wrapUp, boolean resumed, String resumeId, int fixedOverheadTokens) {
        this(goal, mode, reason, truncated, cancelled, summary, roundsUsed, maxRounds, toolCalls,
                filesRead, detail, report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens, "", null);
    }

    private SubagentResult(String goal, SubagentSpec.Mode mode, FailureReason reason,
                           boolean truncated, boolean cancelled, String summary,
                           int roundsUsed, int maxRounds, int toolCalls,
                           Set<String> filesRead, String detail,
                           SubagentReport report, SubagentUsage usage,
                           boolean wrapUp, boolean resumed, String resumeId, int fixedOverheadTokens,
                           String resumeNote) {
        this(goal, mode, reason, truncated, cancelled, summary, roundsUsed, maxRounds, toolCalls,
                filesRead, detail, report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens,
                resumeNote, null);
    }

    private SubagentResult(String goal, SubagentSpec.Mode mode, FailureReason reason,
                           boolean truncated, boolean cancelled, String summary,
                           int roundsUsed, int maxRounds, int toolCalls,
                           Set<String> filesRead, String detail,
                           SubagentReport report, SubagentUsage usage,
                           boolean wrapUp, boolean resumed, String resumeId, int fixedOverheadTokens,
                           String resumeNote, HandoffResult handoff) {
        this(goal, mode, reason, truncated, cancelled, summary, roundsUsed, maxRounds, toolCalls,
                filesRead, detail, report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens,
                resumeNote, handoff, List.of());
    }

    private SubagentResult(String goal, SubagentSpec.Mode mode, FailureReason reason,
                           boolean truncated, boolean cancelled, String summary,
                           int roundsUsed, int maxRounds, int toolCalls,
                           Set<String> filesRead, String detail,
                           SubagentReport report, SubagentUsage usage,
                           boolean wrapUp, boolean resumed, String resumeId, int fixedOverheadTokens,
                           String resumeNote, HandoffResult handoff,
                           List<HandoffResult.ExecutedCommand> executedCommands) {
        this.goal = goal == null ? "" : goal;
        this.mode = mode == null ? SubagentSpec.Mode.EXPLORE : mode;
        this.failureReason = reason == null ? FailureReason.SUCCESS : reason;
        this.truncated = truncated;
        this.cancelled = cancelled;
        this.summary = summary == null ? "" : summary;
        this.roundsUsed = roundsUsed;
        this.maxRounds = maxRounds;
        this.toolCalls = toolCalls;
        this.filesRead = filesRead == null ? new LinkedHashSet<>() : new LinkedHashSet<>(filesRead);
        this.detail = detail == null ? "" : detail;
        this.report = report;
        this.usage = usage;
        this.wrapUp = wrapUp;
        this.resumed = resumed;
        this.resumeId = resumeId == null ? "" : resumeId;
        this.fixedOverheadTokens = fixedOverheadTokens;
        this.resumeNote = resumeNote == null ? "" : resumeNote;
        this.handoff = handoff;
        this.executedCommands = executedCommands == null || executedCommands.isEmpty()
                ? List.of() : List.copyOf(executedCommands);
    }

    // ==================== 工厂：一种停因一个入口，避免布尔组合拼错 ====================

    public static SubagentResult success(String goal, SubagentSpec.Mode mode, String summary,
                                         int roundsUsed, int maxRounds, int toolCalls, Set<String> filesRead) {
        return new SubagentResult(goal, mode, FailureReason.SUCCESS, false, false,
                summary, roundsUsed, maxRounds, toolCalls, filesRead, "",
                null, null, false, false, null, 0);
    }

    public static SubagentResult roundsExhausted(String goal, SubagentSpec.Mode mode, String summary,
                                                 int roundsUsed, int maxRounds, int toolCalls,
                                                 Set<String> filesRead, String detail) {
        return new SubagentResult(goal, mode, FailureReason.ROUNDS_EXHAUSTED, true, false,
                summary, roundsUsed, maxRounds, toolCalls, filesRead, detail,
                null, null, false, false, null, 0);
    }

    public static SubagentResult toolFailures(String goal, SubagentSpec.Mode mode, String summary,
                                              int roundsUsed, int maxRounds, int toolCalls,
                                              Set<String> filesRead, String detail) {
        return new SubagentResult(goal, mode, FailureReason.TOOL_FAILURES, false, false,
                summary, roundsUsed, maxRounds, toolCalls, filesRead, detail,
                null, null, false, false, null, 0);
    }

    public static SubagentResult cancelled(String goal, SubagentSpec.Mode mode,
                                           int roundsUsed, int maxRounds, int toolCalls,
                                           Set<String> filesRead) {
        return new SubagentResult(goal, mode, FailureReason.CANCELLED, false, true,
                "(用户已停止，子代理未给出结论)", roundsUsed, maxRounds, toolCalls, filesRead, "",
                null, null, false, false, null, 0);
    }

    /** 根本没进房间：同 goal 超出重派上限 */
    public static SubagentResult retryLimit(String goal, SubagentSpec.Mode mode, int attempts, int allowed) {
        return new SubagentResult(goal, mode, FailureReason.RETRY_LIMIT, false, false,
                "同一目标在本轮对话里已派出 " + attempts + " 次（上限 " + allowed
                        + " 次），不再新开房间。请换一个更具体的目标，或直接自己做。",
                0, 0, 0, new LinkedHashSet<>(), "",
                null, null, false, false, null, 0);
    }

    /** 根本没进房间：池拒绝（并发满 / 第二个 implement 在跑 / 功能关闭） */
    public static SubagentResult rejected(String goal, SubagentSpec.Mode mode, String why) {
        return new SubagentResult(goal, mode, FailureReason.REJECTED, false, false,
                why, 0, 0, 0, new LinkedHashSet<>(), "",
                null, null, false, false, null, 0);
    }

    /** 兜底：房间内部抛异常（不新增停因枚举，归到工具失败一类，但把异常写进 detail） */
    public static SubagentResult crashed(String goal, SubagentSpec.Mode mode, String why,
                                         int roundsUsed, int maxRounds) {
        return new SubagentResult(goal, mode, FailureReason.TOOL_FAILURES, false, false,
                "子代理房间内部异常，未给出结论。", roundsUsed, maxRounds, 0,
                new LinkedHashSet<>(), why,
                null, null, false, false, null, 0);
    }

    // ==================== 复制式改写（字段是 final 的，避免到处传一长串参数） ====================

    /** 挂上结构化报告（**内核生成**的台账 + 模型产出的结论） */
    public SubagentResult withReport(SubagentReport r) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                r, usage, wrapUp, resumed, resumeId, fixedOverheadTokens, resumeNote, handoff,
                executedCommands);
    }

    /** 挂上真实用量（成本核算用） */
    public SubagentResult withUsage(SubagentUsage u) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                report, u, wrapUp, resumed, resumeId, fixedOverheadTokens, resumeNote, handoff,
                executedCommands);
    }

    /** 挂上运行细节：走没走收尾轮 / 是不是续跑 / 固定开销 */
    public SubagentResult withRunInfo(boolean wrapUpUsed, boolean wasResumed, int fixedOverhead) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                report, usage, wrapUpUsed, wasResumed, resumeId, fixedOverhead, resumeNote, handoff,
                executedCommands);
    }

    /** 由调度层挂上"可以续跑"的凭据（房间历史已由 {@link SubagentSessions} 留好） */
    public SubagentResult withResumeId(String id) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                report, usage, wrapUp, resumed, id, fixedOverheadTokens, resumeNote, handoff,
                executedCommands);
    }

    /** 说明这次续跑是"接了历史"还是"降级成新房间 + 注入摘要"（降级原因必须如实写出来） */
    public SubagentResult withResumeNote(String note) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens, note, handoff,
                executedCommands);
    }

    /** 把台账路径作为"读了哪些文件"（**唯一来源是内核，不是模型自报**） */
    public SubagentResult withFilesRead(Set<String> paths) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, paths, detail,
                report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens, resumeNote, handoff,
                executedCommands);
    }

    /** 挂上 general-purpose 完工单（只回传结构化结果，不回传房间对话） */
    public SubagentResult withHandoff(HandoffResult h) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens, resumeNote, h,
                executedCommands);
    }

    /**
     * 挂上本趟实际跑过的命令（核对之外再落盘一份；PARTIAL / 降级也保留）。
     * 不进 {@link #toModelText}，避免把房间过程原文带回父上下文。
     */
    public SubagentResult withExecutedCommands(List<HandoffResult.ExecutedCommand> cmds) {
        return new SubagentResult(goal, mode, failureReason, truncated, cancelled, summary,
                roundsUsed, maxRounds, toolCalls, filesRead, detail,
                report, usage, wrapUp, resumed, resumeId, fixedOverheadTokens, resumeNote, handoff,
                cmds);
    }

    // ==================== 读取 ====================

    public String goal() { return goal; }
    public SubagentSpec.Mode mode() { return mode; }
    public FailureReason failureReason() { return failureReason; }
    public boolean ok() { return failureReason == FailureReason.SUCCESS; }
    public boolean truncated() { return truncated; }
    public boolean cancelled() { return cancelled; }
    public String summary() { return summary; }
    public int roundsUsed() { return roundsUsed; }
    public int maxRounds() { return maxRounds; }
    public int toolCalls() { return toolCalls; }
    public List<String> filesRead() { return new ArrayList<>(filesRead); }
    public String detail() { return detail; }
    public SubagentReport report() { return report; }
    public SubagentUsage usage() { return usage; }
    public boolean wrapUp() { return wrapUp; }
    public boolean resumed() { return resumed; }
    public String resumeId() { return resumeId; }
    public String resumeNote() { return resumeNote; }
    public HandoffResult handoff() { return handoff; }
    public List<HandoffResult.ExecutedCommand> executedCommands() { return executedCommands; }
    public boolean resumable() { return !resumeId.isBlank(); }
    public int fixedOverheadTokens() { return fixedOverheadTokens; }

    /**
     * 完整性。**与停因正交**：轮数用尽但收尾轮给出了结论 → PARTIAL（可用的部分结果），
     * 而不是 NONE（什么都没有）。主 agent 与前端的黄/红两态都看这个。
     *
     * 挂了报告就听报告的（内核在构造时就按"自然收尾 / 收尾轮 / 保底摘要"定好了 status）；
     * 没挂报告的（老路径、根本没进房间）按停因退化。
     */
    public SubagentReport.Status completeness() {
        if (handoff != null) {
            return handoff.status() == HandoffResult.Status.SUCCEEDED
                    ? SubagentReport.Status.COMPLETE : SubagentReport.Status.PARTIAL;
        }
        if (report != null) return report.status();
        return failureReason == FailureReason.SUCCESS
                ? SubagentReport.Status.COMPLETE : SubagentReport.Status.NONE;
    }

    /** 结论正文（报告里的 answer；没有报告时退化成 summary） */
    public String answer() {
        return report == null ? summary : report.answer();
    }

    /** 写进 LastRunNote / 供重派注入用的短摘要 */
    public String noteSummary(int maxChars) {
        String s = answer();
        if (detail != null && !detail.isBlank()) {
            s = s.isBlank() ? detail : s + "\n（卡点：" + detail + "）";
        }
        return trim(s, maxChars);
    }

    /**
     * 交给主 agent 的那段中文（工具的返回值）。
     * **只给坐标和结论**：目标 / 停因 / 轮数与工具次数 / 台账 / 结论 / 缺口 / 下一步。
     * 子房间的工具原文一律不出房间。
     *
     * {@code resumeFirst=true}（默认，评测 V1）：PARTIAL 时把可复制的续跑调用放第一位。
     * {@code false}（评测 V0）：旧的三选一，续跑是选项 (b)。
     */
    public String toModelText(int maxSummaryChars) {
        return toModelText(maxSummaryChars, true);
    }

    public String toModelText(int maxSummaryChars, boolean resumeFirst) {
        StringBuilder sb = new StringBuilder();
        sb.append("[子代理 ").append(mode().name().toLowerCase())
          .append(ok() ? " 完成] 目标：" : " 未完成] 目标：").append(goal).append('\n');
        sb.append("原因：").append(failureReason);
        if (maxRounds > 0) sb.append(" | 轮数 ").append(roundsUsed).append('/').append(maxRounds);
        sb.append(" | 工具 ").append(toolCalls).append(" 次");
        if (!filesRead.isEmpty()) sb.append(" | 读了 ").append(filesRead.size()).append(" 处");
        if (wrapUp) sb.append(" | 已走收尾轮");
        if (resumed) sb.append(" | 这是续跑");
        if (truncated) sb.append(" | 输出被截断");
        sb.append('\n');
        sb.append("完整性：").append(completeness());
        if (fixedOverheadTokens > 0) sb.append(" | 固定开销约 ").append(fixedOverheadTokens).append(" tokens");
        sb.append('\n');
        if (!resumeNote.isBlank()) {
            sb.append("续跑方式：").append(resumeNote).append('\n');
        }

        if (handoff != null) {
            sb.append(handoff.render());
        } else if (report != null) {
            sb.append(report.render(12));
        } else {
            sb.append("摘要：\n").append(trim(summary, maxSummaryChars));
        }
        if (!filesRead.isEmpty()) {
            sb.append("\n【已读文件】").append(String.join(", ", filesRead));
        }
        if (!detail.isBlank()) {
            sb.append("\n卡点：").append(trim(detail, 300));
        }
        sb.append('\n').append(hints(resumeFirst));
        return sb.toString();
    }

    /** 给主 agent 的"下一步怎么办"。**核心改写**：不再邀请它整份重读已读文件。 */
    private String hints(boolean resumeFirst) {
        StringBuilder sb = new StringBuilder();
        if (handoff != null && handoff.status() == HandoffResult.Status.SUCCEEDED) {
            sb.append("【给主 agent 的提示】STATUS=SUCCEEDED 且 VERIFICATION 有命令与结果："
                    + "**立刻** updatePlanStep 标完成。不要再 grep、不要重读被改文件、"
                    + "不要重复执行它已经跑过的编译/测试。");
            return sb.toString();
        }
        if (completeness() == SubagentReport.Status.COMPLETE) {
            sb.append("【给主 agent 的提示】结论完整，可直接用。若要核对细节，"
                    + "只读上面清单里的**具体行范围**（带 offset/limit），不要为了保险整份重读。");
            return sb.toString();
        }
        if (completeness() == SubagentReport.Status.PARTIAL) {
            sb.append("【给主 agent 的提示（务必照做）】\n")
              .append("- 上面【已读清单】里的文件**不要整份重读**：结论就在这里，重读等于白花时间与 token。整份重读台账文件是错的。\n")
              .append("- 这是 **PARTIAL** 结论：**不能**当完整结论对用户断言，回答时要写清哪几点没查清。\n");
            if (resumeFirst && resumable()) {
                sb.append("- 【优先·续跑】把下面这行**原样再调一次**（goal 必须保持不变；房间历史已保留，前缀不变、可复用缓存；同一目标最多续跑 1 次）：\n")
                  .append("  ").append(copyableResumeCall()).append('\n')
                  .append("- 其它两条路：\n")
                  .append("  (a) 结论已够 → 直接用，并如实说明未覆盖的部分；\n")
                  .append("  (c) 自己补读：只读【未覆盖】提到的具体位置（带 offset/limit）。**禁止**整份重读【已读清单】里的文件。");
            } else if (resumable()) {
                sb.append("- 三选一：\n")
                  .append("  (a) 结论已够 → 直接用，并如实说明未覆盖的部分；\n")
                  .append("  (b) 针对【未覆盖】续跑：再调一次 spawnSubagent，**goal 保持不变**并带上 resumeTaskId=")
                  .append(resumeId).append("（房间历史已保留，前缀不变、可直接复用缓存；同一目标最多续跑 1 次）；\n")
                  .append("  (c) 自己补读：只读【未覆盖】提到的具体位置（带 offset/limit）。**禁止**整份重读【已读清单】里的文件。");
            } else {
                sb.append("- 三选一：\n")
                  .append("  (a) 结论已够 → 直接用，并如实说明未覆盖的部分；\n")
                  .append("  (b) **不可用**：此类型不可续跑（没有保留房间历史）；\n")
                  .append("  (c) 自己补读：只读【未覆盖】提到的具体位置（带 offset/limit）。**禁止**整份重读【已读清单】里的文件。");
            }
            return sb.toString();
        }
        switch (failureReason) {
            case SUCCESS -> sb.append("【给主 agent 的提示】子代理结束了但**没有给出可用结论**"
                    + "（只留下上面那点坐标）。请把这个目标收窄（补 scope / 只问一个问题）后换一个更具体的目标再派，"
                    + "或自己做 —— **不要**因此就把【已读文件】里的路径整份读一遍。");
            case ROUNDS_EXHAUSTED -> sb.append("【给主 agent 的提示】轮数用尽且**没有拿到可用结论**（无台账以外的产出）。"
                    + "请把这个目标收窄（补 scope / 只问一个问题）后**换一个更具体的目标**再派一次；"
                    + "上面【已读文件】只是它碰过的路径，**不要**因此就把它们整份读一遍。");
            case TOOL_FAILURES -> sb.append("【给主 agent 的提示】有工具连续失败。**不要原样再派**：换路径 / 换工具，或自己做。");
            case CANCELLED -> sb.append("【给主 agent 的提示】用户点了停止。不要再重试。");
            case RETRY_LIMIT -> sb.append("【给主 agent 的提示】已达重试上限。请自己做，或把这一步标记为 failed。");
            case REJECTED -> sb.append("【给主 agent 的提示】这次派出被拒（并发已满 / 写操作互斥 / 功能关闭）。"
                    + "请自己做这一步，或等其它子代理结束后再派。");
            default -> { }
        }
        return sb.toString();
    }

    /** 给主 agent 抄的续跑调用。goal 必须是原目标（续跑按 goalKey 计数，换了就绕过上限）。 */
    String copyableResumeCall() {
        String g = goal.replace('\\', '/').replace("\"", "'");
        if (g.length() > 160) g = g.substring(0, 157) + "...";
        return "spawnSubagent(resumeTaskId=\"" + resumeId + "\", goal=\"" + g + "\")";
    }

    static String trim(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0 || s.length() <= maxChars) return s;
        return s.substring(0, Math.max(0, maxChars - 20)) + "\n…（摘要已截断）";
    }

    @Override
    public String toString() {
        return "SubagentResult[" + mode().name().toLowerCase() + " " + failureReason
                + " " + completeness() + " rounds=" + roundsUsed + "/" + maxRounds
                + " tools=" + toolCalls + " files=" + filesRead.size() + "]";
    }
}
