package com.example.jingjingai2.DTO;

import java.util.Date;

public class MessageRecord {
    /** 数据库主键。内存里临时构造、尚未入库的消息为 null。 */
    private Long id;
    private String role;
    private String content;
    private Date timestamp;
    public MessageRecord() {}
    public MessageRecord(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = new Date();
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
}
