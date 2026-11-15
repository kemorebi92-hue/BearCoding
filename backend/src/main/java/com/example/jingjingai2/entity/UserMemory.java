package com.example.jingjingai2.entity;

import java.util.Date;

/**
 * 用户记忆一行（对应表 user_memory）。
 * 归属是 user_id，不按 conversation_id 隔离；conversation_id 只是审计字段。
 */
public class UserMemory {
    private Long id;
    private Long userId;
    private String category;
    private String memKey;
    private String content;
    private String source;
    private String conversationId;
    private Date createdAt;
    private Date updatedAt;

    public UserMemory() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getMemKey() { return memKey; }
    public void setMemKey(String memKey) { this.memKey = memKey; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
