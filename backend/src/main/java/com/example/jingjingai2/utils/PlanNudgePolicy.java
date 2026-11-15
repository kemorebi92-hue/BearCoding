package com.example.jingjingai2.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「该不该催模型用 createPlan」的判定策略（漂移信号 + 分级提醒）。
 *
 * 为什么需要它：诊断确认"要不要规划"完全交给模型自觉并不可靠 —— 它对"够不够复杂"的判断
 * 太保守，14 轮的多文件重建任务全程走纯 ReAct。光靠提示词喊话不行，harness 必须**基于
 * 可观测的行为**判断"这个任务正在失控（或者规模已经明显超出单轮能做完的范围）"，再出手。
 *
 * 核心设计：**只在信号出现时才花钱**。
 *  - 0 个信号 → 什么都不做（表现良好的任务零打扰，这才是"简单任务只用 ReAct"）；
 *  - 1 个信号 → SOFT：在下一轮请求末尾追加一条提醒（文案里点明命中的是哪条信号）；
 *  - ≥2 个信号，或 SOFT 提醒过一次后模型仍不建计划 → HARD：下一轮**强制**它调用 createPlan。
 *
 * 本类是**纯逻辑**（不依赖 Spring、不联网、不读文件），文件版本通过
 * {@link FileVersionResolver} 注入 —— 这样"改完再读不算重复读取"这类规则可以用假实现单测。
 */
public final class PlanNudgePolicy {

    /** 写/编辑类工具：只有它们才算"改了文件" */
    public static final Set<String> WRITE_TOOLS = Set.of(
            "writeFile", "createFile", "editFile", "appendFile",
            "deletePath", "createDirectory", "movePath", "copyPath");

    /** 读类工具：用于判定"原地重复读" */
    public static final Set<String> READ_TOOLS = Set.of("readFile");

    /**
     * HARD 逃生舱（strategy=stripWriteTools）要不要把某个工具摘掉。
     *
     * 摘的标准 = "能改变工作区状态"：所有写/编辑类工具，外加 executeCommand
     * （命令同样能改文件、能跑编译器，留着等于给绕路的机会）。
     * 读类工具（readFile / listDirectory / globFile / grepFile）、联网搜索，
     * 以及 createPlan / updatePlanStep 必须保留 —— 逃生舱的目的正是"让模型除了先规划无事可做"。
     *
     * spawnSubagent 也在摘除之列：implement 模式的子代理**同样能改工作区**，
     * 留着它等于"HARD 逼你规划，你说好，然后派个 implement 子代理把文件改了"——
     * 和当年 executeCommand 那个漏洞是同一个口子。explore 虽然只读，
     * 但它会耗掉本轮的时间和 token，同样应该在内核里做不成。
     *
     * saveUserMemory / forgetUserMemory 不摘：记一条偏好不是偷偷写代码。
     * saveMemory 会改 memory/*.md，HARD 那一轮摘掉。
     */
    public static boolean isStrippableTool(String toolName) {
        if (toolName == null) return false;
        // 外部 MCP 工具 + 搜索入口都不该在 HARD 逃生舱里乱跑
        if (toolName.startsWith("mcp__")) return true;
        if ("mcpToolSearch".equals(toolName)) return true;
        return isDangerousBuiltin(toolName);
    }

    /**
     * 写类内置工具：污点策略剥离用。有意<strong>不含</strong> {@code mcp__*} ——
     * 那些是不可信内容的来源，HARD 逃生舱才整段摘掉。
     */
    public static boolean isDangerousBuiltin(String toolName) {
        if (toolName == null) return false;
        return WRITE_TOOLS.contains(toolName)
                || "executeCommand".equals(toolName)
                || "spawnSubagent".equals(toolName)
                || "saveMemory".equals(toolName);
    }

    /** 提醒级别 */
    public enum Level { NONE, SOFT, HARD }

    /** 一次文件类工具调用（由 ChatController 从 toolCallRecords 里提取，按发生顺序传入） */
    public static final class FileOp {
        public final String toolName;
        public final String path;

        public FileOp(String toolName, String path) {
            this.toolName = toolName == null ? "" : toolName;
            this.path = path == null ? "" : path;
        }

        public boolean isWrite() { return WRITE_TOOLS.contains(toolName); }
        public boolean isRead() { return READ_TOOLS.contains(toolName); }
    }

    /**
     * 取某个文件"当前内容版本"的抽象（真实实现用 mtime+size，测试用假实现）。
     * 取不到（文件不存在/解析失败）返回 null。
     */
    public interface FileVersionResolver {
        String versionOf(String path);
    }

