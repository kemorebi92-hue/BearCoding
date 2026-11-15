package com.example.jingjingai2.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮数用尽的补记只在「没有任何其他结束原因」时发生。
 * 判定是纯函数，两个循环共用，这里不启动 Spring、不连数据库。
 */
class IterationCapReasonTest {

    @Test
    @DisplayName("非流式：只有轮数用尽才记 MAX_ITERATIONS")
    void nonStreamOnlyOnCap() {
        assertTrue(ChatController.iterationCapReached(false, false, false, false, false, false));
    }

    @Test
    @DisplayName("流式：空响应不算轮数用尽")
    void streamEmptyResponseIsNotCap() {
        assertFalse(ChatController.iterationCapReached(false, false, false, true, false, false));
    }

    @Test
    @DisplayName("正常完成、取消、打转、输入预算、计划收尾都不记")
    void otherExitsAreNotCap() {
        assertFalse(ChatController.iterationCapReached(true, false, false, false, false, false), "自然收尾");
        assertFalse(ChatController.iterationCapReached(false, true, false, false, false, false), "用户取消");
        assertFalse(ChatController.iterationCapReached(false, false, true, false, false, false), "原地打转");
        assertFalse(ChatController.iterationCapReached(false, false, false, false, true, false), "输入预算");
        assertFalse(ChatController.iterationCapReached(true, false, false, false, false, true), "计划收尾");
    }
}
