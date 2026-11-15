package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DescribedToolCallbackTest {

    @Test
    @DisplayName("只换说明、名字与调用仍走原工具")
    void relabelChangesDescriptionOnly() {
        AtomicInteger n = new AtomicInteger();
        ToolCallback[] cbs = ToolStubs.fullSet(null, null, null, n);
        ToolCallback[] out = DescribedToolCallback.relabel(cbs, "spawnSubagent", "新说明");

        ToolCallback spawn = null;
        for (ToolCallback cb : out) {
            if ("spawnSubagent".equals(cb.getToolDefinition().name())) spawn = cb;
        }
        assertNotNull(spawn);
        assertEquals("新说明", spawn.getToolDefinition().description());
        spawn.call("{}");
        assertEquals(1, n.get(), "执行必须落到原 callback");

        ToolCallback read = null;
        for (ToolCallback cb : out) {
            if ("readFile".equals(cb.getToolDefinition().name())) read = cb;
        }
        assertEquals("stub of readFile", read.getToolDefinition().description());
    }
}
