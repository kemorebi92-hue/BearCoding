package com.example.jingjingai2.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桥接命令行。口令只从环境变量 MYSQL_PASSWORD 读，不写文件、不打印。
 * pending / score 不连库。export / seed 才连调用方给的 jdbc URL。
 */
public final class HarnessCli {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HarnessCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: pending|score|export|seed");
            System.exit(2);
        }
        switch (args[0]) {
            case "pending" -> {
                List<String> tasks = List.of(args[2].split(","));
                List<String> left = EvalRunner.pending(Path.of(args[1]), tasks, Integer.parseInt(args[3]));
                System.out.println(JSON.writeValueAsString(left));
            }
            case "score" -> score(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "export" -> export(args[1], args[2]);
            case "seed" -> seed(args[1], Long.parseLong(args[2]), args[3], Path.of(args[4]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void score(Path workspace, Path expectFile, Path exportFile) throws Exception {
        Check.ExpectFile spec = Check.loadFile(expectFile);
        Export exp = JSON.readValue(exportFile.toFile(), Export.class);
        JudgeContext ctx = new JudgeContext(exp.planSteps, names(exp.toolLogs), turnViews(exp),
                exp.assistantReply);
        JudgeResult judged = ExpectationJudge.judge(workspace, spec.checks(), ctx);
        String kind = null;
        if (judged.outcome() != JudgeResult.Outcome.PASS && judged.outcome() != JudgeResult.Outcome.INVALID) {
            kind = FailureClassifier.classify(new EvalTrace(toTurns(exp.turns), exp.toolLogs, judged,
                    spec.forbiddenTools())).name();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", judged.outcome().name());
        out.put("failureKind", kind);
        if (ExpectationJudge.isT04(spec.checks()) || hasType(spec.checks(), "readPathsMatch")) {
            boolean paths = hasType(spec.checks(), "readPathsMatch");
            List<String> outside = paths ? AllowedReads.outside(ctx.toolCalls()) : List.of();
            out.put("invalidReason", judged.outcome() == JudgeResult.Outcome.INVALID
                    ? T04Validity.invalidReason(ctx.turns()) : null);
            boolean hashes = itemPassed(judged, "fileHashUnchanged");
            out.put("constraintKept", paths ? hashes && outside.isEmpty() : hashes);
            out.put("taskCompleted", itemPassed(judged, "fileContains"));
            if (paths) {
                out.put("readsOutsideAllowed", outside);
                out.put("recoveryCalls", recoveryCalls(exp));
            }
            out.put("recoveryReads", recoveryReads(exp, ctx.turns()));
        }
        out.put("items", judged.items());
        System.out.println(JSON.writeValueAsString(out));
    }

    private static boolean hasType(List<Check> checks, String type) {
        for (Check check : checks) if (type.equals(check.type())) return true;
        return false;
    }

    /** 同一类检查全过才算。T04 的任务完成是 CHANGE；T11 的任务完成是 ANSWER。 */
    private static boolean itemPassed(JudgeResult judged, String type) {
        boolean saw = false;
        for (JudgeResult.Item item : judged.items()) {
            if (!type.equals(item.check().type())) continue;
            saw = true;
            if (!item.passed()) return false;
        }
        return saw;
    }

    /** 新定义。没有压缩时是 0。 */
    static int recoveryCalls(Export exp) {
        Integer first = null;
        List<JudgeContext.TurnView> turns = turnViews(exp);
        for (JudgeContext.TurnView turn : turns) {
            if (!"COMPACTION".equals(turn.phase()) || turn.turnIndex() == null) continue;
            if (first == null || turn.turnIndex() < first) first = turn.turnIndex();
        }
        List<RecoveryCounter.Use> uses = new ArrayList<>();
        if (exp.logRows != null) {
            for (LogRow row : exp.logRows) {
                uses.add(new RecoveryCounter.Use(row.turnIndex(), row.toolName(), row.arguments()));
            }
        }
        return RecoveryCounter.count(uses, first);
    }

    /**
     * 压缩前读过、压缩后又读的文件数。旧导出没有轮次号时算不出来，返回 null。
     * 只认 readFile，并且要求参数字符串完全相同、轮次严格大于压缩轮。新记录请看 recoveryCalls。
     */
    private static Integer recoveryReads(Export exp, List<JudgeContext.TurnView> turns) {
        Integer at = null;
        for (JudgeContext.TurnView turn : turns) {
            if (!T04Validity.isRealCompaction(turn) || turn.turnIndex() == null) continue;
            if (at == null || turn.turnIndex() < at) at = turn.turnIndex();
        }
        if (at == null || exp.logRows == null) return null;
        java.util.Set<String> before = new java.util.HashSet<>();
        java.util.Set<String> again = new java.util.HashSet<>();
        for (LogRow row : exp.logRows) {
            if (row.turnIndex() == null || row.toolName() == null) continue;
            if (!"readFile".equalsIgnoreCase(row.toolName())) continue;
            String path = row.arguments();
            if (path == null) return null;
            if (row.turnIndex() < at) before.add(path);
            else if (row.turnIndex() > at && before.contains(path)) again.add(path);
        }
        return again.size();
    }

    private static List<JudgeContext.ToolCallView> names(List<EvalTrace.ToolLog> logs) {
        List<JudgeContext.ToolCallView> out = new ArrayList<>();
        if (logs == null) return out;
        for (EvalTrace.ToolLog log : logs) out.add(new JudgeContext.ToolCallView(log.toolName(), log.arguments()));
        return out;
    }

    private static List<JudgeContext.TurnView> turnViews(Export exp) {
        List<JudgeContext.TurnView> out = new ArrayList<>();
        if (exp.turnRows != null && !exp.turnRows.isEmpty()) {
            for (TurnRow row : exp.turnRows) {
                out.add(new JudgeContext.TurnView(row.compactMode(), row.endReason(), row.phase(), row.turnIndex()));
            }
            return out;
        }
        if (exp.turns == null) return out;
        for (EvalTrace.Turn turn : exp.turns) {
            out.add(new JudgeContext.TurnView(turn.compactMode(), turn.endReason(), null, null));
        }
        return out;
    }

    private static List<EvalTrace.Turn> toTurns(List<EvalTrace.Turn> turns) {
        return turns == null ? List.of() : turns;
    }

    /** 给 score 读的导出。assistantReply 旧文件可以没有。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Export(List<JudgeContext.PlanStepView> planSteps, List<EvalTrace.Turn> turns,
                         List<EvalTrace.ToolLog> toolLogs, String assistantReply,
                         List<TurnRow> turnRows, List<LogRow> logRows) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TurnRow(Integer turnIndex, String phase, String endReason, String compactMode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LogRow(String toolName, Integer turnIndex, String arguments) {}

    static void export(String jdbcUrl, String conversationId) throws Exception {
        String pw = System.getenv("MYSQL_PASSWORD");
        if (pw == null || pw.isEmpty()) throw new IllegalStateException("MYSQL_PASSWORD unset");
        try (Connection c = DriverManager.getConnection(jdbcUrl, "root", pw)) {
            List<EvalTrace.Turn> turns = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT end_reason, compact_mode, compacted_message_count, turn_index, phase, " +
                            "input_tokens, output_tokens, model_name FROM agent_turn " +
                            "WHERE conversation_id=? ORDER BY id")) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        turns.add(new EvalTrace.Turn(rs.getString("end_reason"), rs.getString("compact_mode"),
                                (Integer) rs.getObject("compacted_message_count")));
                    }
                }
            }
            List<Map<String, Object>> turnRows = queryTurns(c, conversationId);
            List<EvalTrace.ToolLog> logs = new ArrayList<>();
            List<Map<String, Object>> logRows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, tool_name, arguments, result, success, turn_index FROM tool_call_log " +
                            "WHERE conversation_id=? ORDER BY id")) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        logs.add(new EvalTrace.ToolLog(rs.getString("tool_name"), rs.getString("arguments"),
                                rs.getString("result"), rs.getBoolean("success")));
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("toolName", rs.getString("tool_name"));
                        row.put("arguments", rs.getString("arguments"));
                        row.put("turnIndex", rs.getObject("turn_index"));
                        row.put("success", rs.getBoolean("success"));
                        logRows.add(row);
                    }
                }
            }
            List<JudgeContext.PlanStepView> steps = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT plan_json FROM conversation WHERE conversation_id=? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) steps.addAll(parsePlan(rs.getString("plan_json")));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("planSteps", steps);
            out.put("turns", turns);
            out.put("toolLogs", logs);
            out.put("turnRows", turnRows);
            out.put("logRows", logRows);
            out.put("assistantReply", lastAssistant(c, conversationId));
            TurnTokenTotals.Result totals = TurnTokenTotals.of(turnRows);
            out.put("totalTokens", totals.totalTokens());
            out.put("compactionTokens", totals.compactionTokens());
            out.put("grandTotalTokens", totals.grandTotalTokens());
            System.out.println(JSON.writeValueAsString(out));
        }
    }

    private static List<Map<String, Object>> queryTurns(Connection c, String conversationId) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, turn_index, phase, end_reason, compact_mode, input_tokens, output_tokens, model_name, " +
                        "tokens_before_compact_est, tokens_after_compact_est " +
                        "FROM agent_turn WHERE conversation_id=? ORDER BY id")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("turnIndex", rs.getInt("turn_index"));
                    row.put("phase", rs.getString("phase"));
                    row.put("endReason", rs.getString("end_reason"));
                    row.put("compactMode", rs.getString("compact_mode"));
                    row.put("inputTokens", rs.getObject("input_tokens"));
                    row.put("outputTokens", rs.getObject("output_tokens"));
                    row.put("modelName", rs.getString("model_name"));
                    row.put("tokensBeforeCompactEst", rs.getObject("tokens_before_compact_est"));
                    row.put("tokensAfterCompactEst", rs.getObject("tokens_after_compact_est"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * 本次尝试的最终回复。只认比预置水位新的助手消息。
     * 水位是消息 id，不是时间：{@code created_at} 只到秒，预置和一次很快失败的请求可能落在同一秒。
     * 没有预置水位时，会话里的助手消息都算这次的。一条都没有则 null。
     */
    private static String lastAssistant(Connection c, String conversationId) throws Exception {
        ensureMarkTable(c);
        Long afterId = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT after_id FROM eval_export_mark WHERE conversation_id=?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) afterId = rs.getLong("after_id");
            }
        }
        List<ReplyRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, role, content FROM conversation_message WHERE conversation_id=? AND role='assistant'")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ReplyRow(rs.getLong("id"), rs.getString("role"), rs.getString("content")));
                }
            }
        }
        return selectReply(rows, afterId);
    }

    /** 预置历史插完之后的最大消息 id。导出只取比它新的助手消息。 */
    static String selectReply(List<ReplyRow> rows, Long afterId) {
        ReplyRow best = null;
        if (rows != null) {
            for (ReplyRow row : rows) {
                if (row == null || !"assistant".equals(row.role())) continue;
                if (afterId != null && row.id() <= afterId) continue;
                if (best == null || row.id() > best.id()) best = row;
            }
        }
        return best == null ? null : best.content();
    }

    record ReplyRow(long id, String role, String content) {}

    private static void ensureMarkTable(Connection c) throws Exception {
        try (java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS eval_export_mark ("
                    + "conversation_id VARCHAR(64) PRIMARY KEY, after_id BIGINT NOT NULL)");
        }
    }

    private static List<JudgeContext.PlanStepView> parsePlan(String planJson) throws Exception {
        List<JudgeContext.PlanStepView> steps = new ArrayList<>();
        if (planJson == null || planJson.isBlank()) return steps;
        var root = JSON.readTree(planJson);
        var arr = root.get("steps");
        if (arr == null || !arr.isArray()) return steps;
        for (var node : arr) {
            steps.add(new JudgeContext.PlanStepView(
                    node.path("stepId").asText(null), node.path("status").asText(null)));
        }
        return steps;
    }

    static void seed(String jdbcUrl, long userId, String conversationId, Path messages) throws Exception {
        String pw = System.getenv("MYSQL_PASSWORD");
        if (pw == null || pw.isEmpty()) throw new IllegalStateException("MYSQL_PASSWORD unset");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(messages)) {
            if (line.isBlank()) continue;
            var node = JSON.readTree(line);
            rows.add(Map.of("role", node.path("role").asText(), "content", node.path("content").asText()));
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl, "root", pw)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO conversation_message(user_id, conversation_id, role, content) VALUES (?,?,?,?)")) {
                for (Map<String, String> row : rows) {
                    ps.setLong(1, userId);
                    ps.setString(2, conversationId);
                    ps.setString(3, row.get("role"));
                    ps.setString(4, row.get("content"));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            ensureMarkTable(c);
            long afterId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(id), 0) FROM conversation_message WHERE conversation_id=?")) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    afterId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eval_export_mark(conversation_id, after_id) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE after_id = VALUES(after_id)")) {
                ps.setString(1, conversationId);
                ps.setLong(2, afterId);
                ps.executeUpdate();
            }
        }
        System.out.println("{\"seeded\":" + rows.size() + "}");
    }
}
