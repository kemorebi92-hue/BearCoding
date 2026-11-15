package com.example.jingjingai2.mcp;

import java.util.Map;

/** 测试用：按服务名交回假 session。放在 mcp 包里才能覆盖包内的 openSession。 */
public final class FakeMcpServers {

    private FakeMcpServers() {}

    public static McpClientManager start(McpProperties props, Map<String, FakeMcpSession> fakes) {
        McpClientManager manager = new McpClientManager(props) {
            @Override
            McpSession openSession(String serverName, McpServerSpec spec) {
                FakeMcpSession fake = fakes.get(serverName);
                if (fake == null) throw new McpException("没有假 session: " + serverName);
                return fake;
            }
        };
        manager.start();
        return manager;
    }
}
