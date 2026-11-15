package com.example.jingjingai2.memory;

import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.UserMemoryMapper;
import com.example.jingjingai2.usermemory.UserMemoryCategory;
import com.example.jingjingai2.usermemory.UserMemoryInjector;
import com.example.jingjingai2.usermemory.UserMemoryProperties;
import com.example.jingjingai2.usermemory.UserMemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4-1 回归：正式注入文案不变、观测不泄正文、影子选择不改 Prompt。
 */
class MemoryInjectionRegressionTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("用户记忆正式注入文案顺序与内容保持不变")
    void userMemoryOfficialTextUnchanged() {
        Map<UserMemoryCategory, List<UserMemory>> grouped = new LinkedHashMap<>();
        grouped.put(UserMemoryCategory.CONSTRAINT, List.of(row("constraint", "build", "本机没有 Maven")));
        grouped.put(UserMemoryCategory.PREFERENCE, List.of(row("preference", "lang", "中文")));
        grouped.put(UserMemoryCategory.PROFILE, List.of(row("profile", "name", "jing")));
        grouped.put(UserMemoryCategory.PROJECT_CONTEXT, List.of(row("project_context", "stack", "Java")));

        String text = UserMemoryInjector.render(grouped, 6000);
        assertNotNull(text);
        assertTrue(text.startsWith(UserMemoryInjector.PREFIX));
        assertTrue(text.indexOf("constraint:") < text.indexOf("preference:"));
        assertTrue(text.indexOf("preference:") < text.indexOf("profile:"));
        assertTrue(text.indexOf("profile:") < text.indexOf("project_context:"));
        assertTrue(text.contains("- build: 本机没有 Maven"));
        assertTrue(text.contains("- lang: 中文"));
    }

    @Test
    @DisplayName("工作区正式注入仍按全量顺序；观测记录跳过原因且不含正文")
    void workspaceOfficialInjectUnchanged() throws Exception {
        Path root = temp.resolve("ws");
        Path mem = root.resolve("memory");
        Files.createDirectories(mem);
        Files.writeString(mem.resolve("MEMORY.md"), "进度：Phase 4", StandardCharsets.UTF_8);
        Files.writeString(mem.resolve("billing.md"), "账单按量计费", StandardCharsets.UTF_8);
        Files.writeString(mem.resolve("api-key.md"), "SECRET", StandardCharsets.UTF_8);

        MemoryProperties props = new MemoryProperties();
        props.setEnabled(true);
        WorkspaceMemory wm = new WorkspaceMemory(props);
        WorkspaceMemory.Snapshot snap = wm.load(root);

        assertTrue(snap.injected);
        assertEquals(List.of("MEMORY.md", "billing.md"), snap.files);
        assertTrue(snap.text.contains("【长期记忆】"));
        assertTrue(snap.text.contains("### MEMORY.md"));
        assertTrue(snap.text.contains("### billing.md"));
        assertFalse(snap.text.contains("SECRET"));
        assertTrue(snap.skipped >= 1);

        boolean sawSecretSkip = false;
        for (MemoryItemObservation o : snap.observations) {
            assertFalse(o.toLogLine().contains("进度：Phase 4"));
            assertFalse(o.toLogLine().contains("账单按量计费"));
            assertFalse(o.toLogLine().contains("SECRET"));
            if ("secret-name".equals(o.skipReason())) sawSecretSkip = true;
        }
        assertTrue(sawSecretSkip);
    }

    @Test
    @DisplayName("影子选择不改正式 Prompt：注入后再跑选择，消息文本不变")
    void shadowSelectionDoesNotMutateOfficialPrompt() {
        FakeMapper mapper = new FakeMapper();
        UserMemoryProperties props = new UserMemoryProperties();
        props.setEnabled(true);
        UserMemoryService service = new UserMemoryService(mapper, props);
        assertTrue(service.upsert(1L, "constraint", "build", "本机没有 Maven", "c1").isAccepted());
        assertTrue(service.upsert(1L, "profile", "city", "杭州", "c1").isAccepted());
        UserMemoryInjector injector = new UserMemoryInjector(service, props);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("你是小熊。"));
        msgs.add(new UserMessage("账单怎么算"));
        injector.injectUserMemory(msgs, 1L);

        List<String> before = texts(msgs);
        List<MemoryItem> items = MemoryShadowSelector.fromUserMemories(service.listByUser(1L));
        MemoryShadowSelector.Result shadow = MemoryShadowSelector.select("账单怎么算", items, 6000);
        assertFalse(shadow.verdicts.isEmpty());
        assertEquals(before, texts(msgs), "影子选择后正式消息不得变化");

        List<MemoryItemObservation> obs = injector.lastObservations();
        assertFalse(obs.isEmpty());
        for (MemoryItemObservation o : obs) {
            assertFalse(o.toLogLine().contains("本机没有 Maven"));
            assertFalse(o.toLogLine().contains("杭州"));
        }
    }

    @Test
    @DisplayName("用户隔离：不同 userId 的记忆互不出现")
    void usersDoNotLeak() {
        FakeMapper mapper = new FakeMapper();
        UserMemoryProperties props = new UserMemoryProperties();
        props.setEnabled(true);
        UserMemoryService service = new UserMemoryService(mapper, props);
        assertTrue(service.upsert(1L, "profile", "name", "alice", "c").isAccepted());
        assertTrue(service.upsert(2L, "profile", "name", "bob", "c").isAccepted());

        UserMemoryInjector injector = new UserMemoryInjector(service, props);
        List<Message> for1 = new ArrayList<>();
        for1.add(new SystemMessage("sys"));
        injector.injectUserMemory(for1, 1L);
        String t1 = ((SystemMessage) for1.get(1)).getText();
        assertTrue(t1.contains("alice"));
        assertFalse(t1.contains("bob"));

        List<Message> for2 = new ArrayList<>();
        for2.add(new SystemMessage("sys"));
        injector.injectUserMemory(for2, 2L);
        String t2 = ((SystemMessage) for2.get(1)).getText();
        assertTrue(t2.contains("bob"));
        assertFalse(t2.contains("alice"));
    }

    @Test
    @DisplayName("工作区隔离：两个根目录互不串文件")
    void workspacesDoNotLeak() throws Exception {
        Path a = temp.resolve("a");
        Path b = temp.resolve("b");
        Files.createDirectories(a.resolve("memory"));
        Files.createDirectories(b.resolve("memory"));
        Files.writeString(a.resolve("memory").resolve("MEMORY.md"), "A项目", StandardCharsets.UTF_8);
        Files.writeString(b.resolve("memory").resolve("MEMORY.md"), "B项目", StandardCharsets.UTF_8);

        MemoryProperties props = new MemoryProperties();
        WorkspaceMemory wm = new WorkspaceMemory(props);
        WorkspaceMemory.Snapshot sa = wm.load(a);
        WorkspaceMemory.Snapshot sb = wm.load(b);
        assertTrue(sa.text.contains("A项目"));
        assertFalse(sa.text.contains("B项目"));
        assertTrue(sb.text.contains("B项目"));
        assertFalse(sb.text.contains("A项目"));
    }

    private static UserMemory row(String category, String key, String content) {
        UserMemory m = new UserMemory();
        m.setCategory(category);
        m.setMemKey(key);
        m.setContent(content);
        return m;
    }

    private static List<String> texts(List<Message> msgs) {
        List<String> out = new ArrayList<>();
        for (Message m : msgs) out.add(m.getText());
        return out;
    }

    static final class FakeMapper implements UserMemoryMapper {
        private final List<UserMemory> rows = new ArrayList<>();
        private long seq = 1;

        @Override
        public int upsert(Long userId, String category, String memKey, String content,
                          String source, String conversationId) {
            UserMemory existing = getOne(userId, category, memKey);
            if (existing != null) {
                existing.setContent(content);
                existing.setSource(source);
                existing.setConversationId(conversationId);
                existing.setUpdatedAt(new Date());
                return 1;
            }
            UserMemory m = new UserMemory();
            m.setId(seq++);
            m.setUserId(userId);
            m.setCategory(category);
            m.setMemKey(memKey);
            m.setContent(content);
            m.setSource(source);
            m.setConversationId(conversationId);
            m.setCreatedAt(new Date());
            m.setUpdatedAt(new Date());
            rows.add(m);
            return 1;
        }

        @Override
        public UserMemory getOne(Long userId, String category, String memKey) {
            for (UserMemory r : rows) {
                if (r.getUserId().equals(userId)
                        && r.getCategory().equals(category)
                        && r.getMemKey().equals(memKey)) return r;
            }
            return null;
        }

        @Override
        public List<UserMemory> listByUser(Long userId) {
            List<UserMemory> out = new ArrayList<>();
            for (UserMemory r : rows) if (r.getUserId().equals(userId)) out.add(r);
            return out;
        }

        @Override
        public int countByUser(Long userId) {
            return listByUser(userId).size();
        }

        @Override
        public int deleteOne(Long userId, String category, String memKey) {
            return rows.removeIf(r -> r.getUserId().equals(userId)
                    && r.getCategory().equals(category)
                    && r.getMemKey().equals(memKey)) ? 1 : 0;
        }

        @Override
        public int deleteByCategory(Long userId, String category) {
            int n = 0;
            for (int i = rows.size() - 1; i >= 0; i--) {
                UserMemory r = rows.get(i);
                if (r.getUserId().equals(userId) && r.getCategory().equals(category)) {
                    rows.remove(i);
                    n++;
                }
            }
            return n;
        }
    }
}
