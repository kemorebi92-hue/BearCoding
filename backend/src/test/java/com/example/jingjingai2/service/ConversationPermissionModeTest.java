package com.example.jingjingai2.service;

import com.example.jingjingai2.db.DbDialect;
import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.mapper.ConversationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话权限模式的读写规则。用内存假 Mapper，不连接任何数据库。
 */
class ConversationPermissionModeTest {

    /** 一行会话：归属、模式、更新时间（列表排序用）。 */
    private record Row(long userId, String conversationId, String mode, long updatedAt) {}

    /** 记录一次真实发出的 UPDATE，用来断言只改了这一列、只改了这一行。 */
    private record Update(long userId, String conversationId, String mode) {}

    private static final class Store {
        final List<Row> rows = new ArrayList<>();
        final List<Update> updates = new ArrayList<>();
        boolean permissionColumnPresent = true;

        Row find(Long userId, String conversationId) {
            for (Row r : rows) {
                if (r.userId == userId && r.conversationId.equals(conversationId)) return r;
            }
            return null;
        }
    }

    private static ConversationService service(Store store) {
        ConversationMapper mapper = (ConversationMapper) Proxy.newProxyInstance(
                ConversationMapper.class.getClassLoader(),
                new Class<?>[]{ConversationMapper.class},
                (proxy, method, args) -> dispatch(store, method, args));
        return new ConversationService(mapper, null);
    }

    private static Object dispatch(Store store, Method method, Object[] args) {
        return switch (method.getName()) {
            case "selectOne" -> toSummary(store.find((Long) args[0], (String) args[1]));
            case "listByUser" -> list(store, (Long) args[0], (Integer) args[1]);
            case "updatePermissionMode" -> update(store, (Long) args[0], (String) args[1], (String) args[2]);
            case "countPermissionModeColumn" -> store.permissionColumnPresent ? 1 : 0;
            case "addPermissionModeColumn" -> {
                store.permissionColumnPresent = true;
                yield null;
            }
            case "touch" -> insert(store, (Long) args[0], (String) args[1], (String) args[4]);
            default -> defaultValue(method.getReturnType());
        };
    }

    /** 首次 INSERT 写入请求里的模式（缺省/非法由 service 归一成 ASK）；重复 touch 不覆盖已有模式。 */
    private static int insert(Store store, Long userId, String conversationId, String mode) {
        if (store.find(userId, conversationId) != null) return 2;
        String initial = (mode == null || mode.isBlank()) ? ConversationService.MODE_ASK : mode;
        store.rows.add(new Row(userId, conversationId, initial, store.rows.size()));
        return 1;
    }

    private static int update(Store store, Long userId, String conversationId, String mode) {
        Row found = store.find(userId, conversationId);
        if (found == null) return 0;
        store.updates.add(new Update(userId, conversationId, mode));
        store.rows.remove(found);
        store.rows.add(new Row(found.userId, found.conversationId, mode, found.updatedAt));
        return 1;
    }

