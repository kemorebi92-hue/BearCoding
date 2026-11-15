package com.example.jingjingai2.DTO;

import java.util.Date;

/**
 * 会话元数据（会话列表用的一条记录）。
 * 只含"列表需要展示的字段"，不含消息正文 —— 消息正文在 conversation_message 表里。
 */
public class ConversationSummary {
    private String conversationId;
    private String title;
    private int messageCount;
    private Date createdAt;
    private Date updatedAt;
    /** NULL = 默认工作区 */
    private Long workspaceId;
    /** 所属工作区显示名；默认工作区为「默认工作区」 */
    private String workspaceName;
    /** 会话权限模式：ASK / WORKSPACE_EDIT / FULL_ACCESS。旧数据缺列时为 null，读取方按 ASK 处理。 */
    private String permissionMode;

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }

    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String workspaceName) { this.workspaceName = workspaceName; }

    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
}
