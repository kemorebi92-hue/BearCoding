package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.ToolCallLog;
import com.example.jingjingai2.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallLogServiceTest {

    @Test
    @DisplayName("写入的行带有调用方传入的 turn_index")
    void logCarriesTurnIndex() {
        ToolCallLog[] saved = new ToolCallLog[1];
        ToolCallLogMapper mapper = log -> saved[0] = log;
        new ToolCallLogService(mapper).log(7L, "conv-1", "readFile",
                "{\"path\":\"a.txt\"}", "ok", true, 12L, false, true, 3);
        assertNotNull(saved[0]);
        assertEquals(3, saved[0].getTurnIndex());
        assertEquals("readFile", saved[0].getToolName());
        assertEquals("conv-1", saved[0].getConversationId());
    }

    @Test
    @DisplayName("没有轮次时 turn_index 为 null，不写成 0")
    void missingTurnIndexStaysNull() {
        ToolCallLog[] saved = new ToolCallLog[1];
        ToolCallLogMapper mapper = log -> saved[0] = log;
        new ToolCallLogService(mapper).log(7L, "conv-1", "readFile",
                "{}", "ok", true, 1L, false, true, null);
        assertNull(saved[0].getTurnIndex());
    }
}
