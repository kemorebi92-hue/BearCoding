package com.example.jingjingai2.entity;

/** mcp_user_policy 一行。toolName 空串是服务级默认。 */
public class McpUserPolicy {

    private Long userId;
    private String serverName;
    private String toolName;
    private String effect;
    private String definitionHash;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }
}
