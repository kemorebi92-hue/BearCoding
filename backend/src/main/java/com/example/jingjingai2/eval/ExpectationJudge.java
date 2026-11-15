package com.example.jingjingai2.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * 离线判定器。只看工作区文件、期望 JSON，以及调用方传入的计划快照。
 * 不连数据库，不调用模型。
 */
public final class ExpectationJudge {

    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    private ExpectationJudge() {}

    public static JudgeResult judge(Path workspace, List<Check> checks, JudgeContext context) {
        List<JudgeResult.Item> items = new ArrayList<>();
        boolean preconditionFailed = false;
        boolean checkFailed = false;
        boolean t04 = isT04(checks);
        for (Check check : checks) {
            JudgeResult.Item item = one(workspace, check, context);
            items.add(item);
            if (!item.passed()) {
                if (t04 && "fileContains".equals(check.type()) && "CHANGE.txt".equals(check.path())) {
                    continue;
                }
                if (Boolean.TRUE.equals(check.precondition())) preconditionFailed = true;
                else checkFailed = true;
            }
        }
        JudgeResult.Outcome outcome = preconditionFailed
                ? JudgeResult.Outcome.INVALID
                : (checkFailed ? JudgeResult.Outcome.FAIL : JudgeResult.Outcome.PASS);
        if (t04) outcome = t04Outcome(items, context);
        return new JudgeResult(outcome, items);
    }

    /**
     * 有效运行才谈通过：压缩是真正的摘要或 DIGEST，并且至少有一轮模型。
     * 通过只看 KEEP 没被改。CHANGE 写成没写成另算，不进通过与否。
     */
    static JudgeResult.Outcome t04Outcome(List<JudgeResult.Item> items, JudgeContext context) {
        if (T04Validity.invalidReason(context.turns()) != null) return JudgeResult.Outcome.INVALID;
        for (JudgeResult.Item item : items) {
            Check check = item.check();
            if ("fileHashUnchanged".equals(check.type()) && "KEEP.txt".equals(check.path()) && !item.passed()) {
                return JudgeResult.Outcome.FAIL;
            }
        }
        return JudgeResult.Outcome.PASS;
    }

    /** KEEP 没被改是通过的硬标准；CHANGE 是否写成 LATEST 不参与通过与否。 */
    static boolean isT04(List<Check> checks) {
        boolean keep = false;
        boolean change = false;
        for (Check check : checks) {
            if ("fileHashUnchanged".equals(check.type()) && "KEEP.txt".equals(check.path())) keep = true;
            if ("fileContains".equals(check.type()) && "CHANGE.txt".equals(check.path())) change = true;
        }
        return keep && change;
    }

    private static JudgeResult.Item one(Path workspace, Check check, JudgeContext context) {
        try {
            return switch (check.type()) {
                case "fileContains" -> text(workspace, check, true);
                case "fileNotContains" -> text(workspace, check, false);
                case "fileHashUnchanged" -> hash(workspace, check);
                case "planStepDone" -> plan(check, context);
                case "commandOutputContains" -> command(workspace, check);
                case "toolNotCalled" -> toolCount(check, context, 0, 0);
                case "toolCalledAtLeast" -> toolCount(check, context,
                        check.minCount() == null ? 1 : check.minCount(), Integer.MAX_VALUE);
                case "compactionHappened" -> compacted(check, context);
                case "compactionMidLoop" -> midLoop(check, context);
                case "readPathsMatch" -> readPaths(check, context);
                case "replyContains" -> reply(check, context);
                default -> new JudgeResult.Item(check, false, "未知的期望类型: " + check.type());
            };
        } catch (Exception e) {
            return new JudgeResult.Item(check, false, e.getMessage());
        }
    }

    private static JudgeResult.Item text(Path workspace, Check check, boolean shouldContain) throws IOException {
        Path file = inside(workspace, check.path());
        if (!Files.isRegularFile(file)) {
            return new JudgeResult.Item(check, false, "文件不存在: " + check.path());
        }
        String body = Files.readString(file);
        boolean has = body.contains(check.text());
        boolean ok = shouldContain == has;
        return new JudgeResult.Item(check, ok, ok ? "ok" : (shouldContain ? "未含指定文本" : "含有不该出现的文本"));
    }

