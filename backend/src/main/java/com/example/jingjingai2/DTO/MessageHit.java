package com.example.jingjingai2.DTO;

import java.util.Date;

/**
 * 历史消息的短索引行：只有 id、角色、时间和摘要，不含完整正文。
 * 正文走 {@code ConversationMapper#selectMessage}，且必须重新校验 userId 和 conversationId。
 */
public class MessageHit {
    private Long id;
    private String role;
    private Date timestamp;
    private String snippet;

    public MessageHit() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
}
