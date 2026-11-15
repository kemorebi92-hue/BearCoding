package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.context.ContextCompactorBudgetTest;
import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mapper.UserMemoryMapper;
import com.example.jingjingai2.service.ConversationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户记忆读路径：注入函数 fail-closed、顺序、与 Compactor 类型分界、不落 conversation_message。
 */
class UserMemoryInjectorTest {

    private static final Long USER = 7L;
    private static final String CONV = "conv-inject-1";

    private FakeUserMemoryMapper mapper;
    private UserMemoryProperties props;
    private UserMemoryService service;
    private UserMemoryInjector injector;
    private RecordingConversationMapper conversationMapper;
    private ConversationHistoryService historyService;

    @BeforeEach
    void setUp() {
        mapper = new FakeUserMemoryMapper();
        props = new UserMemoryProperties();
        props.setEnabled(true);
        service = new UserMemoryService(mapper, props);
        injector = new UserMemoryInjector(service, props);
        conversationMapper = new RecordingConversationMapper();
        historyService = new ConversationHistoryService(conversationMapper);
    }

    private void seedFourCategoriesOutOfOrder() {
        assertTrue(service.upsert(USER, "project_context", "stack", "常做 Java/Spring", CONV).isAccepted());
        assertTrue(service.upsert(USER, "profile", "name", "jing", CONV).isAccepted());
        assertTrue(service.upsert(USER, "preference", "reply_lang", "中文", CONV).isAccepted());
        assertTrue(service.upsert(USER, "constraint", "build", "本机没有 Maven，用 test.sh", CONV).isAccepted());
        assertTrue(service.upsert(USER, "preference", "verbosity", "短", CONV).isAccepted());
    }

