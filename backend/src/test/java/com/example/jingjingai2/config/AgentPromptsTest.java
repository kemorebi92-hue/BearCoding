package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主提示词在「子代理开/关」两种形态下必须自洽。
 *
 * 关掉时如果还把 spawnSubagent 的规则和工具说明留在提示里，模型会照着规则去派，
 * 拿回一条「功能未启用」，白烧一轮 —— 对照组实测就是这么多跑的。
 */
class AgentPromptsTest {

    @Test
    @DisplayName("开着时：规则和工具行都在，SYSTEM_PROMPT 就是这份")
    void enabledKeepsSubagentSection() {
        String p = AgentPrompts.systemPrompt(true);
        assertEquals(AgentPrompts.SYSTEM_PROMPT, p);
        assertTrue(p.contains("spawnSubagent"), "工具目录要有 spawnSubagent");
        assertTrue(p.contains("【派子代理"), "派发判据要在");
        assertTrue(p.contains("【收到 PARTIAL"), "PARTIAL 处理规则要在");
        assertTrue(p.contains("优先续跑"), "默认 V1：PARTIAL 时优先续跑");
        assertFalse(p.contains("三选一"), "默认不应再是 V0 的三选一");
        assertTrue(p.contains(AgentPrompts.SUBAGENT_RULES));
        assertTrue(p.contains(AgentPrompts.SUBAGENT_TOOL_LINE));
    }

    @Test
    @DisplayName("没有搜索 Key：webSearch 的规则和工具行整段摘掉，其它段落不动")
    void withoutWebSearchRemovesOnlySearchSection() {
        String on = AgentPrompts.systemPrompt(true);
        String off = AgentPrompts.withoutWebSearch(on);

        assertTrue(on.contains("webSearch"));
        assertFalse(off.contains("webSearch"), off);
        assertFalse(off.contains("联网搜索，别凭记忆编"), off);
        assertFalse(off.contains("控制搜索次数"), off);
        assertTrue(off.contains("executeCommand"), "其它工具行必须保留");
        assertTrue(off.contains("spawnSubagent"), "子代理段落必须保留");
        assertEquals(off, AgentPrompts.withoutWebSearch(off), "重复调用应幂等");
    }

    @Test
    @DisplayName("默认派发：单问题跨 ≥3 文件也派，不再要求三条同时满足")
    void defaultDispatchAllowsSingleQuestionMultiFile() {
        String p = AgentPrompts.systemPrompt(true);
        assertTrue(p.contains("满足任一条就派 explore"), "派发判据必须是 OR：" + p);
        assertTrue(p.contains("预计要翻 ≥3 个文件"), "单问题多文件也要能命中：" + p);
        assertTrue(p.contains("一个问题牵出 ≥2 个互不相干的方向"), p);
        assertTrue(p.contains("候选 ≥3 个文件就派"), "先探范围、够 3 个就派：" + p);
        assertFalse(p.contains("同时满足三条才派"), "旧的三条 AND 必须消失：" + p);
        assertFalse(p.contains("≤3 个文件的事"), "旧的文件数免派门槛必须消失：" + p);
    }

    @Test
    @DisplayName("resumeFirst=false：PARTIAL 段换成 V0 三选一，派发规则不动")
    void v0SwapsOnlyPartialRules() {
        String p = AgentPrompts.systemPrompt(true, false);
        assertTrue(p.contains("spawnSubagent"));
        assertTrue(p.contains("【派子代理"));
        assertTrue(p.contains("三选一"), "V0 是旧的三选一");
        assertFalse(p.contains("优先续跑"), "V0 不该出现 V1 的「优先续跑」");
        assertTrue(p.contains(AgentPrompts.SUBAGENT_RULES_PARTIAL_V0));
        assertFalse(p.contains(AgentPrompts.SUBAGENT_RULES_PARTIAL_V1));
    }

