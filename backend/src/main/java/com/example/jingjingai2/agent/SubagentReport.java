package com.example.jingjingai2.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理交回主 agent 的**结构化报告**。
 *
 * 为什么要有它：今天子代理只回一段自由文本，于是
 *  - "有没有查清"只能靠人读；
 *  - "还差什么"完全丢失 → 主 agent 只能自己重做一遍（这正是本模块要修的那个 ROI 漏洞）。
 *
 * 四个字段对应主 agent 必须能区分的四件事：**结论 / 依据 / 缺口 / 下一步**。
 *
 * {@link Status} 只有三个值，和 {@link SubagentResult.FailureReason}（停因，六个值）正交：
 * 停因说"为什么停"，完整性说"交出来的东西能不能用"。
 * 轮数用尽（ROUNDS_EXHAUSTED）时如果收尾轮产出了结论 → **PARTIAL 而不是 NONE**，
 * 因为 PARTIAL 是可用的部分结果，NONE 才是"什么都没有"。
 */
public final class SubagentReport {

    /** 完整性 */
    public enum Status {
        /** 模型自然收尾，结论完整 */
        COMPLETE,
        /** 收尾轮产出 / 内核保底摘要：**有结论但不完整**，主 agent 必须知道还差什么 */
        PARTIAL,
        /** 没有任何可用结论（取消 / 被拒 / 摘要为空） */
        NONE;

        public static Status parse(String raw) {
            if (raw == null || raw.isBlank()) return NONE;
            String s = raw.trim().toUpperCase();
            if (s.startsWith("COMPLETE")) return COMPLETE;
            if (s.startsWith("PARTIAL")) return PARTIAL;
            return NONE;
        }
    }

    /** 收尾指令里约定的三行标记，解析**纯机械**（不调第二次模型） */
    public static final String MARK_ANSWER = "结论：";
    public static final String MARK_UNRESOLVED = "未覆盖：";
    public static final String MARK_NEXT = "下一步建议：";

    private final Status status;
    private final String answer;
    private final boolean answerTruncated;
    private final List<ReadLedgerEntry> readLedger;
    private final List<String> unresolved;
    private final String suggestedNextGoal;

    public SubagentReport(Status status, String answer, boolean answerTruncated,
                          List<ReadLedgerEntry> readLedger, List<String> unresolved,
                          String suggestedNextGoal) {
        this.status = status == null ? Status.NONE : status;
        this.answer = answer == null ? "" : answer;
        this.answerTruncated = answerTruncated;
        this.readLedger = readLedger == null ? List.of() : new ArrayList<>(readLedger);
        this.unresolved = unresolved == null ? List.of() : new ArrayList<>(unresolved);
        this.suggestedNextGoal = suggestedNextGoal == null ? "" : suggestedNextGoal;
    }

    public Status status() { return status; }
    public String answer() { return answer; }
    public boolean answerTruncated() { return answerTruncated; }
    public List<ReadLedgerEntry> readLedger() { return new ArrayList<>(readLedger); }
    public List<String> unresolved() { return new ArrayList<>(unresolved); }
    public String suggestedNextGoal() { return suggestedNextGoal; }

    public boolean usable() { return !answer.isBlank(); }

    /**
     * 由模型文本 + 内核台账构造报告。
     *
     * @param modelText     模型最后一段文字（收尾轮产出，或自然收尾产出）
     * @param status        完整性
     * @param ledger        内核根据工具调用生成的台账
     * @param maxAnswerChars answer 硬顶（多个子代理各回一大段会把主上下文重新撑爆）
     */
    public static SubagentReport of(String modelText, Status status,
                                    List<ReadLedgerEntry> ledger, int maxAnswerChars) {
        String text = modelText == null ? "" : modelText.trim();
        String rawAnswer = section(text, MARK_ANSWER, MARK_UNRESOLVED, MARK_NEXT);
        if (rawAnswer == null) rawAnswer = text;   // 没按标记写 → 整段当结论，不装作解析成功
        List<String> unresolved = bullets(text, MARK_UNRESOLVED, MARK_NEXT);
        String next = section(text, MARK_NEXT, null, null);

        String answer = rawAnswer.trim();
        boolean truncated = false;
        if (maxAnswerChars > 0 && answer.length() > maxAnswerChars) {
            answer = answer.substring(0, Math.max(0, maxAnswerChars - 12)) + "\n…（已截断）";
            truncated = true;
        }
        if (answer.isBlank()) status = Status.NONE;
        return new SubagentReport(status, answer, truncated, ledger, unresolved, next);
    }

