package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.UserMemoryMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存假 Mapper：不碰 MySQL。行为对齐 {@link UserMemoryMapper} 的 SQL 语义
 * （同一 user+category+key 覆盖、list 按 category ASC / updated_at DESC）。
 */
final class FakeUserMemoryMapper implements UserMemoryMapper {

    private final Map<String, UserMemory> store = new LinkedHashMap<>();
    private long nextId = 1;

    private static String id(Long userId, String category, String memKey) {
        return userId + "\0" + category + "\0" + memKey;
    }

    @Override
    public int upsert(Long userId, String category, String memKey, String content,
                      String source, String conversationId) {
        String k = id(userId, category, memKey);
        UserMemory row = store.get(k);
        Date now = new Date();
        if (row == null) {
            row = new UserMemory();
            row.setId(nextId++);
            row.setUserId(userId);
            row.setCategory(category);
            row.setMemKey(memKey);
            row.setCreatedAt(now);
            store.put(k, row);
        }
        row.setContent(content);
        row.setSource(source);
        row.setConversationId(conversationId);
        row.setUpdatedAt(now);
        return 1;
    }

    @Override
    public List<UserMemory> listByUser(Long userId) {
        List<UserMemory> out = new ArrayList<>();
        for (UserMemory row : store.values()) {
            if (userId.equals(row.getUserId())) out.add(copy(row));
        }
        out.sort(Comparator.comparing(UserMemory::getCategory)
                .thenComparing(UserMemory::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Override
    public UserMemory getOne(Long userId, String category, String memKey) {
        UserMemory row = store.get(id(userId, category, memKey));
        return row == null ? null : copy(row);
    }

    @Override
    public int deleteOne(Long userId, String category, String memKey) {
        return store.remove(id(userId, category, memKey)) == null ? 0 : 1;
    }

    @Override
    public int deleteByCategory(Long userId, String category) {
        int before = store.size();
        store.entrySet().removeIf(e ->
                userId.equals(e.getValue().getUserId()) && category.equals(e.getValue().getCategory()));
        return before - store.size();
    }

    @Override
    public int countByUser(Long userId) {
        int n = 0;
        for (UserMemory row : store.values()) {
            if (userId.equals(row.getUserId())) n++;
        }
        return n;
    }

    private static UserMemory copy(UserMemory src) {
        UserMemory d = new UserMemory();
        d.setId(src.getId());
        d.setUserId(src.getUserId());
        d.setCategory(src.getCategory());
        d.setMemKey(src.getMemKey());
        d.setContent(src.getContent());
        d.setSource(src.getSource());
        d.setConversationId(src.getConversationId());
        d.setCreatedAt(src.getCreatedAt());
        d.setUpdatedAt(src.getUpdatedAt());
        return d;
    }
}