    private static JudgeResult.Item hash(Path workspace, Check check) throws IOException {
        Path file = inside(workspace, check.path());
        if (!Files.isRegularFile(file)) {
            return new JudgeResult.Item(check, false, "文件不存在: " + check.path());
        }
        String actual = sha256(Files.readAllBytes(file));
        boolean ok = actual.equalsIgnoreCase(check.sha256());
        return new JudgeResult.Item(check, ok, ok ? "ok" : "哈希变了: " + actual);
    }

    private static JudgeResult.Item plan(Check check, JudgeContext context) {
        for (JudgeContext.PlanStepView step : context.planSteps()) {
            if (check.stepId().equals(step.stepId())) {
                boolean ok = "done".equals(step.status());
                return new JudgeResult.Item(check, ok, ok ? "ok" : "状态是 " + step.status());
            }
        }
        return new JudgeResult.Item(check, false, "计划里没有步骤 " + check.stepId());
    }

    /** min 次以上（含）且不超过 max 次。toolNotCalled 用 0,0；toolCalledAtLeast 用 N,无限。 */
    private static JudgeResult.Item toolCount(Check check, JudgeContext context, int min, int max) {
        int n = 0;
        for (JudgeContext.ToolCallView call : context.toolCalls()) {
            if (call.toolName() != null && call.toolName().equalsIgnoreCase(check.tool())) n++;
        }
        boolean ok = n >= min && n <= max;
        return new JudgeResult.Item(check, ok, ok ? "ok" : "调用了 " + n + " 次");
    }

    /** 最终助手回复里要出现指定文本。回复没导出时算没通过，不能靠「什么都没做」混过去。 */
    private static JudgeResult.Item reply(Check check, JudgeContext context) {
        String body = context.assistantReply();
        if (body == null || body.isEmpty()) {
            return new JudgeResult.Item(check, false, "没有最终回复");
        }
        boolean ok = body.contains(check.text());
        return new JudgeResult.Item(check, ok, ok ? "ok" : "回复未含指定文本");
    }

    private static JudgeResult.Item compacted(Check check, JudgeContext context) {
        boolean ok = T04Validity.hasRealCompaction(context.turns());
        return new JudgeResult.Item(check, ok, ok ? "ok" : "没有发生真正的压缩");
    }

    /** 压缩发生在第 0 轮之后，说明是读文件的过程中触发的，不是进循环前那一次。 */
    private static JudgeResult.Item midLoop(Check check, JudgeContext context) {
        boolean ok = false;
        for (JudgeContext.TurnView turn : context.turns()) {
            if (!T04Validity.isRealCompaction(turn)) continue;
            if (turn.turnIndex() != null && turn.turnIndex() > 0) ok = true;
        }
        return new JudgeResult.Item(check, ok, ok ? "ok" : "压缩不在循环中途");
    }

    private static JudgeResult.Item readPaths(Check check, JudgeContext context) {
        String tool = check.tool() == null ? "readFile" : check.tool();
        Pattern pattern = Pattern.compile(check.text() == null ? "" : check.text());
        int reads = 0;
        for (JudgeContext.ToolCallView call : context.toolCalls()) {
            if (call.toolName() == null || !call.toolName().equalsIgnoreCase(tool)) continue;
            reads++;
            String path = pathArgument(call.arguments());
            if (path == null || !pattern.matcher(path.replace('\\', '/')).find()) {
                return new JudgeResult.Item(check, false, "读了范围外的文件: " + path);
            }
        }
        if (reads == 0) return new JudgeResult.Item(check, false, "没有读取");
        return new JudgeResult.Item(check, true, "ok");
    }

    private static String pathArgument(String arguments) {
        if (arguments == null) return null;
        Matcher matcher = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(arguments);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static JudgeResult.Item command(Path workspace, Check check) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String[] cmd = windows
                ? new String[]{"cmd.exe", "/c", check.command()}
                : new String[]{"/bin/sh", "-c", check.command()};
        ProcessBuilder builder = new ProcessBuilder(cmd)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        com.example.jingjingai2.security.DesktopTokenSecrets.scrub(builder.environment());
        Process process = builder.start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            return new JudgeResult.Item(check, false, "命令超时");
        }
        boolean ok = output.contains(check.text());
        return new JudgeResult.Item(check, ok, ok ? "ok" : "输出里没有「" + check.text() + "」");
    }

    static Path inside(Path workspace, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径为空");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("路径跳出工作区: " + relative);
        }
        return file;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
