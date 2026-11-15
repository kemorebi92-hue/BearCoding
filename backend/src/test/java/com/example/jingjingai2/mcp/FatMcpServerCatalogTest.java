package com.example.jingjingai2.mcp;

import com.example.jingjingai2.mcp.testdouble.FatMcpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FatMcpServerCatalogTest {

    @Test
    @DisplayName("tracker 15 + ops 15 = 30，名字不重复，调用带 [T-*-OK]")
    void thirtyUniqueToolsWithMarkers() {
        List<FatMcpServer.ToolSpec> tracker = FatMcpServer.tracker();
        List<FatMcpServer.ToolSpec> ops = FatMcpServer.ops();
        assertEquals(15, tracker.size());
        assertEquals(15, ops.size());
        Set<String> names = new HashSet<>();
        for (FatMcpServer.ToolSpec t : tracker) {
            assertTrue(names.add(t.name), "tracker 重名 " + t.name);
            assertTrue(t.description.length() > 20);
            assertTrue(t.schemaJson.contains("\"type\":\"object\""));
        }
        for (FatMcpServer.ToolSpec t : ops) {
            assertTrue(names.add(t.name), "ops 重名 " + t.name);
        }
        assertEquals(30, names.size());

        String listed = FatMcpServer.call("list_issues", null);
        assertTrue(listed.contains("[T-list_issues-OK]"));
        com.fasterxml.jackson.databind.node.ObjectNode args =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        args.put("title", "deferred-eval");
        args.put("head", "feature-mcp-deferred");
        String pr = FatMcpServer.call("create_pr", args);
        assertTrue(pr.contains("[T-create_pr-OK]"));
        assertTrue(pr.contains("deferred-eval"));
        assertTrue(FatMcpServer.call("get_weather", null).contains("[T-get_weather-OK]"));
        assertTrue(FatMcpServer.call("list_calendar", null).contains("[T-list_calendar-OK]"));
    }
}