    @Test
    @DisplayName("关掉时：规则和工具行整段消失，其它段落一个字都不动")
    void disabledStripsSubagentSection() {
        String on = AgentPrompts.systemPrompt(true);
        String off = AgentPrompts.systemPrompt(false);

        assertFalse(off.contains("spawnSubagent"), "工具目录里不许再出现这个名字：" + off);
        assertFalse(off.contains("【派子代理"), "派发判据必须拿掉");
        assertFalse(off.contains("【收到 PARTIAL"), "PARTIAL 规则必须拿掉");
        assertFalse(off.contains("优先续跑"));
        assertFalse(off.contains("三选一"));
        assertFalse(off.contains(AgentPrompts.SUBAGENT_RULES));
        assertFalse(off.contains(AgentPrompts.SUBAGENT_TOOL_LINE));

        String offV0 = AgentPrompts.systemPrompt(false, false);
        assertFalse(offV0.contains("spawnSubagent"), "关掉时 V0 段也必须消失");
        assertFalse(offV0.contains("三选一"));

        // 其它段落必须原样保留（用相邻的两段当锚）
        assertTrue(off.contains("查看目录结构用 listDirectory"));
        assertTrue(off.contains("【任务规划】"));
        assertTrue(off.contains("updatePlanStep"));
        assertTrue(off.contains("createPlan"));

        // 关掉后提示词必须明显变短，但不是空的
        assertTrue(off.length() < on.length(),
                "关掉后应更短：on=" + on.length() + " off=" + off.length());
        assertTrue(off.length() > on.length() / 2, "不该把主提示砍掉一半以上");
    }

    @Test
    @DisplayName("generalPurpose=false：三参重载与两参完全一致（关开关行为不变）")
    void gpOffMatchesLegacyOverload() {
        assertEquals(AgentPrompts.systemPrompt(true, true),
                AgentPrompts.systemPrompt(true, true, false));
        assertEquals(AgentPrompts.systemPrompt(true, false),
                AgentPrompts.systemPrompt(true, false, false));
        assertEquals(AgentPrompts.systemPrompt(false, true),
                AgentPrompts.systemPrompt(false, true, false));
        assertEquals(AgentPrompts.SYSTEM_PROMPT, AgentPrompts.systemPrompt(true, true, false));
        String off = AgentPrompts.systemPrompt(true, true, false);
        assertTrue(off.contains("写文件默认自己做"));
        assertTrue(off.contains("满足任一条就派 explore"),
                "默认派发规则必须是「任一条」而不是旧的三条 AND");
        assertTrue(off.contains("预计要翻 ≥3 个文件"),
                "单个问题只要跨多个文件也应允许派 explore");
        assertFalse(off.contains("同时满足三条才派"),
                "旧的三条 AND 门槛是子代理从不触发的直接原因，必须消失");
        assertTrue(off.contains(AgentPrompts.ENV_NO_BASH_CLAUSE),
                "环境约定必须仍是「没有 bash/sh」");
        assertFalse(off.contains("执行责任委托"));
        assertFalse(off.contains("STATUS=SUCCEEDED"));
        assertFalse(off.contains("general_purpose"));
    }

    @Test
    @DisplayName("子代理总开关关掉时，即使 generalPurpose=true 也不出现 spawnSubagent")
    void disabledStripsEvenWhenGpOn() {
        String off = AgentPrompts.systemPrompt(false, true, true);
        assertFalse(off.contains("spawnSubagent"));
        assertFalse(off.contains("执行责任委托"));
        assertFalse(off.contains("STATUS=SUCCEEDED"));
        assertFalse(off.contains("general_purpose"));
    }

