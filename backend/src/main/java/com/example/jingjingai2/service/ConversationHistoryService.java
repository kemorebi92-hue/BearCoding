package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.mapper.ConversationMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 对话历史服务。原来用裸 HashMap 存内存（非线程安全 + 重启丢失），
 * 现在委托给 ConversationMapper 落地 MySQL，并发安全、可持久化。
 */
@Service
public class ConversationHistoryService {

    /** 多轮上下文最多回看多少轮（user+assistant 各算一条） */
    private static final int MAX_HISTORY_TURNS = 30;

    private final ConversationMapper mapper;

    public ConversationHistoryService(ConversationMapper mapper) {
        this.mapper = mapper;
    }

    public void addMessage(Long userId, String conversationId, MessageRecord record) {
        mapper.insert(userId, conversationId, record.getRole(), record.getContent());
    }

    public List<MessageRecord> getHistory(Long userId, String conversationId) {
        List<MessageRecord> all = mapper.getHistory(userId, conversationId, MAX_HISTORY_TURNS * 2);
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        // 数据库是倒序取的，这里逆序还原成时间顺序，避免历史过长撑爆上下文
        Collections.reverse(all);
        return all;
    }
}
