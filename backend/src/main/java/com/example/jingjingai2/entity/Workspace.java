package com.example.jingjingai2.entity;

import java.util.Date;

/**
 * 工作区元数据：一个用户绑定的一个真实目录。
 * 默认工作区不建行（会话的 workspace_id 为 NULL）。
 */
public class Workspace {
    private Long id;
    private Long userId;
    private String name;
    private String boundPath;
    private Date createdAt;
    private Date updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBoundPath() { return boundPath; }
    public void setBoundPath(String boundPath) { this.boundPath = boundPath; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
