package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行层工具白名单。
 *
 * 背景（真实漏洞，H4 探针实测）：{@code stripWriteTools} 只把写类工具从"下发给模型的清单"里摘掉，
 * 执行时却仍用全量 callbacks 派发 —— 模型凭上下文记忆喊出已被摘掉的 `executeCommand`，照样被执行。
 * 这个守卫是补在**执行前**的那道闸：不在本轮清单里，就不执行。
 */
class ToolGuardTest {

    private static Set<String> tools(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    @Test
    @DisplayName("清单内的工具：放行（返回 null）")
    void allowListed() {
        Set<String> allowed = tools("readFile", "createPlan", "executeCommand");
        assertNull(ToolGuard.rejectReason("readFile", allowed));
        assertNull(ToolGuard.rejectReason("createPlan", allowed));
        assertNull(ToolGuard.rejectReason("executeCommand", allowed));
    }

    @Test
    @DisplayName("清单外的工具：拒绝，且说明里要带工具名 + 可用清单（模型能据此自我纠正）")
    void rejectUnlisted() {
        String reason = ToolGuard.rejectReason("writeFile", tools("readFile", "createPlan"));
        assertNotNull(reason);
        assertTrue(reason.contains("writeFile"), "要点名是哪个工具");
        assertTrue(reason.contains("本轮不可用"), "要说清'本轮'而不是'不存在'");
        assertTrue(reason.contains("readFile") && reason.contains("createPlan"), "要给出可用清单");
    }

    @Test
    @DisplayName("stripWriteTools 场景：被摘掉的写类工具/命令一律拒绝，规划与只读工具仍放行")
    void stripScenario() {
        // 摘除后本轮真正下发的清单
        Set<String> afterStrip = tools("readFile", "listDirectory", "globFile", "grepFile",
                "webSearch", "createPlan", "updatePlanStep",
                "saveUserMemory", "forgetUserMemory");
        for (String writeTool : new String[]{"createFile", "editFile", "writeFile", "deletePath",
                "movePath", "copyPath", "appendFile", "createDirectory"}) {
            assertNotNull(ToolGuard.rejectReason(writeTool, afterStrip),
                    writeTool + " 被摘掉了，绝不能还能执行（这正是 H4 暴露的漏洞）");
        }
        assertNotNull(ToolGuard.rejectReason("executeCommand", afterStrip),
                "executeCommand 同样被摘掉，必须拒绝");
        assertNull(ToolGuard.rejectReason("createPlan", afterStrip), "逃生舱的目的就是逼它先规划");
        assertNull(ToolGuard.rejectReason("readFile", afterStrip), "只读工具要留着");
        assertNull(ToolGuard.rejectReason("saveUserMemory", afterStrip),
                "HARD 摘写工具时记忆工具仍放行：记偏好不是写代码");
        assertNull(ToolGuard.rejectReason("forgetUserMemory", afterStrip));
    }

    @Test
    @DisplayName("模型幻觉出一个未下发的 mcp__ 名：拒绝，即使名字长得像真的")
    void rejectHallucinatedMcp() {
        Set<String> allowed = tools("readFile", "mcp__echo__echo");
        assertNull(ToolGuard.rejectReason("mcp__echo__echo", allowed));
        String reason = ToolGuard.rejectReason("mcp__github__create_issue", allowed);
        assertNotNull(reason);
        assertTrue(reason.contains("mcp__github__create_issue"));
        assertTrue(reason.contains("本轮不可用"));
        assertTrue(reason.contains("mcpToolSearch"), "未激活的 mcp__ 应提示先搜索");
    }

    @Test
    @DisplayName("污点：写类内置工具即使在清单里也拒绝，给出注入防护文案")
    void taintRejectsDangerousBuiltins() {
        java.util.Set<String> allowed = tools("readFile", "executeCommand", "writeFile",
                "mcp__playwright__browser_snapshot", "createPlan");
        assertNull(ToolGuard.rejectReason("readFile", allowed, true));
        assertNull(ToolGuard.rejectReason("mcp__playwright__browser_snapshot", allowed, true));
        assertNull(ToolGuard.rejectReason("createPlan", allowed, true));
        String r = ToolGuard.rejectReason("executeCommand", allowed, true);
        assertNotNull(r);
        assertTrue(r.contains("本会话已读取外部不可信内容"), r);
        assertTrue(r.contains("暂不允许执行命令/写文件"), r);
        assertNotNull(ToolGuard.rejectReason("writeFile", allowed, true));
        assertNull(ToolGuard.rejectReason("executeCommand", allowed, false));
    }

    @Test
    @DisplayName("边界：工具名为空 / 清单为空 / 清单为 null，都不抛异常且一律拒绝")
    void boundaries() {
        assertNotNull(ToolGuard.rejectReason(null, tools("readFile")));
        assertNotNull(ToolGuard.rejectReason("", tools("readFile")));
        assertNotNull(ToolGuard.rejectReason("readFile", tools()));
        assertNotNull(ToolGuard.rejectReason("readFile", null));
        assertTrue(ToolGuard.rejectReason("readFile", null).contains("(无)"));
    }
}
