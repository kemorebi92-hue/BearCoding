package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpHistoryPrunerTest {

    private static Map<String, McpServerSpec> playwrightServers() {
        McpServerSpec spec = new McpServerSpec();
        spec.setHistoryPruneTools(List.of("browser_snapshot", "browser_navigate", "browser_click"));
        spec.setHistoryPruneMinChars(500);
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("playwright", spec);
        return servers;
    }

    private static ToolResponseMessage tool(String id, String name, String data) {
        return new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(id, name, data)));
    }

    @Test
    @DisplayName("只有一条页面结果：不替换")
    void singleUnchanged() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("open"));
        String body = "x".repeat(800);
        msgs.add(tool("1", "mcp__playwright__browser_snapshot", body));
        McpHistoryPruner.pruneInPlace(msgs, playwrightServers());
        ToolResponseMessage trm = (ToolResponseMessage) msgs.get(1);
        assertEquals(body, trm.getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("多条同类：只留最后一份全文，更早的换成省略标记")
    void manyKeepLast() {
        List<Message> msgs = new ArrayList<>();
        String a = "A".repeat(800);
        String b = "B".repeat(900);
        String c = "C".repeat(1000);
        msgs.add(tool("1", "mcp__playwright__browser_snapshot", a));
        msgs.add(tool("2", "mcp__playwright__browser_snapshot", b));
        msgs.add(tool("3", "mcp__playwright__browser_snapshot", c));
        McpHistoryPruner.pruneInPlace(msgs, playwrightServers());
        String r0 = ((ToolResponseMessage) msgs.get(0)).getResponses().get(0).responseData();
        String r1 = ((ToolResponseMessage) msgs.get(1)).getResponses().get(0).responseData();
        String r2 = ((ToolResponseMessage) msgs.get(2)).getResponses().get(0).responseData();
        assertEquals(McpHistoryPruner.omitMarker("mcp__playwright__browser_snapshot", 800), r0);
        assertEquals(McpHistoryPruner.omitMarker("mcp__playwright__browser_snapshot", 900), r1);
        assertEquals(c, r2);
    }

    @Test
    @DisplayName("短于 min-chars 的不替换")
    void smallNotReplaced() {
        List<Message> msgs = new ArrayList<>();
        String small = "nav-ok"; // << 500
        String big1 = "P".repeat(600);
        String big2 = "Q".repeat(700);
        msgs.add(tool("1", "mcp__playwright__browser_navigate", small));
        msgs.add(tool("2", "mcp__playwright__browser_snapshot", big1));
        msgs.add(tool("3", "mcp__playwright__browser_snapshot", big2));
        McpHistoryPruner.pruneInPlace(msgs, playwrightServers());
        assertEquals(small, ((ToolResponseMessage) msgs.get(0)).getResponses().get(0).responseData());
        assertTrue(McpHistoryPruner.isOmitMarker(
                ((ToolResponseMessage) msgs.get(1)).getResponses().get(0).responseData()));
        assertEquals(big2, ((ToolResponseMessage) msgs.get(2)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("未列入 history-prune-tools 的工具不动")
    void nonListedUntouched() {
        List<Message> msgs = new ArrayList<>();
        String echo1 = "E".repeat(800);
        String echo2 = "F".repeat(800);
        msgs.add(tool("1", "mcp__echo__echo", echo1));
        msgs.add(tool("2", "mcp__echo__echo", echo2));
        McpHistoryPruner.pruneInPlace(msgs, playwrightServers());
        assertEquals(echo1, ((ToolResponseMessage) msgs.get(0)).getResponses().get(0).responseData());
        assertEquals(echo2, ((ToolResponseMessage) msgs.get(1)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("只改 tool 正文，id/name 仍配对；assistant 消息还在")
    void pairingRemainsLegal() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new AssistantMessage("calling"));
        msgs.add(tool("call-1", "mcp__playwright__browser_snapshot", "X".repeat(600)));
        msgs.add(tool("call-2", "mcp__playwright__browser_click", "Y".repeat(600)));
        McpHistoryPruner.pruneInPlace(msgs, playwrightServers());
        assertTrue(msgs.get(0) instanceof AssistantMessage);
        ToolResponseMessage.ToolResponse r0 = ((ToolResponseMessage) msgs.get(1)).getResponses().get(0);
        ToolResponseMessage.ToolResponse r1 = ((ToolResponseMessage) msgs.get(2)).getResponses().get(0);
        assertEquals("call-1", r0.id());
        assertEquals("mcp__playwright__browser_snapshot", r0.name());
        assertEquals("call-2", r1.id());
        assertEquals("Y".repeat(600), r1.responseData());
        assertTrue(McpHistoryPruner.isOmitMarker(r0.responseData()));
    }

    @Test
    @DisplayName("history-prune-tools 为空：什么都不做")
    void emptyConfigNoop() {
        McpServerSpec spec = new McpServerSpec();
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("playwright", spec);
        List<Message> msgs = new ArrayList<>();
        String a = "Z".repeat(800);
        String b = "W".repeat(800);
        msgs.add(tool("1", "mcp__playwright__browser_snapshot", a));
        msgs.add(tool("2", "mcp__playwright__browser_snapshot", b));
        McpHistoryPruner.pruneInPlace(msgs, servers);
        assertEquals(a, ((ToolResponseMessage) msgs.get(0)).getResponses().get(0).responseData());
    }
}