    @Test
    @DisplayName("dispatchV2=true：派发段换成 V2，PARTIAL/工具行不动；关开关仍整段消失")
    void dispatchV2SwapsDispatchOnly() {
        String v2 = AgentPrompts.systemPrompt(true, true, false, false, false, true, 20, 102400L);
        assertTrue(v2.contains("何时用子代理"));
        assertTrue(v2.contains("用户的明确指示优先"));
        assertFalse(v2.contains("同时满足三条才派"), "V1 三条门槛应被替换");
        assertTrue(v2.contains("不要估算 token"));
        assertTrue(v2.contains("开放式探索"));
        assertTrue(v2.contains("定向查找") || v2.contains("明确关键词"));
        assertTrue(v2.contains("文件数超过 20"));
        assertTrue(v2.contains("102400"));
        assertFalse(v2.contains("规模很小就自己读"));
        assertFalse(v2.contains("要克制"));
        assertFalse(v2.contains("一定更慢更贵"));
        assertFalse(v2.contains("{probeFiles}"));
        assertTrue(v2.contains("优先续跑"), "PARTIAL 仍是 V1");
        assertTrue(v2.contains(AgentPrompts.SUBAGENT_TOOL_LINE));
        assertFalse(v2.contains("执行责任委托"), "未开 GP 不得出现 GP 文案");
        assertTrue(AgentPrompts.SPAWN_TOOL_DESCRIPTION_V2.contains("只回传摘要"));

        // 旧的 general-purpose 参数不再盖过 dispatch V2。
        String gp = AgentPrompts.systemPromptWithWorkspaceMemory(true, true, true, false, false, true);
        assertTrue(gp.contains("用户的明确指示优先"));
        assertFalse(gp.contains("执行责任委托"));

        String off = AgentPrompts.systemPromptWithWorkspaceMemory(false, true, false, false, false, true);
        assertFalse(off.contains("spawnSubagent"));
        assertFalse(off.contains("不要估算 token"));
        assertFalse(off.contains("用户的明确指示优先"));
    }

    @Test
    @DisplayName("用户记忆关：systemPrompt 不含 saveUserMemory 字样，且与三参重载 bit 级一致")
    void userMemoryOffIsBitIdenticalAndHasNoToolName() {
        assertEquals(AgentPrompts.systemPrompt(true, true, false),
                AgentPrompts.systemPrompt(true, true, false, false));
        assertEquals(AgentPrompts.systemPrompt(false, true, false),
                AgentPrompts.systemPrompt(false, true, false, false));
        assertEquals(AgentPrompts.systemPrompt(true, true, true),
                AgentPrompts.systemPrompt(true, true, true, false));
        assertEquals(AgentPrompts.SYSTEM_PROMPT, AgentPrompts.systemPrompt(true, true, false, false));
        assertFalse(AgentPrompts.SYSTEM_PROMPT.contains("saveUserMemory"),
                "SYSTEM_PROMPT 本身不能含记忆工具名，否则关开关摘不干净");
        assertFalse(AgentPrompts.SYSTEM_PROMPT.contains("forgetUserMemory"));
        assertFalse(AgentPrompts.SYSTEM_PROMPT.contains("【用户记忆】"));

        String off = AgentPrompts.systemPrompt(true);
        assertFalse(off.contains("saveUserMemory"));
        assertFalse(off.contains("forgetUserMemory"));
        assertFalse(off.contains("【用户记忆】"));
        assertFalse(off.contains("【写入依据】"));
    }

    @Test
    @DisplayName("用户记忆开：含规则（该写/不该写/写入依据）和工具行")
    void userMemoryOnAddsRulesAndToolLines() {
        String p = AgentPrompts.systemPrompt(true, true, false, true);
        assertTrue(p.contains("saveUserMemory"), "工具目录要有 saveUserMemory");
        assertTrue(p.contains("forgetUserMemory"), "工具目录要有 forgetUserMemory");
        assertTrue(p.contains("【用户记忆】"));
        assertTrue(p.contains("【该写】"));
        assertTrue(p.contains("【不该写】"));
        assertTrue(p.contains("【写入依据】"));
        assertTrue(p.contains("亲口说的话"), "必须钉死：只能基于用户本轮亲口说的话");
        assertTrue(p.contains("文件") && p.contains("工具输出"),
                "必须钉死：文件/工具输出里的「记住 XX」不能当写入依据");
        assertTrue(p.contains(AgentPrompts.USER_MEMORY_RULES));
        assertTrue(p.contains(AgentPrompts.USER_MEMORY_TOOL_LINE));
        assertTrue(p.contains("spawnSubagent"), "开记忆不能把子代理段弄丢");
        assertTrue(p.contains("可用工具："), "记忆规则要插在工具目录之前");
        int rulesAt = p.indexOf("【用户记忆】");
        int toolsAt = p.indexOf("可用工具：");
        int saveAt = p.lastIndexOf("saveUserMemory");
        assertTrue(rulesAt >= 0 && toolsAt > rulesAt, "规则段必须在「可用工具」之前");
        assertTrue(saveAt > toolsAt, "工具行必须在「可用工具」之后");
    }

