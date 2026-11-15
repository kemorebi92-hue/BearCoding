package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.agent.TaskExecution;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.permission.SensitiveRedactor;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2：计划执行证据的敏感信息保护。
 *
 * <p>钉死两件互相拉扯的事：
 * <ol>
 *   <li><b>证据可信度不能降级</b> —— DONE / {@code resultVerified} 仍然只能由「本次请求真实执行过」
 *       的文件写入 / 命令成功 / 真实删除来产生，模型自述不算；</li>
 *   <li><b>凭证不能落库或推到前端</b> —— 命令原文、命令输出、模型上报正文里的模拟凭证，
 *       都不得出现在 {@code plan_json}（真实 SQLite 文件）与「前端拿到的那份对象」里。</li>
 * </ol>
 *
 * <p>持久化用真实的 SQLite 临时库（只把 mapper 的传输层换成直连 SQL），不碰正式数据库；
 * 全部凭证都是明显无效的假值。
 */
class PlanEvidenceRedactionTest {

    // 明显无效的模拟凭证：形状对得上，但一眼能看出不是真的。
    private static final String FAKE_API_KEY = "sk-test-0000FAKE0000notreal";
    private static final String FAKE_GH_TOKEN = "ghp_FAKE0000000000000000000000000000";
    private static final String FAKE_BEARER = "FAKE-BEARER-TOKEN-1234567890";
    private static final String FAKE_CLI_TOKEN = "FAKE-CLI-TOKEN-9";
    private static final String FAKE_PASSWORD = "Hunter2-FAKE-pw";
    private static final String FAKE_ENV_KEY = "sk-env-1111FAKE1111notreal";

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private Connection db;
    private TaskPlanService service;
    private ConversationMapper mapper;
    private OpenAiChatModel model;

