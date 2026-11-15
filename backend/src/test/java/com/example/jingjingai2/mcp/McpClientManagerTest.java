package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpClientManagerTest {

    @Test
    @DisplayName("默认关：start 不拉进程，callbacks 为空")
    void disabledIsNoop() {
        McpProperties p = new McpProperties();
        assertFalse(p.isEnabled());
        McpClientManager m = new McpClientManager(p);
        m.start();
        assertEquals(0, m.callbacks().length);
        assertFalse(m.isEnabled());
    }

    @Test
    @DisplayName("enabled-tools 非空也不再收窄：白名单外的工具照样暴露，只打一次废弃警告")
    void enabledToolsNoLongerNarrow() {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        System.setErr(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            McpProperties p = new McpProperties();
            p.setEnabled(true);
            McpServerSpec spec = new McpServerSpec();
            spec.setCommand("java");
            spec.setEnabledTools(List.of("echo"));   // 旧白名单：故意漏掉 add
            Map<String, McpServerSpec> servers = new LinkedHashMap<>();
            servers.put("echo", spec);
            p.setServers(servers);

            FakeMcpSession fake = new FakeMcpSession("echo", List.of(
                    new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}"),
                    new McpRemoteTool("add", "Add", "{\"type\":\"object\"}")));
            McpClientManager m = new McpClientManager(p) {
                @Override
                McpSession openSession(String serverName, McpServerSpec s) {
                    return fake;
                }
            };
            m.start();   // 旧配置不得导致启动失败
            ToolCallback[] cbs = m.callbacks();
            assertEquals(2, cbs.length, "enabled-tools 不再收窄，add 也应暴露");
            assertTrue(namesOf(cbs).contains("mcp__echo__echo"), namesOf(cbs).toString());
            assertTrue(namesOf(cbs).contains("mcp__echo__add"), namesOf(cbs).toString());

            String err = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(err.contains("enabled-tools 已废弃"), err);
            assertTrue(err.contains("disabled-tools"), err);
            assertEquals(1, occurrences(err, "enabled-tools 已废弃"), "废弃警告只应打一次：" + err);
            assertFalse(err.contains("不存在"), "不再按旧名单判断工具是否存在：" + err);
            m.stop();
            assertTrue(fake.isClosed());
        } finally {
            System.setErr(old);
        }
    }

    @Test
    @DisplayName("disabled-tools 仍然排除：不下发、面板可见但 exposed=false、搜不到、执行层拒绝")
    void disabledToolsFiltered() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        spec.setDescription("echo 测试");
        spec.setDisabledTools(List.of("add"));
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("echo", spec);
        p.setServers(servers);

        FakeMcpSession fake = new FakeMcpSession("echo", List.of(
                new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}"),
                new McpRemoteTool("add", "Add", "{\"type\":\"object\"}")));
        McpClientManager m = new McpClientManager(p) {
            @Override
            McpSession openSession(String serverName, McpServerSpec s) {
                return fake;
            }
        };
        m.start();
        ToolCallback[] cbs = m.callbacks();
        assertEquals(1, cbs.length);
        assertEquals("mcp__echo__echo", cbs[0].getToolDefinition().name());

        // 被发现但标记为不暴露：面板要能列出它，只是不能改
        McpListedTool add = m.findListed("echo", "add");
        assertNotNull(add);
        assertFalse(add.exposed);
        assertTrue(m.findListed("echo", "echo").exposed);

        // 不在搜索目录里
        String found = new McpToolSearchCallback(m.callbacks(), m.nameMap(), 5)
                .call("{\"query\":\"add\"}");
        assertFalse(found.contains("mcp__echo__add"), found);

        // 模型直接喊名字：执行层白名单拒绝
        assertNotNull(ToolGuard.rejectReason("mcp__echo__add", namesOf(cbs)));

        assertEquals(List.of("echo（echo 测试）"), m.serverLines());
        m.stop();
    }

    @Test
    @DisplayName("连接会改用数据库里的新环境变量，相同配置再连一次不拆进程")
    void connectUsesDatabaseSpecAndLeavesUnchangedSession() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        McpServerConfig row = new McpServerConfig();
        row.setId(3L);
        row.setName("headed");
        row.setCommand("npx");
        row.setArgsJson("[\"-y\",\"pkg\"]");
        row.setEnvJson("{\"FOO\":\"old\"}");
        row.setTrust("untrusted");
        row.setTransport("stdio");
        row.setAuthType("none");
        row.setCreatedBy(1L);
        row.setEnabled(true);
        McpServerConfigMapper mapper = (McpServerConfigMapper) Proxy.newProxyInstance(
                McpServerConfigMapper.class.getClassLoader(),
                new Class<?>[] {McpServerConfigMapper.class},
                (proxy, method, args) -> {
                    if ("findByOwnerAndName".equals(method.getName())) return row;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        List<Map<String, String>> seen = new ArrayList<>();
        McpClientManager m = new McpClientManager(p) {
            @Override
            McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
                seen.add(new LinkedHashMap<>(spec.getEnv()));
                return new FakeMcpSession(serverName, List.of(
                        new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}")));
            }
        };
        m.setServerConfigMapper(mapper);
        McpServerSpec initial = new McpServerSpec();
        initial.setCommand("npx");
        initial.setArgs(List.of("-y", "pkg"));
        initial.setEnv(new LinkedHashMap<>(Map.of("FOO", "old")));
        assertFalse(m.addServer(1L, "headed", initial, true).rejected());
        assertEquals(1, seen.size());
        assertEquals("old", seen.get(0).get("FOO"));

        row.setEnvJson("{\"FOO\":\"from-db\",\"ProgramFiles(x86)\":\"C:\\\\Program Files (x86)\"}");
        assertFalse(m.connectServer(1L, "headed").rejected());
        assertEquals(2, seen.size(), "配置变了必须拆掉旧进程再连");
        assertEquals("from-db", seen.get(1).get("FOO"));
        assertEquals("C:\\Program Files (x86)", seen.get(1).get("ProgramFiles(x86)"));

        assertFalse(m.connectServer(1L, "headed").rejected());
        assertEquals(2, seen.size(), "配置没变不能再拆进程");
        m.stop();
    }

    private static List<String> namesOf(ToolCallback[] cbs) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (cbs == null) return out;
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null) out.add(cb.getToolDefinition().name());
        }
        return out;
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