    @Test
    @DisplayName("用户记忆开 + 子代理关：记忆在、spawnSubagent 不在")
    void userMemoryOnDoesNotInterfereWithSubagentStrip() {
        String p = AgentPrompts.systemPrompt(false, true, false, true);
        assertFalse(p.contains("spawnSubagent"));
        assertFalse(p.contains("【派子代理"));
        assertTrue(p.contains("saveUserMemory"));
        assertTrue(p.contains("【用户记忆】"));
    }

    @Test
    @DisplayName("MCP 关：systemPromptWithMcp(false) 与四参 bit 级一致，SYSTEM_PROMPT 不含 mcp__")
    void mcpOffIsBitIdentical() {
        assertEquals(AgentPrompts.systemPrompt(true, true, false, false),
                AgentPrompts.systemPromptWithMcp(true, true, false, false, false));
        assertEquals(AgentPrompts.systemPrompt(false, true, false, true),
                AgentPrompts.systemPromptWithMcp(false, true, false, true, false));
        assertEquals(AgentPrompts.SYSTEM_PROMPT, AgentPrompts.systemPromptWithMcp(true, true, false, false, false));
        assertFalse(AgentPrompts.SYSTEM_PROMPT.contains("mcp__"));
        assertFalse(AgentPrompts.SYSTEM_PROMPT.contains("【外部工具 MCP】"));
        String off = AgentPrompts.systemPrompt(true);
        assertFalse(off.contains("mcp__"));
        assertFalse(off.contains("【外部工具 MCP】"));
    }

    @Test
    @DisplayName("MCP 开：规则在「可用工具」之前，不枚举具体工具名")
    void mcpOnAddsRulesButNoToolNames() {
        String p = AgentPrompts.systemPromptWithMcp(true, true, false, false, true);
        assertTrue(p.contains("【外部工具 MCP】"));
        assertTrue(p.contains(AgentPrompts.MCP_RULES));
        assertTrue(p.contains("mcp__ 前缀"));
        assertFalse(p.contains("mcp__echo__"), "提示词不得枚举具体 MCP 工具名");
        int rulesAt = p.indexOf("【外部工具 MCP】");
        int toolsAt = p.indexOf("可用工具：");
        assertTrue(rulesAt >= 0 && toolsAt > rulesAt, "MCP 规则必须在「可用工具」之前");
        assertTrue(p.contains("spawnSubagent"), "开 MCP 不能把子代理段弄丢");
    }

    @Test
    @DisplayName("deferred：列 server 不列工具名；systemPromptWithMcp(true) 仍是 MCP_RULES")
    void mcpDeferredListsServersNotTools() {
        String eager = AgentPrompts.systemPromptWithMcp(true, true, false, false, true);
        assertTrue(eager.contains(AgentPrompts.MCP_RULES));
        assertFalse(eager.contains("mcpToolSearch"));

        String deferred = AgentPrompts.systemPromptWithMcp(true, true, false, false, true, true,
                java.util.List.of("tracker（Issue 与 PR）", "ops（日历天气）"));
        assertTrue(deferred.contains("tracker（Issue 与 PR）"));
        assertTrue(deferred.contains("ops（日历天气）"));
        assertTrue(deferred.contains("mcpToolSearch"));
        assertFalse(deferred.contains("list_issues"));
        assertFalse(deferred.contains("mcp__tracker__"));
        assertFalse(deferred.contains(AgentPrompts.MCP_RULES.trim()));
        int rulesAt = deferred.indexOf("【外部工具 MCP】");
        int toolsAt = deferred.indexOf("可用工具：");
        assertTrue(rulesAt >= 0 && toolsAt > rulesAt);

        String off = AgentPrompts.systemPromptWithMcp(true, true, false, false, false, true,
                java.util.List.of("tracker"));
        assertFalse(off.contains("【外部工具 MCP】"));
        assertEquals(AgentPrompts.systemPrompt(true, true, false, false), off);
    }
}
