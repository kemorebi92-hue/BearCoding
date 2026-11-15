package com.example.jingjingai2.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 已读台账的**一行**。
 *
 * 硬约束：**由内核根据实际工具调用记录生成**，不许模型自己填。
 * 模型自报"我读了什么"在轮数被截断时最不可靠（它正是没来得及汇报才被截断的），
 * 而"这台机器到底调了哪些工具、参数是什么、返回多大"是**事实**，永远拿得到。
 *
 * 所以这里只有机械信息：工具名 + 路径 + 行范围 + 返回体量。
 * 一句话收获那一栏（{@link #note()}）也只是"读了 120-260 行，返回 3412 字"这种客观描述，
 * **不是**语义总结 —— 语义总结要再调一次模型，那正是本模块明确不做的事。
 */
public final class ReadLedgerEntry {

    private final String tool;
    private final String path;
    private final String range;
    private final String note;

    public ReadLedgerEntry(String tool, String path, String range, String note) {
        this.tool = tool == null ? "" : tool;
        this.path = path == null ? "" : path;
        this.range = range == null ? "" : range;
        this.note = note == null ? "" : note;
    }

    public String tool() { return tool; }
    public String path() { return path; }
    public String range() { return range; }
    public String note() { return note; }

    /** 给主 agent 看的一行：{@code - path（行 120-260）· readFile · 返回 3412 字} */
    public String line() {
        StringBuilder sb = new StringBuilder("- ").append(path.isBlank() ? "(未知路径)" : path);
        if (!range.isBlank()) sb.append("（行 ").append(range).append('）');
        if (!tool.isBlank()) sb.append(" · ").append(tool);
        if (!note.isBlank()) sb.append(" · ").append(note);
        return sb.toString();
    }

    /** 台账里去重用的 key：同一文件同一段只留一行 */
    public String key() {
        return (path + "#" + range).toLowerCase();
    }

    /**
     * 从一次工具调用的参数 + 结果体量生成一行（纯函数，便于单测钉死）。
     *
     * @param tool       工具名
     * @param args       已解析的参数（可能为空 map）
     * @param resultSize 工具返回的字符数
     */
    public static ReadLedgerEntry of(String tool, Map<String, Object> args, int resultSize) {
        String path = firstNonBlank(args,
                "path", "file", "from", "dir", "directory", "pattern", "query", "url");
        String range = rangeOf(args);
        String note;
        if (range.isBlank()) {
            note = "返回 " + resultSize + " 字";
        } else {
            note = "返回 " + resultSize + " 字";
        }
        return new ReadLedgerEntry(tool, path == null ? "" : path, range, note);
    }

    private static String rangeOf(Map<String, Object> args) {
        Integer off = asInt(args.get("offset"));
        Integer lim = asInt(args.get("limit"));
        if (lim == null && args.get("lineCount") != null) lim = asInt(args.get("lineCount"));
        if (off == null && lim == null) return "";
        int start = off == null ? 1 : off;
        if (lim == null) return "从 " + start + " 起";
        return start + "-" + (start + Math.max(0, lim) - 1);
    }

    private static String firstNonBlank(Map<String, Object> args, String... keys) {
        if (args == null) return null;
        for (String k : keys) {
            Object v = args.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return null;
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    /** 台账：保持**首次出现顺序**去重（LinkedHashMap 语义），顺序本身就是"调查路径" */
    public static final class Ledger {
        private final List<ReadLedgerEntry> entries = new ArrayList<>();
        private final java.util.Set<String> keys = new java.util.LinkedHashSet<>();

        public void add(ReadLedgerEntry e) {
            if (e == null) return;
            if (keys.add(e.key())) entries.add(e);
        }

        public List<ReadLedgerEntry> entries() { return new ArrayList<>(entries); }

        public int size() { return entries.size(); }

        public boolean isEmpty() { return entries.isEmpty(); }

        /** 只取路径清单（去重，保序）—— 冗余读提示与"已读过"都只需要路径 */
        public List<String> paths() {
            java.util.Set<String> ps = new java.util.LinkedHashSet<>();
            for (ReadLedgerEntry e : entries) {
                if (!e.path().isBlank()) ps.add(e.path());
            }
            return new ArrayList<>(ps);
        }
    }
}