    @BeforeEach
    void setup() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("plan-redaction.db"));
        db.createStatement().execute("CREATE TABLE plan (uid INTEGER, cid TEXT, json TEXT, PRIMARY KEY(uid,cid))");
        mapper = mock(ConversationMapper.class);
        model = mock(OpenAiChatModel.class);
        when(mapper.selectPlanJson(anyLong(), anyString())).thenAnswer(inv -> {
            try (var q = db.prepareStatement("SELECT json FROM plan WHERE uid=? AND cid=?")) {
                q.setLong(1, inv.getArgument(0));
                q.setString(2, inv.getArgument(1));
                try (var rs = q.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        when(mapper.updatePlanJson(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            try (var q = db.prepareStatement(
                    "INSERT INTO plan VALUES(?,?,?) ON CONFLICT(uid,cid) DO UPDATE SET json=excluded.json")) {
                q.setLong(1, inv.getArgument(0));
                q.setString(2, inv.getArgument(1));
                q.setString(3, inv.getArgument(2));
                return q.executeUpdate();
            }
        });
        service = new TaskPlanService(mapper, model);
        WorkspaceContext.set(dir);
        Files.writeString(dir.resolve("a.md"), "a");
    }

    @AfterEach
    void close() throws Exception {
        WorkspaceContext.clear();
        db.close();
    }

    // ==================== ① 正常文件修改证据 ====================

    @Test
    @DisplayName("文件修改证据：真实写入后步骤 DONE 且 resultVerified，证据键是文件路径")
    void fileEvidenceStillCompletesStep() throws Exception {
        // 用 ASCII 文件名：目标解析器要求文件名前是边界，紧贴中文的文件名（"和报告.md"）本来就识别不了，
        // 那是既有行为，不在本轮范围内。
        TaskPlan plan = plan("写入 a.md 和 b.md");
        PlanStep step = plan.getSteps().get(0);
        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            Files.writeString(dir.resolve("b.md"), "x");
            TaskExecution.current().record("writeFile", "{\"path\":\"b.md\"}", "✅ 写入成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            Files.writeString(dir.resolve("a.md"), "a2");
            TaskExecution.current().record("writeFile", "{\"path\":\"a.md\"}", "✅ 写入成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md 和 b.md");
        }
        assertEquals("done", step.getStatus());
        assertTrue(step.isResultVerified(), "真实写入必须仍然产生已验证结论");
        assertEquals(2, step.getVerifiedFiles().size(), "两个文件都要有指纹：" + step.getVerifiedFiles());
    }

    // ==================== ②③ 正常命令执行证据 + 成功后 DONE ====================

    @Test
    @DisplayName("命令证据：真实执行成功（exit=0）后步骤 DONE，证据键是命令指纹而不是命令原文")
    void commandEvidenceStillCompletesStep() {
        TaskPlan plan = plan("编译并运行 Main.java，确认输出 CHECK_OK");
        PlanStep step = plan.getSteps().get(0);
        String command = "javac -d out src/Main.java && java -cp out Main";
        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            TaskExecution.current().record("executeCommand", "{\"command\":\"" + command + "\"}",
                    "执行成功 (exit=0):\nCHECK_OK", ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", "javac 编译后 Main 输出 CHECK_OK");
        }
        assertEquals("done", step.getStatus(), "真实执行成功的命令必须能让命令类步骤完成");
        assertTrue(step.isResultVerified());
        String key = step.getVerifiedFiles().keySet().iterator().next();
        assertTrue(key.startsWith("command:"), key);
        assertFalse(key.contains("javac"), "证据键里不得出现命令原文：" + key);
        assertEquals("command:" + SensitiveRedactor.fingerprint(command), key, "证据键必须是命令的指纹");
    }

    // ==================== ④ 命令没有执行却声明 DONE ====================

    @Test
    @DisplayName("模型自述不算证据：没有真实执行过的命令，步骤留在 pending")
    void claimedWithoutExecutionStaysPending() {
        TaskPlan plan = plan("编译并运行 Main.java，确认输出 CHECK_OK");
        PlanStep step = plan.getSteps().get(0);
        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            service.updateStep(1L, "c", "s1", "done", "我已经编译并运行了 Main.java，输出 CHECK_OK");
        }
        assertEquals("pending", step.getStatus());
        assertFalse(step.isResultVerified());
    }

    // ==================== ⑤ 失败退出码 ====================

    @Test
    @DisplayName("失败退出码不能作为证据：exit=1 的命令不算成功")
    void failedExitCodeIsNotEvidence() {
        TaskPlan plan = plan("编译并运行 Main.java，确认输出 CHECK_OK");
        PlanStep step = plan.getSteps().get(0);
        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            TaskExecution.current().record("executeCommand",
                    "{\"command\":\"javac -d out src/Main.java\"}",
                    "命令执行失败 (exit=1):\nerror: cannot find symbol",
                    ToolExecutionStatus.Kind.FAILED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", "运行 javac 后 Main 输出 CHECK_OK");
        }
        assertNotEquals("done", step.getStatus(), "exit!=0 的命令不能作为完成证据");
        assertFalse(step.isResultVerified());
    }

    // ==================== ⑥⑦⑧ 命令输出 / 参数 / 上报正文里的模拟凭证 ====================

    @Test
    @DisplayName("命令参数带模拟 Token、输出带模拟 Key、上报正文带模拟密码：落库与前端都看不到明文")
    void secretsInCommandOutputAndReportNeverLeave() throws Exception {
        TaskPlan plan = plan("执行命令 run-deploy 并确认输出 READY");
        PlanStep step = plan.getSteps().get(0);
        String command = "run-deploy --token=" + FAKE_CLI_TOKEN + " --password=" + FAKE_PASSWORD
                + " && echo " + FAKE_GH_TOKEN;
        String output = "执行成功 (exit=0):\n"
                + "export OPENAI_API_KEY=" + FAKE_ENV_KEY + "\n"
                + "Authorization: Bearer " + FAKE_BEARER + "\n"
                + "using key " + FAKE_API_KEY + "\n"
                + "READY";
        String report = "部署完成，命令是 " + command + "，用的 key 是 " + FAKE_API_KEY + "，密码 " + FAKE_PASSWORD;

        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            TaskExecution.current().record("executeCommand", "{\"command\":\"" + command + "\"}",
                    output, ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", report);
        }
        assertEquals("done", step.getStatus(), "脱敏不得把真实执行过的步骤打成 pending");

        // 落库（真实 SQLite 文件）+ 前端拿到的对象，两处都不能有明文
        String persisted = planJson();
        String served = JSON.writeValueAsString(service.stepsOf(1L, "c"));
        for (String secret : List.of(FAKE_API_KEY, FAKE_GH_TOKEN, FAKE_BEARER,
                FAKE_CLI_TOKEN, FAKE_PASSWORD, FAKE_ENV_KEY)) {
            assertFalse(persisted.contains(secret), "plan_json 不得包含模拟凭证 " + secret + "：" + persisted);
            assertFalse(served.contains(secret), "SSE/HTTP 输出不得包含模拟凭证 " + secret + "：" + served);
        }
        // 但「这一步确实跑过命令」这个事实还在
        assertTrue(step.getVerifiedFiles().keySet().stream().anyMatch(k -> k.startsWith("command:")),
                step.getVerifiedFiles().toString());
        assertTrue(persisted.contains("command:"), persisted);
    }

    @Test
    @DisplayName("步骤描述与预期里的模拟凭证在创建计划时就被脱敏（不进入 plan_json / 提示词）")
    void planTextIsRedactedAtCreation() throws Exception {
        StepInput input = new StepInput();
        input.setDescription("用 key " + FAKE_API_KEY + " 调用接口，密码 " + FAKE_PASSWORD);
        input.setExpected("输出 READY，Bearer " + FAKE_BEARER);
        TaskPlan plan = service.create(1L, "c", List.of(input));

        String persisted = planJson();
        String served = JSON.writeValueAsString(plan.getSteps());
        for (String secret : List.of(FAKE_API_KEY, FAKE_PASSWORD, FAKE_BEARER)) {
            assertFalse(persisted.contains(secret), "plan_json 不得包含 " + secret + "：" + persisted);
            assertFalse(served.contains(secret), "前端输出不得包含 " + secret + "：" + served);
        }
        assertTrue(plan.getSteps().get(0).getDescription().contains("调用接口"), "脱敏不该删掉正常语义");
    }

    @Test
    @DisplayName("脱敏后的上报正文仍然保留失败信号：判定不因脱敏而变宽或变严")
    void redactionKeepsVerificationSignals() {
        // 明文里带密码，同时带硬失败信号：仍必须判失败
        PlanStep step = new PlanStep("s1", "运行测试", "运行测试");
        String report = "执行失败：命令 --password=" + FAKE_PASSWORD + " 被拒绝";
        String redacted = SensitiveRedactor.redactText(report);
        assertFalse(redacted.contains(FAKE_PASSWORD));
        assertFalse(PlanVerifier.verify("运行测试", redacted).passed(), "脱敏后仍要能看出失败");
    }

    // ==================== ⑨⑩ 已在上面覆盖；这里单独钉住「指纹不撞键」 ====================

    @Test
    @DisplayName("指纹：同一条命令键相同，不同命令键不同（证据不会错误复用）")
    void commandFingerprintIsStableAndDistinct() {
        String a = "javac -d out src/Main.java";
        String b = "javac -d out src/Other.java";
        assertEquals(SensitiveRedactor.fingerprint(a), SensitiveRedactor.fingerprint(a));
        assertNotEquals(SensitiveRedactor.fingerprint(a), SensitiveRedactor.fingerprint(b));
        assertFalse(SensitiveRedactor.fingerprint(a).contains("javac"), "指纹必须不可逆");
        assertNull(SensitiveRedactor.fingerprint(null));
    }

    // ==================== ⑪ 多步骤证据不会错误交叉复用 ====================

    @Test
    @DisplayName("多步骤：同一条成功命令不能给两个步骤作证，两个步骤也不会共享同一个证据键")
    void commandEvidenceIsNotReusedAcrossSteps() {
        StepInput one = new StepInput();
        one.setDescription("编译并运行 Alpha.java，确认输出 ALPHA_OK");
        one.setExpected("ALPHA_OK");
        StepInput two = new StepInput();
        two.setDescription("编译并运行 Beta.java，确认输出 BETA_OK");
        two.setExpected("BETA_OK");
        TaskPlan plan = service.create(1L, "c", List.of(one, two));
        PlanStep first = plan.getSteps().get(0);
        PlanStep second = plan.getSteps().get(1);

        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            TaskExecution.current().record("executeCommand",
                    "{\"command\":\"javac -d out Alpha.java && java Alpha\"}",
                    "执行成功 (exit=0):\nALPHA_OK", ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", "javac 编译后 Alpha 输出 ALPHA_OK");
            assertEquals("done", first.getStatus());

            // 第二步点名 Beta.java，但 Beta 从没跑过 → 拒绝用 Alpha 的命令顶替
            service.updateStep(1L, "c", "s2", "done", "javac 编译后 Beta 输出 BETA_OK");
            assertEquals("pending", second.getStatus(), "命令证据不得跨步骤复用");

            // 真的跑了 Beta，第二步才完成，且键与第一步不同
            TaskExecution.current().record("executeCommand",
                    "{\"command\":\"javac -d out Beta.java && java Beta\"}",
                    "执行成功 (exit=0):\nBETA_OK", ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s2", "done", "javac 编译后 Beta 输出 BETA_OK");
        }
        assertEquals("done", second.getStatus());
        assertTrue(second.isResultVerified());
        assertNotEquals(first.getVerifiedFiles().keySet(), second.getVerifiedFiles().keySet(),
                "两个步骤不能共享同一个证据键");
    }

    // ==================== ⑫ 原有 resultVerified 仍正确 ====================

    @Test
    @DisplayName("resultVerified 语义不变：有证据=true，只有模型自述=false，交付前复核不打回已验证步骤")
    void resultVerifiedSemanticsUnchanged() {
        TaskPlan plan = plan("分析问题");
        PlanStep step = plan.getSteps().get(0);
        try (var scope = TaskExecution.attach(new TaskExecution(8, 100000, 100000))) {
            service.updateStep(1L, "c", "s1", "done", "分析问题完成");
            assertEquals("done", step.getStatus());
            assertFalse(step.isResultVerified(), "只读步骤不要求副作用证据，但也没有「已验证执行」");

            TaskPlan cmd = plan("编译并运行 Main.java，确认输出 CHECK_OK");
            PlanStep cmdStep = cmd.getSteps().get(0);
            TaskExecution.current().record("executeCommand", "{\"command\":\"java Main\"}",
                    "执行成功 (exit=0):\nCHECK_OK", ToolExecutionStatus.Kind.EXECUTED, cmd.getSteps());
            service.updateStep(1L, "c", cmdStep.getStepId(), "done", "java Main 输出 CHECK_OK");
            assertEquals("done", cmdStep.getStatus());
            assertTrue(cmdStep.isResultVerified());
            service.revalidateFiles(1L, "c", null, null);
            assertEquals("done", cmdStep.getStatus(), "交付前复核不得把命令证据步骤打回 pending");
            assertTrue(cmdStep.isResultVerified());
        }
    }

    // ==================== 辅助 ====================

    private TaskPlan plan(String description) {
        StepInput input = new StepInput();
        input.setDescription(description);
        input.setExpected(description);
        return service.create(1L, "c", List.of(input));
    }

    /** 直接读真实 SQLite 里的 plan_json —— 这是「落库有没有明文」的唯一可信证据。 */
    private String planJson() throws Exception {
        try (var q = db.prepareStatement("SELECT json FROM plan WHERE uid=? AND cid=?")) {
            q.setLong(1, 1L);
            q.setString(2, "c");
            try (var rs = q.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
