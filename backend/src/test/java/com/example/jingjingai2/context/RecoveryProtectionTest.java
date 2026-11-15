package com.example.jingjingai2.context;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase M3-3：刚恢复的内容不会在下一轮被再次退休。
 *
 * <p>两条恢复路径：
 * <ul>
 *   <li>A. {@code searchContext operation=read} 取回旧助手消息 / 用户记忆 / 计划信息；</li>
 *   <li>B. {@code readFile} 按签名引用读回旧工具正文。</li>
 * </ul>
 *
 * <p>恢复结果就是模型这一步要用的证据，所以在 {@link ContextProjectionPolicy#RECOVERY_PROTECT_ROUNDS}
 * 个模型轮次内不参与退休。身份只来自**工具名 + 入参结构**（签名引用 / operation=read），
 * 不看正文关键词，也不跨请求。保护到期后所有闸门重新生效。
 */
class RecoveryProtectionTest {

    @TempDir
    Path tmp;

    private static final int SOFT_BUDGET = 1000;
    private static final String SNAPSHOT_BODY = "旧工具正文：" + "证据".repeat(400);
    private static final String RECOVERED = "恢复的旧正文：" + "内容".repeat(1500);

    @Test
    @DisplayName("readFile 按引用恢复的正文：保护期内不退休，正文继续发给模型")
    void readFileRecoveryIsProtected() throws Exception {
        Path snapshot = snapshot("readfile.txt");
        String ref = markerOf(snapshot);
        List<Message> actual = prompt(call("read-1", "readFile", "{\"path\":\"" + escape(ref) + "\"}"),
                result("read-1", "readFile", recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true);

        ContextProjectionPolicy.Decision decision = decision(projected, "tool:read-1");
        assertEquals(ContextProjectionPolicy.DecisionKind.KEEP, decision.kind(), decision.toString());
        assertEquals("TOOL_RESULT_RECOVERY_PROTECTED", decision.reason());
        assertTrue(bodyOf(projected.messages(), "read-1").contains("恢复的旧正文"), "刚恢复的正文不能被换掉");
    }

    @Test
    @DisplayName("searchContext read 恢复的旧内容：保护期内不退休")
    void searchContextReadRecoveryIsProtected() throws Exception {
        Path snapshot = snapshot("search.txt");
        List<Message> actual = prompt(
                call("ctx-1", "searchContext", "{\"operation\":\"read\",\"locator\":\"message|21\"}"),
                result("ctx-1", "searchContext", recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 4, 0, SOFT_BUDGET, 0, false, true);

        ContextProjectionPolicy.Decision decision = decision(projected, "tool:ctx-1");
        assertEquals(ContextProjectionPolicy.DecisionKind.KEEP, decision.kind(), decision.toString());
        assertEquals("TOOL_RESULT_RECOVERY_PROTECTED", decision.reason());
    }

    @Test
    @DisplayName("保护到期后仍需满足全部条件：到期即按 M2 规则退休，配对保持")
    void protectionExpiresAndNormalGatesApply() throws Exception {
        Path snapshot = snapshot("expire.txt");
        String ref = markerOf(snapshot);
        List<Message> actual = prompt(call("read-1", "readFile", "{\"path\":\"" + escape(ref) + "\"}"),
                result("read-1", "readFile", recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);

        // producedRound=1；保护期 6 轮，round=7 时刚好到期。
        ContextProjectionPolicy.Result expired = ContextProjectionPolicy.project(
                ledger, actual, null, 1 + ContextProjectionPolicy.RECOVERY_PROTECT_ROUNDS, 0,
                SOFT_BUDGET, 0, false, true);
        assertEquals(ContextProjectionPolicy.DecisionKind.RETIRE,
                decision(expired, "tool:read-1").kind(),
                decision(expired, "tool:read-1").toString());
        assertFalse(bodyOf(expired.messages(), "read-1").contains("恢复的旧正文"));
        assertTrue(hasCall(expired.messages(), "read-1"), "退休只换正文，tool_call 必须留下");
        assertTrue(MessageProtocolValidator.check(expired.messages()).ok());
        assertNotNull(ToolArtifactRef.parse(bodyOf(expired.messages(), "read-1")),
                "退休后的省略标记必须带可验证引用");
    }

    @Test
    @DisplayName("保护到期也不等于无条件退休：快照坏了照样留下")
    void expiryStillNeedsAnIntactSnapshot() throws Exception {
        Path snapshot = snapshot("broken.txt");
        String ref = markerOf(snapshot);
        List<Message> actual = prompt(call("read-1", "readFile", "{\"path\":\"" + escape(ref) + "\"}"),
                result("read-1", "readFile", recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);
        Files.writeString(snapshot, "被替换的内容", StandardCharsets.UTF_8);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 1 + ContextProjectionPolicy.RECOVERY_PROTECT_ROUNDS, 0,
                SOFT_BUDGET, 0, false, true);

        assertEquals("TOOL_RESULT_SNAPSHOT_HASH_MISMATCH", decision(projected, "tool:read-1").reason());
        assertTrue(bodyOf(projected.messages(), "read-1").contains("恢复的旧正文"));
    }

    @Test
    @DisplayName("保护到期也不等于无条件退休：没有预算压力就不退休")
    void expiryStillNeedsBudgetPressure() throws Exception {
        Path snapshot = snapshot("nopressure.txt");
        String ref = markerOf(snapshot);
        List<Message> actual = prompt(call("read-1", "readFile", "{\"path\":\"" + escape(ref) + "\"}"),
                result("read-1", "readFile", recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 1 + ContextProjectionPolicy.RECOVERY_PROTECT_ROUNDS, 0,
                1_000_000, 0, false, true);

        assertEquals("TOOL_RESULT_NO_BUDGET_PRESSURE", decision(projected, "tool:read-1").reason());
    }

    @Test
    @DisplayName("普通工具结果不受这条保护：同一轮次下照常退休")
    void ordinaryToolResultIsNotProtected() throws Exception {
        Path snapshot = snapshot("ordinary.txt");
        List<Message> actual = prompt(call("grep-1", "grepFile", "{\"pattern\":\"Invoice\"}"),
                result("grep-1", "grepFile", recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true);

        assertEquals(ContextProjectionPolicy.DecisionKind.RETIRE,
                decision(projected, "tool:grep-1").kind(), "没有恢复身份的结果按原规则处理");
    }

    @Test
    @DisplayName("身份来自工具名和入参，不看正文：正文写着「已恢复」也不算恢复结果")
    void identityIsNotDecidedByContent() throws Exception {
        Path snapshot = snapshot("content.txt");
        List<Message> actual = prompt(call("grep-2", "grepFile", "{\"pattern\":\"已恢复\"}"),
                result("grep-2", "grepFile", "已恢复的必要内容如下：\n" + recoveredWithRef(snapshot)));
        ContextSourceLedger ledger = ledgerFor(actual);

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, SOFT_BUDGET, 0, false, true);

        assertEquals(ContextProjectionPolicy.DecisionKind.RETIRE,
                decision(projected, "tool:grep-2").kind(), "不能靠正文措辞判断恢复身份");
    }

    @Test
    @DisplayName("活动步骤依赖的恢复证据仍然受 PROTECTED 保护（复用原有规则）")
    void activeStepEvidenceWins() throws Exception {
        Path snapshot = snapshot("step.txt");
        String ref = markerOf(snapshot);
        List<Message> actual = prompt(call("read-1", "readFile", "{\"path\":\"" + escape(ref) + "\"}"),
                result("read-1", "readFile", recoveredWithRef(snapshot)));

        TaskPlan plan = new TaskPlan();
        PlanStep step = new PlanStep("s1", "核对 " + snapshot.getFileName(), "核对完成");
        step.setStatus(PlanStep.RUNNING);
        plan.setSteps(List.of(step));

        ContextSourceLedger ledger = new ContextSourceLedger("conv-m3");
        ledger.absorb(actual, 1, plan);
        assertNotNull(ledger.get("tool:read-1").planStepId(), "测试前提：恢复结果必须匹配到活动步骤");

        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, plan, 9, 0, SOFT_BUDGET, 0, false, true);

        assertEquals("PROTECTED", decision(projected, "tool:read-1").reason(),
                "活动步骤证据保护优先，且不重复增加状态");
    }

    // ===================== helpers =====================

    private Path snapshot(String name) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, SNAPSHOT_BODY, StandardCharsets.UTF_8);
        return file;
    }

    private String markerOf(Path snapshot) throws Exception {
        return ToolArtifactRef.marker(snapshot.toString(), SNAPSHOT_BODY.length(), "executeCommand",
                sha256(SNAPSHOT_BODY));
    }

    /** 恢复结果的正文：取回的内容 + 一条仍然有效的签名引用（所以它本来是可退休的）。 */
    private String recoveredWithRef(Path snapshot) throws Exception {
        return RECOVERED + "\n" + markerOf(snapshot);
    }

    private static List<Message> prompt(AssistantMessage call, ToolResponseMessage result) {
        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(new UserMessage("当前问题"));
        actual.add(call);
        actual.add(result);
        return actual;
    }

    private static ContextSourceLedger ledgerFor(List<Message> actual) {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-m3");
        ledger.absorb(actual, 1, null);
        return ledger;
    }

    private static AssistantMessage call(String id, String name, String arguments) {
        return new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)));
    }

    private static ToolResponseMessage result(String id, String name, String body) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, name, body)));
    }

    /** JSON 入参里的引号与反斜杠转义。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ContextProjectionPolicy.Decision decision(ContextProjectionPolicy.Result projected, String id) {
        return projected.decisions().stream().filter(d -> id.equals(d.id())).findFirst()
                .orElseThrow(() -> new AssertionError("没有决策: " + id));
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

    private static boolean hasCall(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant) || assistant.getToolCalls() == null) continue;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call != null && id.equals(call.id())) return true;
            }
        }
        return false;
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
