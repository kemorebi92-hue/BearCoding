package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpNames;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/** 下发前再滤一遍 DENY。没有删掉任何工具时返回原数组，避免无谓换引用。 */
public final class McpToolFilter {

    private McpToolFilter() {}

    public static ToolCallback[] dropDenied(Long userId, ToolCallback[] callbacks,
                                            McpPermissionExplainer explainer) {
        if (callbacks == null || callbacks.length == 0 || userId == null || explainer == null) {
            return callbacks;
        }
        List<ToolCallback> kept = null;
        for (int i = 0; i < callbacks.length; i++) {
            ToolCallback cb = callbacks[i];
            if (denied(userId, cb, explainer)) {
                if (kept == null) {
                    kept = new ArrayList<>();
                    for (int j = 0; j < i; j++) kept.add(callbacks[j]);
                }
                continue;
            }
            if (kept != null) kept.add(cb);
        }
        return kept == null ? callbacks : kept.toArray(new ToolCallback[0]);
    }

    private static boolean denied(Long userId, ToolCallback cb, McpPermissionExplainer explainer) {
        if (cb == null || cb.getToolDefinition() == null) return false;
        String name = cb.getToolDefinition().name();
        if (!McpNames.isMcp(name)) return false;
        McpPermissionExplainer.ExplainResult er = explainer.explain(
                userId, McpNames.serverOf(name), McpNames.originalOf(name), null);
        return er.effect == McpPermissionExplainer.Effect.DENY;
    }
}
