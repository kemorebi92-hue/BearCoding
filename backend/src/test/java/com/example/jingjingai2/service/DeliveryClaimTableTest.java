package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.*;
import com.example.jingjingai2.agent.*;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P2 回归：交付核对（{@link TaskExecution#acceptableAnswer}）不能因为最终回答用了 Markdown 表格就误判。
 *
 * <p>真实模型 D2 轮次的原始缺陷：计划 4/4 全部 DONE 且 {@code resultVerified=true}、文件确实改对、
 * 编译运行通过，但最终回答里的表格行
 * {@code | s2 | 修正公式为 `price * (1.0 - rate)`，清除 BOM | ✅ 已修改 |}
 * 被判成「声称写了文件却没有证据」，于是整个任务显示「未确认完整交付」。</p>
 *
 * <p>根因不是表格本身：反引号里的表达式 {@code price * (1.0 - rate)} 被当成「引用了一个文件目标」，
 * 但它又解析不成工作区路径（Windows 上 {@code *} 是非法路径字符），于是
 * 「提到了目标」为真、「解析出目标」为空 —— 两者矛盾，交付被否。</p>
 *
 * <p>这些用例同时锁住边界：真的没写、真的没跑、真的编译失败时，仍然不能算交付成功。</p>
 */
class DeliveryClaimTableTest {
    @TempDir Path dir;
    Connection db;
    TaskPlanService service;
    ConversationMapper mapper;

    /** 真实模型 D2 的最终回答原文（仅把日志里被压平的空行还原）。 */
    private static final String D2_FINAL_ANSWER = """
            ## 检查结果与修复交付

            ### 找到的逻辑问题

            在 `src/Pricing.java` 中，`applyDiscount` 的实现与它自己的注释、以及调用方预期都不一致：

            ```java
            /** rate 是折扣比例，例如 0.1 表示打九折（减价 10%）。 */
            public static double applyDiscount(double price, double rate) {
                return price * rate;          // 错：算成了「只付 rate 的比例」
            }
            ```

            当 rate 表示“减价比例”时，正确金额应为 `price * (1 - rate)`。原实现对于 `price=300, rate=0.1` 算得 30.0，而实际应收 270.0——相当于把折扣当成了实付比例，金额错了将近 10 倍。这是典型的“注释/契约正确、实现反了”的缺陷。

            另外还发现一个**会阻断编译**的问题：`Pricing.java` 文件头带有 UTF-8 BOM，`javac` 直接报 `非法字符: '\\ufeff'`，导致项目连编译都过不去。

            ### 修改计划（4 步，均已完成）

            | 步骤 | 内容 | 结果 |
            |---|---|---|
            | s1 | 编译运行，复现问题 | ✅ 首次编译因 BOM 报 `非法字符: '\\ufeff'` 失败；去 BOM 后基线输出 `total=30.0` / `CHECK_FAIL expected 270.0` |
            | s2 | 修正公式为 `price * (1.0 - rate)`，清除 BOM | ✅ 已修改 |
            | s3 | 重新编译运行主程序 | ✅ 输出 `total=270.0` / `CHECK_OK` |
            | s4 | 一次性测试类验证折扣边界，跑通后删除 | ✅ 6 个用例全部 PASS，测试类与 class 已清理 |

            ### 实际改动内容

            `src/Pricing.java`：

            ```java
            public class Pricing {
                /** rate 是折扣比例，例如 0.1 表示打九折（减价 10%）。 */
                public static double applyDiscount(double price, double rate) {
                    return price * (1.0 - rate);
                }
            }
            ```

            本次只改动了这一个文件的两处：去掉首行 BOM、修正公式。`Main.java` 与 `Order.java` 未改动，`Order.total()` 的委托关系本身没问题。

            ### 验证证据

            边界测试（临时类 `PricingCheck`，已删除）运行输出：

            ```
            PASS rate=0 无折扣 actual=300.0 expected=300.0
            PASS rate=0.1 九折 actual=270.0 expected=270.0
            PASS rate=0.5 半价 actual=150.0 expected=150.0
            PASS rate=1 全免 actual=0.0 expected=0.0
            PASS price=0 actual=0.0 expected=0.0
            PASS 小数金额 99.9 rate=0.2 actual=79.92000000000002 expected=79.92
            ALL_BOUNDARY_CHECKS_PASSED
            ```

            清理后的最终干净编译运行：

            ```
            total=270.0
            CHECK_OK
            ```

            工作区当前状态：`src/` 只含 `Main.java`、`Order.java`、`Pricing.java`；`out/` 只含对应的 3 个 `.class` 文件，临时测试产物已清除。

            ### 一个遗留建议（未改，需你决定）

            `Pricing.applyDiscount` 没有对 `rate` 做范围校验。若上游传入负数或大于 1 的值（例如 `rate=1.2`），会算出负金额 `-60.0`。这属于防御性校验范畴、且会改变方法契约（抛异常还是钳位到 [0,1]），是否要加取决于你的业务约定，所以我没有擅自改动。需要的话我可以按你选定的策略补上。
            """;

    private static final String COMPILE_AND_RUN =
            "javac -encoding UTF-8 -d out src/Main.java src/Order.java src/Pricing.java && java -cp out Main";

    @BeforeEach void setup() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("delivery-claim-test.db"));
        db.createStatement().execute("CREATE TABLE plan (uid INTEGER, cid TEXT, json TEXT, PRIMARY KEY(uid,cid))");
        mapper = mock(ConversationMapper.class);
        when(mapper.selectPlanJson(anyLong(), anyString())).thenAnswer(inv -> {
            try (var q = db.prepareStatement("SELECT json FROM plan WHERE uid=? AND cid=?")) {
                q.setLong(1, inv.getArgument(0));
                q.setString(2, inv.getArgument(1));
                try (var rs = q.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
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
        service = new TaskPlanService(mapper, mock(OpenAiChatModel.class));
        WorkspaceContext.set(dir);
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Pricing.java"), "public class Pricing {\n    public static double applyDiscount(double price, double rate) {\n        return price * rate;\n    }\n}\n");
        Files.writeString(dir.resolve("src/Main.java"), "public class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("src/Order.java"), "public class Order {}\n");
        Files.createDirectories(dir.resolve("out"));
    }

    @AfterEach void close() throws Exception {
        WorkspaceContext.clear();
        db.close();
    }

    private static StepInput step(String description, String expected) {
        StepInput input = new StepInput();
        input.setDescription(description);
        input.setExpected(expected);
        return input;
    }

    private void command(TaskExecution task, List<PlanStep> steps, String command, int exit) {
        task.record("executeCommand", "{\"command\":\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
                "exit=" + exit + "\n输出若干行", ToolExecutionStatus.Kind.EXECUTED, steps);
    }

    /**
     * 用例 1 + 3：真实改对文件、真实编译运行通过，最终回答是 Markdown 表格 → 必须判定可交付。
     * 同时验证「相对路径 src/Pricing.java」能对上请求期记录的绝对路径证据。
     */
    @Test void realFileEditDeliveredAsMarkdownTableIsAcceptable() throws Exception {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("在 out/ 下编译并运行当前代码，复现问题（预期输出 total=30.0 与 CHECK_FAIL）",
                        "输出 total=30.0 且打印 CHECK_FAIL"),
                step("修复 src/Pricing.java 的 applyDiscount 实现为 price * (1 - rate)，并清掉文件开头的 BOM",
                        "src/Pricing.java 的公式正确、无 BOM"),
                step("重新编译运行 Main，确认输出 CHECK_OK（total=270.0）",
                        "输出 total=270.0 且打印 CHECK_OK"),
                step("补充验证折扣边界：rate=0 应得原价、rate=1 应得 0，用一次性测试类跑通后删除",
                        "边界用例全部通过且临时文件已删除")));
        List<PlanStep> steps = plan.getSteps();
        TaskExecution task = new TaskExecution(16, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            // 按真实顺序重放：先编译失败（BOM），再去 BOM 编译，再改公式，再验证，最后清理。
            command(task, steps, COMPILE_AND_RUN, 1);
            Files.writeString(dir.resolve("src/Pricing.java"),
                    "public class Pricing {\n    public static double applyDiscount(double price, double rate) {\n        return price * rate;\n    }\n}\n");
            task.record("editFile", "{\"path\":\"src/Pricing.java\",\"oldStr\":\"\\ufeffpublic class Pricing {\","
                    + "\"newStr\":\"public class Pricing {\"}", "✅ 修改成功", ToolExecutionStatus.Kind.EXECUTED, steps);
            command(task, steps, COMPILE_AND_RUN, 0);
            service.updateStep(1L, "c", "s1", "done",
                    "✅ 首次编译因 BOM 报非法字符失败；去 BOM 后基线输出 total=30.0 / CHECK_FAIL");

            Files.writeString(dir.resolve("src/Pricing.java"),
                    "public class Pricing {\n    public static double applyDiscount(double price, double rate) {\n        return price * (1.0 - rate);\n    }\n}\n");
            task.record("editFile", "{\"path\":\"src/Pricing.java\",\"oldStr\":\"return price * rate;\","
                    + "\"newStr\":\"return price * (1.0 - rate);\"}", "✅ 修改成功", ToolExecutionStatus.Kind.EXECUTED, steps);
            service.updateStep(1L, "c", "s2", "done", "已修改 src/Pricing.java 的公式并清除 BOM");

            command(task, steps, COMPILE_AND_RUN, 0);
            service.updateStep(1L, "c", "s3", "done", "输出 total=270.0 / CHECK_OK");

            Files.writeString(dir.resolve("src/PricingCheck.java"), "public class PricingCheck {}\n");
            task.record("createFile", "{\"path\":\"src/PricingCheck.java\"}", "✅ 创建成功",
                    ToolExecutionStatus.Kind.EXECUTED, steps);
            command(task, steps, "javac -encoding UTF-8 -d out src/Pricing.java src/PricingCheck.java "
                    + "&& java -cp out PricingCheck", 0);
            task.record("deletePath", "{\"path\":\"src/PricingCheck.java\"}", "✅ 已删除",
                    ToolExecutionStatus.Kind.EXECUTED, steps);
            Files.deleteIfExists(dir.resolve("src/PricingCheck.java"));
            command(task, steps, COMPILE_AND_RUN + " && dir /b out src", 0);
            service.updateStep(1L, "c", "s4", "done", "✅ 6 个用例全部 PASS，测试类与 class 已清理");

            // 前置条件：计划确实 4/4 全部完成并且每一步都有真实证据。
            assertTrue(plan.allDone(), "4 个步骤都应完成");
            for (PlanStep done : steps) {
                assertTrue(done.isResultVerified(), done.getStepId() + " 应有真实证据");
            }
            // 证据键是请求期的绝对路径；最终回答里写的是相对路径，两者必须能对上。
            String verifiedKey = steps.get(1).getVerifiedFiles().keySet().iterator().next();
            assertTrue(Path.of(verifiedKey).isAbsolute(), "证据键应为绝对路径，实际=" + verifiedKey);
            assertTrue(verifiedKey.endsWith("src" + java.io.File.separator + "Pricing.java"),
                    "证据键应指向 src/Pricing.java，实际=" + verifiedKey);

            // 缺陷复现点：这条回答曾经被判「未证实片段=| s2 | ... ✅ 已修改 |」。
            assertTrue(task.acceptableAnswer(D2_FINAL_ANSWER, () -> service.stepsOf(1L, "c")),
                    "真实改对文件、编译运行通过时，表格化最终回答必须可交付");
        }
    }

    /** 用例 2：Markdown 表头与分隔行是排版，不是「声称写了文件」。 */
    @Test void tableHeaderAndSeparatorAreNotTreatedAsClaims() {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("修复 src/Pricing.java 的公式", "src/Pricing.java 公式正确")));
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("editFile", "{\"path\":\"src/Pricing.java\"}", "✅ 修改成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", "已修改 src/Pricing.java");
            String header = """
                    | 步骤 | 文件 | 已修改 |
                    |---|---|---|
                    | s1 | `src/Pricing.java` | 是 |
                    """;
            assertTrue(task.acceptableAnswer(header, () -> service.stepsOf(1L, "c")),
                    "表头里的「文件」「已修改」是列名，不是交付声明");
            String plainHeader = """
                    | 步骤 | 描述 | 期望 | 实际 |
                    |---|---|---|---|
                    | s1 | 修正公式 | 通过 | ✅ 已完成 |
                    """;
            assertTrue(task.acceptableAnswer(plainHeader, () -> service.stepsOf(1L, "c")));
        }
    }

    /** 用例 4 + 6：文件没有真的改成功时，文字声明不能冒充交付。 */
    @Test void claimWithoutRealWriteIsStillRejected() throws Exception {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("分析 src/Pricing.java 的折扣逻辑", "指出公式错误")));
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            // 只读步骤可以 DONE，但没有写入证据。
            service.updateStep(1L, "c", "s1", "done", "✅ 指出公式错误：price * rate 与注释不符");
            assertEquals("done", plan.getSteps().get(0).getStatus());
            assertFalse(plan.getSteps().get(0).isResultVerified());
            assertFalse(task.acceptableAnswer("已修改 `src/Pricing.java`，公式已改为 price * (1 - rate)。",
                    () -> service.stepsOf(1L, "c")), "没写过就不能说已修改");
            assertFalse(task.acceptableAnswer("已写入 `src/Pricing.java`。", () -> service.stepsOf(1L, "c")));
            // 同一句话在真的写入之后应当通过。
            task.record("editFile", "{\"path\":\"src/Pricing.java\"}", "✅ 修改成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            assertTrue(task.acceptableAnswer("已修改 `src/Pricing.java`，公式已改为 price * (1 - rate)。",
                    () -> service.stepsOf(1L, "c")));
        }
    }

    /** 用例 5：编译失败（exit≠0）不能变成「编译成功」。 */
    @Test void failedCompileCannotCompleteTheStep() throws Exception {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("编译并运行 Main 验证修复", "编译通过并输出 CHECK_OK")));
        PlanStep step = plan.getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            command(task, plan.getSteps(), COMPILE_AND_RUN, 1);
            assertTrue(task.verifiedCommands(step).isEmpty(), "exit=1 的命令不是成功证据");
            service.updateStep(1L, "c", "s1", "done", "编译报错，非法字符 '\\ufeff'");
            assertNotEquals("done", step.getStatus());
            assertFalse(step.isResultVerified());
            // 真的编译成功之后才能完成。
            command(task, plan.getSteps(), COMPILE_AND_RUN, 0);
            service.updateStep(1L, "c", "s1", "done", "编译通过，输出 CHECK_OK");
            assertEquals("done", step.getStatus());
            assertTrue(step.isResultVerified());
        }
    }

    /**
     * 用例 7：不相关的成功命令不能证明另一步骤。
     *
     * <p>模型声称「跑过 VerifyTmp」，但本次请求里真正执行成功的只有 PricingCheck 那条命令。
     * 以前会退回「最近一次成功命令」，把 PricingCheck 的成功当成 VerifyTmp 的证据。
     */
    @Test void unrelatedSuccessfulCommandDoesNotProveAnotherStep() throws Exception {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("编译并运行 VerifyTmp 校验折扣边界", "边界用例全部通过")));
        PlanStep step = plan.getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            command(task, plan.getSteps(),
                    "javac -encoding UTF-8 -d out src/Pricing.java src/PricingCheck.java && java -cp out PricingCheck", 0);
            assertTrue(task.verifiedCommands(step).isEmpty(), "没有跑过 VerifyTmp 就不能拿别的命令顶替");
            service.updateStep(1L, "c", "s1", "done", "VerifyTmp 6 个用例全部通过");
            assertNotEquals("done", step.getStatus(), "缺少对应证据的步骤必须保持 pending");
            assertFalse(step.isResultVerified());
            // 真的跑了 VerifyTmp 之后才允许完成。
            command(task, plan.getSteps(),
                    "javac -encoding UTF-8 -d out src/Pricing.java src/VerifyTmp.java && java -cp out VerifyTmp", 0);
            service.updateStep(1L, "c", "s1", "done", "VerifyTmp 输出 ALL_BOUNDARY_CHECKS_PASSED");
            assertEquals("done", step.getStatus());
            assertTrue(step.isResultVerified());
        }
    }

    /** 用例 7（后半）：同一条成功命令不能给两个步骤重复作证（过期证据复用）。 */
    @Test void sameCommandCannotVouchForTwoSteps() {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("编译并运行 PricingCheck 校验折扣边界", "PricingCheck 全部通过"),
                step("编译并运行 VerifyTmp 校验折扣边界", "VerifyTmp 全部通过")));
        List<PlanStep> steps = plan.getSteps();
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            command(task, steps,
                    "javac -encoding UTF-8 -d out src/Pricing.java src/PricingCheck.java && java -cp out PricingCheck", 0);
            service.updateStep(1L, "c", "s1", "done", "PricingCheck 全部通过");
            assertEquals("done", steps.get(0).getStatus());
            assertTrue(steps.get(0).isResultVerified());
            // s2 没有新命令，不能复用 s1 用过的同一条命令。
            assertTrue(task.verifiedCommands(steps.get(1)).isEmpty());
            service.updateStep(1L, "c", "s2", "done", "VerifyTmp 全部通过");
            assertNotEquals("done", steps.get(1).getStatus());
            assertFalse(steps.get(1).isResultVerified());
        }
    }

    /** 用例 8：普通文字回答不回归。 */
    @Test void plainTextAnswerStillPasses() {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("修复 src/Pricing.java 的公式", "src/Pricing.java 公式正确")));
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            task.record("editFile", "{\"path\":\"src/Pricing.java\"}", "✅ 修改成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            service.updateStep(1L, "c", "s1", "done", "已修改 src/Pricing.java");
            assertTrue(task.acceptableAnswer(
                    "问题出在 applyDiscount：注释说 rate 是折扣比例，实现却写成了 price * rate。"
                            + "已修改 `src/Pricing.java`，改成 price * (1 - rate)，重新编译运行输出 CHECK_OK。",
                    () -> service.stepsOf(1L, "c")));
            assertTrue(task.acceptableAnswer("修复已完成，编译通过，输出 CHECK_OK。",
                    () -> service.stepsOf(1L, "c")));
            // 没有任何执行记录时，纯承诺仍然不算交付。
            TaskExecution idle = new TaskExecution(8, 1000000, 1000000);
            try (var other = TaskExecution.attach(idle)) {
                assertFalse(idle.acceptableAnswer("我会先读取 src/Pricing.java，然后修改公式并重新编译。",
                        () -> List.of()));
            }
        }
    }

    /** 交付核对使用的证据键必须能按相对路径反查到同一个工作区文件。 */
    @Test void relativeAndAbsoluteEvidenceAgree() throws Exception {
        TaskPlan plan = service.create(1L, "c", List.of(
                step("修改 src/Pricing.java 与 src/Order.java 的折扣调用", "两个文件都改对")));
        PlanStep step = plan.getSteps().get(0);
        TaskExecution task = new TaskExecution(8, 1000000, 1000000);
        try (var scope = TaskExecution.attach(task)) {
            Files.writeString(dir.resolve("src/Order.java"), "public class Order { double t(){return 0;} }\n");
            task.record("editFile", "{\"path\":\"src/Pricing.java\"}", "✅ 修改成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            task.record("editFile", "{\"path\":\"src/Order.java\"}", "✅ 修改成功",
                    ToolExecutionStatus.Kind.EXECUTED, plan.getSteps());
            Map<String, String> files = task.verifiedFiles(step);
            assertEquals(2, files.size(), "两个文件都要有证据，实际=" + files);
            for (String key : files.keySet()) {
                assertTrue(Path.of(key).isAbsolute(), key);
                assertTrue(Files.exists(Path.of(key)), key);
            }
            service.updateStep(1L, "c", "s1", "done",
                    "已修改 src/Pricing.java 与 src/Order.java，两个文件都改对");
            assertEquals("done", step.getStatus());
            assertTrue(task.acceptableAnswer("已修改 `src/Pricing.java` 和 `src/Order.java`，公式与调用都改对了。",
                    () -> service.stepsOf(1L, "c")));
        }
    }
}
