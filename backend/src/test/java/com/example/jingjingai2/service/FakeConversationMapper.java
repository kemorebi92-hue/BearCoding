package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.entity.Workspace;
import com.example.jingjingai2.mapper.ConversationMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 内存假 Mapper：会话元数据 + 消息，对齐 workspace_id 归属语义。 */
final class FakeConversationMapper implements ConversationMapper {

    static final class Row {
        Long userId;
        String conversationId;
        String title;
        int messageCount;
        Long workspaceId;
        Date createdAt;
        Date updatedAt;
        String planJson;
        String permissionMode;
    }

    private final Map<String, Row> store = new LinkedHashMap<>();
    private final List<MessageRecord> messages = new ArrayList<>();
    private long nextMessageId = 1L;
    private final FakeWorkspaceMapper workspaces;

    FakeConversationMapper(FakeWorkspaceMapper workspaces) {
        this.workspaces = workspaces;
    }

    private static String key(Long userId, String conversationId) {
        return userId + "\0" + conversationId;
    }

    @Override
    public void insert(Long userId, String conversationId, String role, String content) {
        MessageRecord m = new MessageRecord();
        m.setId(nextMessageId++);
        m.setRole(role);
        m.setContent(content);
        m.setTimestamp(new Date());
        messages.add(m);
    }

    @Override
    public List<MessageRecord> getHistory(Long userId, String conversationId, int limit) {
        return List.of();
    }

    @Override
    public List<MessageHit> searchMessages(Long userId, String conversationId, String pattern, String needle,
                                           int before, int window, int limit) {
        return List.of();
    }

    @Override
    public List<MessageHit> searchMessagesDescPage(Long userId, String conversationId, String pattern,
                                                   String needle, int before, int window, int limit, int offset) {
        return List.of();
    }

    @Override
    public List<MessageHit> searchMessagesAscPage(Long userId, String conversationId, String pattern,
                                                  String needle, int before, int window, int limit, int offset) {
        return List.of();
    }

    @Override
    public List<MessageHit> searchMessagesAnyDesc(Long userId, String conversationId, java.util.List<String> patterns,
                                                   java.util.List<String> needles, int before, int window,
                                                   int limit, int offset) {
        return List.of();
    }

    @Override
    public List<MessageHit> searchMessagesAnyAsc(Long userId, String conversationId, java.util.List<String> patterns,
                                                  java.util.List<String> needles, int before, int window,
                                                  int limit, int offset) {
        return List.of();
    }

    @Override
    public MessageRecord selectMessage(Long userId, String conversationId, long id) {
        return null;
    }

    @Override
    public int touch(Long userId, String conversationId, String title, Long workspaceId,
                     String permissionMode) {
        String k = key(userId, conversationId);
        Row row = store.get(k);
        Date now = new Date();
        if (row == null) {
            row = new Row();
            row.userId = userId;
            row.conversationId = conversationId;
            row.title = title;
            row.messageCount = 1;
            row.workspaceId = workspaceId;
            row.permissionMode = permissionMode == null ? ConversationService.MODE_ASK : permissionMode;
            row.createdAt = now;
            row.updatedAt = now;
            store.put(k, row);
            return 1;
        }
        // 与 SQL 的 ON DUPLICATE KEY UPDATE 一致：已有会话不按请求体重置权限模式
        row.messageCount++;
        row.updatedAt = now;
        return 1;
    }

    @Override
    public ConversationSummary selectOne(Long userId, String conversationId) {
        Row row = store.get(key(userId, conversationId));
        return row == null ? null : toSummary(row);
    }

    @Override
    public List<ConversationSummary> listByUser(Long userId, int limit) {
        List<ConversationSummary> out = new ArrayList<>();
        for (Row row : store.values()) {
            if (Objects.equals(row.userId, userId)) out.add(toSummary(row));
        }
        out.sort(Comparator.comparing(ConversationSummary::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (out.size() > limit) return out.subList(0, limit);
        return out;
    }

    @Override
    public List<ConversationSummary> listByWorkspace(Long userId, Long workspaceId) {
        List<ConversationSummary> out = new ArrayList<>();
        for (Row row : store.values()) {
            if (Objects.equals(row.userId, userId) && Objects.equals(row.workspaceId, workspaceId)) {
                out.add(toSummary(row));
            }
        }
        out.sort(Comparator.comparing(ConversationSummary::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Override
    public List<ConversationSummary> listUnbound(Long userId) {
        List<ConversationSummary> out = new ArrayList<>();
        for (Row row : store.values()) {
            if (Objects.equals(row.userId, userId) && row.workspaceId == null) out.add(toSummary(row));
        }
        out.sort(Comparator.comparing(ConversationSummary::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Override
    public int updateWorkspaceId(Long userId, String conversationId, Long workspaceId) {
        Row row = store.get(key(userId, conversationId));
        if (row == null) return 0;
        row.workspaceId = workspaceId;
        row.updatedAt = new Date();
        return 1;
    }

    @Override
    public int clearWorkspaceId(Long userId, Long workspaceId) {
        int n = 0;
        for (Row row : store.values()) {
            if (Objects.equals(row.userId, userId) && Objects.equals(row.workspaceId, workspaceId)) {
                row.workspaceId = null;
                row.updatedAt = new Date();
                n++;
            }
        }
        return n;
    }

    @Override
    public int updateTitle(Long userId, String conversationId, String title) {
        Row row = store.get(key(userId, conversationId));
        if (row == null) return 0;
        row.title = title;
        row.updatedAt = new Date();
        return 1;
    }

    @Override
    public int updatePermissionMode(Long userId, String conversationId, String mode) {
        Row row = store.get(key(userId, conversationId));
        if (row == null) return 0;
        row.permissionMode = mode;
        return 1;
    }

    @Override
    public int deleteConversation(Long userId, String conversationId) {
        return store.remove(key(userId, conversationId)) == null ? 0 : 1;
    }

    @Override
    public int deleteMessages(Long userId, String conversationId) {
        return 1;
    }

    @Override
    public int backfillFromMessages() { return 0; }

    @Override
    public int updatePlanJson(Long userId, String conversationId, String planJson) {
        Row row = store.get(key(userId, conversationId));
        if (row == null) return 0;
        row.planJson = planJson;
        return 1;
    }

    @Override
    public String selectPlanJson(Long userId, String conversationId) {
        Row row = store.get(key(userId, conversationId));
        return row == null ? null : row.planJson;
    }

    @Override
    public int countPlanColumn() { return 1; }

    @Override
    public void addPlanColumn() {}

    @Override
    public int countWorkspaceIdColumn() { return 1; }

    @Override
    public void addWorkspaceIdColumn() {}

    @Override
    public int countWorkspaceIdIndex() { return 1; }

    @Override
    public void addWorkspaceIdIndex() {}

    @Override
    public int countPermissionModeColumn() { return 1; }

    @Override
    public void addPermissionModeColumn() {}

    private ConversationSummary toSummary(Row row) {
        ConversationSummary s = new ConversationSummary();
        s.setConversationId(row.conversationId);
        s.setTitle(row.title);
        s.setMessageCount(row.messageCount);
        s.setCreatedAt(row.createdAt);
        s.setUpdatedAt(row.updatedAt);
        s.setWorkspaceId(row.workspaceId);
        s.setPermissionMode(row.permissionMode);
        if (row.workspaceId != null) {
            Workspace w = workspaces.selectById(row.workspaceId);
            if (w != null) s.setWorkspaceName(w.getName());
        }
        return s;
    }
}
