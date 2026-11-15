package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.entity.UserMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserMemoryService 单测：假 Mapper，不碰 MySQL、不接 ChatController。
 *
 * 钉死步骤 1 的应用层硬顶：覆盖写、未知类别拒绝、满 40 拒新 key、非法 key、content 截断、按类分组。
 */
class UserMemoryServiceTest {

    private static final Long USER = 7L;
    private static final String CONV = "conv-mem-1";

    private FakeUserMemoryMapper mapper;
    private UserMemoryProperties props;
    private UserMemoryService service;

    @BeforeEach
    void setUp() {
        mapper = new FakeUserMemoryMapper();
        props = new UserMemoryProperties();
        service = new UserMemoryService(mapper, props);
    }

    private UserMemoryService.WriteResult save(String category, String key, String content) {
        return service.upsert(USER, category, key, content, CONV);
    }

    @Test
    @DisplayName("Java 默认与方案对齐：enabled=false / 40 条 / 单条 1500 / 注入 6000")
    void propertyDefaults() {
        UserMemoryProperties p = new UserMemoryProperties();
        assertFalse(p.isEnabled(), "开关默认关，主路径行为与现在一致");
        assertEquals(40, p.getMaxItems());
        assertEquals(1500, p.getMaxItemChars());
        assertEquals(6000, p.getMaxInjectChars());
    }

    @Test
    @DisplayName("upsert 同一 user+category+key 覆盖，不新增行")
    void upsertOverwritesSameKey() {
        assertTrue(save("preference", "reply_lang", "中文").isAccepted());
        UserMemoryService.WriteResult second = save("preference", "reply_lang", "English");
        assertTrue(second.isAccepted());
        assertEquals(1, service.countByUser(USER));
        assertEquals("English", service.getOne(USER, "preference", "reply_lang").getContent());
        assertEquals(1, service.listByUser(USER).size());
    }

    @Test
    @DisplayName("未知 category（含 progress）拒绝，不落库")
    void unknownCategoryRejected() {
        UserMemoryService.WriteResult r = save("progress", "next_step", "下一步编译");
        assertFalse(r.isAccepted());
        assertTrue(r.getMessage().contains("未知 category"), r.getMessage());
        assertEquals(0, service.countByUser(USER));

        assertFalse(save("next_step", "x", "y").isAccepted());
        assertFalse(save("mood", "today", "happy").isAccepted());
        assertFalse(save("", "k", "v").isAccepted());
        assertFalse(save(null, "k", "v").isAccepted());
    }

    @Test
    @DisplayName("满 40 条拒绝新 key；覆盖已有 key 仍允许")
    void fullFortyRejectsNewKeyButAllowsOverwrite() {
        for (int i = 0; i < 40; i++) {
            UserMemoryService.WriteResult r = save("profile", "k" + i, "v" + i);
            assertTrue(r.isAccepted(), "第 " + (i + 1) + " 条应写入：" + r.getMessage());
        }
        assertEquals(40, service.countByUser(USER));

        UserMemoryService.WriteResult neu = save("profile", "k40", "too-many");
        assertFalse(neu.isAccepted());
        assertTrue(neu.getMessage().contains("已满 40 条"), neu.getMessage());
        assertTrue(neu.getMessage().contains("先删一条"), neu.getMessage());
        assertEquals(40, service.countByUser(USER));
        assertNull(service.getOne(USER, "profile", "k40"));

        UserMemoryService.WriteResult overwrite = save("profile", "k0", "updated");
        assertTrue(overwrite.isAccepted(), "满员后覆盖旧 key 必须放行");
        assertEquals(40, service.countByUser(USER));
        assertEquals("updated", service.getOne(USER, "profile", "k0").getContent());
    }

