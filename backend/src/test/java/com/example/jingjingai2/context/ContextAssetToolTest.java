package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.usermemory.UserMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 统一上下文检索工具。Mapper 和两个 Service 都是假的，不连数据库、不调模型。
 */
class ContextAssetToolTest {

    private static final Long USER = 7L;
    private static final Long OTHER = 8L;
    private static final String CONV = "conv-a";
    private static final String OTHER_CONV = "conv-b";

    private final ObjectMapper json = new ObjectMapper();
    private ConversationMapper mapper;
    private UserMemoryService memories;
    private TaskPlanService plans;
    private ContextAssetTool tool;

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationMapper.class);
        memories = mock(UserMemoryService.class);
        plans = mock(TaskPlanService.class);
        when(memories.listByUser(any())).thenReturn(List.of());
        tool = new ContextAssetTool(mapper, memories, plans);
        ConversationContext.set(USER, CONV);
    }

    @AfterEach
    void tearDown() {
        ConversationContext.clear();
    }

    @Test
    @DisplayName("关键词能搜到窗口外的旧消息，并给出稳定的 messageId")
    void searchFindsMessageBeyondRecentWindow() throws Exception {
        when(mapper.searchMessagesAnyDesc(eq(USER), eq(CONV), eq(List.of("%预算%")), eq(List.of("预算")),
                eq(ContextAssetTool.SNIPPET_BEFORE), eq(ContextAssetTool.SNIPPET_CHARS),
                eq(ContextAssetTool.DEFAULT_LIMIT + 1), eq(0)))
                .thenReturn(List.of(hit(101L, "user", "很早之前讨论过预算上限", date(1))));

        JsonNode out = json.readTree(tool.searchContext("search", "message", "预算", null, null, null));

        assertEquals(1, out.get("count").asInt());
        JsonNode item = out.get("items").get(0);
        assertEquals("message", item.get("type").asText());
        assertEquals(101L, item.get("id").asLong());
        assertEquals("user", item.get("role").asText());
        assertEquals("message|101", item.get("locator").asText());
        assertTrue(item.get("snippet").asText().contains("预算上限"), "片段直接带命中正文");
        verify(mapper, never()).getHistory(any(), any(), anyInt());
    }

    @Test
    @DisplayName("按搜索给出的 locator 读到正文；别的用户、别的会话读不到")
    void readMessageRechecksOwner() {
        MessageRecord row = new MessageRecord("user", "预算上限是 60 轮");
        when(mapper.selectMessage(USER, CONV, 101L)).thenReturn(row);
        when(mapper.selectMessage(OTHER, CONV, 101L)).thenReturn(null);
        when(mapper.selectMessage(USER, OTHER_CONV, 101L)).thenReturn(null);

        String ok = tool.searchContext("read", null, null, null, "message|101", null);
        assertTrue(ok.contains("预算上限是 60 轮"), ok);

        ConversationContext.set(OTHER, CONV);
        String otherUser = tool.searchContext("read", null, null, null, "message|101", null);
        assertTrue(otherUser.contains("没有这条消息"), otherUser);

        ConversationContext.set(USER, OTHER_CONV);
        String otherConv = tool.searchContext("read", null, null, null, "message|101", null);
        assertTrue(otherConv.contains("没有这条消息"), otherConv);

        verify(mapper).selectMessage(OTHER, CONV, 101L);
        verify(mapper).selectMessage(USER, OTHER_CONV, 101L);
    }

    @Test
    @DisplayName("空关键词不扫消息；通配符按字面转义；无结果和 limit 都说清楚")
    void queryEdges() throws Exception {
        String noKeyword = tool.searchContext("search", "message", "   ", null, null, null);
        assertTrue(noKeyword.contains("message 需要关键词"), noKeyword);
        verify(mapper, never()).searchMessages(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(mapper, never()).searchMessagesAnyDesc(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(mapper, never()).searchMessagesAnyAsc(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt());

        assertEquals("%100\\%\\_off%", ContextAssetTool.likePattern("100%_off"));
        assertEquals(List.of("预算", "中文"), ContextAssetTool.keywords("预算，中文"));

        when(mapper.searchMessagesAnyDesc(eq(USER), eq(CONV), eq(List.of("%不存在的词%")), eq(List.of("不存在的词")),
                anyInt(), anyInt(), eq(ContextAssetTool.DEFAULT_LIMIT + 1), eq(0)))
                .thenReturn(List.of());
        JsonNode empty = json.readTree(tool.searchContext("search", "message", "不存在的词", null, null, null));
        assertEquals(0, empty.get("count").asInt());
        assertEquals("没有匹配结果", empty.get("empty").asText());
        assertFalse(empty.get("hasMore").asBoolean());

        List<MessageHit> exact = new ArrayList<>();
        for (int i = 0; i < ContextAssetTool.MAX_LIMIT; i++) exact.add(hit(i + 1L, "user", "预算 " + i, date(i)));
        when(mapper.searchMessagesAnyDesc(eq(USER), eq(CONV), eq(List.of("%预算%")), eq(List.of("预算")),
                eq(ContextAssetTool.SNIPPET_BEFORE), eq(ContextAssetTool.SNIPPET_CHARS),
                eq(ContextAssetTool.MAX_LIMIT + 1), eq(0)))
                .thenReturn(exact);
        JsonNode capped = json.readTree(tool.searchContext("search", "message", "预算", 999, null, null));
        assertEquals(ContextAssetTool.MAX_LIMIT, capped.get("count").asInt());
        assertFalse(capped.get("hasMore").asBoolean(), "刚好满一页且没有更多时 hasMore 必须是 false");
        assertFalse(capped.get("truncated").asBoolean());

        List<MessageHit> extra = new ArrayList<>(exact);
        extra.add(hit(99L, "user", "预算 extra", date(99)));
        when(mapper.searchMessagesAnyDesc(eq(USER), eq(CONV), eq(List.of("%预算%")), eq(List.of("预算")),
                eq(ContextAssetTool.SNIPPET_BEFORE), eq(ContextAssetTool.SNIPPET_CHARS),
                eq(ContextAssetTool.MAX_LIMIT + 1), eq(0)))
                .thenReturn(extra);
        JsonNode more = json.readTree(tool.searchContext("search", "message", "预算", 999, null, null));
        assertEquals(ContextAssetTool.MAX_LIMIT, more.get("count").asInt());
        assertTrue(more.get("hasMore").asBoolean());
        assertEquals(ContextAssetTool.MAX_LIMIT, more.get("nextCursor").asInt());
        assertTrue(more.get("truncated").asBoolean());
        assertFalse(more.get("items").toString().contains("\"id\":99"), "多取的那一条不能出现在本页");
    }

    @Test
    @DisplayName("只搜当前用户的记忆，索引不含全文，读取走 getOne")
    void memorySearchAndReadStayWithCurrentUser() throws Exception {
        UserMemory mine = memory("preference", "reply_lang", "以后都用中文回答，不要切换英文");
        when(memories.listByUser(USER)).thenReturn(List.of(mine));
        when(memories.listByUser(OTHER)).thenReturn(List.of());
        when(memories.getOne(USER, "preference", "reply_lang")).thenReturn(mine);
        when(memories.getOne(OTHER, "preference", "reply_lang")).thenReturn(null);

        JsonNode found = json.readTree(tool.searchContext("search", "user_memory", "中文", null, null, null));
        assertEquals(1, found.get("count").asInt());
        assertEquals("user_memory|preference|reply_lang", found.get("items").get(0).get("locator").asText());
        assertTrue(found.get("items").get(0).get("snippet").asText().contains("以后都用中文回答"),
                "片段够用时正文就在搜索结果里");

        String full = tool.searchContext("read", null, null, null, "user_memory|preference|reply_lang", null);
        assertTrue(full.contains("以后都用中文回答"), full);

        ConversationContext.set(OTHER, CONV);
        JsonNode none = json.readTree(tool.searchContext("search", "user_memory", "中文", null, null, null));
        assertEquals(0, none.get("count").asInt());
        String denied = tool.searchContext("read", null, null, null, "user_memory|preference|reply_lang", null);
        assertTrue(denied.contains("没有这条记忆"), denied);
        verify(memories, never()).listByUser(isNull());
    }

    @Test
    @DisplayName("记忆搜索最多返回 limit 条，片段总量有上限")
    void memoryRespectsLimit() throws Exception {
        List<UserMemory> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(memory("profile", "k" + i, "内容" + i + " " + "x".repeat(500)));
        when(memories.listByUser(USER)).thenReturn(rows);

        JsonNode out = json.readTree(tool.searchContext("search", "user_memory", null, 5, null, null));

        assertTrue(out.get("count").asInt() <= 5);
        assertTrue(out.get("count").asInt() >= 1);
        assertTrue(out.get("truncated").asBoolean());
        int snippetChars = 0;
        for (JsonNode item : out.get("items")) snippetChars += item.get("snippet").asText().length();
        assertTrue(snippetChars <= ContextAssetTool.TOTAL_SNIPPET_CHARS, "全部片段不超过总上限");
    }

    @Test
    @DisplayName("能搜当前计划和步骤；DONE 与 actualResult 不被说成已核验证据")
    void planSearchKeepsReportedStatus() throws Exception {
        TaskPlan plan = new TaskPlan("p1");
        PlanStep step = new PlanStep("s1", "核对预算", "文档写明上限");
        step.setStatus(PlanStep.DONE);
        step.setActualResult("模型说已经核对完成");
        plan.getSteps().add(step);
        when(plans.get(USER, CONV)).thenReturn(plan);

        JsonNode out = json.readTree(tool.searchContext("search", "plan", "预算", null, null, null));
        assertTrue(out.get("count").asInt() >= 1, out.toString());

        String detail = tool.searchContext("read", null, null, null, "plan|s1", null);
        assertTrue(detail.contains("核对预算"), detail);
        assertTrue(detail.contains("不是系统核验过的证据"), detail);
        assertTrue(detail.contains("未经系统核验"), detail);
        assertFalse(detail.contains("系统已核验"), detail);
    }

    @Test
    @DisplayName("没有计划时明确说空，不编历史计划")
    void noPlanIsExplicit() throws Exception {
        when(plans.get(USER, CONV)).thenReturn(null);

        JsonNode out = json.readTree(tool.searchContext("search", "plan", null, null, null, null));
        assertEquals(0, out.get("count").asInt());
        assertEquals("没有可列出的资产", out.get("empty").asText());

        String read = tool.searchContext("read", null, null, null, "plan|overview", null);
        assertTrue(read.contains("当前会话没有计划"), read);
    }

    @Test
    @DisplayName("没有会话上下文、以及自编的 locator，都不能读到东西")
    void missingContextAndBadLocator() {
        ConversationContext.clear();
        String missing = tool.searchContext("search", null, "预算", null, null, null);
        assertTrue(missing.contains("不在会话上下文中"), missing);
        verifyNoInteractions(mapper);

        ConversationContext.set(USER, CONV);
        String bad = tool.searchContext("read", null, null, null, "message|101|extra", null);
        assertTrue(bad.contains("没有这条消息"), bad);
        verify(mapper).selectMessage(USER, CONV, 101L);
    }

    @Test
    @DisplayName("工具只有一个 Schema，参数里没有 userId 和 conversationId")
    void schemaExposesOneToolWithoutIdentityParams() throws Exception {
        Method[] tools = java.util.Arrays.stream(ContextAssetTool.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .toArray(Method[]::new);
        assertEquals(1, tools.length);
        assertEquals("searchContext", tools[0].getAnnotation(Tool.class).name());
        for (var p : tools[0].getParameters()) {
            String name = p.getName();
            assertNotEquals("userId", name);
            assertNotEquals("conversationId", name);
        }
        String description = tools[0].getAnnotation(Tool.class).description();
        assertTrue(description.contains("片段已经够回答时直接用"));
        assertTrue(description.contains("不是系统核验过的证据"));
        assertTrue(description.contains("nextOffset"), "工具说明应告诉模型如何继续读下一段");
        assertTrue(description.length() < 1100, "工具说明不应明显膨胀: " + description.length());
    }

    @Test
    @DisplayName("4000 字符之后的唯一事实能靠 nextOffset 分段取回")
    void longMessageIsReadInSegments() throws Exception {
        String fact = "唯一事实-深埋在四千字之后";
        String body = "a".repeat(4000) + fact + "b".repeat(100);
        when(mapper.selectMessage(USER, CONV, 101L)).thenReturn(new MessageRecord("user", body));

        JsonNode first = json.readTree(tool.searchContext("read", null, null, null, "message|101", null));
        assertEquals(4000, first.get("content").asText().length());
        assertEquals(body.length(), first.get("totalChars").asInt());
        assertEquals(0, first.get("start").asInt());
        assertEquals(4000, first.get("end").asInt());
        assertTrue(first.get("hasMore").asBoolean());
        assertFalse(first.get("content").asText().contains(fact), "第一段不应含后面的事实");

        JsonNode second = json.readTree(tool.searchContext(
                "read", null, null, null, "message|101", first.get("nextOffset").asInt()));
        assertTrue(second.get("content").asText().startsWith(fact), second.get("content").asText());
        assertEquals(4000, second.get("start").asInt());
        assertEquals(body.length(), second.get("end").asInt());
        assertFalse(second.get("hasMore").asBoolean());
        assertFalse(second.has("nextOffset"));
        assertTrue(second.get("content").asText().length() <= ContextAssetTool.READ_CHARS);

        verify(mapper, times(2)).selectMessage(USER, CONV, 101L);
    }

    @Test
    @DisplayName("同一段立刻重复读会被拦住，换页继续读不受影响")
    void repeatedSameRangeIsSuppressedButPagingWorks() {
        when(mapper.selectMessage(USER, CONV, 101L)).thenReturn(new MessageRecord("user", "n".repeat(9000)));

        assertFalse(tool.searchContext("read", null, null, null, "message|101", 0).contains("刚刚已经读过"));
        assertFalse(tool.searchContext("read", null, null, null, "message|101", 0).contains("刚刚已经读过"));
        String third = tool.searchContext("read", null, null, null, "message|101", 0);
        assertTrue(third.contains("刚刚已经读过"), third);

        String next = tool.searchContext("read", null, null, null, "message|101", 4000);
        assertTrue(next.contains("\"start\":4000"), next);
        verify(mapper, times(3)).selectMessage(USER, CONV, 101L);
    }

    @Test
    @DisplayName("不传 offset 的旧调用仍返回正文开头")
    void defaultReadStaysCompatible() throws Exception {
        when(mapper.selectMessage(USER, CONV, 101L)).thenReturn(new MessageRecord("user", "预算上限是 60 轮"));
        JsonNode out = json.readTree(tool.searchContext("read", null, null, null, "message|101", null));
        assertEquals("预算上限是 60 轮", out.get("content").asText());
        assertEquals(0, out.get("start").asInt());
        assertFalse(out.get("hasMore").asBoolean());
    }

    @Test
    @DisplayName("负数、超大和越界偏移都有确定结果，单段不超过上限")
    void offsetEdgesAreDeterministic() throws Exception {
        String body = "c".repeat(9000);
        when(mapper.selectMessage(USER, CONV, 101L)).thenReturn(new MessageRecord("user", body));

        JsonNode negative = json.readTree(tool.searchContext("read", null, null, null, "message|101", -5));
        assertEquals(0, negative.get("start").asInt());
        assertEquals(4000, negative.get("content").asText().length());

        JsonNode huge = json.readTree(tool.searchContext("read", null, null, null, "message|101", Integer.MAX_VALUE));
        assertEquals("", huge.get("content").asText());
        assertEquals(body.length(), huge.get("start").asInt());
        assertFalse(huge.get("hasMore").asBoolean());

        JsonNode past = json.readTree(tool.searchContext("read", null, null, null, "message|101", 9001));
        assertEquals("", past.get("content").asText());
        assertEquals(body.length(), past.get("start").asInt());
    }

    @Test
    @DisplayName("换了用户或会话后，同一个 locator 和 offset 也读不到")
    void offsetDoesNotBypassOwnership() {
        when(mapper.selectMessage(USER, CONV, 101L)).thenReturn(new MessageRecord("user", "x".repeat(5000)));
        when(mapper.selectMessage(OTHER, CONV, 101L)).thenReturn(null);
        when(mapper.selectMessage(USER, OTHER_CONV, 101L)).thenReturn(null);

        ConversationContext.set(OTHER, CONV);
        assertTrue(tool.searchContext("read", null, null, null, "message|101", 4000).contains("没有这条消息"));
        ConversationContext.set(USER, OTHER_CONV);
        assertTrue(tool.searchContext("read", null, null, null, "message|101", 4000).contains("没有这条消息"));
        verify(mapper).selectMessage(OTHER, CONV, 101L);
        verify(mapper).selectMessage(USER, OTHER_CONV, 101L);
    }

    private static MessageHit hit(long id, String role, String snippet, Date timestamp) {
        MessageHit hit = new MessageHit();
        hit.setId(id);
        hit.setRole(role);
        hit.setSnippet(snippet);
        hit.setTimestamp(timestamp);
        return hit;
    }

    private static UserMemory memory(String category, String key, String content) {
        UserMemory row = new UserMemory();
        row.setCategory(category);
        row.setMemKey(key);
        row.setContent(content);
        row.setUpdatedAt(date(2));
        return row;
    }

    private static Date date(int day) {
        return new Date(1_700_000_000_000L + day * 86_400_000L);
    }
}
