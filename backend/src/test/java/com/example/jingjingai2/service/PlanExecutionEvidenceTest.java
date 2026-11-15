package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.*;
import com.example.jingjingai2.agent.*;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real JSON persistence in an isolated SQLite file; only mapper transport is adapted. */
class PlanExecutionEvidenceTest {
    @TempDir Path dir;
    Connection db;
    TaskPlanService service;
    ConversationMapper mapper;
    OpenAiChatModel model;
    @BeforeEach void setup() throws Exception {
        db=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("plan-test.db"));
        db.createStatement().execute("CREATE TABLE plan (uid INTEGER, cid TEXT, json TEXT, PRIMARY KEY(uid,cid))");
        mapper=mock(ConversationMapper.class); model=mock(OpenAiChatModel.class);
        when(mapper.selectPlanJson(anyLong(),anyString())).thenAnswer(inv -> {
            try(var q=db.prepareStatement("SELECT json FROM plan WHERE uid=? AND cid=?")) {
                q.setLong(1,inv.getArgument(0)); q.setString(2,inv.getArgument(1));
                try(var rs=q.executeQuery()) {return rs.next()?rs.getString(1):null;}
            }
        });
        when(mapper.updatePlanJson(anyLong(),anyString(),anyString())).thenAnswer(inv -> {
            try(var q=db.prepareStatement("INSERT INTO plan VALUES(?,?,?) ON CONFLICT(uid,cid) DO UPDATE SET json=excluded.json")) {
                q.setLong(1,inv.getArgument(0));q.setString(2,inv.getArgument(1));q.setString(3,inv.getArgument(2));return q.executeUpdate();
            }
        });
        service=new TaskPlanService(mapper,model);
        WorkspaceContext.set(dir);
        Files.writeString(dir.resolve("DELIVERY.md"), "delivery");
        Files.writeString(dir.resolve("a.md"), "a");
        Files.writeString(dir.resolve("b.md"), "b");
    }
    @AfterEach void close() throws Exception {
        WorkspaceContext.clear();
        db.close();
    }
    private TaskPlan plan(String description) {
        StepInput input=new StepInput(); input.setDescription(description); input.setExpected(description);
        return service.create(1L,"c",List.of(input));
    }
    @Test void earlyDoneDeniedWriteAndAmbiguousEvidenceCannotCompleteFileStep() {
        TaskPlan plan=plan("写入 DELIVERY.md");PlanStep step=plan.getSteps().get(0);
        TaskExecution task=new TaskExecution(4,100000,100000);
        try(var scope=TaskExecution.attach(task)) {
            service.updateStep(1L,"c","s1","done","已写入 DELIVERY.md");
            assertEquals("pending",step.getStatus());
            task.record("writeFile","{\"path\":\"DELIVERY.md\"}","拒绝",ToolExecutionStatus.Kind.DENIED,plan.getSteps());
            service.updateStep(1L,"c","s1","done","已写入 DELIVERY.md");
            assertEquals("pending",step.getStatus());assertEquals(0,step.getFailCount());
            PlanStep another=new PlanStep("s2","写入 DELIVERY.md","写入 DELIVERY.md");
            task.record("writeFile","{\"path\":\"DELIVERY.md\"}","✅ 写入成功",ToolExecutionStatus.Kind.EXECUTED,List.of(step,another));
            assertFalse(task.verified(step));
            task.record("writeFile","{\"path\":\"DELIVERY.md\"}","✅ 写入成功",ToolExecutionStatus.Kind.EXECUTED,List.of(step));
            service.updateStep(1L,"c","s1","done","已写入 DELIVERY.md");
            assertEquals("done",step.getStatus());assertTrue(step.isResultVerified());
        }
        PlanStep persisted=new TaskPlanService(mapper,model).get(1L,"c").getSteps().get(0);
        assertTrue(persisted.isResultVerified());assertNotNull(persisted.getLastReportKey());
    }
    @Test void readBackStepDoesNotMakeTheWriteAmbiguous() throws Exception {
        StepInput write = new StepInput();
        write.setDescription("用 writeFile 创建 plan-marker.txt，内容正好是 PLAN-MARKER-9");
        write.setExpected("plan-marker.txt 存在且内容为 PLAN-MARKER-9");
        StepInput read = new StepInput();
        read.setDescription("用 readFile 读回 plan-marker.txt 并确认内容与 PLAN-MARKER-9 完全一致");
        read.setExpected("读回内容等于 PLAN-MARKER-9");
        TaskPlan created = service.create(1L, "c", List.of(write, read));
        PlanStep writing = created.getSteps().get(0);
        PlanStep reading = created.getSteps().get(1);
        assertTrue(TaskExecution.requiresEffectEvidence(writing, ""));
        assertFalse(TaskExecution.requiresEffectEvidence(reading, ""));
        Files.writeString(dir.resolve("plan-marker.txt"), "PLAN-MARKER-9");
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("writeFile", "{\"path\":\"plan-marker.txt\"}", "✅ 写入成功",
                    ToolExecutionStatus.Kind.EXECUTED, created.getSteps());
            service.updateStep(1L, "c", "s1", "done", "已创建 plan-marker.txt");
            assertEquals("done", writing.getStatus());
            assertTrue(writing.isResultVerified());
            service.updateStep(1L, "c", "s2", "done", "readFile 读回内容为 PLAN-MARKER-9");
            assertEquals("done", reading.getStatus());
            assertFalse(reading.isResultVerified());
            // 上报正文里复述「创建」也不能让只读步骤重新要求写入证据。
            service.updateStep(1L, "c", "s2", "done", "readFile 读回已创建的 plan-marker.txt，内容为 PLAN-MARKER-9");
            assertEquals("done", reading.getStatus());
            // 读回步骤也提到同一个文件名。交付前的复核不能因此把已经验证过的写入步骤打回 pending。
            service.revalidateFiles(1L, "c", null, null);
            assertEquals("done", writing.getStatus());
            assertTrue(writing.isResultVerified());
            assertTrue(created.allDone());
            assertTrue(task.acceptableAnswer("已创建 plan-marker.txt，内容为 PLAN-MARKER-9。",
                    () -> service.stepsOf(1L, "c")));
            assertTrue(task.acceptableAnswer(
                    "用 writeFile 创建了 `plan-marker.txt`，工具返回「已创建文件: plan-marker.txt (大小 13 字节, 1 行)」。",
                    () -> service.stepsOf(1L, "c")));
        }
    }
    @Test void surveyAndScanStepsAreReadOnly() {
        assertFalse(TaskExecution.requiresEffectEvidence(step(
                "读 README.md、ACCEPTANCE.md、docs/ 三份文档，提取项目自己声明的能力作为盘点基线"), ""));
        assertFalse(TaskExecution.requiresEffectEvidence(step(
                "grep 全仓扫描未完成痕迹（TODO、FIXME、XXX）并归类"), ""));
        // 带写动作的步骤仍然要证据，不能因为句首有个「读」就被放过。
        assertTrue(TaskExecution.requiresEffectEvidence(step(
                "读完 README.md 后用 editFile 更新其中的默认值"), ""));
    }

    private static PlanStep step(String description) {
        PlanStep s = new PlanStep();
        s.setDescription(description);
        return s;
    }

    @Test void duplicateFailureAndIllegalTransitionsDoNotChangeCounts() {
        TaskPlan plan=plan("分析问题");PlanStep step=plan.getSteps().get(0);
        service.updateStep(1L,"c","s1","bogus","成功");assertEquals("pending",step.getStatus());
        service.updateStep(1L,"c","s1","failed","无法确定事实");
        int version=plan.getVersion();
        service.updateStep(1L,"c","s1","failed","无法确定事实");
        assertEquals(1,step.getFailCount());assertEquals(version,plan.getVersion());
        TaskExecution task=new TaskExecution(4,100000,100000);
        try(var scope=TaskExecution.attach(task)) {
            task.blockApproval("拒绝");service.updateStep(1L,"c","s1","failed","审批未完成");
            assertEquals(1,step.getFailCount());
        }
        service.updateStep(1L,"c","s1","done","分析问题完成");
        assertEquals("done",step.getStatus());assertFalse(step.isResultVerified());
        service.updateStep(1L,"c","s1","failed","其他失败");assertEquals("done",step.getStatus());
    }
    @Test void aFileNameInTextDoesNotRequireWritingAndPartialFileEvidenceIsNotEnough() {
        PlanStep analysis = plan("解释 README.md 中已经提供的内容").getSteps().get(0);
        service.updateStep(1L,"c","s1","done","解释 README.md 中已经提供的内容完成");
        assertEquals("done", analysis.getStatus()); assertFalse(analysis.isResultVerified());
        PlanStep write = new PlanStep("s1","写入 a.md 和 b.md","写入 a.md 和 b.md");
        TaskExecution task = new TaskExecution(4,100000,100000);
        task.record("writeFile","{\"path\":\"a.md\"}","✅ 成功",ToolExecutionStatus.Kind.EXECUTED,List.of(write));
        assertFalse(task.verified(write));
        task.record("createDirectory","{\"path\":\"b.md\"}","✅ 成功",ToolExecutionStatus.Kind.EXECUTED,List.of(write));
        assertFalse(task.verified(write));
        task.record("writeFile","{\"path\":\"b.md\"}","✅ 成功",ToolExecutionStatus.Kind.EXECUTED,List.of(write));
        assertTrue(task.verified(write));
        task.record("writeFile","{\"path\":\"b.md\"}","拒绝",ToolExecutionStatus.Kind.DENIED,List.of(write));
        assertFalse(task.verified(write));
    }
    @Test void finalStageCannotModifyPlanAndResplitCannotBypassBudget() {
        PlanStep step=plan("分析问题").getSteps().get(0);
        TaskExecution task=new TaskExecution(1,100000,100000);
        try(var scope=TaskExecution.attach(task)) {
            // Three distinct failed reports would normally call the resplit model.
            for(int i=0;i<3;i++) service.updateStep(1L,"c","s1","failed","错误"+i);
            verifyNoInteractions(model);assertEquals(0,task.calls());
            int failures=step.getFailCount();task.beginFinal("finish");
            service.updateStep(1L,"c","s1","done","成功");service.markRunning(1L,"c","s1");
            assertEquals(failures,step.getFailCount());assertNotEquals("done",step.getStatus());
        }
    }
    @Test void mixedExtensionsRequireEveryFile() throws Exception {
        PlanStep step = plan("写入 a.md 和 b.yaml").getSteps().get(0);
        Files.writeString(dir.resolve("b.yaml"), "setting: true");
        TaskExecution task = new TaskExecution(6, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            recordWrite(task, "a.md", List.of(step));
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md 和 b.yaml");
            assertEquals("pending", step.getStatus());
            recordWrite(task, "b.yaml", List.of(step));
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md 和 b.yaml");
            assertTrue(step.isResultVerified());
        }
    }

    @Test void extensionlessTargetsMustBeAccountedFor() throws Exception {
        Files.writeString(dir.resolve("plain notes"), "notes");
        try (var scope = TaskExecution.attach(new TaskExecution(6, 100000, 100000))) {
            TaskExecution task = TaskExecution.current();
            PlanStep ambiguous = plan("写入 a.md 和 custom").getSteps().get(0);
            recordWrite(task, "a.md", List.of(ambiguous));
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md");
            assertEquals("pending", ambiguous.getStatus());

            PlanStep explicit = plan("写入 `a.md` 和 `plain notes`").getSteps().get(0);
            recordWrite(task, "a.md", List.of(explicit));
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md");
            assertEquals("pending", explicit.getStatus());
            recordWrite(task, "plain notes", List.of(explicit));
            service.updateStep(1L, "c", "s1", "done", "已写入全部文件");
            assertTrue(explicit.isResultVerified());
        }
    }

    @Test void basenameDoesNotMatchSpacedPathButWorkspaceAliasesDo() throws Exception {
        PlanStep step = plan("写入 notes/weekly a.md").getSteps().get(0);
        TaskExecution task = new TaskExecution(6, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            recordWrite(task, "a.md", List.of(step));
            service.updateStep(1L, "c", "s1", "done", "已写入 notes/weekly a.md");
            assertEquals("pending", step.getStatus());

            step = plan("写入 `notes/weekly a.md`").getSteps().get(0);
            Path actual = dir.resolve("notes/weekly a.md");
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, "weekly notes");
            recordWrite(task, dir.resolve("notes/../notes/weekly a.md").toString(), List.of(step));
            service.updateStep(1L, "c", "s1", "done", "已写入 `notes/weekly a.md`");
            assertTrue(step.isResultVerified());
        }
    }

    @Test void newPlanWithSameStepIdCannotReuseOldWrites() {
        TaskExecution task = new TaskExecution(6, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            PlanStep original = plan("写入 a.md").getSteps().get(0);
            recordWrite(task, "a.md", List.of(original));
            PlanStep replacement = plan("写入 a.md").getSteps().get(0);
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md");
            assertEquals("pending", replacement.getStatus());
            recordWrite(task, "a.md", List.of(replacement));
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md");
            assertTrue(replacement.isResultVerified());
        }
    }

    @Test void budgetDeferredResplitCanResumeFromPersistedPlan() {
        PlanStep step = plan("分析问题").getSteps().get(0);
        try (var scope = TaskExecution.attach(new TaskExecution(1, 100000, 100000))) {
            for (int i = 0; i < 3; i++) service.updateStep(1L, "c", "s1", "failed", "错误" + i);
            assertEquals("failed", step.getStatus());
            assertEquals(3, step.getFailCount());
            verifyNoInteractions(model);
        }
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("重新提取事实 || 提供证据\n核对事实 || 列出结果")))));
        TaskPlanService reloaded = new TaskPlanService(mapper, model);
        try (var scope = TaskExecution.attach(new TaskExecution(18, 100000, 100000))) {
            var outcome = reloaded.updateStep(1L, "c", "s1", "failed", "错误2");
            assertEquals(2, outcome.subSteps.size());
            assertEquals(3, outcome.step.getFailCount());
            verify(model).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        }
    }

    // ===================== F2：命令 / 删除类步骤的执行证据 =====================

    @Test void compileStepCompletesFromRealCommandEvidence() {
        PlanStep step = plan("编译并运行 Main.java，复现错误输出作为基线").getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("executeCommand",
                    "{\"command\":\"javac -d out src/Main.java && java -cp out Main\"}",
                    "执行成功 (exit=0):\ntotal=30.0\nCHECK_FAIL expected 270.0",
                    ToolExecutionStatus.Kind.EXECUTED, List.of(step));
            service.updateStep(1L, "c", "s1", "done",
                    "运行 javac 编译后跑 Main，输出 total=30.0 与 CHECK_FAIL");
            assertEquals("done", step.getStatus(), "真实执行成功的命令必须能让编译步骤完成");
            assertTrue(step.isResultVerified());
            assertTrue(step.getVerifiedFiles().keySet().stream().anyMatch(k -> k.startsWith("command:")));
        }
    }

    @Test void commandStepWithoutRealExecutionStaysPending() {
        PlanStep step = plan("编译并运行 Main.java，确认输出 CHECK_OK").getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            // 只有文字声明，没有任何命令执行记录。
            service.updateStep(1L, "c", "s1", "done", "我已经编译并运行了 Main.java，输出 CHECK_OK");
            assertEquals("pending", step.getStatus(), "模型自述不能替代执行证据");
            assertFalse(step.isResultVerified());
        }
    }

    @Test void failedCommandDoesNotCompleteStep() {
        PlanStep step = plan("编译并运行 Main.java，确认输出 CHECK_OK").getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("executeCommand",
                    "{\"command\":\"javac -d out src/Main.java && java -cp out Main\"}",
                    "命令执行失败 (exit=1):\nerror: cannot find symbol",
                    ToolExecutionStatus.Kind.FAILED, List.of(step));
            service.updateStep(1L, "c", "s1", "done", "运行 javac 后 Main 输出 CHECK_OK");
            assertNotEquals("done", step.getStatus(), "exit!=0 的命令不能作为完成证据");
        }
    }

    @Test void deleteStepCompletesFromDeletePathEvidence() throws Exception {
        Files.writeString(dir.resolve("tmp-marker.txt"), "to be removed");
        PlanStep step = plan("删除临时校验文件 tmp-marker.txt").getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("deletePath", "{\"path\":\"tmp-marker.txt\"}", "✅ 已删除: tmp-marker.txt",
                    ToolExecutionStatus.Kind.EXECUTED, List.of(step));
            Files.deleteIfExists(dir.resolve("tmp-marker.txt"));
            service.updateStep(1L, "c", "s1", "done", "已删除 tmp-marker.txt");
            assertEquals("done", step.getStatus(), "真实删除也必须能完成删除类步骤");
            assertTrue(step.getVerifiedFiles().keySet().stream().anyMatch(k -> k.startsWith("deleted:")));
        }
    }

    @Test void commandEvidenceDoesNotSatisfyFileStep() {
        PlanStep step = plan("写入 a.md 和 b.md").getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("executeCommand", "{\"command\":\"type a.md\"}", "执行成功 (exit=0):\na",
                    ToolExecutionStatus.Kind.EXECUTED, List.of(step));
            service.updateStep(1L, "c", "s1", "done", "已写入 a.md 和 b.md");
            assertEquals("pending", step.getStatus(), "命令证据不能顶替文件写入证据");
        }
    }

    @Test void commandVerifiedStepSurvivesRevalidation() {
        PlanStep step = plan("编译并运行 Main.java，确认输出 CHECK_OK").getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("executeCommand",
                    "{\"command\":\"javac -d out src/Main.java && java -cp out Main\"}",
                    "执行成功 (exit=0):\nCHECK_OK", ToolExecutionStatus.Kind.EXECUTED, List.of(step));
            service.updateStep(1L, "c", "s1", "done", "javac 编译后 Main 输出 CHECK_OK");
            assertEquals("done", step.getStatus());
            service.revalidateFiles(1L, "c", null, null);
            assertEquals("done", step.getStatus(), "交付前复核不得把命令证据步骤打回 pending");
            assertTrue(step.isResultVerified());
        }
    }

    @Test void naturalLanguageFixStepStillGetsFileEvidence() throws Exception {
        // 真实模型常见写法：步骤描述里没有「修改/写入」字样，只有「修复 + 文件路径」。
        PlanStep step = plan("修复 src/Pricing.java 中 applyDiscount 的计算逻辑，使其符合注释语义").getSteps().get(0);
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Pricing.java"), "class Pricing { double f(double p, double r) { return p * (1 - r); } }");
        TaskExecution task = new TaskExecution(8, 100000, 100000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("editFile", "{\"path\":\"src/Pricing.java\"}",
                    "✅ 已修改文件: src/Pricing.java", ToolExecutionStatus.Kind.EXECUTED, List.of(step));
            service.updateStep(1L, "c", "s1", "done",
                    "已修改 src/Pricing.java，applyDiscount 现在返回 price * (1 - rate)");
            assertEquals("done", step.getStatus(), "自然语言描述的修复步骤也必须能拿到文件证据");
            assertTrue(step.isResultVerified());
        }
    }

    private static void recordWrite(TaskExecution task, String path, List<PlanStep> steps) {
        String escaped = path.replace("\\", "\\\\").replace("\"", "\\\"");
        task.record("writeFile", "{\"path\":\"" + escaped + "\"}", "✅ 成功", ToolExecutionStatus.Kind.EXECUTED, steps);
    }

}
