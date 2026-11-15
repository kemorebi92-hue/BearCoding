package com.example.jingjingai2.DTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 侧边栏里的一个已绑定工作区组。
 */
public class WorkspaceGroup {
    private Long id;
    private String name;
    private String boundPath;
    private List<ConversationSummary> conversations = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBoundPath() { return boundPath; }
    public void setBoundPath(String boundPath) { this.boundPath = boundPath; }

    public List<ConversationSummary> getConversations() { return conversations; }
    public void setConversations(List<ConversationSummary> conversations) {
        this.conversations = conversations == null ? new ArrayList<>() : conversations;
    }
}
