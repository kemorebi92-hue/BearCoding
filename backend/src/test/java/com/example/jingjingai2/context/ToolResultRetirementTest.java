package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2：工具结果退休的触发条件、预算统计和安全边界。
 *
 * <p>真实组装顺序下工具结果永远在本轮 user 之后。旧规则用「最近用户回合」保护它们，
 * 结果一条都不可能退休；新规则改用**同一请求内的模型轮次**，并且只有在
 * 历史 + 工具结果确实超过软预算时才把较早、可安全恢复的结果换成省略标记。
 *
 * <p>本测试用 {@link TempDir} 建真实快照文件：标记指向的文件必须真的存在。
 * 恢复内容的哈希/篡改校验留给 M3，不在这里冒充已完成。
 */
class ToolResultRetirementTest {

    @TempDir
    Path tmp;

    /** 历史本身远低于这条线，只有把工具结果算进去才会超预算。 */
    private static final int TEST_SOFT_BUDGET = 1000;

    private static final int BODY_REPEAT = 1500;

    @Test
    @DisplayName("同一请求多轮工具调用：预算压力下只退休较早且可安全恢复的结果")
    void multiRoundToolResultsRetireOnlyOldSafeBodies() throws Exception {
        List<Message> actual = promptWithFourToolRounds();
        List<String> bodies = bodiesOf(actual);
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, TEST_SOFT_BUDGET, 0, false, true);

        assertRetired(projected, "call-1", bodies.get(0));
        assertRetired(projected, "call-2", bodies.get(1));
        assertKept(projected, "call-3", bodies.get(2), "TOOL_RESULT_RECENT_ROUND");
        assertKept(projected, "call-4", bodies.get(3), "TOOL_RESULT_RECENT_ROUND");

        assertTrue(MessageProtocolValidator.check(projected.messages()).ok(), "退休后工具配对必须完整");
        for (int r = 1; r <= 4; r++) {
            assertTrue(hasCall(projected.messages(), "call-" + r), "tool_call 必须留下: call-" + r);
            assertEquals(bodies.get(r - 1), bodyOf(actual, "call-" + r), "原始执行消息不能被改写");
        }
        assertTrue(ledger.get("tool:call-1").fullText().contains(bodies.get(0)), "源账本原文不变");