    /** 内核保底摘要里那句话开头的几个字（{@link #fallback} 认得它） */
    public static final String KERNEL_FALLBACK_MARK = "（内核保底摘要";

    /**
     * **内核保底报告**：正文是内核按工具调用记录拼的，**不是模型写的**。
     *
     * 为什么不复用 {@link #of}：保底文本里有"…非模型结论：…"这样一句，
     * 而 {@code of} 是用 {@code indexOf("结论：")} 切段的 —— 它会把"非模型结论："里的
     * "结论："当成起始标记，把前面整段说明砍掉（这正是测试里红过的那条）。
     * 语义上也不该解析：这段文本压根没有标记结构，装作解析成功是自欺。
     *
     * {@code unresolved} 里必须留一条实话：缺口**未知**。否则主 agent 会把保底摘要
     * 当成完整结论用下去 —— 那就又回到"白派"的老问题上了。
     */
    public static SubagentReport fallback(String kernelText, List<ReadLedgerEntry> ledger,
                                          int maxAnswerChars) {
        String text = kernelText == null ? "" : kernelText.trim();
        String answer = text;
        boolean truncated = false;
        if (maxAnswerChars > 0 && answer.length() > maxAnswerChars) {
            answer = answer.substring(0, Math.max(0, maxAnswerChars - 12)) + "\n…（已截断）";
            truncated = true;
        }
        List<String> unresolved = new ArrayList<>();
        unresolved.add("子代理没能在预算内交出结构化报告，**具体缺口未知** —— 以上是内核按实际工具调用"
                + "拼出的保底摘要，请当线索用，不要当结论用。");
        Status status = answer.isBlank() ? Status.NONE : Status.PARTIAL;
        return new SubagentReport(status, answer, truncated, ledger, unresolved, "");
    }

    /** 取两个标记之间（到下一个标记为止）的正文；找不到起始标记返回 null */
    private static String section(String text, String startMark, String endMark1, String endMark2) {
        int s = text.indexOf(startMark);
        if (s < 0) return null;
        int from = s + startMark.length();
        int to = text.length();
        for (String end : new String[]{endMark1, endMark2}) {
            if (end == null) continue;
            int i = text.indexOf(end, from);
            if (i >= 0 && i < to) to = i;
        }
        return text.substring(from, to);
    }

    /** 一行一条的清单（"没有" / "无" / "-" 视为空） */
    private static List<String> bullets(String text, String startMark, String... endMarks) {
        String body = section(text, startMark, endMarks.length > 0 ? endMarks[0] : null,
                endMarks.length > 1 ? endMarks[1] : null);
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        for (String line : body.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("-") || t.startsWith("*") || t.startsWith("•")) t = t.substring(1).trim();
            if (t.isEmpty()) continue;
            if (t.equals("无") || t.equals("没有") || t.equals("（无）") || t.equals("暂无")
                    || t.equalsIgnoreCase("none")) continue;
            out.add(t);
        }
        return out;
    }

    /** 写进工具结果 / 便条的多行文本（不含"给主 agent 的提示"那一段） */
    public String render(int maxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("【已确认结论】").append(answer.isBlank() ? "（无）" : answer);
        if (!readLedger.isEmpty()) {
            sb.append("\n【已读清单】").append(readLedger.size()).append(" 处");
            int n = 0;
            for (ReadLedgerEntry e : readLedger) {
                if (maxLines > 0 && n >= maxLines) {
                    sb.append("\n- …（其余 ").append(readLedger.size() - n).append(" 处略）");
                    break;
                }
                sb.append('\n').append(e.line());
                n++;
            }
        }
        if (!unresolved.isEmpty()) {
            sb.append("\n【未覆盖】");
            for (String u : unresolved) sb.append("\n- ").append(u);
        }
        if (!suggestedNextGoal.isBlank()) {
            sb.append("\n【建议的下一步目标】").append(suggestedNextGoal.trim());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "SubagentReport[" + status + " answer=" + answer.length() + "字 ledger="
                + readLedger.size() + " unresolved=" + unresolved.size() + "]";
    }
}
