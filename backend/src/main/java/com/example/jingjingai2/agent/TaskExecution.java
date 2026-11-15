package com.example.jingjingai2.agent;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.agent.reconcile.ToolBusinessOutcome;
import com.example.jingjingai2.agent.reconcile.ToolOutcomeClassifier;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Request-owned ledger. Explicitly shared with workers; never inherited by unrelated requests. */
public final class TaskExecution {
    private static final ThreadLocal<TaskExecution> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final int limit;
    private final long inputLimit;
    private final int window;
    private int calls;
    private int auxiliaryCalls;
    private volatile BooleanSupplier stopRequested = () -> false;
    private volatile boolean disconnected;
    private TaskPlan completedPlan;
    private long input;
    private long auxiliaryInput;
    private boolean finalizing;
    private boolean finalCall;
    private boolean closed;
    private boolean approvalBlocked;
    private long finalOwner;
    private int modelRounds;
    private int toolCalls;
    private String endReason = "RUNNING";
    private String deliveryStatus = "UNCONFIRMED";
    private String stopReason = "执行未完成";
    private static final Set<String> WRITE_TOOLS = Set.of("writeFile", "createFile", "editFile", "appendFile");
    private static final Pattern FILE_CLAIM = Pattern.compile(
            "(?iu)已(?:写入|保存|生成|创建|修改|覆盖)|文件已(?:生成|创建|写入|保存)"
                    + "|(?<!not )(?<!never )\\b(?:written|saved|created|generated)\\b");
    private static final Pattern FAKE_TOOL = Pattern.compile(
            "(?i)<\\/?tool_call|<function=|function_call|<parameter=|<tool_name|invoke name=|^\\s*\\{\\s*\"tool_calls\"\\s*:");
    private static final Pattern PROMISE = Pattern.compile(
            "(?is)^\\s*(?:好的[，,。!！\\s]+)?(?:我(?:会|将|准备)|接下来|改为直接|现在生成|将为你|I (?:will|plan to))"
                    + "[^\\n]{0,180}[。.!！]?(?:\\s*\\n\\s*[-*]?\\s*(?:我(?:会|将|准备)|接下来)[^\\n]{0,180})*\\s*$");
    private final Map<Path, Evidence> evidence = new LinkedHashMap<>();
    private record Evidence(PlanStep step, String tool, String fingerprint) {}

    /**
     * 命令类步骤的执行证据（F2）。
     *
     * <p>旧实现里 {@link #verifiedFiles} 对「编译 / 执行命令 / 删除」直接返回空，
     * 而 {@link #requiresEffectEvidence} 又要求这些步骤必须有副作用证据 —— 两步合起来是死锁：
     * 命令类步骤永远无法标记 DONE。这里记录本次请求真实执行过的命令（命令原文、退出状态、结果指纹），
     * 供 {@link #verifiedCommands} 归属给命令类步骤。
     *
     * <p><b>命令原文只活在本次请求的内存里</b>：{@code command} 只用于「步骤目标 ↔ 命令」的标识符对齐；
     * 对外（plan_json / SSE / HTTP）用 {@code key} —— 命令的不可逆指纹。
     * 这样同一条命令的证据键稳定、不同命令不会撞键，命令行里的 token / 密码 / API Key 也不会被持久化。
     */
    private record CommandRun(String command, boolean ok, String digest, String key) {}
    private final List<CommandRun> commands = new ArrayList<>();
    /** 命令下标 → 已经拿它做过证据的 stepId。同一条成功命令不能给两个步骤作证。 */
    private final Map<Integer, String> commandOwner = new LinkedHashMap<>();
    /** 本次请求真实删除过的路径（deletePath）。删除类步骤用它做证据。 */
    private final Set<Path> deleted = new LinkedHashSet<>();
    private static final Pattern EXIT_CODE = Pattern.compile("exit=(-?\\d+)");
    /** 命令类步骤的判定词。只影响「能不能用命令证据」，不影响是否需要证据。 */
    private static final Pattern COMMAND_STEP = Pattern.compile(
            "(?isu).*(编译|运行|执行|命令|测试|验证|compile|run|exec|test|verify|javac|mvn|npm|gradle|del |type |dir ).*");
    /** 对齐目标时忽略的通用词：它们出现在几乎每条命令里，不构成「同一个目标」。 */
    private static final Set<String> GENERIC_TOKENS = Set.of(
            "java", "javac", "the", "and", "out", "src", "bin", "exit", "cmd", "command",
            "type", "dir", "del", "class", "code", "true", "false", "null", "utf");

