package com.example.jingjingai2.sqlite;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextAssetTool;
import com.example.jingjingai2.context.ContextProjectionPolicy;
import com.example.jingjingai2.context.ContextSourceLedger;
import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.db.DbDialect;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mapper.sql.ConversationSqlProvider;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.usermemory.UserMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3.2-E3-R2 离线验收：退休 → 检索 → 原文恢复 的完整闭环。
 *
 * <p>用真实 SQLite 文件 + 真实 Mapper SQL，搜索路径不用 mock 替掉：
 * 分页、排序、locator 读取、权限隔离都在真库上验证。
 * 数据库是 JUnit 临时目录里的独立文件，不碰 ./data/jingjing.db。
 */
class RetirementSearchRecoveryTest {

    /** 唯一、不可猜测的事实，只出现在最早那条消息里。 */
    private static final String FACT = "校验码 K7Q2-9XW4-LM31";
    private static final String KEYWORD = "审查";
    private static final String CONV = "conv-e3r2";
    private static final String OTHER_CONV = "conv-other";
    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;
    private static final int HISTORY_SIZE = 24;

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tempDir;

    private static String jdbcUrl;
    private static SqlSessionFactory sessionFactory;
    private static PooledDataSource pooledDataSource;

    /** 本次 seed 实际落库的消息 id，按时间升序。不假设自增值。 */
    private static List<Long> seededIds;
    private static long oldestId;

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("e3r2-acceptance.db").toAbsolutePath();
        DbDialect.overrideForTest(true);
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema-sqlite.sql"));
        }
        pooledDataSource = new PooledDataSource("org.sqlite.JDBC", jdbcUrl, "", "");
        Environment env = new Environment("e3r2-acceptance", new JdbcTransactionFactory(), pooledDataSource);
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(ConversationMapper.class);
        sessionFactory = new SqlSessionFactoryBuilder().build(cfg);
    }

    @AfterAll
    static void tearDown() {
        DbDialect.overrideForTest(null);
        if (pooledDataSource != null) pooledDataSource.forceCloseAll();
    }

    @BeforeEach
    void seed() {
        exec("DELETE FROM conversation_message");
        // 最早的一条：用户消息，含唯一事实，且足够长以触发分页读取
        insertMessage(USER, CONV, "user", oldestContent(), "2026-11-10 08:00:00");
        // 后面大量消息含同一个关键词
        for (int i = 2; i <= HISTORY_SIZE; i++) {
            insertMessage(USER, CONV, i % 2 == 0 ? "assistant" : "user",
                    "第 " + i + " 条" + KEYWORD + "记录：常规内容，没有特殊约定。",
                    "2026-11-10 08:00:" + pad(i));
        }
        seededIds = idsOf(USER, CONV);
        oldestId = seededIds.get(0);
        // 另一个用户的同名关键词消息
        insertMessage(OTHER_USER, CONV, "user", "别的用户的" + KEYWORD + "记录，不该被看到", "2026-11-10 09:00:00");
        // 同一用户的另一个会话
        insertMessage(USER, OTHER_CONV, "user", "另一个会话的" + KEYWORD + "记录，不该被看到", "2026-11-10 09:00:01");
    }

    // ===================== 1 & 3：分页能找到最早那条，且不重不漏 =====================

    @Test
    @DisplayName("默认第一页看不到最早消息；按 cursor 翻页能找到，且不重复不漏项")
    void paginationReachesOldestWithoutDupOrMiss() throws Exception {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);

            JsonNode first = search(tool, 5, 0, null);
            List<Long> firstIds = ids(first);
            assertEquals(5, firstIds.size(), "默认第一页只有 5 条");
            assertFalse(firstIds.contains(oldestId),
                    "最早的目标消息不在第一页，这正是原缺陷的触发条件");
            assertTrue(first.get("hasMore").asBoolean(), "第一页必须明确告知还有下一页");

            List<Long> paged = new ArrayList<>();
            int cursor = 0;
            int guard = 0;
            while (true) {
                JsonNode page = search(tool, 5, cursor, null);
                paged.addAll(ids(page));
                if (!page.get("hasMore").asBoolean()) break;
                assertEquals(paged.size(), page.get("nextCursor").asInt(),
                        "nextCursor 必须等于已返回条数，翻页才能衔接");
                cursor = page.get("nextCursor").asInt();
                if (++guard > 100) fail("翻页没有终止");
            }

            assertEquals(HISTORY_SIZE, paged.size(), "翻完应当正好拿到全部命中，不多不少");
            assertEquals(new ArrayList<>(new LinkedHashSet<>(paged)), paged, "翻页不能有重复");
            assertEquals(new LinkedHashSet<>(seededIds), new LinkedHashSet<>(paged), "翻页不能漏项");
            assertTrue(paged.contains(oldestId), "翻页必须能找到最早的消息");

            for (int i = 1; i < paged.size(); i++) {
                assertTrue(paged.get(i - 1) > paged.get(i), "默认降序：id 必须严格递减");
            }
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("order=asc 从最早开始，第一页就包含最早的消息")
    void ascendingOrderFindsOldestImmediately() throws Exception {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);

            JsonNode asc = search(tool, 5, 0, "asc");
            List<Long> ascIds = ids(asc);
            assertEquals("asc", asc.get("order").asText());
            assertEquals(oldestId, ascIds.get(0), "升序第一条就是最早的消息");
            for (int i = 1; i < ascIds.size(); i++) {
                assertTrue(ascIds.get(i - 1) < ascIds.get(i), "升序：id 必须严格递增");
            }
        } finally {
            ConversationContext.clear();
        }
    }

    // ===================== 2：按 locator 读到与数据库一致的完整原文 =====================

    @Test
    @DisplayName("按 locator 分页读回完整原文，拼接结果与数据库原文逐字符一致")
    void locatorReadRestoresExactOriginal() throws Exception {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);

            String dbOriginal = rowStr("SELECT content FROM conversation_message WHERE id=" + oldestId);
            assertNotNull(dbOriginal, "前提：最早那条消息在库里");
            assertTrue(dbOriginal.contains(FACT), "前提：数据库原文含唯一事实");
            assertEquals(1, countFactOccurrences(), "唯一事实在整个库里只出现一次");

            JsonNode asc = search(tool, 5, 0, "asc");
            JsonNode target = asc.get("items").get(0);
            assertEquals(oldestId, target.get("id").asLong());
            String locator = target.get("locator").asText();
            assertEquals("message|" + oldestId, locator, "locator 格式稳定");

            StringBuilder restored = new StringBuilder();
            Integer offset = 0;
            int guard = 0;
            while (offset != null) {
                JsonNode page = JSON.readTree(
                        tool.searchContext("read", null, null, null, locator, offset, null, null));
                restored.append(page.get("content").asText());
                assertEquals(dbOriginal.length(), page.get("totalChars").asInt(), "总长度与库一致");
                offset = page.get("hasMore").asBoolean() ? page.get("nextOffset").asInt() : null;
                if (++guard > 100) fail("读取分页没有终止");
            }

            assertEquals(dbOriginal, restored.toString(), "恢复内容必须与数据库原文逐字符一致");
            assertTrue(restored.toString().contains(FACT), "唯一事实被完整恢复");
            assertTrue(dbOriginal.length() > 4000, "原文超过单次读取上限，确实走了多页");
        } finally {
            ConversationContext.clear();
        }
    }

    // ===================== 4：用户与会话隔离 =====================

    @Test
    @DisplayName("搜索只在本用户本会话内命中；别人的 id 读不到")
    void searchAndReadAreIsolated() throws Exception {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);

            List<Long> paged = new ArrayList<>();
            int cursor = 0;
            while (true) {
                JsonNode page = search(tool, 5, cursor, null);
                paged.addAll(ids(page));
                if (!page.get("hasMore").asBoolean()) break;
                cursor = page.get("nextCursor").asInt();
            }
            assertEquals(HISTORY_SIZE, paged.size(), "别人和别的会话的消息不能混进来");
            assertEquals(new LinkedHashSet<>(seededIds), new LinkedHashSet<>(paged));

            long foreignUserMsg = rowLong(
                    "SELECT id FROM conversation_message WHERE user_id=" + OTHER_USER);
            long foreignConvMsg = rowLong(
                    "SELECT id FROM conversation_message WHERE conversation_id='" + OTHER_CONV + "'");

            String a = tool.searchContext("read", null, null, null, "message|" + foreignUserMsg, 0, null, null);
            assertTrue(a.contains("没有这条消息"), "别的用户的 id 读不到：" + a);
            String b = tool.searchContext("read", null, null, null, "message|" + foreignConvMsg, 0, null, null);
            assertTrue(b.contains("没有这条消息"), "别的会话的 id 读不到：" + b);

            ConversationContext.set(OTHER_USER, CONV);
            String c = tool.searchContext("read", null, null, null, "message|" + oldestId, 0, null, null);
            assertTrue(c.contains("没有这条消息"), "用户 2 读不到用户 1 的消息");
        } finally {
            ConversationContext.clear();
        }
    }

    // ===================== 5 & 7：普通任务不额外搜索；关闭工作集保持基线 =====================

    @Test
    @DisplayName("关闭工作集时投影结果与原消息完全一致（基线不变）")
    void disabledKeepsBaseline() {
        List<Message> actual = shortConversation();
        ContextSourceLedger ledger = new ContextSourceLedger(CONV);
        ledger.absorb(actual, 0, null);

        ContextProjectionPolicy.Result off = ContextProjectionPolicy.project(
                ledger, actual, null, 5, 0, 1, 0, false, false);

        assertEquals(actual, off.messages(), "关闭时一条都不能改");
        assertEquals(0, off.stats().shadowRetireTokensEst(), "关闭时不退休任何内容");
        assertTrue(MessageProtocolValidator.check(off.messages()).ok());
    }

    @Test
    @DisplayName("普通短任务不触发退休，历史原样留在上下文里，不需要额外搜索")
    void ordinaryTaskNeedsNoRetirement() {
        List<Message> actual = shortConversation();
        ContextSourceLedger ledger = new ContextSourceLedger(CONV);
        ledger.absorb(actual, 0, null);

        // 软预算远大于历史估算：不该退休
        ContextProjectionPolicy.Result on = ContextProjectionPolicy.project(
                ledger, actual, null, 0, 0, 1_000_000, 0, false, true);

        assertEquals(0, on.stats().shadowRetireTokensEst(), "预算充足时不得退休");
        assertEquals(actual, on.messages(), "没有可退休内容时 Prompt 不变");
        assertNull(on.error());
    }

    // ===================== 6：退休不丢用户要求与工具协议 =====================

    @Test
    @DisplayName("退休后用户要求与工具协议都还在，退休说明给的是可直接读取的 locator")
    void retirementKeepsProtocolAndLeavesReadableLocator() {
        String oldReply = "很久以前的旧回复" + "正文".repeat(400);
        List<MessageRecord> rows = List.of(
                row(9001L, "user", "任务要求：只允许改 report.md"),
                row(9002L, "assistant", oldReply),
                row(9003L, "user", "纠正：用中文，禁止英文"),
                row(9004L, "user", "授权：允许修改配置"),
                row(9005L, "user", "最近问题一"),
                row(9006L, "assistant", "最近回复一"),
                row(9007L, "user", "最近问题二"),
                row(9008L, "assistant", "最近回复二"),
                row(9009L, "user", "当前问题"));

        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(new UserMessage("任务要求：只允许改 report.md"));
        actual.add(assistantCall("c1", "readFile", "{\"path\":\"a.txt\"}"));
        actual.add(toolResult("c1", "readFile", "工具正文不可恢复，必须留下"));
        actual.add(new AssistantMessage(oldReply));
        actual.add(new UserMessage("纠正：用中文，禁止英文"));
        actual.add(new UserMessage("授权：允许修改配置"));
        actual.add(new UserMessage("最近问题一"));
        actual.add(new AssistantMessage("最近回复一"));
        actual.add(new UserMessage("最近问题二"));
        actual.add(new AssistantMessage("最近回复二"));
        actual.add(new UserMessage("当前问题"));

        ContextSourceLedger ledger = new ContextSourceLedger(CONV);
        ledger.addHistory(rows, "当前问题");
        ledger.absorb(actual, 0, null);
        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 9, 0, 1, 0, false, true);

        List<String> texts = texts(projected.messages());
        assertFalse(texts.stream().anyMatch(t -> t.contains("很久以前的旧回复")), "旧助手回复应退出");
        assertTrue(texts.contains("任务要求：只允许改 report.md"), "用户任务要求不丢");
        assertTrue(texts.contains("纠正：用中文，禁止英文"), "用户纠正不丢");
        assertTrue(texts.contains("授权：允许修改配置"), "用户授权不丢");
        assertTrue(toolBodies(projected.messages()).contains("工具正文不可恢复"), "不可恢复的工具正文留下");
        assertTrue(MessageProtocolValidator.check(projected.messages()).ok(), "投影后工具协议完整");

        String note = texts.stream().filter(t -> t.contains("旧回复已移出上下文")).findFirst()
                .orElseThrow(() -> new AssertionError("退休说明缺失"));
        assertTrue(Pattern.compile("locator=message\\|\\d+").matcher(note).find(),
                "退休说明必须给出可直接读取的 locator，实际：" + note);
        assertFalse(note.contains("搜索 message"), "不能再让模型把 id 当关键词搜索");
    }

    @Test
    @DisplayName("退休说明里的 locator 能直接读回数据库原文（端到端闭环）")
    void retiredLocatorReadsBackFromDatabase() throws Exception {
        String longBody = "退休前的长回复" + "细节".repeat(1500) + "尾部标记END";
        exec("DELETE FROM conversation_message");
        insertMessage(USER, CONV, "assistant", longBody, "2026-11-10 07:00:00");
        long id = rowLong("SELECT id FROM conversation_message");

        List<MessageRecord> rows = List.of(
                row(id, "assistant", longBody),
                row(9101L, "user", "后续问题0"),
                row(9102L, "user", "后续问题1"),
                row(9103L, "user", "后续问题2"),
                row(9104L, "user", "当前问题"));

        List<Message> actual = new ArrayList<>();
        actual.add(new SystemMessage("系统"));
        actual.add(new AssistantMessage(longBody));
        actual.add(new UserMessage("后续问题0"));
        actual.add(new UserMessage("后续问题1"));
        actual.add(new UserMessage("后续问题2"));
        actual.add(new UserMessage("当前问题"));

        ContextSourceLedger ledger = new ContextSourceLedger(CONV);
        ledger.addHistory(rows, "当前问题");
        ledger.absorb(actual, 0, null);
        ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                ledger, actual, null, 9, 0, 1, 0, false, true);

        String note = texts(projected.messages()).stream()
                .filter(t -> t.contains("旧回复已移出上下文")).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "没有发生退休，测试前提不成立：" + texts(projected.messages())));
        Matcher m = Pattern.compile("locator=message\\|(\\d+)").matcher(note);
        assertTrue(m.find(), note);
        assertEquals(Long.toString(id), m.group(1), "退休说明里的 id 就是数据库里的真实 id");

        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);
            StringBuilder restored = new StringBuilder();
            Integer offset = 0;
            while (offset != null) {
                JsonNode page = JSON.readTree(tool.searchContext(
                        "read", null, null, null, "message|" + m.group(1), offset, null, null));
                restored.append(page.get("content").asText());
                offset = page.get("hasMore").asBoolean() ? page.get("nextOffset").asInt() : null;
            }
            assertEquals(longBody, restored.toString(), "从退休说明的 locator 读回的必须与原文逐字符一致");
            assertTrue(restored.toString().endsWith("尾部标记END"));
        } finally {
            ConversationContext.clear();
        }
    }

    // ===================== E4B：多关键词统一分页 =====================

    @Test
    @DisplayName("统一 SQL 不把用户输入拼进语句")
    void unifiedSqlDoesNotInterpolateUserText() {
        Map<String, Object> params = new HashMap<>();
        params.put("needles", List.of("a' OR '1'='1", "蓝"));
        params.put("patterns", List.of("%a%", "%蓝%"));
        String desc = ConversationSqlProvider.searchMessagesAnyDesc(params);
        String asc = ConversationSqlProvider.searchMessagesAnyAsc(params);
        assertFalse(desc.contains("OR '1'"), desc);
        assertFalse(desc.contains("${"), desc);
        assertFalse(asc.contains("${"), asc);
        assertTrue(desc.contains("ORDER BY id DESC"), desc);
        assertTrue(asc.contains("ORDER BY id ASC"), asc);
        assertTrue(desc.contains("content LIKE #{patterns[0]} ESCAPE '\\'"), desc);
        assertTrue(desc.contains("OR content LIKE #{patterns[1]}"), desc);
        assertTrue(desc.contains("#{needles[0]}"), desc);
        assertTrue(desc.contains("user_id = #{userId}"), desc);
        assertTrue(desc.contains("conversation_id = #{conversationId}"), desc);
    }

    @Test
    @DisplayName("刚好满一页时 hasMore 为 false；多一条才为 true")
    void exactPageLengthDoesNotClaimMore() throws Exception {
        exec("DELETE FROM conversation_message");
        for (int i = 1; i <= 5; i++) {
            insertMessage(USER, CONV, "user", "整页标记-" + i, stamp(i));
        }
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);
            JsonNode exact = searchQuery(tool, "整页标记", 5, 0, null);
            assertEquals(5, ids(exact).size());
            assertFalse(exact.get("hasMore").asBoolean(), "刚好 5 条不能报还有下一页");
            assertFalse(exact.has("nextCursor"));
        } finally {
            ConversationContext.clear();
        }
        insertMessage(USER, CONV, "user", "整页标记-6", stamp(6));
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);
            JsonNode first = searchQuery(tool, "整页标记", 5, 0, null);
            assertEquals(5, ids(first).size());
            assertTrue(first.get("hasMore").asBoolean());
            JsonNode second = searchQuery(tool, "整页标记", 5, first.get("nextCursor").asInt(), null);
            assertEquals(1, ids(second).size());
            assertFalse(second.get("hasMore").asBoolean());
            assertFalse(ids(first).contains(ids(second).get(0)), "两页不能重复");
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("多关键词统一分页不重不漏，升降序互为逆序，中间记录也能读回")
    void multiKeywordPagesDoNotRepeatOrSkip() throws Exception {
        exec("DELETE FROM conversation_message");
        for (int i = 0; i < 12; i++) insertMessage(USER, CONV, "user", "仅红-" + i, stamp(i));
        for (int i = 0; i < 12; i++) insertMessage(USER, CONV, "assistant", "仅蓝-" + i, stamp(20 + i));
        insertMessage(USER, CONV, "user", "红蓝都有-一次", stamp(40));
        insertMessage(USER, CONV, "user", "无关内容", stamp(41));
        insertMessage(OTHER_USER, CONV, "user", "仅红-别人的", stamp(42));
        insertMessage(USER, OTHER_CONV, "user", "仅蓝-别的会话", stamp(43));
        int own = rowInt("SELECT COUNT(*) FROM conversation_message WHERE user_id=" + USER
                + " AND conversation_id='" + CONV + "' AND (content LIKE '%红%' OR content LIKE '%蓝%')");
        assertEquals(25, own, "12 红 + 12 蓝 + 1 条两者都有");

        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);
            List<Long> desc = pageAll(tool, "红 蓝", 5, "desc");
            List<Long> asc = pageAll(tool, "红 蓝", 5, "asc");
            assertEquals(own, desc.size(), "翻完应正好是本会话命中，不多不少");
            assertEquals(new ArrayList<>(new LinkedHashSet<>(desc)), desc, "跨页不能重复");
            assertEquals(desc, reverse(asc), "升序必须是降序的严格逆序");
            assertFalse(desc.stream().anyMatch(id -> "无关内容".equals(contentOf(id))), "无关消息不能命中");
            assertTrue(desc.contains(rowLong(
                    "SELECT id FROM conversation_message WHERE content='红蓝都有-一次'")));
            assertEquals(1, desc.stream().filter(id -> "红蓝都有-一次".equals(contentOf(id))).count(),
                    "同时命中两个关键词的消息只能出现一次");

            long newest = desc.get(0);
            long oldest = desc.get(desc.size() - 1);
            long middle = desc.get(desc.size() / 2);
            assertEquals(rowLong("SELECT MAX(id) FROM conversation_message WHERE user_id=" + USER
                    + " AND conversation_id='" + CONV + "' AND content!='无关内容'"), newest);
            assertEquals(rowLong("SELECT MIN(id) FROM conversation_message WHERE user_id=" + USER
                    + " AND conversation_id='" + CONV + "' AND content!='无关内容'"), oldest);
            JsonNode first = searchQuery(tool, "红 蓝", 5, 0, "desc");
            assertFalse(ids(first).contains(middle), "中间记录不在第一页，必须靠翻页");
            assertTrue(desc.contains(middle));
            String body = contentOf(middle);
            JsonNode read = JSON.readTree(tool.searchContext(
                    "read", null, null, null, "message|" + middle, 0, null, null));
            assertEquals(body, read.get("content").asText(), "中间记录按 locator 读回必须与库原文一致");
            assertFalse(read.get("hasMore").asBoolean());
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("同一关键词超过 200 条时仍能翻到最早一条，cursor 不被截断")
    void cursorPast200StillReachesOldest() throws Exception {
        exec("DELETE FROM conversation_message");
        int n = 210;
        for (int i = 1; i <= n; i++) {
            insertMessage(USER, CONV, "user", "批量命中-" + i, stamp(i));
        }
        long oldest = rowLong("SELECT MIN(id) FROM conversation_message WHERE content LIKE '%批量命中%'");
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);
            JsonNode at200 = searchQuery(tool, "批量命中", 5, 200, null);
            assertFalse(ids(at200).isEmpty(), "cursor=200 之后还有更早的命中，不能被截掉");
            assertTrue(at200.get("hasMore").asBoolean());
            List<Long> all = pageAll(tool, "批量命中", 5, "desc");
            assertEquals(n, all.size());
            assertEquals(new ArrayList<>(new LinkedHashSet<>(all)), all);
            assertEquals(oldest, all.get(all.size() - 1).longValue(), "最早一条必须能翻到");
            for (int i = 1; i < all.size(); i++) {
                assertTrue(all.get(i - 1) > all.get(i), "降序必须始终严格递减");
            }
        } finally {
            ConversationContext.clear();
        }
    }

    @Test
    @DisplayName("中文关键词和中文逗号拆词都能命中，且同一条不重复")
    void chineseKeywordsMatchWithoutDuplication() throws Exception {
        try (SqlSession s = sessionFactory.openSession(true)) {
            ContextAssetTool tool = tool(s.getMapper(ConversationMapper.class));
            ConversationContext.set(USER, CONV);
            JsonNode early = searchQuery(tool, "早期约定", 5, 0, "asc");
            assertEquals(oldestId, ids(early).get(0));
            assertTrue(early.get("items").get(0).get("snippet").asText().contains("早期约定"),
                    early.toString());

            List<Long> paged = pageAll(tool, "审查，补充", 5, "desc");
            assertEquals(HISTORY_SIZE, paged.size(), "中文逗号拆开的两个词，并集仍是这 24 条");
            assertEquals(new LinkedHashSet<>(seededIds), new LinkedHashSet<>(paged));
            assertEquals(1, paged.stream().filter(id -> id == oldestId).count(),
                    "同时含两个中文词的最早消息只能出现一次");
        } finally {
            ConversationContext.clear();
        }
    }

    // ===================== helpers =====================

    private static String oldestContent() {
        // 超过 READ_CHARS，强制多页读取；唯一事实放在里面，且只在这里出现
        StringBuilder sb = new StringBuilder();
        sb.append("早期约定（第一次").append(KEYWORD).append("）：").append(FACT).append("。");
        while (sb.length() < 5200) sb.append("补充说明，无特殊内容。");
        return sb.toString();
    }

    private static List<Message> shortConversation() {
        List<Message> out = new ArrayList<>();
        out.add(new SystemMessage("系统"));
        out.add(new UserMessage("帮我把标题改成中文"));
        out.add(new AssistantMessage("好的，已改"));
        out.add(new UserMessage("当前问题"));
        return out;
    }

    private static MessageRecord row(Long id, String role, String content) {
        MessageRecord r = new MessageRecord(role, content);
        r.setId(id);
        r.setTimestamp(new Date(id));
        return r;
    }

    private static ContextAssetTool tool(ConversationMapper mapper) {
        UserMemoryService memories = mock(UserMemoryService.class);
        TaskPlanService plans = mock(TaskPlanService.class);
        when(memories.listByUser(USER)).thenReturn(List.of());
        when(memories.listByUser(OTHER_USER)).thenReturn(List.of());
        when(plans.get(USER, CONV)).thenReturn(null);
        when(plans.get(USER, OTHER_CONV)).thenReturn(null);
        when(plans.get(OTHER_USER, CONV)).thenReturn(null);
        return new ContextAssetTool(mapper, memories, plans);
    }

    private static JsonNode search(ContextAssetTool tool, int limit, int cursor, String order)
            throws Exception {
        return searchQuery(tool, KEYWORD, limit, cursor, order);
    }

    private static JsonNode searchQuery(ContextAssetTool tool, String query, int limit,
                                         int cursor, String order) throws Exception {
        return JSON.readTree(tool.searchContext(
                "search", "message", query, limit, null, null, cursor, order));
    }

    /** 按 cursor 连续翻完。每一页内部不重复，hasMore 与 nextCursor 必须能衔接。 */
    private static List<Long> pageAll(ContextAssetTool tool, String query, int limit, String order)
            throws Exception {
        List<Long> paged = new ArrayList<>();
        int cursor = 0;
        int guard = 0;
        while (true) {
            JsonNode page = searchQuery(tool, query, limit, cursor, order);
            List<Long> pageIds = ids(page);
            assertEquals(new ArrayList<>(new LinkedHashSet<>(pageIds)), pageIds, "同一页不能重复");
            assertTrue(pageIds.size() <= limit, "一页不能超过 limit");
            paged.addAll(pageIds);
            if (!page.get("hasMore").asBoolean()) {
                assertFalse(page.has("nextCursor"), "没有下一页时不能给 nextCursor：" + page);
                break;
            }
            assertEquals(cursor + pageIds.size(), page.get("nextCursor").asInt(),
                    "nextCursor 必须等于已跳过的条数加本页条数");
            cursor = page.get("nextCursor").asInt();
            if (++guard > 500) fail("翻页没有终止");
        }
        return paged;
    }

    private static List<Long> reverse(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        Collections.reverse(copy);
        return copy;
    }

    private static String contentOf(long id) {
        return rowStr("SELECT content FROM conversation_message WHERE id=" + id);
    }

    private static String stamp(int i) {
        return String.format("2026-11-10 %02d:%02d:%02d", i / 3600, (i / 60) % 60, i % 60);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        JsonNode items = page.get("items");
        assertNotNull(items, "items 缺失：" + page);
        for (JsonNode item : items) out.add(item.get("id").asLong());
        return out;
    }

    private static List<String> texts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message m : messages) out.add(m.getText() == null ? "" : m.getText());
        return out;
    }

    /** 工具结果的正文不在 getText() 里，单独拼出来断言。 */
    private static String toolBodies(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : tool.getResponses()) {
                    out.append(r.responseData());
                }
            }
        }
        return out.toString();
    }

    private static AssistantMessage assistantCall(String id, String name, String args) {
        return new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(id, "function", name, args)));
    }

    private static ToolResponseMessage toolResult(String id, String name, String body) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, name, body)));
    }

    private static String pad(int i) {
        return i < 10 ? "0" + i : Integer.toString(i);
    }

    private static int countFactOccurrences() {
        return rowInt("SELECT COUNT(*) FROM conversation_message WHERE content LIKE '%" + FACT + "%'");
    }

    private static void insertMessage(long userId, String conversationId, String role,
                                      String content, String createdAt) {
        exec("INSERT INTO conversation_message(user_id, conversation_id, role, content, created_at) " +
                "VALUES(?,?,?,?,?)", userId, conversationId, role, content, createdAt);
    }

    private static List<Long> idsOf(long userId, String conversationId) {
        List<Long> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM conversation_message WHERE user_id="
                     + userId + " AND conversation_id='" + conversationId + "' ORDER BY id")) {
            while (rs.next()) out.add(rs.getLong(1));
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败", e);
        }
        return out;
    }

    private static void exec(String sql, Object... args) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    private static String rowStr(String sql) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    private static int rowInt(String sql) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    private static long rowLong(String sql) {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("没有结果: " + sql);
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }
}