    private static List<ConversationSummary> list(Store store, Long userId, int limit) {
        return store.rows.stream()
                .filter(r -> r.userId == userId)
                .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt))
                .limit(limit)
                .map(ConversationPermissionModeTest::toSummary)
                .toList();
    }

    private static ConversationSummary toSummary(Row r) {
        if (r == null) return null;
        ConversationSummary s = new ConversationSummary();
        s.setConversationId(r.conversationId);
        s.setTitle(r.conversationId);
        s.setMessageCount(1);
        s.setPermissionMode(r.mode);
        return s;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (List.class.isAssignableFrom(type)) return List.of();
        return null;
    }

    @Test
    @DisplayName("新建会话默认 ASK：INSERT 不写该列，读出来是列默认值")
    void newConversationDefaultsToAsk() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "你好");
        assertEquals(ConversationService.MODE_ASK, svc.permissionMode(1L, "c1"));
    }

    @Test
    @DisplayName("首条消息可以带上新建会话时选的模式")
    void firstMessageAppliesSelectedMode() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "你好", null, ConversationService.MODE_FULL_ACCESS);
        assertEquals(ConversationService.MODE_FULL_ACCESS, svc.permissionMode(1L, "c1"));
    }

    @Test
    @DisplayName("后续消息不会用请求体覆盖已保存的模式")
    void laterMessagesDoNotOverrideMode() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "第一句", null, ConversationService.MODE_WORKSPACE_EDIT);
        svc.touch(1L, "c1", "第二句", null, ConversationService.MODE_ASK);
        assertEquals(ConversationService.MODE_WORKSPACE_EDIT, svc.permissionMode(1L, "c1"));
    }

    @Test
    @DisplayName("非法模式按 ASK 落库，不写进列里")
    void illegalInitialModeFallsBackToAsk() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "你好", null, "FULL");
        assertEquals(ConversationService.MODE_ASK, svc.permissionMode(1L, "c1"));
    }

    @Test
    @DisplayName("旧库缺列时启动补列，补列后默认 ASK")
    void missingColumnBackfilledAsAsk() {
        Store store = new Store();
        store.permissionColumnPresent = false;
        store.rows.add(new Row(1L, "old", null, 1));
        ConversationService svc = service(store);

        DbDialect.overrideForTest(false);
        try {
            svc.migrateAndBackfill();
            assertTrue(store.permissionColumnPresent, "缺列时应当执行 ADD COLUMN");
            assertEquals(ConversationService.MODE_ASK, svc.permissionMode(1L, "old"));
        } finally {
            DbDialect.overrideForTest(null);
        }
    }

    @Test
    @DisplayName("三种合法模式都能保存并读回")
    void allModesRoundTrip() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "你好");
        for (String mode : List.of(ConversationService.MODE_ASK,
                ConversationService.MODE_WORKSPACE_EDIT, ConversationService.MODE_FULL_ACCESS)) {
            assertTrue(svc.updatePermissionMode(1L, "c1", mode), mode);
            assertEquals(mode, svc.permissionMode(1L, "c1"));
        }
    }

    @Test
    @DisplayName("非法模式拒绝：不落库、原值不变")
    void illegalModeRejected() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "你好");
        for (String bad : new String[]{null, "", "ask", "ADMIN", "FULL", " full_access "}) {
            assertFalse(svc.updatePermissionMode(1L, "c1", bad), String.valueOf(bad));
        }
        assertTrue(store.updates.isEmpty(), "非法值不能发出 UPDATE");
        assertEquals(ConversationService.MODE_ASK, svc.permissionMode(1L, "c1"));
    }

    @Test
    @DisplayName("别人的会话和不存在的会话都不能改")
    void foreignAndMissingRejected() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "c1", "你好");

        assertFalse(svc.updatePermissionMode(2L, "c1", ConversationService.MODE_FULL_ACCESS));
        assertNull(svc.permissionMode(2L, "c1"));
        assertEquals(ConversationService.MODE_ASK, svc.permissionMode(1L, "c1"), "不能改到别人的会话");

        assertFalse(svc.updatePermissionMode(1L, "nope", ConversationService.MODE_FULL_ACCESS));
        assertTrue(store.updates.isEmpty());
    }

    @Test
    @DisplayName("会话列表返回 permissionMode，旧数据空值显示 ASK")
    void listReturnsMode() {
        Store store = new Store();
        store.rows.add(new Row(1L, "a", ConversationService.MODE_FULL_ACCESS, 2));
        store.rows.add(new Row(1L, "b", null, 1));
        store.rows.add(new Row(2L, "other", ConversationService.MODE_WORKSPACE_EDIT, 3));
        ConversationService svc = service(store);

        List<ConversationSummary> list = svc.list(1L);
        Map<String, String> modes = new LinkedHashMap<>();
        for (ConversationSummary c : list) modes.put(c.getConversationId(), c.getPermissionMode());
        assertEquals(List.of("a", "b"), List.copyOf(modes.keySet()), "仍按 updated_at 倒序，且不串用户");
        assertEquals(ConversationService.MODE_FULL_ACCESS, modes.get("a"));
        assertEquals(ConversationService.MODE_ASK, modes.get("b"));
    }

    @Test
    @DisplayName("更新会话 A 不影响会话 B，且只改 permission_mode 这一列")
    void updateIsolated() {
        Store store = new Store();
        ConversationService svc = service(store);
        svc.touch(1L, "a", "甲");
        svc.touch(1L, "b", "乙");
        assertTrue(svc.updatePermissionMode(1L, "a", ConversationService.MODE_WORKSPACE_EDIT));

        assertEquals(List.of(new Update(1L, "a", ConversationService.MODE_WORKSPACE_EDIT)), store.updates);
        assertEquals(ConversationService.MODE_ASK, svc.permissionMode(1L, "b"));
        assertEquals(1, svc.get(1L, "a").getMessageCount(), "其他字段保持原样");
    }

    @Test
    @DisplayName("列表上限和排序不回归：超过 200 条只返回最近 200 条")
    void listLimitUnchanged() {
        Store store = new Store();
        for (int i = 0; i < 205; i++) store.rows.add(new Row(1L, "c" + i, ConversationService.MODE_ASK, i));
        List<ConversationSummary> list = service(store).list(1L);
        assertEquals(200, list.size());
        assertEquals("c204", list.get(0).getConversationId(), "最新的排最前");
        assertEquals("c5", list.get(199).getConversationId());
    }
}
