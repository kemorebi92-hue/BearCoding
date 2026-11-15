package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpActivationTrackerTest {

    @Test
    @DisplayName("ACTIVATED 行按首次出现顺序收集")
    void activatedLineFirstSeen() {
        List<Message> msgs = List.of(tool("mcpToolSearch",
                "ACTIVATED: mcp__tracker__list_issues, mcp__ops__get_weather\nhits"));
        assertEquals(List.of("mcp__tracker__list_issues", "mcp__ops__get_weather"),
                McpActivationTracker.activated(msgs, 8));
    }

    @Test
    @DisplayName("成功调用过的 mcp__ 也算激活；ERROR_PREFIX 和守卫拒绝不算")
    void successfulCallsOnly() {
        List<Message> msgs = List.of(
                tool("mcp__tracker__create_pr", "[T-create_pr-OK] created"),
                tool("mcp__ops__get_weather", McpToolCallback.ERROR_PREFIX + "timeout"),
                tool("mcp__ghost__x", "工具 mcp__ghost__x 本轮不可用（它不在本轮下发的工具清单里），已拒绝执行。")
        );
        assertEquals(List.of("mcp__tracker__create_pr"), McpActivationTracker.activated(msgs, 8));
    }

    @Test
    @DisplayName("没有 ACTIVATED 行的成功调用超出上限：保留最近的")
    void capKeepsMostRecentCalls() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            msgs.add(tool("mcp__t__t" + i, "[T-t" + i + "-OK]"));
        }
        List<String> got = McpActivationTracker.activated(msgs, 8);
        assertEquals(8, got.size());
        assertEquals("mcp__t__t10", got.get(0));
        assertEquals("mcp__t__t3", got.get(7));
        assertFalse(got.contains("mcp__t__t1"));
        assertFalse(got.contains("mcp__t__t2"));
    }

    @Test
    @DisplayName("两组搜索各 5 个、上限 8：两组的首选都留住")
    void capKeepsHeadOfEachSearch() {
        List<Message> msgs = List.of(tools(
                "mcpToolSearch", "ACTIVATED: mcp__git__git_status, mcp__git__git_branch, "
                        + "mcp__git__git_diff_unstaged, mcp__git__git_diff_staged, mcp__git__git_diff\n",
                "mcpToolSearch", "ACTIVATED: mcp__filesystem__read_file, mcp__filesystem__read_text_file, "
                        + "mcp__filesystem__read_media_file, mcp__filesystem__read_multiple_files, "
                        + "mcp__filesystem2__read_file\n"));
        List<String> got = McpActivationTracker.activated(msgs, 8);
        assertEquals(8, got.size());
        assertEquals("mcp__git__git_status", got.get(0));
        assertEquals("mcp__filesystem__read_file", got.get(1));
        assertFalse(got.contains("mcp__git__git_branch"));
        assertFalse(got.contains("mcp__git__git_diff_unstaged"));
        assertTrue(got.contains("mcp__filesystem2__read_file"));
    }

    @Test
    @DisplayName("单条搜索超过上限：只留最靠前的，总数不超上限")
    void singleSearchRespectsCap() {
        List<Message> msgs = List.of(tool("mcpToolSearch",
                "ACTIVATED: mcp__t__a, mcp__t__b, mcp__t__c\nhits"));
        List<String> got = McpActivationTracker.activated(msgs, 2);
        assertEquals(List.of("mcp__t__a", "mcp__t__c"), got);
    }

    @Test
    @DisplayName("未超上限时全部保留，顺序不变")
    void underCapKeepsFirstSeenOrder() {
        List<Message> msgs = List.of(tools(
                "mcpToolSearch", "ACTIVATED: mcp__t__a, mcp__t__b\n",
                "mcpToolSearch", "ACTIVATED: mcp__t__c\n"));
        assertEquals(List.of("mcp__t__a", "mcp__t__b", "mcp__t__c"),
                McpActivationTracker.activated(msgs, 8));
    }

    @Test
    @DisplayName("重复出现不占名额，再次 select 也不把首选挤出去")
    void duplicatesDoNotConsumeCap() {
        List<Message> msgs = List.of(tools(
                "mcpToolSearch", "ACTIVATED: mcp__t__a, mcp__t__b, mcp__t__c\n",
                "mcpToolSearch", "ACTIVATED: mcp__t__a, mcp__t__d\n",
                "mcpToolSearch", "ACTIVATED: mcp__t__a\n"));
        List<String> got = McpActivationTracker.activated(msgs, 3);
        assertEquals(List.of("mcp__t__a", "mcp__t__d", "mcp__t__c"), got);
        assertEquals(3, new java.util.HashSet<>(got).size());
    }

    @Test
    @DisplayName("不同服务的同名工具按完整名字区分")
    void sameToolDifferentServer() {
        List<Message> msgs = List.of(tool("mcpToolSearch",
                "ACTIVATED: mcp__filesystem__read_file, mcp__filesystem2__read_file\n"));
        assertEquals(List.of("mcp__filesystem__read_file", "mcp__filesystem2__read_file"),
                McpActivationTracker.activated(msgs, 8));
    }

    @Test
    @DisplayName("压缩把搜索结果摘掉 → 激活集变空")
    void compactionDropsSearch() {
        List<Message> withSearch = List.of(
                new UserMessage("查天气"),
                tool("mcpToolSearch", "ACTIVATED: mcp__ops__get_weather\nweather tool")
        );
        assertEquals(List.of("mcp__ops__get_weather"), McpActivationTracker.activated(withSearch, 8));

        List<Message> compacted = List.of(
                new SystemMessage("[历史摘要] 用户问了北京天气，未保留工具原文。"),
                new UserMessage("查天气")
        );
        assertTrue(McpActivationTracker.activated(compacted, 8).isEmpty());
    }

    @Test
    @DisplayName("null / 空消息不抛；maxActive≤0 回落 8")
    void nullSafe() {
        assertTrue(McpActivationTracker.activated(null, 8).isEmpty());
        assertTrue(McpActivationTracker.activated(List.of(), 0).isEmpty());
    }

    private static ToolResponseMessage tool(String name, String data) {
        return tools(name, data);
    }
    private static ToolResponseMessage tools(String... nameAndData) {
        List<ToolResponseMessage.ToolResponse> rs = new ArrayList<>();
        for (int i = 0; i < nameAndData.length; i += 2) {
            rs.add(new ToolResponseMessage.ToolResponse(
                    "id-" + i, nameAndData[i], nameAndData[i + 1]));
        }
        return new ToolResponseMessage(rs);
    }
}
