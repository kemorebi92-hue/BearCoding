package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ChatRequest;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.agent.SubagentProperties;
import com.example.jingjingai2.agent.SubagentService;
import com.example.jingjingai2.agent.SubagentTelemetry;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.config.ChatModelFactory;
import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.config.PlanProperties;
import com.example.jingjingai2.context.ContextAssetTool;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.context.ToolHistoryCompactor;
import com.example.jingjingai2.db.DbDialect;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.mcp.McpServerTemplateCatalog;
import com.example.jingjingai2.memory.MemoryConsolidator;
import com.example.jingjingai2.memory.WorkspaceMemory;
import com.example.jingjingai2.permission.AllowAllGate;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.McpPermissionExplainer;
import com.example.jingjingai2.ratelimit.RateLimiter;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.search.SearchService;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.service.CodeVerifier;
import com.example.jingjingai2.service.ConversationHistoryService;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.service.McpServerProvisionService;
import com.example.jingjingai2.service.ModelConfigService;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.service.ToolCallLogService;
import com.example.jingjingai2.service.WorkspaceService;
import com.example.jingjingai2.tool.McpAdminTool;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;
import com.example.jingjingai2.usermemory.UserMemoryInjector;
import com.example.jingjingai2.usermemory.UserMemoryProperties;
import com.example.jingjingai2.usermemory.UserMemoryService;
import com.example.jingjingai2.usermemory.UserMemoryTool;
import com.example.jingjingai2.utils.FileTool;
import com.example.jingjingai2.utils.MemoryTool;
import com.example.jingjingai2.utils.PlanInjectionMessage;
import com.example.jingjingai2.utils.PlanTool;
import com.example.jingjingai2.utils.PlanToolFlat;
import com.example.jingjingai2.utils.SubagentTool;
import com.example.jingjingai2.utils.TerminalTool;
import com.example.jingjingai2.utils.WebSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * M4：上下文四套机制（条件历史摘要 / 工作集投影 / 工具结果退休 / 快照完整性）的组合回归。
 *
 * <p>与各阶段单测的区别：这里只有<strong>一次真实的控制器请求</strong>（{@code ChatController#chat}），
 * 历史来自真实 SQLite（真 Mapper、真 SQL），工具是真 {@code TerminalTool} / {@code FileTool} /
 * {@code ContextAssetTool}，工具结果由真的落盘层写成带签名与内容哈希的快照，投影与摘要走生产默认配置
 * （{@code application.yml}：working-set.enabled=true、soft-budget-tokens=64000、history-max-tokens=64000、
 * compact-window-percent=65）。唯一被替换的是模型本身：受控脚本模型按固定步骤返回工具调用，
 * 用来驱动真实的消息循环。
 *
 * <p>要证明的不是「每个机制单独能跑」，而是它们在同一个请求里同时生效且互不破坏：
 * 摘要替代了旧历史、退休只替换了旧工具正文、最近结果与恢复结果仍在、配对没坏、
 * 数据库原文与快照原文都没有被改写。
 */
class M4CombinedContextTest {

    private static final long USER = 1L;
    private static final String CONV = "conv-m4-combined";
    /** 生产默认模型窗口（未配置具体模型时 ChatController 用 128000）。 */
    private static final int WINDOW = 128_000;
    private static final String CURRENT = "当前问题：按计划继续，只允许改 notes.txt，不要动其它文件";
    private static final String FINAL_ANSWER =
            "执行完成：计划第一步与第二步都已上报；日志读取、旧结论恢复和校验命令的输出都与预期一致。";

    private static final Path WS = Path.of("target", "test-ws", "m4-combined").toAbsolutePath().normalize();
    private static final Path SPILL = WS.resolve("spill");

    /** 10 个短回合（可被摘要）+ 3 个长回合（最近 3 个用户回合，必须逐条保留）。 */
    private static final int SHORT_TURNS = 10;
    private static final int LONG_TURNS = 3;
    private static final int SHORT_CHARS = 1500;
    private static final int LONG_CHARS = 10_000;
    /** 每条命令输出远大于 6000 字符，必然触发落盘 + 预览 + 签名引用。 */
    private static final int FILE_LINES = 140;
    private static final int LINE_CHARS = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dbDir;

    private static String jdbcUrl;
    private static SqlSessionFactory sessionFactory;
    private static PooledDataSource pooledDataSource;

    private SqlSession session;
    private ConversationMapper mapper;
    private Harness harness;

    // ===================== 真实 SQLite =====================

    @BeforeAll
    static void startDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + dbDir.resolve("m4-combined.db").toAbsolutePath();
        DbDialect.overrideForTest(true);
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema-sqlite.sql"));
        }
        pooledDataSource = new PooledDataSource("org.sqlite.JDBC", jdbcUrl, "", "");
        Environment env = new Environment("m4-combined", new JdbcTransactionFactory(), pooledDataSource);
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(ConversationMapper.class);
        sessionFactory = new SqlSessionFactoryBuilder().build(cfg);
    }

    @AfterAll
    static void stopDatabase() {
        DbDialect.overrideForTest(null);
        if (pooledDataSource != null) pooledDataSource.forceCloseAll();
    }

    @BeforeEach
    void setUp() throws Exception {
        session = sessionFactory.openSession(true);
        mapper = session.getMapper(ConversationMapper.class);
        // 本用例验证的是上下文编排（摘要 / 退休 / 快照 / 检索），不是审批：
        // 显式安装「授予全部权限」的网关让工具真的执行。
        // 以前靠「不装网关 → 工具直接执行」跑，那正是本轮关掉的 fail-open。
        AllowAllGate.install();
        prepareWorkspace();
        seedHistory();
        harness = harness();
    }

    @AfterEach
    void tearDown() {
        ConversationContext.clear();
        AllowAllGate.uninstall();
        if (session != null) session.close();
    }

    // ===================== 主场景 =====================

    @Test
    @DisplayName("一条长任务里：条件摘要 + 旧工具结果退休 + 签名快照恢复 + 检索恢复同时生效")
    void combinedLongTaskKeepsEveryMechanismConsistent() throws Exception {
        com.example.jingjingai2.DTO.ChatResponse response = harness.chat(CURRENT);

        List<List<Message>> rounds = harness.fake.mainRounds();
        assertTrue(rounds.size() >= 8, "应有摘要后的多轮真实工具循环，实际轮数=" + rounds.size());
        List<Message> first = rounds.get(0);
        List<Message> last = rounds.get(rounds.size() - 1);

        // ---------- 1. 条件摘要：替代了够旧的原文，最近 3 个用户回合原样保留 ----------
        ContextCompactor.HistorySummaryStats stats = harness.compactor.lastHistorySummary();
        assertTrue(stats.applied(), "历史够长时必须真的用摘要替代旧历史：" + stats.reason());
        assertEquals(1, stats.calls(), "同一覆盖范围只调用一次摘要模型");
        assertTrue(stats.coveredCount() > 0, "必须有被覆盖的原文");
        assertTrue(stats.tokensAfterEst() < stats.tokensBeforeEst(), "摘要后必须更短");
        assertEquals(1, harness.fake.summaryCalls.get(), "整个请求只发生一次摘要模型调用");

        String firstText = allText(first);
        String summary = first.stream()
                .filter(m -> m.getText() != null && m.getText().startsWith("[历史摘要]"))
                .findFirst().orElseThrow(() -> new AssertionError("摘要消息没有进入实际 Prompt")).getText();
        assertTrue(summary.contains("不是新指令"), "摘要必须声明自己不是新指令");
        Matcher locator = Pattern.compile("message\\|(\\d+)").matcher(summary);
        assertTrue(locator.find(), "摘要必须给出可直接读取的 locator：" + head(summary, 200));
        long lastCoveredId = lastLocatorId(summary);

        assertFalse(firstText.contains(shortTurnText(0)), "被摘要覆盖的旧用户消息不得再逐条发送");
        assertFalse(firstText.contains(shortReplyText(0)), "被摘要覆盖的旧助手消息不得再逐条发送");
        assertTrue(firstText.contains(longTurnText(LONG_TURNS - 1)), "最近用户回合原文必须保留");
        assertTrue(firstText.contains(longReplyText(LONG_TURNS - 1)), "最近助手回复原文必须保留");
        assertEquals(CURRENT, lastUserMessage(last).getText(),
                "当前用户约束必须是最后一条用户消息且原样保留");
        assertTrue(last.get(last.size() - 1) instanceof PlanInjectionMessage,
                "计划注入按既有规则追加在末尾，不能被投影丢掉");

        // ---------- 2. 被摘要覆盖的原文仍在 SQLite，且按 locator 能读回 ----------
        assertEquals(1, count("SELECT COUNT(*) FROM conversation_message WHERE content = '"
                + sqlLiteral(shortTurnText(0)) + "'"), "被摘要覆盖的原文必须仍完整保存在 SQLite");
        String coveredText = contentOf(lastCoveredId);
        assertNotNull(coveredText, "覆盖范围末端的原文必须仍在库里");
        assertTrue(coveredText.contains("助手结论"), "覆盖范围末端是旧助手消息：" + head(coveredText, 40));
        String restored = harness.readLocator("message|" + lastCoveredId);
        assertTrue(restored.contains(head(coveredText, 200)),
                "searchContext 按 locator 读回的必须是原文，实际开头：" + head(restored, 200));

        // ---------- 3. 工具结果退休：旧正文换成带签名/哈希的快照引用，最近结果原样保留 ----------
        List<String> lastBodies = toolBodies(last);
        List<String> markers = new ArrayList<>();
        for (String body : lastBodies) {
            if (body != null && body.startsWith(ToolHistoryCompactor.MARKER_PREFIX)) markers.add(body);
        }
        assertTrue(markers.size() >= 2, "预算压力下应有多条旧工具结果被退休，实际=" + markers.size());
        for (String marker : markers) {
            ToolArtifactRef.Ref ref = ToolArtifactRef.parse(marker);
            assertNotNull(ref, "省略标记里必须带验签通过的引用：" + marker);
            assertTrue(ref.hasContentHash(), "省略标记的引用必须带 sha256：" + marker);
            assertTrue(Files.exists(Path.of(ref.path())), "引用指向的快照必须真实存在：" + ref.path());
        }
        assertTrue(markers.stream().anyMatch(m -> m.contains("readFile")),
                "标记里要写明用 readFile 读回：" + markers);

        String oldPreview = previewWith("FILE-1", rounds);
        assertNotNull(oldPreview, "前提：较早的某一轮能看到 f1 的完整预览");
        assertFalse(allText(last).contains(oldPreview), "退休后旧正文不得再出现在最终 Prompt 里");
        assertTrue(Files.readString(harness.snapshotOf("FILE-1"), StandardCharsets.UTF_8).contains("FILE-1"),
                "快照里的完整原文没有被退休改写");

        String newestPreview = previewWith("FILE-8", rounds);
        assertNotNull(newestPreview, "前提：能看到 f8 的预览");
        assertTrue(lastBodies.contains(newestPreview), "最新工具结果必须原样保留，不能被退休");

        // ---------- 4. 恢复：签名快照 readFile 与 searchContext 取回的内容仍在，并受 M3 保护 ----------
        String restoredFile = null;
        for (String body : lastBodies) {
            if (body != null && body.contains("FILE-1") && body.contains("LINE-" + FILE_LINES + "-")) {
                restoredFile = body;
            }
        }
        assertNotNull(restoredFile, "按签名引用恢复的快照正文必须仍在 Prompt 里");
        assertFalse(restoredFile.startsWith(ToolHistoryCompactor.MARKER_PREFIX),
                "刚恢复的内容不能在同一请求里被立刻退休");

        String searchRecovered = null;
        for (String body : lastBodies) {
            // 工具结果交给模型时是一段 JSON 字符串（引号被转义），按字段名判断而不是按原始 JSON 片段。
            if (body != null && body.contains("operation") && body.contains("locator")
                    && body.contains("message|") && body.contains("role")) {
                searchRecovered = body;
            }
        }
        assertNotNull(searchRecovered, "searchContext 的恢复结果必须仍在 Prompt 里。实际正文摘要=" + describe(lastBodies));
        assertTrue(searchRecovered.contains(head(shortReplyText(SHORT_TURNS - 1), 200)),
                "检索恢复的正文必须与原文一致");

        // ---------- 5. 配对、计划证据、真实落盘 ----------
        MessageProtocolValidator.Result protocol = MessageProtocolValidator.check(last);
        assertTrue(protocol.ok(), "退休 + 恢复之后 tool_call / tool_result 必须仍配对：" + protocol.summary());
        assertTrue(last.stream().anyMatch(m -> m instanceof PlanInjectionMessage), "活动计划必须仍被注入");
        assertTrue(allText(last).contains("s1"), "计划步骤状态必须可见");
        assertEquals("配置版本二", Files.readString(WS.resolve("notes.txt"), StandardCharsets.UTF_8).trim(),
                "后续代码修改必须真的落盘");
        assertTrue(allText(last).contains("配置版本二"), "修改后的内容必须经校验命令回灌到上下文");

        // ---------- 6. 摘要与退休不重复处理同一份原文 ----------
        assertTrue(stats.coveredIds().size() >= SHORT_TURNS * 2 - 2,
                "覆盖范围应覆盖大部分短回合，实际=" + stats.coveredIds().size());
        for (Long id : stats.coveredIds()) {
            assertFalse(allText(last).contains("locator=message|" + id + "]"),
                    "被摘要覆盖的消息不应再被退休成 locator 说明（同一份原文不能处理两次）");
        }

        // ---------- 7. 终态 ----------
        assertNotNull(response);
        assertEquals(FINAL_ANSWER, response.getMessage());
        assertTrue(harness.fake.calls.get() >= 9, "模型调用次数应覆盖摘要 + 全部脚本轮次");
    }

    // ===================== 恢复失败必须明确报错 =====================

    @Test
    @DisplayName("快照被篡改 / 被删除 / 越出沙箱 / 缺哈希：readFile 明确失败，不返回看似成功的正文")
    void brokenSnapshotFailsLoudly() throws Exception {
        Path good = SPILL.resolve(CONV).resolve("manual-snapshot.txt");
        Files.createDirectories(good.getParent());
        String body = "完整正文" + "细节".repeat(500) + "结束标记";
        Files.writeString(good, body, StandardCharsets.UTF_8);
        String marker = ToolArtifactRef.marker(good.toString(), body.length(), "executeCommand", sha256(body));

        FileTool fileTool = new FileTool(new PathSandbox(WS.toString()));
        String ok = fileTool.readFile(marker, 1, 1);
        assertTrue(ok.contains("完整正文"), "签名与哈希都对时必须能读回正文：" + head(ok, 120));

        Files.writeString(good, body + "被篡改", StandardCharsets.UTF_8);
        String tampered = fileTool.readFile(marker, 1, 1);
        assertTrue(tampered.contains("快照内容校验不一致"), "内容被替换必须明确失败：" + tampered);
        assertFalse(tampered.contains("完整正文"), "失败时不能返回未校验的正文");

        Files.delete(good);
        String missing = fileTool.readFile(marker, 1, 1);
        assertTrue(missing.contains("文件不存在"), "快照被删除必须明确失败：" + missing);

        Path outside = Path.of(System.getProperty("java.io.tmpdir")).resolve("m4-outside-snapshot.txt");
        Files.writeString(outside, body, StandardCharsets.UTF_8);
        String outsideMarker = ToolArtifactRef.marker(outside.toString(), body.length(), "executeCommand",
                sha256(body));
        String unauthorized = fileTool.readFile(outsideMarker, 1, 1);
        assertTrue(unauthorized.contains("读取失败"), "沙箱外快照必须拒绝读取：" + unauthorized);
        assertFalse(unauthorized.contains("完整正文"), "越权时不能返回正文");
        Files.deleteIfExists(outside);

        Files.writeString(good, body, StandardCharsets.UTF_8);
        String unsigned = ToolArtifactRef.marker(good.toString(), body.length(), "executeCommand");
        String noHash = fileTool.readFile(unsigned, 1, 1);
        assertTrue(noHash.contains("缺少内容哈希"), "缺哈希的旧引用必须拒绝：" + noHash);
    }

    // ===================== 夹具 =====================

    private static void prepareWorkspace() throws IOException {
        deleteTree(WS);
        Files.createDirectories(WS);
        Files.createDirectories(SPILL);
        for (int i = 1; i <= 8; i++) {
            StringBuilder sb = new StringBuilder();
            for (int line = 1; line <= FILE_LINES; line++) {
                sb.append("FILE-").append(i).append(" LINE-").append(line).append('-');
                while (sb.length() % LINE_CHARS != 0) sb.append('内');
                sb.append('\n');
            }
            Files.writeString(WS.resolve("f" + i + ".txt"), sb.toString(), StandardCharsets.UTF_8);
        }
        Files.writeString(WS.resolve("notes.txt"), "配置版本一\n", StandardCharsets.UTF_8);
    }

    private void seedHistory() {
        exec("DELETE FROM conversation_message");
        int seq = 0;
        for (int turn = 0; turn < SHORT_TURNS; turn++) {
            insert("user", shortTurnText(turn), seq++);
            insert("assistant", shortReplyText(turn), seq++);
        }
        for (int turn = 0; turn < LONG_TURNS; turn++) {
            insert("user", longTurnText(turn), seq++);
            insert("assistant", longReplyText(turn), seq++);
        }
    }

    private void insert(String role, String content, int seq) {
        exec("INSERT INTO conversation_message(user_id, conversation_id, role, content, created_at) "
                        + "VALUES(?,?,?,?,?)", USER, CONV, role, content,
                String.format("2026-11-13 09:%02d:%02d", seq / 60, seq % 60));
    }

    private static String shortTurnText(int turn) {
        return "第" + turn + "轮用户约束：只允许改 notes.txt，" + "约".repeat(SHORT_CHARS);
    }

    private static String shortReplyText(int turn) {
        return "第" + turn + "轮助手结论：已核对，" + "答".repeat(SHORT_CHARS);
    }

    private static String longTurnText(int turn) {
        return "最近第" + turn + "轮用户要求：保持中文回复，" + "求".repeat(LONG_CHARS);
    }

    private static String longReplyText(int turn) {
        return "最近第" + turn + "轮助手回复：继续执行，" + "复".repeat(LONG_CHARS);
    }

    // ===================== 受控脚本模型 + 真实工具装配 =====================

    private Harness harness() {
        ScriptedModel fake = new ScriptedModel();
        ConversationHistoryService history = new ConversationHistoryService(mapper);
        ConversationService conversation = mock(ConversationService.class);

        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryAcquire(anyLong())).thenReturn(null);

        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.currentRoot(any(), any())).thenReturn(WS);

        SubagentProperties subagentProperties = mock(SubagentProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        PlanProperties planProperties = mock(PlanProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planProperties.getInjection().isSlim()).thenReturn(true);
        when(planProperties.getInjection().getFullRefreshEvery()).thenReturn(6);
        MemoryProperties memoryProperties = mock(MemoryProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        UserMemoryProperties userMemoryProperties = mock(UserMemoryProperties.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);

        WorkspaceMemory workspaceMemory = mock(WorkspaceMemory.class);
        WorkspaceMemory.Snapshot memorySnapshot = new WorkspaceMemory(memoryProperties).load(null);
        when(workspaceMemory.load(any())).thenReturn(memorySnapshot);

        McpClientManager mcp = mock(McpClientManager.class);
        when(mcp.isEnabled()).thenReturn(false);
        when(mcp.callbacks(anyLong())).thenReturn(null);

        // 生产默认口径：历史预算 64000、模型窗口 128000、触发线 65% = 83200。
        ContextCompactor compactor = new ContextCompactor(fake.model, 64_000, WINDOW, 65);

        ModelConfigService modelConfig = mock(ModelConfigService.class);
        when(modelConfig.contextWindowOf(any(), any())).thenReturn(WINDOW);

        PathSandbox sandbox = new PathSandbox(WS.toString());
        FileTool fileTool = new FileTool(sandbox);
        McpOutputProperties outputProperties = new McpOutputProperties();
        outputProperties.setDir(SPILL.toString());
        ToolOutputStore outputStore = new ToolOutputStore(outputProperties);
        TerminalTool terminalTool = new TerminalTool(sandbox);
        terminalTool.setToolOutputStore(outputStore);

        PlanServiceFixture plans = new PlanServiceFixture();
        UserMemoryService userMemories = mock(UserMemoryService.class);
        when(userMemories.listByUser(any())).thenReturn(List.of());
        ContextAssetTool assetTool = new ContextAssetTool(mapper, userMemories, plans.service);

        PlanTool planTool = new PlanTool(plans.service);
        SubagentService subagentService = mock(SubagentService.class);
        CodeVerifier codeVerifier = mock(CodeVerifier.class);
        when(codeVerifier.verify(any())).thenReturn(CodeVerifier.Result.skipped("m4"));
        SearchService search = mock(SearchService.class);
        when(search.isConfigured()).thenReturn(true);

        ChatController controller = new ChatController(
                mock(ChatClient.class),
                fake.model,
                history,
                conversation,
                spy(new AgentCancellationRegistry()),
                plans.service,
                planTool,
                new PlanToolFlat(planTool),
                planProperties,
                terminalTool,
                fileTool,
                mock(ToolCallLogService.class),
                rateLimiter,
                compactor,
                modelConfig,
                mock(ChatModelFactory.class),
                workspaceService,
                codeVerifier,
                new WebSearchTool(search),
                subagentService,
                new SubagentTool(subagentService),
                mock(SubagentTelemetry.class),
                subagentProperties,
                assetTool,
                new UserMemoryTool(mock(UserMemoryService.class)),
                userMemoryProperties,
                mock(UserMemoryInjector.class),
                mcp,
                mock(ApprovalService.class),
                new McpAdminTool(mock(McpServerTemplateCatalog.class),
                        mock(McpServerProvisionService.class), mock(McpServerConfigMapper.class)),
                workspaceMemory,
                new MemoryTool(workspaceMemory),
                memoryProperties,
                mock(MemoryConsolidator.class),
                mock(AgentTurnRecorder.class));
        // 本测试用 useSystemModel=true 驱动；系统模型在测试里按“已配置”处理。
        ChatEntryPromptViewTest.setSystemApiKey(controller, "test-system-model-key");

        // 脚本：建计划 → 四轮各跑两条真实命令（输出必然落盘）→ 两次恢复（检索旧消息 + 按引用读快照）
        // → 改文件并跑校验命令 → 收尾。
        List<Function<List<Message>, AssistantMessage>> script = new ArrayList<>();
        script.add(p -> call("p1", "createPlan", args("steps",
                "读取日志文件并汇总\n修改 notes.txt 并核对\n运行校验命令")));
        script.add(p -> calls(
                new String[]{"cmd1", "executeCommand", args("command", "type f1.txt")},
                new String[]{"cmd2", "executeCommand", args("command", "type f2.txt")}));
        script.add(p -> calls(
                new String[]{"cmd3", "executeCommand", args("command", "type f3.txt")},
                new String[]{"cmd4", "executeCommand", args("command", "type f4.txt")}));
        script.add(p -> calls(
                new String[]{"cmd5", "executeCommand", args("command", "type f5.txt")},
                new String[]{"cmd6", "executeCommand", args("command", "type f6.txt")}));
        script.add(p -> calls(
                new String[]{"cmd7", "executeCommand", args("command", "type f7.txt")},
                new String[]{"cmd8", "executeCommand", args("command", "type f8.txt")}));
        script.add(p -> calls(
                new String[]{"read1", "searchContext", args("operation", "read", "locator",
                        "message|" + firstLocatorId(p))},
                new String[]{"read2", "readFile", args("path", firstArtifactRef(p))}));
        script.add(p -> calls(
                new String[]{"step1", "updatePlanStep", args("stepId", "s1", "status", "done",
                        "actualResult", "已读取 8 份日志并汇总")},
                new String[]{"edit1", "editFile", args("path", "notes.txt",
                        "oldStr", "配置版本一", "newStr", "配置版本二")},
                new String[]{"verify1", "executeCommand", args("command", "type notes.txt")}));
        script.add(p -> new AssistantMessage(FINAL_ANSWER));
        fake.script.addAll(script);

        return new Harness(controller, fake, compactor, plans, outputStore);
    }

    /** 把受控控制器放进真实 Spring 环境，让 application.yml 的默认值真正生效。 */
    private static void withProductionDefaults(ChatController controller, Runnable action) {
        new ApplicationContextRunner()
                .withInitializer(context -> loadApplicationYml(context.getEnvironment()))
                .withBean(McpPermissionExplainer.class, () -> mock(McpPermissionExplainer.class))
                .withBean(McpRoundActivation.class, McpRoundActivation::new)
                .withBean("chatController", ChatController.class, () -> controller)
                .run(context -> action.run());
    }

    private static void loadApplicationYml(ConfigurableEnvironment environment) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            for (int i = sources.size() - 1; i >= 0; i--) {
                environment.getPropertySources().addFirst(sources.get(i));
            }
            // 本测试以“系统模型已配置”为前提；必须放在 application.yml 之后 addFirst，
            // 否则会被 ${DEEPSEEK_API_KEY:} 的空值覆盖（Spring 会重新注入 @Value 字段）。
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "testSystemModel", java.util.Map.of("spring.ai.openai.api-key", "test-system-model-key")));
        } catch (IOException e) {
            throw new IllegalStateException("加载生产 application.yml 失败", e);
        }
    }

    // ===================== 脚本步骤的辅助 =====================

    private static String firstLocatorId(List<Message> prompt) {
        for (Message message : prompt) {
            String text = message.getText();
            if (text == null || !text.startsWith("[历史摘要]")) continue;
            return String.valueOf(lastLocatorId(text));
        }
        throw new IllegalStateException("Prompt 里没有摘要 locator");
    }

    /** 摘要里「到 message|N」那个 N：覆盖范围末端（旧助手消息）。 */
    private static long lastLocatorId(String summary) {
        Matcher range = Pattern.compile("到 message\\|(\\d+)").matcher(summary);
        if (range.find()) return Long.parseLong(range.group(1));
        Matcher m = Pattern.compile("message\\|(\\d+)").matcher(summary);
        long last = -1;
        while (m.find()) last = Long.parseLong(m.group(1));
        if (last < 0) throw new IllegalStateException("摘要里没有 locator");
        return last;
    }

    /** 模型从上一轮拿到的结果里取一条真实签名引用，用来恢复快照。 */
    private static String firstArtifactRef(List<Message> prompt) {
        for (String body : toolBodies(prompt)) {
            if (body == null) continue;
            ToolArtifactRef.Ref ref = ToolArtifactRef.parse(body);
            if (ref != null && ref.hasContentHash()) {
                return body.substring(body.indexOf(ToolArtifactRef.OPEN),
                        body.indexOf(ToolArtifactRef.CLOSE) + ToolArtifactRef.CLOSE.length());
            }
        }
        throw new IllegalStateException("Prompt 里没有可恢复的签名引用");
    }

    private static String args(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AssistantMessage call(String id, String name, String arguments) {
        return new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)));
    }

    private static AssistantMessage calls(String[]... specs) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String[] spec : specs) calls.add(new AssistantMessage.ToolCall(spec[0], "function", spec[1], spec[2]));
        return new AssistantMessage("", Map.of(), calls);
    }

    // ===================== 断言辅助 =====================

    private static List<String> toolBodies(List<Message> messages) {
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

    private static String allText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(message.getText() == null ? "" : message.getText()).append('\n');
        }
        for (String body : toolBodies(messages)) sb.append(body).append('\n');
        return sb.toString();
    }

    /** 找出含某个文件标记的完整预览（落盘后的头尾预览，不是省略标记）。 */
    private static String previewWith(String fileTag, List<List<Message>> rounds) {
        for (List<Message> round : rounds) {
            for (String body : toolBodies(round)) {
                if (body != null && body.contains(fileTag) && body.contains("命令输出过长")) return body;
            }
        }
        return null;
    }

    private static Message lastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof org.springframework.ai.chat.messages.UserMessage) {
                return messages.get(i);
            }
        }
        throw new AssertionError("Prompt 里没有用户消息");
    }

    private static String describe(List<String> bodies) {
        StringBuilder sb = new StringBuilder("[");
        for (String body : bodies) {
            sb.append(body == null ? "null" : body.length() + ":" + head(body, 60).replace('\n', ' '))
                    .append(" | ");
        }
        return sb.append(']').toString();
    }

    private static String head(String text, int n) {
        if (text == null) return "";
        return text.length() <= n ? text : text.substring(0, n);
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private String contentOf(long id) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT content FROM conversation_message WHERE id=" + id)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败", e);
        }
    }

    private static int count(String sql) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    private static String sqlLiteral(String text) {
        return text.replace("'", "''");
    }

    private static void exec(String sql, Object... params) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>(stream.toList());
            Collections.reverse(paths);
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    // ===================== 夹具类型 =====================

    private final class Harness {
        final ChatController controller;
        final ScriptedModel fake;
        final ContextCompactor compactor;
        final PlanServiceFixture plans;
        final ToolOutputStore store;

        Harness(ChatController controller, ScriptedModel fake, ContextCompactor compactor,
                PlanServiceFixture plans, ToolOutputStore store) {
            this.controller = controller;
            this.fake = fake;
            this.compactor = compactor;
            this.plans = plans;
            this.store = store;
        }

        com.example.jingjingai2.DTO.ChatResponse chat(String message) {
            ChatRequest request = new ChatRequest();
            request.setMessage(message);
            request.setConversationId(CONV);
            request.setUseSystemModel(true);
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.setAttribute("userId", USER);
            com.example.jingjingai2.DTO.ChatResponse[] out =
                    new com.example.jingjingai2.DTO.ChatResponse[1];
            withProductionDefaults(controller, () -> out[0] = controller.chat(request, http));
            return out[0];
        }

        String readLocator(String locator) {
            ConversationContext.set(USER, CONV);
            try {
                UserMemoryService memories = mock(UserMemoryService.class);
                when(memories.listByUser(any())).thenReturn(List.of());
                ContextAssetTool tool = new ContextAssetTool(mapper, memories, plans.service);
                return tool.searchContext("read", null, null, null, locator, 0, null, null);
            } finally {
                ConversationContext.clear();
            }
        }

        /** 按内容定位某条命令结果落盘后的快照文件。 */
        Path snapshotOf(String fileTag) throws IOException {
            try (var stream = Files.list(SPILL.resolve(CONV))) {
                for (Path file : stream.toList()) {
                    if (Files.readString(file, StandardCharsets.UTF_8).contains(fileTag)) return file;
                }
            }
            throw new IllegalStateException("没有找到含 " + fileTag + " 的快照");
        }
    }

    /** 受控脚本模型：摘要请求固定返回一段短摘要，主请求按脚本逐步返回工具调用。 */
    private static final class ScriptedModel {
        static final String SUMMARY = "早期 10 轮已确认：用户只允许修改 notes.txt，回复必须用中文；"
                + "已读取日志并汇总，旧结论是继续按计划执行。";

        final OpenAiChatModel model = mock(OpenAiChatModel.class);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger summaryCalls = new AtomicInteger();
        final List<List<Message>> recorded = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedDeque<Function<List<Message>, AssistantMessage>> script = new ConcurrentLinkedDeque<>();

        ScriptedModel() {
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                List<Message> messages = List.copyOf(prompt.getInstructions());
                recorded.add(messages);
                calls.incrementAndGet();
                if (isSummaryRequest(messages)) {
                    summaryCalls.incrementAndGet();
                    return response(new AssistantMessage(SUMMARY));
                }
                Function<List<Message>, AssistantMessage> step = script.pollFirst();
                if (step == null) throw new IllegalStateException("脚本步骤已用尽");
                return response(step.apply(messages));
            });
        }

        private static boolean isSummaryRequest(List<Message> messages) {
            return messages.size() == 1 && messages.get(0).getText() != null
                    && messages.get(0).getText().startsWith("请把下面这段对话历史压缩成一段摘要");
        }

        private static ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }

        List<List<Message>> mainRounds() {
            List<List<Message>> out = new ArrayList<>();
            for (List<Message> messages : recorded) {
                if (!isSummaryRequest(messages)) out.add(messages);
            }
            return out;
        }
    }

    /** 计划服务替身：状态真的会变（create / updateStep），其余读接口照常返回。 */
    private static final class PlanServiceFixture {
        final TaskPlanService service = mock(TaskPlanService.class);
        final TaskPlan plan = new TaskPlan("plan-m4");

        PlanServiceFixture() {
            when(service.get(any(), any())).thenAnswer(i -> plan.getSteps().isEmpty() ? null : plan);
            when(service.stepsOf(any(), any())).thenAnswer(i -> plan.getSteps());
            when(service.hasPlan(any(), any())).thenAnswer(i -> !plan.getSteps().isEmpty());
            when(service.allStepsDone(any(), any())).thenAnswer(i -> plan.allDone());
            when(service.versionOf(any(), any())).thenAnswer(i -> plan.getVersion());
            when(service.budget(any(), any(), anyInt())).thenAnswer(i -> 30);
            when(service.render(any(), any(), anyInt())).thenAnswer(i -> renderText());
            when(service.renderSlim(any(), any(), anyInt())).thenAnswer(i -> renderText());
            when(service.resetIfFinished(any(), any())).thenReturn(false);
            when(service.create(any(), any(), any())).thenAnswer(invocation -> {
                List<StepInput> inputs = invocation.getArgument(2);
                List<PlanStep> steps = new ArrayList<>();
                int i = 1;
                for (StepInput in : inputs) {
                    steps.add(new PlanStep("s" + i++, in.getDescription(), in.getExpected()));
                }
                plan.setSteps(steps);
                plan.setMaxIterations(TaskPlan.budgetFor(steps.size()));
                return plan;
            });
            when(service.updateStep(any(), any(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                TaskPlanService.UpdateOutcome out = new TaskPlanService.UpdateOutcome();
                PlanStep step = plan.findStep(invocation.getArgument(2));
                if (step == null) {
                    out.message = "找不到步骤";
                    return out;
                }
                String status = invocation.getArgument(3);
                if (PlanStep.DONE.equals(status) || PlanStep.FAILED.equals(status)) step.setStatus(status);
                step.setActualResult(invocation.getArgument(4));
                plan.bumpVersion();
                out.step = step;
                out.finalStatus = step.getStatus();
                out.message = "已上报 " + step.getStepId() + " " + step.getStatus();
                return out;
            });
        }

        private String renderText() {
            StringBuilder sb = new StringBuilder("【当前计划】");
            for (PlanStep step : plan.getSteps()) {
                sb.append(step.getStepId()).append(' ').append(step.getStatus()).append(' ')
                        .append(step.getDescription()).append('\n');
            }
            return sb.toString();
        }
    }
}
