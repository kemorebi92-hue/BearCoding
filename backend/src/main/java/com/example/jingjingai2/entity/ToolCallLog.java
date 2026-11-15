package com.example.jingjingai2.entity;

/**
 * 工具调用审计日志实体（对应表 tool_call_log）。
 * 与数据库列一一对应，MyBatis 开启 map-underscore-to-camel-case 后自动映射。
 */
public class ToolCallLog {
    private Long id;
    private Long userId;
    private String conversationId;
    private String toolName;
    private String arguments;
    private String result;
    private boolean success;
    private long elapsedMs;
    private boolean highRisk;
    private boolean approved;
    /** 与 agent_turn.turn_index 对齐。旧数据或对不上的调用为 null。 */
    private Integer turnIndex;

    public ToolCallLog() {}

    public ToolCallLog(Long userId, String conversationId, String toolName,
                      String arguments, String result, boolean success,
                      long elapsedMs, boolean highRisk, boolean approved, Integer turnIndex) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.success = success;
        this.elapsedMs = elapsedMs;
        this.highRisk = highRisk;
        this.approved = approved;
        this.turnIndex = turnIndex;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public boolean isHighRisk() { return highRisk; }
    public void setHighRisk(boolean highRisk) { this.highRisk = highRisk; }
    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }
    public Integer getTurnIndex() { return turnIndex; }
    public void setTurnIndex(Integer turnIndex) { this.turnIndex = turnIndex; }
}
