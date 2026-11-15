package com.example.jingjingai2.DTO;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /ai/workspaces 的响应：已绑定组 + 默认工作区会话。
 */
public class WorkspaceListResponse {
    private List<WorkspaceGroup> workspaces = new ArrayList<>();
    private List<ConversationSummary> defaultConversations = new ArrayList<>();

    public List<WorkspaceGroup> getWorkspaces() { return workspaces; }
    public void setWorkspaces(List<WorkspaceGroup> workspaces) {
        this.workspaces = workspaces == null ? new ArrayList<>() : workspaces;
    }

    public List<ConversationSummary> getDefaultConversations() { return defaultConversations; }
    public void setDefaultConversations(List<ConversationSummary> defaultConversations) {
        this.defaultConversations = defaultConversations == null ? new ArrayList<>() : defaultConversations;
    }
}
