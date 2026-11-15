package com.example.jingjingai2.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * general-purpose 子代理交回父代理的完工单。
 *
 * 不复用 {@link SubagentReport}：那边是 结论/未覆盖/下一步建议，字段与状态枚举都对不上。
 * 解析失败不抛异常，一律降成 PARTIAL，原文有限长度放进 REMAINING。
 */
public final class HandoffResult {

    public enum Status { SUCCEEDED, PARTIAL, FAILED }

    public static final String H_STATUS = "STATUS";
    public static final String H_SUMMARY = "SUMMARY";
    public static final String H_CHANGES = "CHANGES";
    public static final String H_VERIFICATION = "VERIFICATION";
    public static final String H_DISCOVERIES = "DISCOVERIES";
    public static final String H_RISKS = "RISKS";
    public static final String H_REMAINING = "REMAINING";

    public static final String VERIFY_NOT_FOUND = "验证未在执行记录中找到";
    public static final String VERIFY_MISSING = "验证缺失";
    public static final String VERIFY_EXIT_MISMATCH = "验证的 passed 与命令退出码不一致";
    public static final String TOOLSET_MISMATCH = "续跑房间的工具集与原始任务不符";

    /** 整张完工单的默认硬顶（不要从头截断整段，按字段优先级裁） */
    public static final int DEFAULT_MAX_CHARS = 2400;

    private static final String[] HEADERS = {
            H_STATUS, H_SUMMARY, H_CHANGES, H_VERIFICATION, H_DISCOVERIES, H_RISKS, H_REMAINING
    };

    private static final Pattern HEADER = Pattern.compile(
            "(?im)^(" + String.join("|", HEADERS) + ")\\s*:\\s*");

    private static final Pattern PASSED = Pattern.compile("(?i)passed\\s*:\\s*(true|false)");
    private static final Pattern EXIT = Pattern.compile("\\(exit=(-?\\d+)\\)");
    private static final Pattern SUCCESS_JSON = Pattern.compile("\"success\"\\s*:\\s*(true|false)");
    private static final Pattern STATUS_SUCCEEDED = Pattern.compile("(?m)^STATUS:\\s*SUCCEEDED\\b");

    /** 进程内累计：SUCCEEDED 因命令对不上 / 退出码不一致被降级的次数（评测用） */
    private static final AtomicInteger DEMOTION_COUNT = new AtomicInteger();

    /**
     * 房间里实际跑过的一条 executeCommand。
     * {@code exitCode == null} 表示结果里拿不到退出码，核对时跳过 passed ↔ exit 对照。
     */
    public static final class ExecutedCommand {
        private final String command;
        private final Integer exitCode;

        public ExecutedCommand(String command, Integer exitCode) {
            this.command = command == null ? "" : command;
            this.exitCode = exitCode;
        }

        public String command() { return command; }
        public Integer exitCode() { return exitCode; }

        /** 一行可读：{@code [exit=0] javac Foo.java}；拿不到退出码写 {@code [exit=?]}。 */
        public String line() {
            return "[exit=" + (exitCode == null ? "?" : exitCode) + "] " + command;
        }
    }

    /**
     * 落盘用：本趟子房间实际跑过的命令 + 退出码。空列表也写 {@code n=0}，
     * 好跟「根本没记」区分。不参与核对。
     */
    public static String formatExecuted(List<ExecutedCommand> cmds) {
        if (cmds == null || cmds.isEmpty()) return "executedCommands n=0";
        StringBuilder sb = new StringBuilder("executedCommands n=").append(cmds.size());
        for (ExecutedCommand c : cmds) {
            if (c == null) continue;
            String line = c.line().replace('\n', ' ').replace('\r', ' ');
            if (line.length() > 400) line = line.substring(0, 392) + "…（已裁剪）";
            sb.append(" | ").append(line);
        }
        return sb.toString();
    }

    private final Status status;
    private final String summary;
    private final String changes;
    private final String verification;
    private final String discoveries;
    private final String risks;
    private final String remaining;
    private final boolean parseFailed;
    private final boolean demoted;

    public HandoffResult(Status status, String summary, String changes, String verification,
                         String discoveries, String risks, String remaining, boolean parseFailed) {
        this(status, summary, changes, verification, discoveries, risks, remaining, parseFailed, false);
    }

    public HandoffResult(Status status, String summary, String changes, String verification,
                         String discoveries, String risks, String remaining,
                         boolean parseFailed, boolean demoted) {
        this.status = status == null ? Status.PARTIAL : status;
        this.summary = nz(summary);
        this.changes = nz(changes);
        this.verification = nz(verification);
        this.discoveries = nz(discoveries);
        this.risks = nz(risks);
        this.remaining = nz(remaining);
        this.parseFailed = parseFailed;
        this.demoted = demoted;
    }