    @Test
    @DisplayName("非法 mem_key 拒绝：路径、..、密钥形文件名、超长、空白")
    void illegalKeyRejected() {
        assertRejectedKey("../secret");
        assertRejectedKey("foo/bar");
        assertRejectedKey("foo\\bar");
        assertRejectedKey("has space");
        assertRejectedKey("中文键");
        assertRejectedKey("..");
        assertRejectedKey(".env");
        assertRejectedKey(".env.local");
        assertRejectedKey("id_rsa");
        assertRejectedKey("id_rsa.pub");
        assertRejectedKey("server.pem");
        assertRejectedKey("credentials");
        assertRejectedKey("");
        assertRejectedKey(null);
        assertRejectedKey("a".repeat(65));

        assertEquals(0, service.countByUser(USER));
        assertTrue(save("constraint", "build", "用 test.sh").isAccepted(), "合法 key 应通过");
        assertTrue(save("constraint", "reply_lang", "中文").isAccepted());
        assertTrue(save("constraint", "no_maven", "本机没有 Maven").isAccepted());
    }

    @Test
    @DisplayName("content 超出 max-item-chars 截断后再写入，不拒绝")
    void contentTruncatedToMaxItemChars() {
        String longBody = "字".repeat(1600);
        UserMemoryService.WriteResult r = save("preference", "style", longBody);
        assertTrue(r.isAccepted());
        UserMemory saved = service.getOne(USER, "preference", "style");
        assertEquals(1500, saved.getContent().length());
        assertEquals("字".repeat(1500), saved.getContent());
        assertEquals(1, service.countByUser(USER));
    }

    @Test
    @DisplayName("listByUser 按注入顺序分组：constraint → preference → profile → project_context")
    void listByUserGroupedInInjectOrder() {
        // 故意反着写，分组结果仍要按注入顺序
        assertTrue(save("project_context", "stack", "常做 Java/Spring").isAccepted());
        assertTrue(save("profile", "name", "jing").isAccepted());
        assertTrue(save("preference", "reply_lang", "中文").isAccepted());
        assertTrue(save("constraint", "build", "本机没有 Maven，用 test.sh").isAccepted());
        assertTrue(save("preference", "verbosity", "短").isAccepted());

        List<UserMemory> flat = service.listByUser(USER);
        assertEquals(5, flat.size());

        Map<UserMemoryCategory, List<UserMemory>> grouped = service.listGrouped(USER);
        assertEquals(List.of(
                UserMemoryCategory.CONSTRAINT,
                UserMemoryCategory.PREFERENCE,
                UserMemoryCategory.PROFILE,
                UserMemoryCategory.PROJECT_CONTEXT), new ArrayList<>(grouped.keySet()));

        assertEquals(List.of("build"), keys(grouped.get(UserMemoryCategory.CONSTRAINT)));
        assertEquals(2, grouped.get(UserMemoryCategory.PREFERENCE).size());
        assertEquals(List.of("name"), keys(grouped.get(UserMemoryCategory.PROFILE)));
        assertEquals(List.of("stack"), keys(grouped.get(UserMemoryCategory.PROJECT_CONTEXT)));
        assertEquals("本机没有 Maven，用 test.sh",
                grouped.get(UserMemoryCategory.CONSTRAINT).get(0).getContent());
    }

    @Test
    @DisplayName("空 content / 空 userId 拒绝")
    void blankContentAndNullUserRejected() {
        assertFalse(save("preference", "reply_lang", "  ").isAccepted());
        assertFalse(save("preference", "reply_lang", "").isAccepted());
        assertFalse(service.upsert(null, "preference", "reply_lang", "中文", CONV).isAccepted());
        assertEquals(0, service.countByUser(USER));
        assertTrue(service.listByUser(null).isEmpty());
        assertTrue(service.listGrouped(null).isEmpty());
    }

    private void assertRejectedKey(String key) {
        UserMemoryService.WriteResult r = save("preference", key, "x");
        assertFalse(r.isAccepted(), "应拒绝 key=" + key + "，实际：" + r.getMessage());
        assertNotNull(r.getMessage());
    }

    private static List<String> keys(List<UserMemory> rows) {
        List<String> out = new ArrayList<>();
        for (UserMemory row : rows) out.add(row.getMemKey());
        return out;
    }
}
