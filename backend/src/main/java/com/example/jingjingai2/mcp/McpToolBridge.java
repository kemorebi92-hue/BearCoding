package com.example.jingjingai2.mcp;

import org.springframework.ai.tool.ToolCallback;

/**
 * 把 MCP 工具接到主循环的回调数组末尾（原生工具在前，利于 prompt cache）。
 *
 * <p>MCP 关掉或一个工具都没有时，原样返回传入数组（引用不变），保证默认关时
 * 工具 JSON 与加 MCP 之前 byte 级一致。
 */
public final class McpToolBridge {

    private McpToolBridge() {}

    public static ToolCallback[] concat(ToolCallback[] nativeCbs, ToolCallback[] mcpCbs) {
        if (mcpCbs == null || mcpCbs.length == 0) {
            return nativeCbs == null ? new ToolCallback[0] : nativeCbs;
        }
        if (nativeCbs == null || nativeCbs.length == 0) {
            return mcpCbs;
        }
        ToolCallback[] out = new ToolCallback[nativeCbs.length + mcpCbs.length];
        System.arraycopy(nativeCbs, 0, out, 0, nativeCbs.length);
        System.arraycopy(mcpCbs, 0, out, nativeCbs.length, mcpCbs.length);
        return out;
    }

    /** 系统/部署级视图（单测用）。 */
    public static ToolCallback[] concat(ToolCallback[] nativeCbs, McpClientManager manager) {
        return concat(nativeCbs, manager, 0L);
    }

    /** 当前登录用户能看到的 MCP 工具：部署级 + 自己的；别人的不会进来。 */
    public static ToolCallback[] concat(ToolCallback[] nativeCbs, McpClientManager manager, long userId) {
        if (manager == null) {
            return nativeCbs == null ? new ToolCallback[0] : nativeCbs;
        }
        return concat(nativeCbs, manager.callbacks(userId));
    }
}