    public Status status() { return status; }
    public String summary() { return summary; }
    public String changes() { return changes; }
    public String verification() { return verification; }
    public String discoveries() { return discoveries; }
    public String risks() { return risks; }
    public String remaining() { return remaining; }
    public boolean parseFailed() { return parseFailed; }
    public boolean demoted() { return demoted; }

    public static int demotionCount() { return DEMOTION_COUNT.get(); }
    public static void resetDemotionCount() { DEMOTION_COUNT.set(0); }

    /**
     * 父循环从工具结果文本判断：这是一张未降级的 SUCCEEDED 完工单。
     * 降级后 STATUS 已被改成 PARTIAL，不会误命中。
     */
    public static boolean looksLikeVerifiedSuccess(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) return false;
        return STATUS_SUCCEEDED.matcher(toolResult).find();
    }

    public static HandoffResult parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isBlank()) {
            return degrade("解析失败：完工单为空", "");
        }
        Map<String, String> sections = splitSections(text);
        List<String> missing = new ArrayList<>();
        for (String h : HEADERS) {
            if (!sections.containsKey(h)) missing.add(h);
        }
        if (!missing.isEmpty()) {
            return degrade("解析失败：完工单缺段（缺 " + String.join(", ", missing) + "）", text);
        }
        Status st = parseStatus(sections.get(H_STATUS));
        if (st == null) {
            return degrade("解析失败：STATUS 不是 SUCCEEDED / PARTIAL / FAILED", text);
        }
        return new HandoffResult(st,
                sections.get(H_SUMMARY),
                sections.get(H_CHANGES),
                sections.get(H_VERIFICATION),
                sections.get(H_DISCOVERIES),
                sections.get(H_RISKS),
                sections.get(H_REMAINING),
                false);
    }

    /**
     * STATUS=SUCCEEDED 时，把 VERIFICATION 里声称的命令与实际 executeCommand 记录做对照。
     * 空白折叠后双向包含（{@code bash test.sh} 对 {@code test.sh} 不算假降级）。
     * 对不上或没有执行记录 → 降为 PARTIAL。
     */
    public HandoffResult verifyAgainst(List<String> executedCommands) {
        List<ExecutedCommand> recs = new ArrayList<>();
        if (executedCommands != null) {
            for (String s : executedCommands) recs.add(new ExecutedCommand(s, null));
        }
        return verifyRecords(recs);
    }

    public HandoffResult verifyRecords(List<ExecutedCommand> executed) {
        if (status != Status.SUCCEEDED) return this;
        if (isBlankOrNone(verification)) {
            return demote(VERIFY_MISSING);
        }
        List<ExecutedCommand> records = executed == null ? List.of() : executed;
        if (records.isEmpty()) {
            return demote(VERIFY_NOT_FOUND);
        }
        ExecutedCommand hit = matchedRecord(verification, records);
        if (hit == null) {
            return demote(VERIFY_NOT_FOUND);
        }
        Boolean passed = parsePassed(verification);
        if (passed != null && hit.exitCode() != null) {
            boolean ok = hit.exitCode() == 0;
            if (passed != ok) return demote(VERIFY_EXIT_MISMATCH);
        }
        return this;
    }

    public HandoffResult trim(int maxChars) {
        int cap = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        if (render().length() <= cap) return this;

        String s = summary;
        String rem = remaining;
        String ch = changes;
        String v = verification;

        HandoffResult cur = withFields(s, ch, v, "（已裁剪）", "（已裁剪）", rem);
        if (cur.render().length() <= cap) return cur;

        cur = withFields(clip(s, 200), ch, v, "（已裁剪）", "（已裁剪）", clip(rem, 200));
        if (cur.render().length() <= cap) return cur;

        int leftover = cap - cur.withFields(s, "", v, "（已裁剪）", "（已裁剪）", rem).render().length();
        leftover = Math.max(80, leftover);
        cur = withFields(clip(s, 120), clip(ch, leftover / 2), clip(v, leftover / 2),
                "（已裁剪）", "（已裁剪）", clip(rem, 80));
        if (cur.render().length() <= cap) return cur;

        // 最后手段：仍超长就把 CHANGES / VERIFICATION 再压一次，绝不整段 substring(0, max)
        return withFields(clip(s, 80), clip(ch, 200), clip(v, 300),
                "（已裁剪）", "（已裁剪）", clip(rem, 80));
    }

    public String render() {
        return H_STATUS + ": " + status.name() + "\n"
                + H_SUMMARY + ": " + blankToNone(summary) + "\n"
                + H_CHANGES + ": " + blankToNone(changes) + "\n"
                + H_VERIFICATION + ": " + blankToNone(verification) + "\n"
                + H_DISCOVERIES + ": " + blankToNone(discoveries) + "\n"
                + H_RISKS + ": " + blankToNone(risks) + "\n"
                + H_REMAINING + ": " + blankToNone(remaining);
    }

    static HandoffResult degrade(String why, String raw) {
        String clipped = raw == null ? "" : raw.trim();
        if (clipped.length() > 800) {
            clipped = clipped.substring(0, 780) + "\n…（原文已截断）";
        }
        return new HandoffResult(Status.PARTIAL, why, "None", "None", "None", "None",
                clipped.isBlank() ? "None" : clipped, true, false);
    }

    private HandoffResult demote(String why) {
        DEMOTION_COUNT.incrementAndGet();
        String rem = remaining;
        if (isBlankOrNone(rem)) rem = why;
        else if (!rem.contains(why)) rem = why + "\n" + rem;
        String sum = summary;
        if (isBlankOrNone(sum)) sum = why;
        else if (!sum.contains(why)) sum = why + "；" + sum;
        return new HandoffResult(Status.PARTIAL, sum, changes, verification,
                discoveries, risks, rem, parseFailed, true);
    }

    /** 续跑工具集与原任务不符：SUCCEEDED 降 PARTIAL；已经不是成功的也把这句话写进报告。 */
    HandoffResult noteToolsetMismatch() {
        return demote(TOOLSET_MISMATCH);
    }

    private HandoffResult withFields(String s, String ch, String v, String d, String rk, String rem) {
        return new HandoffResult(status, s, ch, v, d, rk, rem, parseFailed, demoted);
    }

    private static Map<String, String> splitSections(String text) {
        Matcher m = HEADER.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1).toUpperCase(Locale.ROOT));
            spans.add(new int[]{m.start(), m.end()});
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int from = spans.get(i)[1];
            int to = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : text.length();
            out.put(names.get(i), text.substring(from, to).trim());
        }
        return out;
    }

    private static Status parseStatus(String body) {
        if (body == null) return null;
        String first = body.trim().split("[\\s|/，,;]+", 2)[0].trim().toUpperCase(Locale.ROOT);
        if (first.equals("SUCCEEDED") || first.equals("SUCCESS") || first.equals("OK")) {
            return Status.SUCCEEDED;
        }
        if (first.equals("PARTIAL")) return Status.PARTIAL;
        if (first.equals("FAILED") || first.equals("FAIL") || first.equals("FAILURE")) {
            return Status.FAILED;
        }
        return null;
    }

    static boolean matchesAny(String verification, List<String> records) {
        List<ExecutedCommand> recs = new ArrayList<>();
        if (records != null) {
            for (String r : records) recs.add(new ExecutedCommand(r, null));
        }
        return matchedRecord(verification, recs) != null;
    }

    static ExecutedCommand matchedRecord(String verification, List<ExecutedCommand> records) {
        if (verification == null || records == null) return null;
        List<String> claimed = claimedCommands(verification);
        if (claimed.isEmpty()) claimed = List.of(normalize(verification));
        for (ExecutedCommand rec : records) {
            if (rec == null) continue;
            String r = normalize(rec.command());
            if (r.length() < 2) continue;
            for (String c : claimed) {
                if (c.length() < 2) continue;
                if (c.contains(r) || r.contains(c)) return rec;
                String head = r.length() > 40 ? r.substring(0, 40) : r;
                if (head.length() >= 8 && c.contains(head)) return rec;
            }
        }
        return null;
    }

    static List<String> claimedCommands(String verification) {
        String stripped = PASSED.matcher(verification == null ? "" : verification).replaceAll(" ");
        List<String> out = new ArrayList<>();
        for (String line : stripped.split("[\\n;]+")) {
            for (String piece : line.split("\\s+/\\s+")) {
                String n = normalize(piece);
                if (n.length() >= 2) out.add(n);
            }
        }
        return out;
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    static Boolean parsePassed(String verification) {
        if (verification == null) return null;
        Matcher m = PASSED.matcher(verification);
        if (!m.find()) return null;
        return Boolean.parseBoolean(m.group(1));
    }

    /**
     * 从 executeCommand 的返回值里抠退出码。只认已经在结果里的信息：
     * {@code 执行失败 (exit=N)}，或 {@code [TC]} JSON 里的 {@code success:true}（⇔ exit 0）。
     * 拿不到就返回 null，核对时跳过 passed 对照。
     */
    public static Integer parseExitCode(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) return null;
        Matcher m = EXIT.matcher(toolResult);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
        }
        Matcher s = SUCCESS_JSON.matcher(toolResult);
        if (s.find() && "true".equalsIgnoreCase(s.group(1))) return 0;
        return null;
    }

    static boolean isBlankOrNone(String s) {
        if (s == null || s.isBlank()) return true;
        String t = s.trim();
        return t.equalsIgnoreCase("none") || t.equals("无") || t.equals("（无）") || t.equals("-");
    }

    private static String blankToNone(String s) {
        return s == null || s.isBlank() ? "None" : s;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 8)) + "…（已裁剪）";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    public String toString() {
        return "HandoffResult[" + status
                + (parseFailed ? " parseFailed" : "")
                + (demoted ? " demoted" : "") + "]";
    }
}
