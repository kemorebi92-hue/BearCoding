package com.example.jingjingai2.memory;

import com.example.jingjingai2.context.TokenEstimator;
import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.usermemory.UserMemoryCategory;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 影子记忆选择：只读、确定性、不改正式注入。
 *
 * <p>这不是语义检索。匹配用的是查询词与条目文本的直接重合，
 * 没有重合不等于无关——调用方必须把「无重合」单独统计，不能当成「判定无关」。
 *
 * <p>保护规则不靠猜测：只有 {@link MemoryItem#protectedItem()} 为 true 的条目
 * （目前仅用户记忆的 constraint 类别）才免于相关性淘汰。数据里看不出来的硬约束，
 * 本层不推断，按普通条目处理并在结果里标 {@code uncertain}。
 */
public final class MemoryShadowSelector {

    /** 查询里短于这个长度的词不参与匹配（中文 1 字、英文 1~2 字母太常见）。 */
    static final int MIN_TOKEN_CHARS = 2;

    private MemoryShadowSelector() {}

    /** 一条的去向。 */
    public enum Decision { SELECTED, EXCLUDED, UNCERTAIN }

    /** 一条的判定结果。 */
    public static final class Verdict {
        public final MemoryItem item;
        public final Decision decision;
        /** 机器可读原因，见本类常量。 */
        public final String reason;
        /** 直接重合的词数。0 不代表无关。 */
        public final int overlap;

        Verdict(MemoryItem item, Decision decision, String reason, int overlap) {
            this.item = item;
            this.decision = decision;
            this.reason = reason;
            this.overlap = overlap;
        }
    }

    /** 一次选择的全部输出。 */
    public static final class Result {
        public final List<Verdict> verdicts;
        public final int selectedChars;
        public final int selectedTokens;
        public final int budgetChars;
        /** 候选里出现了无法消解的新旧或互斥陈述。 */
        public final boolean conflict;
        /** 有条目因缺信息无法判定，见 verdicts 里的 UNCERTAIN。 */
        public final boolean uncertain;

        Result(List<Verdict> verdicts, int selectedChars, int selectedTokens,
               int budgetChars, boolean conflict, boolean uncertain) {
            this.verdicts = List.copyOf(verdicts);
            this.selectedChars = selectedChars;
            this.selectedTokens = selectedTokens;
            this.budgetChars = budgetChars;
            this.conflict = conflict;
            this.uncertain = uncertain;
        }

        public List<MemoryItem> selected() {
            List<MemoryItem> out = new ArrayList<>();
            for (Verdict v : verdicts) if (v.decision == Decision.SELECTED) out.add(v.item);
            return out;
        }

        public List<MemoryItem> notSelected() {
            List<MemoryItem> out = new ArrayList<>();
            for (Verdict v : verdicts) if (v.decision != Decision.SELECTED) out.add(v.item);
            return out;
        }
    }

    public static final String REASON_PROTECTED = "protected";
    public static final String REASON_OVERLAP = "query-overlap";
    public static final String REASON_NO_OVERLAP = "no-overlap-not-judged-irrelevant";
    public static final String REASON_BUDGET = "over-budget";
    public static final String REASON_EMPTY_QUERY = "empty-query";
    /** 同键不同正文，无法判断哪条还有效。 */
    public static final String REASON_CONFLICT = "conflicting-versions";

    /**
     * @param query          当前用户消息；为空时除受保护条目外全部标 {@code empty-query}
     * @param items          候选，顺序不影响判定
     * @param budgetChars    选中条目正文字符上限；≤0 视为不设上限
     */
    public static Result select(String query, List<MemoryItem> items, int budgetChars) {
        List<MemoryItem> safe = items == null ? List.of() : items;
        List<String> terms = terms(query);
        boolean emptyQuery = terms.isEmpty();
        int cap = budgetChars <= 0 ? Integer.MAX_VALUE : budgetChars;

        Map<String, List<MemoryItem>> byKey = groupByComparableKey(safe);

        List<Verdict> verdicts = new ArrayList<>();
        int used = 0;
        boolean conflict = false;
        boolean uncertain = false;

        // 受保护条目先占预算：约束不能因为排在后面或没命中词而被挤掉。
        List<MemoryItem> rest = new ArrayList<>();
        for (MemoryItem item : safe) {
            if (item == null || item.content().isBlank()) continue;
            if (item.protectedItem()) {
                Verdict v = place(item, Decision.SELECTED, REASON_PROTECTED, overlap(terms, item), used, cap);
                if (v.decision == Decision.SELECTED) used += item.chars();
                verdicts.add(v);
            } else {
                rest.add(item);
            }
        }

        for (MemoryItem item : rest) {
            String key = comparableKey(item);
            boolean conflicted = key != null && conflicts(byKey.get(key));
            int overlap = overlap(terms, item);
            if (conflicted) {
                conflict = true;
                uncertain = true;
                verdicts.add(new Verdict(item, Decision.UNCERTAIN, REASON_CONFLICT, overlap));
                continue;
            }
            if (emptyQuery) {
                uncertain = true;
                verdicts.add(new Verdict(item, Decision.UNCERTAIN, REASON_EMPTY_QUERY, 0));
                continue;
            }
            if (overlap == 0) {
                // 没有重合词：不选，但明确标注「未判定为无关」。
                uncertain = true;
                verdicts.add(new Verdict(item, Decision.UNCERTAIN, REASON_NO_OVERLAP, 0));
                continue;
            }
            Verdict v = place(item, Decision.SELECTED, REASON_OVERLAP, overlap, used, cap);
            if (v.decision == Decision.SELECTED) used += item.chars();
            verdicts.add(v);
        }
        int tokens = 0;
        for (Verdict v : verdicts) if (v.decision == Decision.SELECTED) tokens += v.item.tokens();
        return new Result(verdicts, used, tokens, budgetChars, conflict, uncertain);
    }

    private static Verdict place(MemoryItem item, Decision wanted, String reason,
                                 int overlap, int used, int cap) {
        if (used + item.chars() > cap) {
            return new Verdict(item, Decision.EXCLUDED, REASON_BUDGET, overlap);
        }
        return new Verdict(item, wanted, reason, overlap);
    }

    /** 把用户记忆行收成条目。constraint 是数据里唯一能证明的硬约束，标为受保护。 */
    public static List<MemoryItem> fromUserMemories(List<UserMemory> rows) {
        List<MemoryItem> out = new ArrayList<>();
        if (rows == null) return out;
        for (UserMemory row : rows) {
            if (row == null) continue;
            UserMemoryCategory cat = UserMemoryCategory.fromWire(row.getCategory());
            String category = cat == null ? "" : cat.wire();
            String key = row.getMemKey() == null ? "" : row.getMemKey();
            Date updated = row.getUpdatedAt();
            out.add(new MemoryItem(
                    MemoryItem.Source.USER,
                    "user:" + category + ":" + key,
                    category,
                    key,
                    row.getContent(),
                    cat == UserMemoryCategory.CONSTRAINT,
                    updated == null ? null : updated.getTime()));
        }
        return out;
    }

    /** 工作区一个文件收成一条。文件没有类别、没有时间戳，不标受保护。 */
    public static MemoryItem fromWorkspaceFile(String filename, String content) {
        String name = filename == null ? "" : filename;
        return new MemoryItem(MemoryItem.Source.WORKSPACE, "workspace:" + name,
                null, name, content, false, null);
    }

    static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flushAscii(ascii, out);
                if (i + 1 < lower.length() && isCjk(lower.charAt(i + 1))) {
                    out.add(lower.substring(i, i + 2));
                }
            } else if (isAsciiWord(c)) {
                ascii.append(c);
            } else {
                flushAscii(ascii, out);
            }
        }
        flushAscii(ascii, out);
        return out;
    }

    private static void flushAscii(StringBuilder ascii, List<String> out) {
        if (ascii.length() >= MIN_TOKEN_CHARS) out.add(ascii.toString());
        ascii.setLength(0);
    }

    static int overlap(List<String> terms, MemoryItem item) {
        if (terms.isEmpty()) return 0;
        String haystack = (item.ref() + "\n" + item.content()).toLowerCase(Locale.ROOT);
        int n = 0;
        for (String term : terms) if (haystack.contains(term)) n++;
        return n;
    }

    /** 同来源、同类别、同引用名，且正文不同：视为无法消解的版本冲突。 */
    private static boolean conflicts(List<MemoryItem> group) {
        if (group == null || group.size() < 2) return false;
        String first = group.get(0).content();
        for (int i = 1; i < group.size(); i++) {
            if (!first.equals(group.get(i).content())) return true;
        }
        return false;
    }

    private static Map<String, List<MemoryItem>> groupByComparableKey(List<MemoryItem> items) {
        Map<String, List<MemoryItem>> grouped = new LinkedHashMap<>();
        for (MemoryItem item : items) {
            if (item == null) continue;
            String key = comparableKey(item);
            if (key == null) continue;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private static String comparableKey(MemoryItem item) {
        if (item.ref() == null || item.ref().isBlank()) return null;
        String category = item.category() == null ? "" : item.category();
        return item.source().name() + "|" + category + "|" + item.ref();
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** 选中条目的正文字符合计，供评估对比全量注入。 */
    public static int selectedChars(Result result) {
        return result == null ? 0 : result.selectedChars;
    }

    /** 选中条目的 token 估算合计。 */
    public static int selectedTokens(Result result) {
        if (result == null) return 0;
        int n = 0;
        for (Verdict v : result.verdicts) {
            if (v.decision == Decision.SELECTED) n += TokenEstimator.estimate(v.item.content());
        }
        return n;
    }
}
