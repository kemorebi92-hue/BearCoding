package com.example.jingjingai2.permission;

/**
 * 用户对 MCP 的策略。键是 (userId, 服务名, 工具名)；工具名为空表示服务级默认。
 * 生产用 MyBatis，单测用内存实现。两边都要重启后还在——内存实现只给测试。
 */
public interface McpUserPolicyStore {

    void setTool(long userId, String server, String tool, String effect, String definitionHash);

    void setServerDefault(long userId, String server, String effect);

    void clearServerDefault(long userId, String server);

    /** 删掉该服务下所有单工具行，不动服务级默认。 */
    void resetTools(long userId, String server);

    /** 没有这一行时返回 null。 */
    String toolEffect(long userId, String server, String tool);

    String toolHash(long userId, String server, String tool);

    String serverDefault(long userId, String server);
}