        ContextProjectionPolicy.Stats stats = projected.stats();
        assertTrue(stats.toolResultTokensEst() > 0, "工具结果必须进入预算统计");
        assertTrue(stats.budgetPressureTokensEst() >= stats.toolResultTokensEst(),
                "预算压力口径必须包含工具结果");
        assertTrue(stats.shadowRetireTokensEst() > 0, "确有退休时 retire 统计必须大于 0");
        assertTrue(stats.reasonCounts().containsKey("RETIRE:RECOVERABLE_OLD_TOOL_GROUP"));
    }

    @Test
    @DisplayName("没有预算压力时一条工具结果都不退休，正文原样留下")
    void noBudgetPressureKeepsEveryToolResult() throws Exception {
        List<Message> actual = promptWithFourToolRounds();
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, 1_000_000, 0, false, true);

        assertEquals(0, projected.stats().shadowRetireTokensEst(), "预算充足时不得退休");
        for (int r = 1; r <= 4; r++) {
            assertEquals(bodiesOf(actual).get(r - 1), bodyOf(projected.messages(), "call-" + r),
                    "预算充足时正文必须原样保留");
            assertEquals("TOOL_RESULT_NO_BUDGET_PRESSURE",
                    decision(projected, "tool:call-" + r).reason());
        }
    }

    @Test
    @DisplayName("快照文件已不存在时禁止退休，正文必须留下")
    void missingSnapshotBlocksRetirement() throws Exception {
        String body = body(1);
        Path gone = tmp.resolve("gone.txt");
        String signed = body + ToolArtifactRef.marker(gone.toString(), body.length(), "readFile", sha256(body));
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, TEST_SOFT_BUDGET, 0, false, true);

        assertEquals(signed, bodyOf(projected.messages(), "call-1"), "没有快照就没有安全恢复入口");
        assertEquals("TOOL_RESULT_SNAPSHOT_MISSING", decision(projected, "tool:call-1").reason());
    }

    @Test
    @DisplayName("无签名快照的内联结果不退休，正文照发")
    void inlineResultIsNeverRetired() throws Exception {
        List<Message> actual = singleToolPrompt("没有快照的搜索结果" + "内容".repeat(BODY_REPEAT));
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, TEST_SOFT_BUDGET, 0, false, true);

        assertTrue(toolBodies(projected.messages()).contains("没有快照的搜索结果"));
        assertEquals("TOOL_RESULT_NOT_RECOVERABLE", decision(projected, "tool:call-1").reason());
    }

    @Test
    @DisplayName("签名被篡改的引用不能当作可恢复快照")
    void tamperedMarkerIsNotRecoverable() throws Exception {
        String body = body(1);
        Path file = writeSnapshot(1, body);
        String marker = ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
        String tampered = marker.replace(sha256(body), sha256("different"));
        assertNotNull(ToolArtifactRef.parse(marker));
        List<Message> actual = singleToolPrompt(body + tampered);
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, TEST_SOFT_BUDGET, 0, false, true);

        assertEquals("TOOL_RESULT_NOT_RECOVERABLE", decision(projected, "tool:call-1").reason());
        assertTrue(toolBodies(projected.messages()).contains(body), "不可验证的正文必须留下");
    }

    @Test
    @DisplayName("轮次未知时不退休：没有轮次就无法判断是不是较早结果")
    void unknownRoundKeepsToolResult() throws Exception {
        String signed = signedBody(1);
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, -1, 0, TEST_SOFT_BUDGET, 0, false, true);

        assertEquals(signed, bodyOf(projected.messages(), "call-1"));
        assertEquals("TOOL_RESULT_ROUND_UNKNOWN", decision(projected, "tool:call-1").reason());
    }

    @Test
    @DisplayName("活动计划步骤依赖的工具结果是证据，不退休")
    void activePlanStepKeepsEvidence() throws Exception {
        String body = body(1);
        Path file = writeSnapshot(1, body);
        String signed = body + ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(assistantCall("call-1", "readFile", "{\"path\":\"" + file + "\"}"));
        actual.add(toolResult("call-1", "readFile", signed));

        TaskPlan plan = new TaskPlan();
        PlanStep step = new PlanStep("s1", "读取 " + file.getFileName(), "读完");
        step.setStatus(PlanStep.RUNNING);
        plan.setSteps(List.of(step));

        ContextSourceLedger ledger = ledgerFor(actual, plan);
        assertNotNull(ledger.get("tool:call-1").planStepId(), "测试前提：结果必须匹配到活动步骤");

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, plan, 5, 0, TEST_SOFT_BUDGET, 0, false, true);

        assertEquals("PROTECTED", decision(projected, "tool:call-1").reason());
        assertEquals(signed, bodyOf(projected.messages(), "call-1"));
    }

    @Test
    @DisplayName("退休只换正文：配对保留，标记里带着可 readFile 的签名引用")
    void retirementKeepsPairingAndRecoveryPointer() throws Exception {
        String body = body(1);
        Path file = writeSnapshot(1, body);
        String signed = body + ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
        List<Message> actual = singleToolPrompt(signed);
        ContextSourceLedger ledger = ledgerFor(actual, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, TEST_SOFT_BUDGET, 0, false, true);

        String bodies = toolBodies(projected.messages());
        assertFalse(bodies.contains(body), "正文应被换掉，不再发给模型");
        assertTrue(bodies.contains(ToolHistoryCompactor.MARKER_PREFIX), "应换成省略标记");
        assertTrue(bodies.contains(file.toString()), "标记里要留下快照路径");

        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(bodies);
        assertNotNull(ref, "标记里的引用必须能验签通过");
        assertEquals(file.toString(), ref.path());
        assertEquals(sha256(body), ref.sha256());
        assertTrue(hasCall(projected.messages(), "call-1"), "tool_call 必须留下，否则结果变孤儿");
        assertTrue(MessageProtocolValidator.check(projected.messages()).ok());
        assertTrue(ledger.get("tool:call-1").fullText().contains(body), "源账本原文不变");
        assertEquals(signed, bodyOf(actual, "call-1"), "传入的执行消息不能被投影改写");
    }

    @Test
    @DisplayName("旧助手消息退休与用户约束保留不回归")
    void oldAssistantRetirementStillWorks() {
        List<MessageRecord> rows = List.of(
                row(1L, "user", "任务要求：只允许改 report.md"),
                row(2L, "assistant", "很久以前的旧回复" + "正文".repeat(400)),
                row(3L, "user", "纠正：用中文，禁止英文"),
                row(4L, "user", "授权：允许修改配置"),
                row(5L, "user", "最近问题一"),
                row(6L, "assistant", "最近回复一"),
                row(7L, "user", "最近问题二"),
                row(8L, "assistant", "最近回复二"),
                row(9L, "user", "当前问题"));

        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        for (MessageRecord row : rows) {
            actual.add("user".equals(row.getRole())
                    ? new UserMessage(row.getContent()) : new AssistantMessage(row.getContent()));
        }

        ContextSourceLedger ledger = new ContextSourceLedger("conv-1");
        ledger.addHistory(rows, "当前问题");
        ledger.absorb(actual, 0, null);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 9, 0, 1, 0, false, true);

        List<String> texts = texts(projected.messages());
        assertFalse(texts.stream().anyMatch(t -> t.contains("很久以前的旧回复")), "旧助手回复应退出");
        assertTrue(texts.stream().anyMatch(t -> t.contains("locator=message|2")),
                "退休说明带可直接读取的 locator");
        assertTrue(texts.contains("任务要求：只允许改 report.md"), "用户任务要求不丢");
        assertTrue(texts.contains("纠正：用中文，禁止英文"), "用户纠正不丢");
        assertTrue(texts.contains("授权：允许修改配置"), "用户授权不丢");
        assertTrue(MessageProtocolValidator.check(projected.messages()).ok());
    }

    // ===================== helpers =====================

    private List<Message> promptWithFourToolRounds() throws Exception {
        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统提示"));
        for (MessageRecord row : historyRows()) {
            actual.add("user".equals(row.getRole())
                    ? new UserMessage(row.getContent()) : new AssistantMessage(row.getContent()));
        }
        for (int r = 1; r <= 4; r++) {
            actual.add(assistantCall("call-" + r, "readFile", "{\"path\":\"f" + r + ".txt\"}"));
            actual.add(toolResult("call-" + r, "readFile", signedBody(r)));
        }
        return actual;
    }

    /** 按真实请求逐轮吸收：producedRound 是「第几次模型调用前看到它」，不是消息下标。 */
    private ContextSourceLedger ledgerFor(List<Message> actual, TaskPlan plan) {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-1");
        ledger.addHistory(historyRows(), "当前问题");
        int toolMessages = 0;
        for (Message message : actual) {
            if (message instanceof ToolResponseMessage) toolMessages++;
        }
        int firstToolAt = actual.size() - toolMessages * 2;
        int round = 1;
        for (int i = 0; i < toolMessages; i++) {
            int end = firstToolAt + (i + 1) * 2;
            ledger.absorb(new ArrayList<>(actual.subList(0, end)), round++, plan);
        }
        ledger.absorb(actual, round, plan);
        return ledger;
    }

    private List<Message> singleToolPrompt(String body) throws Exception {
        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(new UserMessage("当前问题"));
        actual.add(assistantCall("call-1", "readFile", "{\"path\":\"f1.txt\"}"));
        actual.add(toolResult("call-1", "readFile", body));
        return actual;
    }

    private String signedBody(int r) throws Exception {
        String body = body(r);
        Path file = writeSnapshot(r, body);
        return body + ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
    }

    private Path writeSnapshot(int r, String body) throws Exception {
        Path file = tmp.resolve("snapshot-" + r + ".txt");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    private static String body(int r) {
        return "工具结果" + r + "：" + "内容".repeat(BODY_REPEAT);
    }

    private static List<MessageRecord> historyRows() {
        return List.of(
                row(1L, "user", "要求一"),
                row(2L, "assistant", "旧回答一"),
                row(3L, "user", "要求二"),
                row(4L, "assistant", "旧回答二"),
                row(5L, "user", "要求三"),
                row(6L, "assistant", "旧回答三"),
                row(7L, "user", "当前问题"));
    }

    private static void assertRetired(ContextProjectionPolicy.Result projected, String callId, String original)
            throws Exception {
        ContextProjectionPolicy.Decision decision = decision(projected, "tool:" + callId);
        assertEquals(ContextProjectionPolicy.DecisionKind.RETIRE, decision.kind(), decision.toString());
        assertEquals("RECOVERABLE_OLD_TOOL_GROUP", decision.reason());
        String body = bodyOf(projected.messages(), callId);
        assertFalse(body.contains(original), "退休后不应再带原文: " + callId);
        assertTrue(ToolHistoryCompactor.isMarker(body), "退休后应是省略标记: " + body);
        assertNotNull(ToolArtifactRef.parse(body), "省略标记必须带可验证引用: " + body);
    }

    private static void assertKept(ContextProjectionPolicy.Result projected, String callId, String original,
                                   String reason) {
        ContextProjectionPolicy.Decision decision = decision(projected, "tool:" + callId);
        assertEquals(ContextProjectionPolicy.DecisionKind.KEEP, decision.kind(), decision.toString());
        assertEquals(reason, decision.reason());
        assertEquals(original, bodyOf(projected.messages(), callId), "保留的工具正文必须逐字一致");
    }

    private static ContextProjectionPolicy.Decision decision(ContextProjectionPolicy.Result projected, String id) {
        return projected.decisions().stream().filter(d -> id.equals(d.id())).findFirst()
                .orElseThrow(() -> new AssertionError("没有决策: " + id));
    }

    private static List<String> bodiesOf(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    out.add(response.responseData());
                }
            }
        }
        return out;
    }

    private static String bodyOf(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && id.equals(response.id())) return response.responseData();
            }
        }
        return null;
    }

    private static String toolBodies(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (String body : bodiesOf(messages)) out.append(body).append('\n');
        return out.toString();
    }

    private static List<String> texts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message message : messages) out.add(message.getText() == null ? "" : message.getText());
        return out;
    }

    private static boolean hasCall(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant) || assistant.getToolCalls() == null) continue;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call != null && id.equals(call.id())) return true;
            }
        }
        return false;
    }

    private static MessageRecord row(Long id, String role, String content) {
        MessageRecord record = new MessageRecord(role, content);
        record.setId(id);
        record.setTimestamp(new Date(id * 1000));
        return record;
    }

    private static AssistantMessage assistantCall(String id, String name, String arguments) {
        return new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)));
    }

    private static ToolResponseMessage toolResult(String id, String name, String body) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, name, body)));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
