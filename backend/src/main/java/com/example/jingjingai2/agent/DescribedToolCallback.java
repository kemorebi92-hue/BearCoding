package com.example.jingjingai2.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 只换工具说明、执行仍走原 callback。
 *
 * {@code @Tool description} 是编译期常量，关开关时必须保持旧文案；
 * 打开 general-purpose 时用这个包装把 spawnSubagent 的说明换成新派单规则，避免新旧规则并存。
 */
public final class DescribedToolCallback implements ToolCallback {

    private final ToolCallback inner;
    private final ToolDefinition def;

    public DescribedToolCallback(ToolCallback inner, String description) {
        this.inner = inner;
        ToolDefinition orig = inner.getToolDefinition();
        this.def = ToolDefinition.builder()
                .name(orig.name())
                .description(description)
                .inputSchema(orig.inputSchema())
                .build();
    }

    /** 按名字替换说明；找不到该名字则原样返回（不新建数组也行，但调用方更好处理）。 */
    public static ToolCallback[] relabel(ToolCallback[] cbs, String name, String description) {
        if (cbs == null || name == null) return cbs;
        ToolCallback[] out = new ToolCallback[cbs.length];
        for (int i = 0; i < cbs.length; i++) {
            ToolCallback cb = cbs[i];
            if (cb != null && cb.getToolDefinition() != null
                    && name.equals(cb.getToolDefinition().name())) {
                out[i] = new DescribedToolCallback(cb, description);
            } else {
                out[i] = cb;
            }
        }
        return out;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return def;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return inner.getToolMetadata();
    }

    @Override
    public String call(String arguments) {
        return inner.call(arguments);
    }

    @Override
    public String call(String arguments, ToolContext context) {
        return inner.call(arguments, context);
    }
}