    private static List<Message> baseMessages() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("你是小熊。"));
        msgs.add(new UserMessage("你好"));
        return msgs;
    }

    private static List<SystemMessage> memoryMessages(List<Message> msgs) {
        List<SystemMessage> out = new ArrayList<>();
        for (Message m : msgs) {
            if (m instanceof SystemMessage sm
                    && sm.getText() != null
                    && sm.getText().startsWith(UserMemoryInjector.PREFIX)) {
                out.add(sm);
            }
        }
        return out;
    }

    @Test
    @DisplayName("预置数据后恰好新增一条【用户记忆】，顺序 constraint→preference→profile→project_context")
    void injectsOneMessageInCategoryOrder() {
        seedFourCategoriesOutOfOrder();
        List<Message> msgs = baseMessages();
        int before = msgs.size();

        injector.injectUserMemory(msgs, USER);

        assertEquals(before + 1, msgs.size());
        List<SystemMessage> mem = memoryMessages(msgs);
        assertEquals(1, mem.size(), "只能新增一条用户记忆 SystemMessage");
        String text = mem.get(0).getText();
        assertTrue(text.startsWith(UserMemoryInjector.PREFIX), text);
        assertTrue(text.contains("跨会话保留的事实"), text);
        assertTrue(text.contains("作参考"), text);
        assertTrue(text.contains("过时的不要沿用"), text);
        assertTrue(text.contains("与本轮冲突以本轮为准"), text);

        int c = text.indexOf("constraint:");
        int p = text.indexOf("preference:");
        int pr = text.indexOf("profile:");
        int pc = text.indexOf("project_context:");
        assertTrue(c >= 0 && p > c && pr > p && pc > pr,
                "类目顺序错了：\n" + text);
        assertTrue(c < text.indexOf("本机没有 Maven"), text);
        assertTrue(p < text.indexOf("reply_lang"), text);
        assertTrue(text.contains("verbosity"), text);
        assertTrue(pr < text.indexOf("jing"), text);
        assertTrue(pc < text.indexOf("常做 Java/Spring"), text);

        assertTrue(msgs.get(0) instanceof SystemMessage);
        assertEquals("你是小熊。", ((SystemMessage) msgs.get(0)).getText());
        assertSame(mem.get(0), msgs.get(1), "应插在 systemPrompt 之后、历史之前");
    }

    @Test
    @DisplayName("开关关闭：不插入、不抛、不读库")
    void disabledDoesNotInsertOrThrow() {
        seedFourCategoriesOutOfOrder();
        props.setEnabled(false);
        UserMemoryInjector throwing = new UserMemoryInjector(
                new UserMemoryService(new ThrowingUserMemoryMapper(), props), props);
        List<Message> msgs = baseMessages();
        int before = msgs.size();

        assertDoesNotThrow(() -> throwing.injectUserMemory(msgs, USER));
        assertEquals(before, msgs.size());
        assertTrue(memoryMessages(msgs).isEmpty());
    }

    @Test
    @DisplayName("该用户没有记忆：不插入、不抛")
    void emptyDoesNotInsertOrThrow() {
        List<Message> msgs = baseMessages();
        int before = msgs.size();
        assertDoesNotThrow(() -> injector.injectUserMemory(msgs, USER));
        assertEquals(before, msgs.size());
        assertTrue(memoryMessages(msgs).isEmpty());

        List<Message> other = baseMessages();
        assertDoesNotThrow(() -> injector.injectUserMemory(other, 99L));
        assertTrue(memoryMessages(other).isEmpty());
    }

    @Test
    @DisplayName("读库异常：不插入、不抛")
    void dbFailureDoesNotInsertOrThrow() {
        UserMemoryInjector throwing = new UserMemoryInjector(
                new UserMemoryService(new ThrowingUserMemoryMapper(), props), props);
        List<Message> msgs = baseMessages();
        int before = msgs.size();
        assertDoesNotThrow(() -> throwing.injectUserMemory(msgs, USER));
        assertEquals(before, msgs.size());
        assertTrue(memoryMessages(msgs).isEmpty());
    }

    @Test
    @DisplayName("与 Compactor 共存：【用户记忆】和 [历史摘要] 都在；再次 compact 吃不到用户记忆")
    void coexistsWithCompactorAndSecondCompactCannotSwallowMemory() {
        seedFourCategoriesOutOfOrder();

        List<MessageRecord> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new MessageRecord("user", "这是一条足够长的历史用户消息用来撑爆上下文预算" + i));
            history.add(new MessageRecord("assistant", "这是对应的助手回复内容也要够长才会被压缩" + i));
        }
        // 摘要模型不可用时保留原始历史，不能把失败截断写成 [历史摘要]
        ContextCompactor compact = new ContextCompactor(
                ContextCompactorBudgetTest.successfulSummaryModel(), 40);

        List<Message> msgs = compact.buildMessages(history, "本轮问题");
        assertTrue(hasPrefix(msgs, "[历史摘要]"), "预算够小，第一次就该压出 [历史摘要]");
        msgs.add(0, new SystemMessage("你是小熊。"));
        injector.injectUserMemory(msgs, USER);

        assertEquals(1, memoryMessages(msgs).size());
        assertTrue(hasPrefix(msgs, "[历史摘要]"));
        assertTrue(hasPrefix(msgs, UserMemoryInjector.PREFIX));
        assertTrue(msgs.get(0) instanceof SystemMessage);
        assertEquals("你是小熊。", ((SystemMessage) msgs.get(0)).getText());
        assertTrue(((SystemMessage) msgs.get(1)).getText().startsWith(UserMemoryInjector.PREFIX));

        // 再次 compact：输入仍是 MessageRecord history，用户记忆根本不在这个类型里
        List<Message> again = compact.buildMessages(history, "下一轮问题");
        assertTrue(hasPrefix(again, "[历史摘要]"), "历史还超预算，摘要还在");
        assertTrue(memoryMessages(again).isEmpty(),
                "用户记忆不是 MessageRecord，第二次 compact 不可能把它吞进摘要");
        for (Message m : again) {
            if (m instanceof SystemMessage sm && sm.getText() != null) {
                assertFalse(sm.getText().contains("本机没有 Maven"),
                        "摘要里不该出现只存在于内存注入的用户记忆: " + sm.getText());
            }
        }

        again.add(0, new SystemMessage("你是小熊。"));
        injector.injectUserMemory(again, USER);
        assertEquals(1, memoryMessages(again).size());
        assertTrue(hasPrefix(again, "[历史摘要]"));
    }

    @Test
    @DisplayName("注入不写 conversation_message：ConversationMapper.insert 一次都不会被叫")
    void injectNeverTouchesConversationMapper() {
        seedFourCategoriesOutOfOrder();
        List<Message> msgs = baseMessages();
        injector.injectUserMemory(msgs, USER);
        assertEquals(1, memoryMessages(msgs).size());
        assertEquals(0, conversationMapper.inserts.size(),
                "Injector 构造里没有 ConversationMapper，inject 更不该去 insert");
        assertTrue(historyService.getHistory(USER, CONV).isEmpty());

        // 生产路径只落 user/assistant；把整份内存 messages 误写入才会把【用户记忆】带进表
        for (Message m : msgs) {
            if (m instanceof SystemMessage sm
                    && sm.getText() != null
                    && sm.getText().startsWith(UserMemoryInjector.PREFIX)) {
                // 不写。断言：本测试没有把这条交给 historyService.addMessage
            }
        }
        assertEquals(0, conversationMapper.inserts.size());
    }

    @Test
    @DisplayName("超 maxInjectChars 时丢掉后面的类目，不截半条")
    void dropsLaterCategoriesWhenOverInjectCap() {
        seedFourCategoriesOutOfOrder();
        // header + constraint 那条能放下，preference 开始放不下
        String constraintBlock = UserMemoryInjector.HEADER
                + "\nconstraint:\n- build: 本机没有 Maven，用 test.sh\n";
        props.setMaxInjectChars(constraintBlock.length() + 10);

        String rendered = UserMemoryInjector.render(service.listGrouped(USER), props.getMaxInjectChars());
        assertNotNull(rendered);
        assertTrue(rendered.contains("constraint:"));
        assertTrue(rendered.contains("本机没有 Maven"));
        assertFalse(rendered.contains("preference:"), rendered);
        assertFalse(rendered.contains("profile:"), rendered);
        assertFalse(rendered.contains("project_context:"), rendered);
    }

    @Test
    @DisplayName("条目观测：正式文案不变，日志行不含正文，预算跳过有原因")
    void itemObservationsDoNotLeakBodyAndMatchRender() {
        seedFourCategoriesOutOfOrder();
        UserMemoryInjector.RenderResult full =
                UserMemoryInjector.renderObserved(service.listGrouped(USER), 6000);
        assertEquals(UserMemoryInjector.render(service.listGrouped(USER), 6000), full.text);
        assertEquals(5, full.observations.size());
        for (com.example.jingjingai2.memory.MemoryItemObservation o : full.observations) {
            assertTrue(o.injected());
            assertFalse(o.toLogLine().contains("本机没有 Maven"));
            assertFalse(o.toLogLine().contains("jing"));
        }
        assertTrue(full.observations.get(0).protectedItem());

        String constraintBlock = UserMemoryInjector.HEADER
                + "\nconstraint:\n- build: 本机没有 Maven，用 test.sh\n";
        UserMemoryInjector.RenderResult capped =
                UserMemoryInjector.renderObserved(service.listGrouped(USER), constraintBlock.length() + 10);
        assertTrue(capped.observations.stream().anyMatch(
                o -> !o.injected() && "over-budget".equals(o.skipReason())));
    }

    private static boolean hasPrefix(List<Message> msgs, String prefix) {
        for (Message m : msgs) {
            if (m instanceof SystemMessage sm
                    && sm.getText() != null
                    && sm.getText().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** listByUser 一律抛，用来钉 fail-closed。 */
    private static final class ThrowingUserMemoryMapper implements UserMemoryMapper {
        @Override
        public int upsert(Long userId, String category, String memKey, String content,
                          String source, String conversationId) {
            throw new RuntimeException("db down");
        }

        @Override
        public List<UserMemory> listByUser(Long userId) {
            throw new RuntimeException("db down");
        }

        @Override
        public UserMemory getOne(Long userId, String category, String memKey) {
            throw new RuntimeException("db down");
        }

        @Override
        public int deleteOne(Long userId, String category, String memKey) {
            throw new RuntimeException("db down");
        }

        @Override
        public int deleteByCategory(Long userId, String category) {
            throw new RuntimeException("db down");
        }

        @Override
        public int countByUser(Long userId) {
            throw new RuntimeException("db down");
        }
    }

    /** 只记 insert 调用。Injector 不该碰它。 */
    private static final class RecordingConversationMapper implements ConversationMapper {
        final List<String> inserts = new ArrayList<>();

        @Override
        public void insert(Long userId, String conversationId, String role, String content) {
            inserts.add(role + ":" + content);
        }

        @Override
        public List<MessageRecord> getHistory(Long userId, String conversationId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<MessageHit> searchMessages(Long userId, String conversationId, String pattern, String needle,
                                               int before, int window, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<MessageHit> searchMessagesDescPage(Long userId, String conversationId, String pattern,
                                                       String needle, int before, int window, int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<MessageHit> searchMessagesAscPage(Long userId, String conversationId, String pattern,
                                                      String needle, int before, int window, int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<MessageHit> searchMessagesAnyDesc(Long userId, String conversationId, List<String> patterns,
                                                       List<String> needles, int before, int window,
                                                       int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<MessageHit> searchMessagesAnyAsc(Long userId, String conversationId, List<String> patterns,
                                                      List<String> needles, int before, int window,
                                                      int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public MessageRecord selectMessage(Long userId, String conversationId, long id) {
            return null;
        }

        @Override
        public int touch(Long userId, String conversationId, String title, Long workspaceId,
                         String permissionMode) { return 0; }

        @Override
        public ConversationSummary selectOne(Long userId, String conversationId) { return null; }

        @Override
        public List<ConversationSummary> listByUser(Long userId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<ConversationSummary> listByWorkspace(Long userId, Long workspaceId) {
            return Collections.emptyList();
        }

        @Override
        public List<ConversationSummary> listUnbound(Long userId) {
            return Collections.emptyList();
        }

        @Override
        public int updateWorkspaceId(Long userId, String conversationId, Long workspaceId) { return 0; }

        @Override
        public int clearWorkspaceId(Long userId, Long workspaceId) { return 0; }

        @Override
        public int updateTitle(Long userId, String conversationId, String title) { return 0; }

        @Override
        public int deleteConversation(Long userId, String conversationId) { return 0; }

        @Override
        public int deleteMessages(Long userId, String conversationId) { return 0; }

        @Override
        public int backfillFromMessages() { return 0; }

        @Override
        public int updatePlanJson(Long userId, String conversationId, String planJson) { return 0; }

        @Override
        public String selectPlanJson(Long userId, String conversationId) { return null; }

        @Override
        public int countPlanColumn() { return 0; }

        @Override
        public void addPlanColumn() { }

        @Override
        public int countWorkspaceIdColumn() { return 0; }

        @Override
        public void addWorkspaceIdColumn() { }

        @Override
        public int countWorkspaceIdIndex() { return 0; }

        @Override
        public void addWorkspaceIdIndex() { }

        @Override
        public int countPermissionModeColumn() { return 0; }

        @Override
        public void addPermissionModeColumn() { }

        @Override
        public int updatePermissionMode(Long userId, String conversationId, String mode) { return 0; }
    }
}
