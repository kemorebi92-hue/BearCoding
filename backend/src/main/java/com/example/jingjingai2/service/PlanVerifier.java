package com.example.jingjingai2.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 计划步骤的**轻量验收核对**：把 expected（预期产出）和 actualResult（实际产出）比一下。
 *
 * 设计原则（很重要）：**宁可放过，不要误判**。
 *  因为"判失败"会累加 failCount，而 failCount 到 3 会触发重拆甚至 blocked —— 误判的代价很高。
 *  所以这里的判定是"三档"：
 *    ① 实际结果是空的 → 不算通过（没有产出就是没做完）；
 *    ② 出现**硬失败信号**且没有任何成功信号 → 不通过（例如 exit=1 / 报错 / 找不到文件）；
 *    ③ 关键词覆盖率为 0 **且**没有任何成功信号 → 不通过（完全没沾到预期要点）；
 *    其余情况一律算通过（模型主动上报 failed 的场景由它自己负责，这里只是"兜底网"）。
 *
 * 不做语义理解、不调模型 —— 纯关键词/结构匹配，好解释、好测试、零成本。
 */
public final class PlanVerifier {

    /**
     * 硬失败信号：出现了基本可以判定"这一步没做成"。
     *
     * ⚠️ 教训（真实踩过）：**不能收裸词 `error` / `exception`** ——
     * 一次实测里，模型上报"已创建 Calculator.java，除零会抛 IllegalArgumentException 已在注释里说明"，
     * 结果被这两个裸词误判为失败，白白累加了一次 failCount（差点触发重拆）。
     * 所以英文信号一律加限定（`error:` / `exception in thread`），只认真正的报错特征。
     */
    /** 编译失败、缺产物、编译器报错。出现后，普通成功词不能把这一步判成通过。 */
    private static final List<String> HARD_FAIL_SIGNALS = Arrays.asList(
            "编译失败", "编译不通过", "构建失败", "测试失败",
            "cannot find symbol", "no such file", "error:"
    );

    /** 只有这些才表示编译/构建本身后来通过，而不是「文件已创建」这类旁路成功词。 */
    private static final List<String> COMPILE_SUCCESS_SIGNALS = Arrays.asList(
            "编译通过", "构建通过", "测试通过", "exit=0", "exit = 0"
    );

    private static final List<String> FAIL_SIGNALS = Arrays.asList(
            "执行失败", "编译失败", "测试失败", "构建失败", "验证失败", "运行失败", "失败",
            "报错", "错误", "无法", "找不到", "未通过", "不通过", "拒绝", "超时", "denied",
            "no such file", "cannot find symbol", "error:", "exception in thread",
            "exit=1", "exit = 1", "failed"
    );

    /** 成功信号：出现它说明"至少干活了并且有正常迹象"，用来抵消上面的误伤 */
    private static final List<String> SUCCESS_SIGNALS = Arrays.asList(
            "成功", "通过", "已完成", "完成", "✅", "正常", "已生成", "已创建", "已写入",
            "passed", "exit=0", "exit = 0", "ok"
    );

