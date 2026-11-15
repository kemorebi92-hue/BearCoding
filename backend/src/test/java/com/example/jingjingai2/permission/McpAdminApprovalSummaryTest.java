package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpServerTemplateCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McpAdminApprovalSummary} 单测：摘要由可信模板生成，与最终保存配置同源。
 * 不连库、不拉进程、不经过审批链。
 */
class McpAdminApprovalSummaryTest {

    private final McpAdminApprovalSummary summary =
            new McpAdminApprovalSummary(new McpServerTemplateCatalog());

    @Test
    @DisplayName("playwright 模板：摘要含固定包名版本、关键启动参数、服务名，并声明仅保存不启动")
    void playwrightSummaryShowsTrustedConfig() {
        String args = "{\"templateId\":\"playwright\",\"serviceName\":\"my-pw\",\"params\":{}}";
        String s = summary.summaryFor("addMcpServerFromTemplate", args);

        assertNotNull(s);
        assertTrue(s.contains("仅保存"), s);
        assertTrue(s.contains("默认禁用"), s);
        assertTrue(s.contains("不启动进程"), s);
        assertTrue(s.contains("不自动授权"), s);
        assertTrue(s.contains("my-pw"), "服务名来自调用参数");
        assertTrue(s.contains("Playwright 浏览器（headless Edge）"), "模板显示名");
        assertTrue(s.contains("@playwright/mcp@0.0.83"), "固定包名与版本");
        assertTrue(s.contains("--isolated"), s);
        assertTrue(s.contains("msedge"), s);
        assertTrue(s.contains("npx"), "固定命令");
        assertFalse(s.contains("C:\\") && s.contains("Users"), "不得回显系统路径值");
    }

    @Test
    @DisplayName("摘要只列 env 键名，不列值")
    void summaryListsEnvKeysNotValues() {
        String args = "{\"templateId\":\"playwright\",\"serviceName\":\"my-pw\"}";
        String s = summary.summaryFor("addMcpServerFromTemplate", args);
        assertNotNull(s);
        // 键名出现；值（系统路径，含反斜杠或盘符）不出现
        if (s.contains("环境变量键")) {
            assertFalse(s.matches("(?s).*环境变量键：[^\\n]*[\\\\/].*"),
                    "env 值（路径）不得出现在摘要里：" + s);
        }
    }

    @Test
    @DisplayName("未知模板说明不会保存，不伪造有效配置摘要")
    void unknownTemplateReturnsNull() {
        String args = "{\"templateId\":\"nope\",\"serviceName\":\"x\"}";
        String s = summary.summaryFor("addMcpServerFromTemplate", args);
        assertNotNull(s);
        assertTrue(s.startsWith("不会保存："), s);
        assertTrue(s.contains("模板不存在"), s);
        assertFalse(summary.executable("addMcpServerFromTemplate", args));
    }

    @Test
    @DisplayName("缺 serviceName 或 templateId 说明不会保存")
    void missingFieldsReturnNull() {
        assertTrue(summary.summaryFor("addMcpServerFromTemplate", "{\"templateId\":\"playwright\"}").startsWith("不会保存："));
        assertTrue(summary.summaryFor("addMcpServerFromTemplate", "{\"serviceName\":\"x\"}").startsWith("不会保存："));
        assertTrue(summary.summaryFor("addMcpServerFromTemplate", "").startsWith("不会保存："));
        assertTrue(summary.summaryFor("addMcpServerFromTemplate", "not json").startsWith("不会保存："));
    }

    @Test
    @DisplayName("其他工具名返回 null：不影响现有审批卡")
    void otherToolsReturnNull() {
        assertNull(summary.summaryFor("executeCommand", "{\"command\":\"dir\"}"));
        assertNull(summary.summaryFor("listMcpServerTemplates", "{}"));
        assertNull(summary.summaryFor(null, "{}"));
    }

    @Test
    @DisplayName("摘要不采信模型自由文本：arguments 里没有 summary 字段也能生成")
    void summaryIgnoresModelFreeText() {
        // 模型即使塞一个 summary 字段，provider 也不读它，摘要仍来自模板
        String args = "{\"templateId\":\"playwright\",\"serviceName\":\"my-pw\","
                + "\"summary\":\"模型自己编的摘要，声称会启动服务\"}";
        String s = summary.summaryFor("addMcpServerFromTemplate", args);
        assertNotNull(s);
        assertFalse(s.contains("模型自己编的摘要"), "不得采用模型传入的自由文本");
        assertTrue(s.contains("不启动进程"), "摘要仍是后端模板生成的");
    }
}
