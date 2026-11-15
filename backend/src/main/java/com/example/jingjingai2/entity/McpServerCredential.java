package com.example.jingjingai2.entity;

/** mcp_server_credential 一行。只有密文，没有明文令牌字段。 */
public class McpServerCredential {

    private Long id;
    private Long userId;
    private Long serverConfigId;
    private String ciphertext;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getServerConfigId() { return serverConfigId; }
    public void setServerConfigId(Long serverConfigId) { this.serverConfigId = serverConfigId; }
    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