    public TaskExecution(int limit, long inputLimit, int window) {
        // 硬夹：2026-11-11 从 60 抬到 256。演示场景要求"一条消息内最多 256 次调用"，
        // 不改这里的话调用方传 256 会被悄悄夹成 60。
        this.limit = Math.max(1, Math.min(256, limit));
        this.inputLimit = inputLimit;
        this.window = window;
    }
    public static TaskExecution current() { return CURRENT.get(); }
    public static Scope attach(TaskExecution task) {
        TaskExecution old = CURRENT.get();
        if (task == null) CURRENT.remove(); else CURRENT.set(task);
        return () -> { if (old == null) CURRENT.remove(); else CURRENT.set(old); };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    public int window() { return window; }

    public synchronized int calls() { return calls; }
    public synchronized int remainingExecutionCalls() { return Math.max(0, limit - 1 - calls); }
    public void watchCancellation(BooleanSupplier stopRequested) { this.stopRequested = stopRequested; }
    public boolean cancelled() { return disconnected || stopRequested.getAsBoolean(); }
    public void disconnect() { disconnect(() -> {}); }

    public synchronized void disconnect(Runnable onDisconnect) {
        if (closed || disconnected) return;
        disconnected = true;
        onDisconnect.run();
    }
    public synchronized void planCompleted(TaskPlan plan) { completedPlan = plan; }
    public synchronized TaskPlan completedPlan() { return completedPlan; }
    public int limit() { return limit; }
    public synchronized boolean mustFinalize() { return approvalBlocked || calls >= limit - 1; }
    public synchronized boolean finalizing() { return finalizing; }
    public synchronized boolean approvalBlocked() { return approvalBlocked; }
    public synchronized boolean acceptsTools() {
        return !closed && !finalizing && !approvalBlocked && !cancelled();
    }
    public synchronized void blockApproval(String reason) { approvalBlocked = true; stopReason = reason; }
    public synchronized void beginFinal(String reason) { finalizing = true; finalOwner = Thread.currentThread().getId(); stopReason = reason; }
    public synchronized void modelRound() { modelRounds++; }
    public synchronized void end(String reason) { endReason = reason; }
    public synchronized void delivered() { deliveryStatus = "TEXT_RETURNED"; }

    /**
     * 正文里是否夹带了"伪工具调用"标记（{@code <tool_call>} 之类）。
     *
     * <p>流式推送要用它做闸门：模型偶尔把工具调用当普通文字吐出来，这种文字一旦逐段推出去，
     * 界面上就会出现一串内部标记（而且它本来就不可交付，见 {@link #acceptableAnswer}）。</p>
     */
    public static boolean looksLikeFakeToolCall(CharSequence text) {
        return text != null && FAKE_TOOL.matcher(text).find();
    }
    public synchronized Map<String, Object> summary() {
        return Map.of("endReason", endReason, "deliveryStatus", deliveryStatus, "modelRounds", modelRounds,
                "modelCalls", calls, "toolCalls", toolCalls, "modelCallLimit", limit,
                "auxiliaryCalls", auxiliaryCalls, "auxiliaryInputTokens", auxiliaryInput);
    }
    public synchronized void close() { closed = true; }

    /** Reserve before a request, including failed requests. No hidden retry gets a free slot. */
    public void acquire(long estimatedInput) { acquire(estimatedInput, false); }

    public synchronized void acquire(long estimatedInput, boolean auxiliary) {
        if (cancelled()) throw new Cancelled();
        if (closed || calls >= limit || (!finalizing && (approvalBlocked || calls >= limit - 1))
                || (finalizing && (finalCall || finalOwner != Thread.currentThread().getId())))
            throw new BudgetExceeded("模型调用预算耗尽");
        if (auxiliary && auxiliaryRefused(estimatedInput)) {
            // 2026-11-11 起辅助调用与主调用共用同一份额度（不再单列"副卡"），
            // 所以这里只剩两种情况：已经进入收尾、主任务剩余调用不足。日志把实测值都打出来便于定位。
            System.err.println("[Budget] 拒绝辅助调用: finalizing=" + finalizing
                    + " remainingExecutionCalls=" + remainingExecutionCalls()
                    + " auxiliaryCalls=" + auxiliaryCalls
                    + " estimatedInput=" + estimatedInput
                    + " auxiliaryInput=" + auxiliaryInput + "/" + inputLimit
                    + " inputLimit=" + inputLimit
                    + " calls=" + calls + "/" + limit);
            throw new BudgetExceeded("辅助调用暂不可用：本次请求已进入收尾，或剩余调用不足以再开子代理");
        }
        if ((window > 0 && estimatedInput > window)
                || (inputLimit > 0 && estimatedInput > inputLimit - input))
            throw new BudgetExceeded("上下文或累计输入预算不足");
        calls++;
        if (auxiliary) {
            auxiliaryCalls++;
            auxiliaryInput += estimatedInput;
        }
        input += estimatedInput;
        if (finalizing) finalCall = true;
    }

    /**
     * 辅助调用现在是否会被拒（子代理、摘要、重拆与主任务共用同一份额度）。
     *
     * <p>开房间之前先问一句：注定被拒时再开一间房只会撞同一堵墙，还会被当成"首轮空失败"重派一次。</p>
     */
    public synchronized boolean auxiliaryShareExhausted() { return auxiliaryRefused(0); }

    /**
     * 辅助调用被拒的条件（{@code estimatedInput}=0 时只判前两条）。
     *
     * <p>2026-11-11 起不再有"辅助份额最多占一半"的单独闸门：子代理与主任务共享同一个
     * {@code limit}，谁先花谁占，只剩两条兜底 —— 已进入收尾、主任务剩余调用 ≤ 2。
     * 2026-11-14 起输入 token 也一样：辅助调用共用整张 {@code inputLimit}，不再只能用一半。
     * 总量仍由 {@link #acquire} 里的 {@code inputLimit - input} 兜住，不会超总预算。</p>
     */
    private boolean auxiliaryRefused(long estimatedInput) {
        return finalizing
                || remainingExecutionCalls() <= Math.min(2, limit - 2)
                || (inputLimit > 0 && estimatedInput > inputLimit - auxiliaryInput);
    }

    public synchronized void accountExtra(long estimated, long actual, boolean auxiliary) {
        long extra = Math.max(0, actual - estimated);
        input += extra;
        if (auxiliary) auxiliaryInput += extra;
    }

    /** Attribute only a unique target in the exact plan step object current at execution time. */
    public synchronized void record(String tool, String args, String result,
                                    ToolExecutionStatus.Kind kind, List<PlanStep> steps) {
        toolCalls++;
        if ("executeCommand".equals(tool)) {
            recordCommand(args, result, kind);
            return;
        }
        if (!WRITE_TOOLS.contains(tool) && !Set.of("deletePath", "movePath", "copyPath", "createDirectory").contains(tool))
            return;
        com.fasterxml.jackson.databind.JsonNode arguments;
        try { arguments = JSON.readTree(args); } catch (Exception invalid) { return; }
        if (arguments == null) return;
        boolean transfer = "movePath".equals(tool) || "copyPath".equals(tool);
        Path path = FileExecutionEvidence.path(arguments.path(transfer ? "to" : "path").asText());
        if ("movePath".equals(tool))
            invalidate(FileExecutionEvidence.path(arguments.path("from").asText()));
        if (path == null) return;
        invalidate(path);
        if ("deletePath".equals(tool) && ToolOutcomeClassifier.classify(tool, kind, result) == ToolBusinessOutcome.OK)
            deleted.add(path);
        if (!WRITE_TOOLS.contains(tool) || ToolOutcomeClassifier.classify(tool, kind, result) != ToolBusinessOutcome.OK)
            return;
        String fingerprint = FileExecutionEvidence.fingerprint(path);
        if (fingerprint == null) return;
        // 只把「这一步自己要求产生副作用」的步骤算作归属。
        // 后面仅用来读回确认的步骤也会提到同一个文件名；把它算进去会让唯一的写入永远对不上。
        List<PlanStep> matches = steps.stream()
                .filter(step -> !readOnlyGoal(goal(step)))
                .filter(step -> FileExecutionEvidence.targets(goal(step)).contains(path)).toList();
        evidence.put(path, new Evidence(matches.size() == 1 ? matches.get(0) : null, tool, fingerprint));
    }

    /** 记录一次真实命令执行：命令原文、退出状态（拿得到就用 exit 码）、结果指纹、命令指纹。 */
    private void recordCommand(String args, String result, ToolExecutionStatus.Kind kind) {
        String command;
        try { command = JSON.readTree(args).path("command").asText(""); } catch (Exception invalid) { return; }
        if (command == null || command.isBlank()) return;
        Integer exit = null;
        java.util.regex.Matcher m = EXIT_CODE.matcher(result == null ? "" : result);
        if (m.find()) {
            try { exit = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { /* 保持 null */ }
        }
        boolean ok = kind == ToolExecutionStatus.Kind.EXECUTED && (exit == null || exit == 0);
        // 键用命令的不可逆指纹：证据仍然一一对应到具体命令，但命令行里的凭证不会进 plan_json / SSE。
        commands.add(new CommandRun(command, ok, digestOf(result),
                com.example.jingjingai2.permission.SensitiveRedactor.fingerprint(command)));
    }

    private static String digestOf(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void invalidate(Path path) {
        if (path != null) evidence.keySet().removeIf(target -> target.startsWith(path));
    }

    public synchronized boolean verified(PlanStep step) { return !verifiedFiles(step).isEmpty(); }

    public synchronized Map<String, String> verifiedFiles(PlanStep step) {
        String goal = goal(step);
        Map<String, String> removed = verifiedDeletions(goal);
        if (!removed.isEmpty()) return removed;
        Set<Path> required = FileExecutionEvidence.targets(goal);
        if (required.isEmpty() || goal.matches("(?isu).*(删除|移动|复制|编译|执行命令|安装|提交代码"
                + "|\\b(?:delete|move|copy|compile|install)\\b).*")) return Map.of();
        Map<String, String> verified = new LinkedHashMap<>();
        for (Path path : required) {
            Evidence entry = evidence.get(path);
            if (entry == null || entry.step != step || !stillCurrent(path, entry.fingerprint)) return Map.of();
            verified.put(path.toString(), entry.fingerprint);
        }
        return verified;
    }

    public static boolean verifiedFilesStillCurrent(PlanStep step) {
        Map<String, String> files = step.getVerifiedFiles();
        if (files == null || files.isEmpty()) return false;
        // 只核对已经验证过的那些文件还在不在。步骤描述里提到、但本步不要求写入的文件
        // （例如后面的读回确认）不能让数量对不上，从而把已验证步骤打回 pending。
        // F2：命令 / 删除证据是「本次请求发生过」的事实，没有文件指纹可复核，按仍有效处理。
        return files.entrySet().stream()
                .filter(entry -> !isSynthetic(entry.getKey()))
                .allMatch(entry -> stillCurrent(Path.of(entry.getKey()), entry.getValue()));
    }

    /** 合成证据键：{@code command:...} / {@code deleted:...}，不是文件路径。 */
    public static boolean isSynthetic(String key) {
        return key != null && (key.startsWith("command:") || key.startsWith("deleted:"));
    }

    /**
     * 命令类步骤的证据（F2）。
     *
     * <p>规则：只对「命令类步骤」生效；只认本次请求里**真实执行成功**（EXECUTED 且 exit=0）的命令。
     * 优先按「步骤目标 / 上报正文里出现的标识符」与命令原文对齐（例如 Main、VerifyTmp），
     * 对齐不到时才退回「本次请求最近一次成功命令」，并打一行日志留痕。
     * 这样模型仅凭文字声明、没有真的跑过命令时仍然拿不到证据。
     *
     * <p>P2 审查（回退条件收紧）：退回「最近一次成功命令」这一条以前只看「有没有成功命令」，
     * 于是两种误判都可能发生 —— ①步骤点名了 {@code VerifyTmp} 这种类/文件，但一条命令里都没跑过它，
     * 却拿另一条 exit=0 的命令顶替；②同一条命令先后给两个步骤作证（过期证据复用）。
     * 现在这两种情况都拒绝归属，步骤会留在 pending 等模型真的去跑。
     * 真正跑过的步骤不受影响：按标识符直接对齐命中的路径完全不变。</p>
     *
     * <p>证据键是 {@code command:<命令指纹>}，不是命令原文：命令原文会带 token / 密码，
     * 而这张表会被写进 {@code plan_json} 并经 SSE / HTTP 送到前端。指纹不可逆、按命令唯一，
     * 所以「这一步是哪条命令作证的」仍然可区分，也不会把不同命令映射到同一个键。</p>
     */
    public synchronized Map<String, String> verifiedCommands(PlanStep step) {
        String goal = goal(step);
        if (!COMMAND_STEP.matcher(goal).matches()) return Map.of();
        if (!requiresEffectEvidence(step, "")) return Map.of();
        if (commands.isEmpty()) return Map.of();
        String haystack = goal + "\n" + Objects.toString(step.getActualResult(), "");
        for (int i = commands.size() - 1; i >= 0; i--) {
            CommandRun run = commands.get(i);
            if (!run.ok()) continue;
            if (sharesTarget(haystack, run.command())) {
                commandOwner.put(i, step.getStepId());
                return Map.of("command:" + run.key(), run.digest());
            }
        }
        for (int i = commands.size() - 1; i >= 0; i--) {
            CommandRun run = commands.get(i);
            if (!run.ok()) continue;
            String owner = commandOwner.get(i);
            if (owner != null && !owner.equals(step.getStepId())) {
                System.out.println("[PlanEvidence] 命令证据已被步骤 " + owner + " 使用，不再复用: step="
                        + step.getStepId() + " command=" + redactedOneLine(run.command()));
                continue;
            }
            if (namesUnrunIdentifier(haystack)) {
                System.out.println("[PlanEvidence] 步骤点名了命令里没出现的标识符，拒绝按最近一次成功命令归属: step="
                        + step.getStepId() + " command=" + redactedOneLine(run.command()));
                return Map.of();
            }
            commandOwner.put(i, step.getStepId());
            System.out.println("[PlanEvidence] 命令证据按最近一次成功命令归属: step="
                    + step.getStepId() + " command=" + redactedOneLine(run.command()));
            return Map.of("command:" + run.key(), run.digest());
        }
        return Map.of();
    }

    /**
     * 步骤目标/上报正文里是否点名了「成功命令里一次都没出现过」的具体标识符。
     *
     * <p>只认两类强特征：多词驼峰类名（{@code VerifyTmp}、{@code PricingCheck}）和带扩展名的文件名。
     * 它们是「这一步要跑的东西」；而 {@code total=270.0}、{@code CHECK_OK} 这类是期望输出，
     * 本来就不会出现在命令行里，不能因此否定归属（否则会把已经通过的 4/4 计划重新打回 pending）。</p>
     */
    private boolean namesUnrunIdentifier(String haystack) {
        Set<String> executed = new HashSet<>();
        for (CommandRun run : commands) if (run.ok()) executed.addAll(identifiers(run.command()));
        for (String identifier : identifiers(haystack)) {
            if (!executed.contains(identifier)) return true;
        }
        return false;
    }

    /** 保留大小写的标识符集合：用来判断「这个类/文件到底跑没跑过」。 */
    private static Set<String> identifiers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}").matcher(text);
        while (m.find()) {
            String identifier = m.group();
            boolean camelClass = identifier.matches("[A-Za-z][a-z]+[A-Z][A-Za-z0-9]*");
            boolean fileName = identifier.matches("[A-Za-z0-9_-]+\\.[A-Za-z][A-Za-z0-9]*");
            if (camelClass || fileName) out.add(identifier.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 删除类步骤：本次请求真实删掉了目标文件才算证据。 */
    private Map<String, String> verifiedDeletions(String goal) {
        if (!goal.matches("(?isu).*(删除|移除|delete|remove).*")) return Map.of();
        for (Path path : deleted) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            if (!name.isBlank() && (goal.contains(name) || goal.contains(path.toString())))
                return Map.of("deleted:" + path, "absent");
        }
        return Map.of();
    }

    private static boolean sharesTarget(String haystack, String command) {
        Set<String> wanted = tokens(haystack);
        if (wanted.isEmpty()) return false;
        for (String token : tokens(command)) {
            if (wanted.contains(token)) return true;
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z0-9_.]{3,}").matcher(text);
        while (m.find()) {
            String token = m.group().toLowerCase(Locale.ROOT);
            if (GENERIC_TOKENS.contains(token)) continue;
            out.add(token);
        }
        return out;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').substring(0, Math.min(120, text.length()));
    }

    /**
     * 日志行里的命令：先脱敏再截断。
     *
     * <p>顺序很重要 —— 先截断会把 {@code --token=} 后面的值截成半截，脱敏规则就认不出来了。
     * 控制台日志会被复制、被采集、长期留在终端里，命令行里的凭证不该原样出现在那里。
     */
    private static String redactedOneLine(String command) {
        return oneLine(com.example.jingjingai2.permission.SensitiveRedactor.redactText(command));
    }

    public synchronized boolean invalidatedFiles(PlanStep step, String tool, String args) {
        if (step.getVerifiedFiles() == null || tool == null || !WRITE_TOOLS.contains(tool)) return false;
        try {
            Path path = FileExecutionEvidence.path(JSON.readTree(args).path("path").asText());
            return path != null && step.getVerifiedFiles().containsKey(path.toString())
                    && !evidence.containsKey(path);
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean stillCurrent(Path path, String fingerprint) {
        return fingerprint != null && fingerprint.equals(FileExecutionEvidence.fingerprint(path));
    }

    private static String goal(PlanStep step) {
        return Objects.toString(step.getDescription(), "") + "\n" + Objects.toString(step.getExpected(), "");
    }

    public synchronized boolean acceptableAnswer(String text, Supplier<List<PlanStep>> steps) {
        if (text == null || text.isBlank() || FAKE_TOOL.matcher(text).find() || PROMISE.matcher(text).matches())
            return false;
        if (toolCalls > 0 && text.matches("[。！!\\s]*(已完成|完成了|已交付)[。！!\\s]*")) return false;
        // 表格的竖线不是句子边界：按它切开后，文件名和「已创建」会落到不同片段。
        // 句号也会把「创建了 `a.txt`。已创建文件」拆开，所以相邻两句再合看一次。
        String[] clauses = text.split("[。；;\\n]");
        for (int i = 0; i < clauses.length; i++) {
            if (!FILE_CLAIM.matcher(clauses[i]).find()) continue;
            if (claimsAreEvidenced(clauses[i], steps)) continue;
            String withPrevious = i == 0 ? clauses[i] : clauses[i - 1] + "。" + clauses[i];
            // 文件名和「已创建」有时隔着一句。整段里能找到已验证的文件，也算数。
            if (!claimsAreEvidenced(withPrevious, steps) && !claimsAreEvidenced(text, steps)) {
                System.out.println("[DeliveryClaim] 未证实片段=" + clauses[i].replace('\n', ' '));
                return false;
            }
        }
        return true;
    }

    private boolean claimsAreEvidenced(String clause, Supplier<List<PlanStep>> steps) {
        if (!FILE_CLAIM.matcher(clause).find()) return true;
        Set<Path> claimed = FileExecutionEvidence.targets(clause);
        if (claimed.isEmpty()) {
            return !(FileExecutionEvidence.mentionsTarget(clause)
                    || clause.matches("(?isu).*(文件|已写入|已保存|\\bfiles?\\b).*"));
        }
        for (Path path : claimed) {
            Evidence current = evidence.get(path);
            if (current != null && stillCurrent(path, current.fingerprint)) continue;
            // 步骤已经验证过这个文件时，最终回答提到它不算无证据。
            // 归属记录可能挂在另一步上（写完再读回），不能因此否定交付。
            if (steps.get().stream().anyMatch(step -> step.isResultVerified()
                    && verifiedFilesStillCurrent(step) && step.getVerifiedFiles().containsKey(path.toString())))
                continue;
            return false;
        }
        return true;
    }

    public static boolean requiresEffectEvidence(PlanStep step, String actual) {
        String goalText = goal(step);
        String text = goalText + "\n" + Objects.toString(actual, "");
        // 步骤本身是只读确认时不要求副作用。豁免只看步骤描述，不看上报正文：
        // 模型复述「创建了什么」不该把一次读回变成一次写入要求。
        if (readOnlyGoal(goalText)) {
            return false;
        }
        return text.matches("(?isu).*(写入|保存|创建文件|修改文件|删除|移动|复制|落地|执行命令|编译|安装|提交代码"
                + "|(?:写|改|创建|新建|生成|追加|编辑|更新|修改)[^\\n]*(?:[.\\x60]|Dockerfile|Makefile|README|LICENSE)"
                + "|writeFile|createFile|editFile|\\b(write|create|edit|delete|move|copy|save|generate|modify"
                + "|update|append|remove|rename|mkdir|compile|install)\\b).*");
    }

    /**
     * 目标只要求「读」、没有任何「写/命令」字样。
     *
     * <p>F2：这个判断同时用于「要不要副作用证据」和「一次写入该归属给哪个步骤」。
     * 归属侧以前用的是 {@code requiresEffectEvidence(step, "")}，而像「修复 src/Pricing.java 的折扣逻辑」
     * 这种自然语言描述里没有「修改/写入」字样，于是写入永远归不到这一步 —— 步骤永远 pending。
     * 现在两侧共用同一份只读判断：不是只读步骤就参与归属，读回步骤仍然不会抢走写入。
     */
    static boolean readOnlyGoal(String goalText) {
        return goalText.matches("(?isu).*(读回|读取|(?<![A-Za-z])读(?![A-Za-z])|确认内容|核对|查看|扫描|检索|搜|查找|统计|盘点|归类|梳理|grepFile|globFile|readFile|\\bread\\b|\\bgrep\\b|\\bfind\\b).*")
                && !goalText.matches("(?isu).*(写入|保存|创建文件|修改文件|删除|移动|复制|落地|执行命令|编译|安装|提交代码"
                + "|writeFile|createFile|editFile|appendFile|deletePath|movePath|copyPath|executeCommand"
                + "|\\b(write|create|edit|delete|move|copy|save|generate|modify|update|append|remove|rename|mkdir|compile|install)\\b).*");
    }

    public synchronized String fallback(String reason) {
        deliveryStatus = "INCOMPLETE";
        StringBuilder out = new StringBuilder("本次未确认完整交付。\n\n已确认的执行结果：");
        boolean any = false;
        for (var entry : evidence.entrySet()) {
            if (!stillCurrent(entry.getKey(), entry.getValue().fingerprint)) continue;
            out.append("\n- ").append(entry.getValue().tool).append("：").append(entry.getKey());
            any = true;
        }
        if (!any) out.append("暂无可验证的产物。 ");
        out.append("\n\n未完成事项：最终交付尚未验证，请勿将先前的计划或执行承诺视为已完成。")
                .append("\n阻碍原因：").append(reason == null ? stopReason : reason)
                .append("\n模型调用：").append(calls).append('/').append(limit).append("。未自动追加调用或扩大权限。");
        return out.toString();
    }
    public static final class Cancelled extends RuntimeException {}

    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String message) { super(message); }
    }
}