    /** 阈值与开关（由 PlanProperties 填，这里刻意不依赖 Spring 才能单测） */
    public static final class Config {
        /** 被修改的不同文件数达到多少算信号 */
        public int modifiedFilesThreshold = 3;
        /** 编译/校验失败累计多少次算信号 */
        public int verifyFailuresThreshold = 2;
        /** 累计轮数达到多少算信号 */
        public int roundsThreshold = 8;
        /** "隐含规划"判定：文本长度下限 */
        public int enumerationMinLength = 120;
        /** "隐含规划"判定：步骤枚举标记数量下限 */
        public int enumerationMinMarkers = 3;
        /** 触发后多少轮内不再触发 */
        public int cooldown = 3;
    }

    /** 命中的漂移信号集合 */
    public static final class Signals {
        public boolean manyFiles;
        public boolean verifyFailures;
        public boolean repeatedRead;
        public boolean manyRounds;
        public boolean implicitPlan;

        public int count() {
            int n = 0;
            if (manyFiles) n++;
            if (verifyFailures) n++;
            if (repeatedRead) n++;
            if (manyRounds) n++;
            if (implicitPlan) n++;
            return n;
        }

        /** 命中信号的**具体**描述，用于提醒文案（要能引用具体信号，不能只说"你该规划了"） */
        public List<String> descriptions(int modifiedFiles, int verifyFailCount, int roundsUsed) {
            List<String> out = new ArrayList<>();
            if (manyFiles) out.add("已修改 " + modifiedFiles + " 个不同文件");
            if (verifyFailures) out.add("编译/校验已失败 " + verifyFailCount + " 次");
            if (repeatedRead) out.add("有文件在内容没变的情况下被重复读取");
            if (manyRounds) out.add("已经跑了 " + roundsUsed + " 轮还没结束");
            if (implicitPlan) out.add("你已经在回答里用自然语言列了好几步");
            return out;
        }

        public Set<String> names() {
            Set<String> s = new LinkedHashSet<>();
            if (manyFiles) s.add("manyFiles");
            if (verifyFailures) s.add("verifyFailures");
            if (repeatedRead) s.add("repeatedRead");
            if (manyRounds) s.add("manyRounds");
            if (implicitPlan) s.add("implicitPlan");
            return s;
        }
    }

    /** 数字/符号序号，例如行首的 "1." "2、" "3)" */
    private static final Pattern NUM_MARKER =
            Pattern.compile("(?:^|\\n|\\s)\\s*\\d{1,2}\\s*[.、)）](?!\\d)");

    /** 步骤枚举的常见连接词 */
    private static final String[] ENUM_WORDS = {
            "首先", "其次", "然后", "接着", "最后", "第一步", "第二步", "第三步", "步骤"};

    private PlanNudgePolicy() {}

    // ==================== ① 各信号判定（全部可单测） ====================

    /** 信号 1：被修改的**不同**文件数 ≥ 阈值（只统计写/编辑类工具，读操作不算） */
    public static boolean hasManyModifiedFiles(List<FileOp> ops, Config cfg) {
        if (ops == null) return false;
        Set<String> files = new LinkedHashSet<>();
        for (FileOp op : ops) {
            if (op.isWrite() && !op.path.isBlank()) files.add(op.path);
        }
        return files.size() >= cfg.modifiedFilesThreshold;
    }

    /**
     * 信号 3：同一文件在**内容未变化**的情况下被重复读取。
     *
     * 判定方式（两层，缺一不可）：
     *  1. 两次读之间**没有对该文件的写操作**（序列里能直接看出来）—— 这是确定性的核心规则；
     *  2. 如果提供了 {@link FileVersionResolver}，还要确认该文件当前版本可解析且两次读数一致，
     *     避免"文件被外部程序改过"这种情况被误判成"重复读"。
     * 反过来说，「改完再读」（读 → 写 → 读）不算重复读，这正是要避免的误报。
     */
    public static boolean hasRepeatedRead(List<FileOp> ops, FileVersionResolver resolver) {
        if (ops == null) return false;
        for (int i = 0; i < ops.size(); i++) {
            FileOp a = ops.get(i);
            if (!a.isRead() || a.path.isBlank()) continue;
            for (int j = i + 1; j < ops.size(); j++) {
                FileOp b = ops.get(j);
                if (!b.isRead() || !a.path.equals(b.path)) continue;
                // 中间只要出现过对该文件的写，就说明是"改完再读"，不算重复读
                boolean writtenBetween = false;
                for (int k = i + 1; k < j; k++) {
                    FileOp mid = ops.get(k);
                    if (mid.isWrite() && a.path.equals(mid.path)) { writtenBetween = true; break; }
                }
                if (writtenBetween) continue;
                // 版本兜底确认：解析不到版本（文件已不存在）就不算
                if (resolver != null && resolver.versionOf(a.path) == null) continue;
                return true;
            }
        }
        return false;
    }

