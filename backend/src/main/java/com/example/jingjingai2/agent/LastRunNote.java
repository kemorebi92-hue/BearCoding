package com.example.jingjingai2.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 「上次这一目标跑到哪儿了」的短笔记。
 *
 * 为什么要有它：子代理房间是**一次性**的（跑完即扔），于是"走不通 → 再派 → 从零再读一遍"
 * 会很浪费。笔记让**新房间**能接着上次的结论/失败点干活，而不是复活整份 transcript。
 * （第二轮又补了一条路：历史能被 {@link SubagentSessions} 保留时，走 {@code resume} 接着跑，
 *   那条路连笔记都不需要 —— 笔记是**降级方案**的载体。）
 *
 * 刻意不做的事：不存文件正文、不存逐轮对话、不跨会话、不做向量。
 * 那是后面的记忆模块；这里只是"死掉的房间留下一张便条"。
 */
public final class LastRunNote {

    private final String goal;
    private final SubagentSpec.Mode mode;
    private final boolean ok;
    private final boolean truncated;
    private final SubagentReport.Status completeness;
    private final SubagentResult.FailureReason failureReason;
    private final String summary;
    private final List<String> filesRead;
    private final List<String> unresolved;

    public LastRunNote(String goal, SubagentSpec.Mode mode, boolean ok, boolean truncated,
                       SubagentReport.Status completeness,
                       SubagentResult.FailureReason failureReason, String summary,
                       List<String> filesRead, List<String> unresolved) {
        this.goal = goal == null ? "" : goal;
        this.mode = mode == null ? SubagentSpec.Mode.EXPLORE : mode;
        this.ok = ok;
        this.truncated = truncated;
        this.completeness = completeness == null ? SubagentReport.Status.NONE : completeness;
        this.failureReason = failureReason == null ? SubagentResult.FailureReason.SUCCESS : failureReason;
        this.summary = summary == null ? "" : summary;
        this.filesRead = filesRead == null ? List.of() : new ArrayList<>(filesRead);
        this.unresolved = unresolved == null ? List.of() : new ArrayList<>(unresolved);
    }

    /** 从一次结果生成笔记（summary 已被调用方按 maxSummaryChars 截过） */
    public static LastRunNote of(SubagentResult r, int maxSummaryChars) {
        List<String> unresolved = r.report() == null ? List.of() : r.report().unresolved();
        return new LastRunNote(r.goal(), r.mode(), r.ok(), r.truncated(), r.completeness(),
                r.failureReason(), r.noteSummary(maxSummaryChars), r.filesRead(), unresolved);
    }

    public String goal() { return goal; }
    public SubagentSpec.Mode mode() { return mode; }
    public boolean ok() { return ok; }
    public boolean truncated() { return truncated; }
    public SubagentReport.Status completeness() { return completeness; }
    public SubagentResult.FailureReason failureReason() { return failureReason; }
    public String summary() { return summary; }
    public List<String> filesRead() { return new ArrayList<>(filesRead); }
    public List<String> unresolved() { return new ArrayList<>(unresolved); }

    /**
     * 注进新房间的那段固定格式文本。
     *
     * 关键：**带上 failureReason 与完整性枚举名**。主 agent 和人读日志时能直接扫到"上次是轮数用尽
     * 还是工具连失败、结论完不完整"，不必去解析一段中文。
     */
    public String injectText() {
        StringBuilder sb = new StringBuilder();
        sb.append("【上次尝试（供参考，不要整份重做）】\n");
        sb.append("上次结果：").append(ok ? "成功" : "未成功")
          .append("（原因：").append(failureReason).append("，完整性：").append(completeness).append("）\n");
        sb.append("上次摘要：").append(summary.isBlank() ? "（无）" : summary).append('\n');
        if (!unresolved.isEmpty()) {
            sb.append("上次没查清的：");
            for (String u : unresolved) sb.append("\n- ").append(u);
            sb.append('\n');
        }
        if (!filesRead.isEmpty()) {
            sb.append("已读过（结论仍有效的不要再读）：").append(String.join(", ", filesRead)).append('\n');
        }
        sb.append("请从上次卡住的地方继续，不要重复读已经读过且结论仍有效的文件。");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "LastRunNote[" + failureReason + " " + completeness + " ok=" + ok
                + " files=" + filesRead.size() + "]";
    }
}