    /** 抽取关键词时忽略的虚词（避免"把/进行/确保"这种词把覆盖率算高） */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
            "的", "了", "和", "与", "或", "及", "并", "且", "然后", "以及", "并且",
            "一个", "这个", "那个", "进行", "确保", "需要", "可以", "应该", "能够",
            "把", "将", "在", "是", "要", "并", "之后", "完成", "实现", "使用"
    ));

    /** 关键词片段：连续的中文/字母/数字，长度 >= 2 */
    private static final Pattern TOKEN = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]{2,}");

    private PlanVerifier() {}

    /** 核对结果 */
    public static final class Result {
        private final boolean passed;
        private final String reason;

        Result(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        public boolean passed() { return passed; }
        public String reason() { return reason; }
    }

    /**
     * @param expected     规划阶段写下的"做完应该是什么样"（可以为空 → 那就不做关键词核对）
     * @param actualResult 执行后模型上报的实际产出摘要
     */
    public static Result verify(String expected, String actualResult) {
        String actual = actualResult == null ? "" : actualResult.trim();
        if (actual.isEmpty()) {
            return new Result(false, "没有上报实际产出");
        }

        Integer lastExit = lastExitCode(actual);
        if (lastExit != null && lastExit != 0) {
            return new Result(false, "实际产出的最后退出码是 " + lastExit + "，成功词不能抵消");
        }
        boolean hasSuccess = containsAny(actual, SUCCESS_SIGNALS);
        String hardHit = firstMatch(actual, HARD_FAIL_SIGNALS);
        if (hardHit != null && !containsAny(actual, COMPILE_SUCCESS_SIGNALS)) {
            return new Result(false, "实际产出里出现不能被成功词抵消的失败信号「" + hardHit + "」");
        }
        String failHit = firstMatch(actual, FAIL_SIGNALS);
        if (failHit != null && !hasSuccess) {
            return new Result(false, "实际产出里出现失败信号「" + failHit + "」");
        }

        List<String> keywords = keywords(expected);
        if (!keywords.isEmpty()) {
            int hit = 0;
            List<String> missed = new ArrayList<>();
            for (String kw : keywords) {
                if (hit(kw, actual)) hit++;
                else missed.add(kw);
            }
            double coverage = (double) hit / keywords.size();
            if (coverage == 0 && !hasSuccess) {
                return new Result(false, "实际产出完全没覆盖预期要点：" + String.join("、", missed));
            }
            return new Result(true, "命中预期要点 " + hit + "/" + keywords.size());
        }
        return new Result(true, "预期描述为空，仅做失败信号核对");
    }

    /** 从 expected 里抽关键词（去虚词、去重） */
    static List<String> keywords(String expected) {
        List<String> out = new ArrayList<>();
        if (expected == null || expected.isBlank()) return out;
        var m = TOKEN.matcher(expected);
        while (m.find()) {
            String token = m.group();
            if (STOP_WORDS.contains(token)) continue;
            // 去掉开头的虚词（如"编译通过"抽出来没问题，但"把文件写入"要修成"文件写入"）
            String trimmed = token;
            for (String sw : STOP_WORDS) {
                if (trimmed.length() > sw.length() && trimmed.startsWith(sw)) {
                    trimmed = trimmed.substring(sw.length());
                }
            }
            if (trimmed.length() < 2) continue;
            if (!out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    /** 关键词命中判定：整词命中，或它的 2-gram 过半命中（容忍"编译通过" vs "已成功编译"这种措辞差异） */
    static boolean hit(String keyword, String actual) {
        String a = actual.toLowerCase();
        String k = keyword.toLowerCase();
        if (a.contains(k)) return true;
        // 2 字词只认整词（上一行已经判过），不再做 2-gram 模糊匹配，避免"编译"命中"编译失败"
        if (k.length() <= 2) return false;
        int total = 0, hit = 0;
        for (int i = 0; i + 2 <= k.length(); i++) {
            total++;
            if (a.contains(k.substring(i, i + 2))) hit++;
        }
        return total > 0 && hit * 2 >= total;
    }

    private static boolean containsAny(String text, List<String> signals) {
        String t = text.toLowerCase();
        for (String s : signals) if (t.contains(s.toLowerCase())) return true;
        return false;
    }

    /** 文本里最后一次 exit=N / exit code N。没有则返回 null。 */
    static Integer lastExitCode(String text) {
        if (text == null || text.isBlank()) return null;
        var m = Pattern.compile("exit\\s*(?:=|code)\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        Integer last = null;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static String firstMatch(String text, List<String> signals) {
        String t = text.toLowerCase();
        for (String s : signals) if (t.contains(s.toLowerCase())) return s;
        return null;
    }
}
