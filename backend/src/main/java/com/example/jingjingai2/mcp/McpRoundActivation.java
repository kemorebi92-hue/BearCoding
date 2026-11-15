package com.example.jingjingai2.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本进程里、这个会话已经下发给模型的 mcp__ 名字。重启清空。
 * 持久历史只存 user/assistant 正文，工具清单不在里面，所以不靠消息表恢复。
 */
@Component
public class McpRoundActivation {

    private final ConcurrentHashMap<String, Set<String>> byConversation = new ConcurrentHashMap<>();

    public void note(String conversationId, List<String> names) {
        if (conversationId == null || conversationId.isBlank() || names == null || names.isEmpty()) return;
        Set<String> set = byConversation.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet());
        set.addAll(names);
    }

    public boolean activated(String conversationId, String exposedName) {
        if (conversationId == null || exposedName == null) return false;
        Set<String> set = byConversation.get(conversationId);
        return set != null && set.contains(exposedName);
    }

    public Set<String> names(String conversationId) {
        Set<String> set = conversationId == null ? null : byConversation.get(conversationId);
        return set == null ? Set.of() : new LinkedHashSet<>(set);
    }
}
