package com.example.jingjingai2.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2C-4 定向测试：验证落地的 C 提示词包含任务要求的全部保留条款。
 * 仅断言字符串内容，不调模型。
 */
class SummaryPromptContentTest {

    @Test
    void landedPromptIsCWithRequiredClauses() {
        String p = ContextCompactor.SUMMARY_PROMPT;
        assertTrue(p.contains("300～400字"), "C 方案长度区间");
        assertTrue(p.contains("核心诉求"), "用户核心目标");
        assertTrue(p.contains("约束"), "用户约束");
        assertTrue(p.contains("计划状态"), "计划状态/进度");
        assertTrue(p.contains("关键事实"), "已确认关键事实");
        assertTrue(p.contains("数值"), "具体数据值");
        assertTrue(p.contains("文件名"), "文件名");
        assertTrue(p.contains("行号"), "必要行号");
        assertTrue(p.contains("工具核验"), "工具核验结果");
        assertTrue(p.contains("决策"), "重要决策");
        assertTrue(p.contains("未完成"), "未完成事项");
        assertTrue(p.contains("只输出摘要本身"), "只输出摘要，不带解释");
    }
}