    /** 信号 5：模型用自然语言列了步骤（"隐含规划"），但没调用 createPlan */
    public static boolean looksLikeImplicitPlan(String text, Config cfg) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < cfg.enumerationMinLength) return false;

        int markers = 0;
        Matcher m = NUM_MARKER.matcher(t);
        while (m.find() && markers < cfg.enumerationMinMarkers) markers++;
        if (markers >= cfg.enumerationMinMarkers) return true;

        for (String w : ENUM_WORDS) {
            if (t.contains(w) && ++markers >= cfg.enumerationMinMarkers) return true;
        }
        return false;
    }

    /**
     * 汇总本轮命中的所有信号。
     *
     * @param ops              按发生顺序排列的文件类操作
     * @param verifyFailCount  编译/校验失败累计次数
     * @param roundsUsed       已经用掉的轮数（1 基）
     * @param lastAssistantText 上一轮模型输出的自然语言文本（用于"隐含规划"判断）
     * @param modifiedFilesOut 输出参数：被修改的不同文件数（写进提醒文案用），传 null 可忽略
     */
    public static Signals detect(List<FileOp> ops, int verifyFailCount, int roundsUsed,
                                 String lastAssistantText, Config cfg, FileVersionResolver resolver,
                                 int[] modifiedFilesOut) {
        Signals s = new Signals();
        Set<String> files = new LinkedHashSet<>();
        if (ops != null) {
            for (FileOp op : ops) if (op.isWrite() && !op.path.isBlank()) files.add(op.path);
        }
        if (modifiedFilesOut != null) modifiedFilesOut[0] = files.size();

        s.manyFiles = files.size() >= cfg.modifiedFilesThreshold;
        s.verifyFailures = verifyFailCount >= cfg.verifyFailuresThreshold;
        s.repeatedRead = hasRepeatedRead(ops, resolver);
        s.manyRounds = roundsUsed >= cfg.roundsThreshold;
        s.implicitPlan = looksLikeImplicitPlan(lastAssistantText, cfg);
        return s;
    }

    // ==================== ② 分级与冷却（纯函数） ====================

    /**
     * 由信号数量推出提醒级别。
     *
     * @param hasPlan          已经建过计划 → 一律不管（提醒只服务于"还没有计划"的场景）
     * @param softAlreadyFired 本请求内是否已经 SOFT 提醒过一次（提醒过还不建 → 升级 HARD）
     */
    public static Level decide(Signals s, boolean softAlreadyFired, boolean hasPlan) {
        if (hasPlan) return Level.NONE;
        int n = s == null ? 0 : s.count();
        if (n == 0) return Level.NONE;
        if (n >= 2) return Level.HARD;
        return softAlreadyFired ? Level.HARD : Level.SOFT;
    }

    /** 冷却期：距上次触发不足 cooldown 轮就不再触发 */
    public static boolean isCoolingDown(int round, int lastNudgeRound, int cooldown) {
        return round - lastNudgeRound < cooldown;
    }

    /**
     * 本轮是否处于"强制调用 createPlan"的那一轮。
     *
     * 刻意做成"**只认准一个轮号**"：HARD 触发时把 forceRound 设成下一轮，
     * 于是强制只作用于那一轮，之后自动失效（不会每轮都强按着模型的手）。
     */
    public static boolean isForceRound(int round, int forceRound) {
        return forceRound >= 0 && round == forceRound;
    }

    /**
     * 消费强制轮：如果 roundIndex 正是被强制的轮号，返回 true 并把它清成 -1（一次性）。
     *
     * ⚠️ **必须在 applyPlanNudge 之前调用**（真实 bug，已实测证实）：
     * 原来的顺序是「先评估 nudge、再检查是否强制轮」，而 applyPlanNudge 会在触发 HARD 时
     * 把 forceRound 设成"再下一轮"。于是只要**连续两轮都命中信号**（cooldown=0/1 时就会），
     * 本轮刚被设好的 forceRound 会在检查之前就被覆盖成更晚的轮号 —— 强制轮永远轮不到，
     * 而且因为根本没发起强制请求，日志里连"强制未生效"都不会打，属于最隐蔽的一种失效。
     * 默认 cooldown=3 时恰好不会连续命中，所以这个坑一直没暴露。
     *
     * @param forceRound 长度为 1 的持有者（-1 表示不强制）
     */
    public static boolean consumeForceRound(int roundIndex, int[] forceRound) {
        if (forceRound == null || forceRound.length == 0) return false;
        if (!isForceRound(roundIndex, forceRound[0])) return false;
        forceRound[0] = -1;
        return true;
    }
}
