package com.example.jingjingai2.DTO;

public class ChatRequest {
    private String message;
    private String conversationId;
    /**
     * 可选：懒创建会话首次落库时挂到这个工作区。
     * 只在 INSERT 时生效，已有会话不会被这条字段改归属（改归属走 bind/unbind）。
     */
    private Long workspaceId;
    /** 可选：用户指定的模型配置 id；为空则用系统默认模型（DeepSeek） */
    private Long modelConfigId;
    /** 前端明确选择“系统兜底模型”时为 true，避免与“未指定，使用用户默认模型”混淆。 */
    private boolean useSystemModel;
    /**
     * 可选：懒创建会话首次落库时的权限模式。
     * 只在 INSERT 时生效；已有会话不会被这条字段改模式（改模式走 PUT permission-mode）。
     */
    private String permissionMode;
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }
    public boolean isUseSystemModel() { return useSystemModel; }
    public void setUseSystemModel(boolean useSystemModel) { this.useSystemModel = useSystemModel; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
}
