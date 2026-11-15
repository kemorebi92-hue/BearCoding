package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 运行时服务管理：添加、断开、删除、刷新，以及配置校验。不拉真实进程。 */
class McpServerAdminTest {

    private static McpClientManager managerWith(String yamlName) {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put(yamlName, spec);
        p.setServers(servers);
        return new McpClientManager(p) {
            @Override
            McpSession openSession(String serverName, McpServerSpec s) {
                return new FakeMcpSession(serverName, List.of(
                        new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}")));
            }
        };
    }

    private static McpServerSpec runtimeSpec() {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("npx");
        spec.setArgs(List.of("-y", "@modelcontextprotocol/server-everything"));
        return spec;
    }

    @Test
    @DisplayName("添加服务不清空已有服务，同名被拒绝")
    void addKeepsExistingAndRejectsDuplicate() {
        McpClientManager m = managerWith("yaml");
        m.start();
        assertEquals(1, m.callbacks().length);

        McpClientManager.McpServerChange added = m.addServer("extra", runtimeSpec(), true);
        assertTrue(added.configSaved, "配置已保存");
        assertTrue(added.connected, "连接已建立");
        assertEquals(1, added.toolCount, "发现到 1 个工具");
        assertEquals(2, m.callbacks().length, "原有服务还在");
        assertNotNull(m.findServer("yaml"));

        McpClientManager.McpServerChange dup = m.addServer("extra", runtimeSpec(), true);
        assertTrue(dup.rejected());
        assertEquals(2, m.callbacks().length, "拒绝时不重复添加");

        McpClientManager.McpServerChange dupYaml = m.addServer("yaml", runtimeSpec(), true);
        assertTrue(dupYaml.rejected());
        m.stop();
    }

    @Test
    @DisplayName("新服务的工具不带任何放行标记，断开后从清单和搜索目录消失")
    void disconnectDropsTools() {
        McpClientManager m = managerWith("yaml");
        m.start();
        m.addServer("extra", runtimeSpec(), true);
        assertTrue(namesOf(m.callbacks()).contains("mcp__extra__echo"));

        String found = new McpToolSearchCallback(m.callbacks(), m.nameMap(), 5)
                .call("{\"query\":\"select:mcp__extra__echo\"}");
        assertTrue(found.contains("mcp__extra__echo"), found);

        McpClientManager.McpServerChange off = m.disconnectServer("extra");
        assertTrue(off.configSaved, "断开后配置仍在");
        assertFalse(off.connected, "连接已断开");
        assertFalse(namesOf(m.callbacks()).contains("mcp__extra__echo"), "工具已从清单移除");
        assertNotNull(ToolGuard.rejectReason("mcp__extra__echo", namesOf(m.callbacks())));

        String after = new McpToolSearchCallback(m.callbacks(), m.nameMap(), 5)
                .call("{\"query\":\"select:mcp__extra__echo\"}");
        assertFalse(after.contains("mcp__extra__echo"), after);
        assertNotNull(m.findServer("yaml"), "别的服务不受影响");
        assertTrue(namesOf(m.callbacks()).contains("mcp__yaml__echo"));
        m.stop();
    }

    @Test
    @DisplayName("删除运行时服务会关闭会话；部署配置里的服务拒绝删除")
    void removeClosesSessionAndYamlIsProtected() {
        McpClientManager m = managerWith("yaml");
        m.start();
        m.addServer("extra", runtimeSpec(), true);
        FakeMcpSession extra = (FakeMcpSession) m.sessionOf("extra");

        McpClientManager.McpServerChange denied = m.removeServer("yaml");
        assertTrue(denied.rejected());
        assertNotNull(m.findServer("yaml"), "YAML 服务还在");

        McpClientManager.McpServerChange removed = m.removeServer("extra");
        assertTrue(removed.removed);
        assertNull(m.findServer("extra"));
        assertTrue(extra.isClosed(), "会话已关闭");
        assertFalse(namesOf(m.callbacks()).contains("mcp__extra__echo"));
        assertEquals(1, m.callbacks().length, "另一个服务不受影响");
        m.stop();
    }

    @Test
    @DisplayName("刷新工具后清单更新为服务当前返回的工具")
    void refreshUpdatesTools() {
        List<McpRemoteTool> tools = new ArrayList<>();
        tools.add(new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}"));
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setServers(new LinkedHashMap<>());
        FakeMcpSession fake = new FakeMcpSession("extra", tools);
        McpClientManager m = new McpClientManager(p) {
            @Override
            McpSession openSession(String serverName, McpServerSpec s) { return fake; }
        };
        m.addServer("extra", runtimeSpec(), true);
        assertEquals(1, m.callbacks().length);

        tools.add(new McpRemoteTool("add", "Add", "{\"type\":\"object\"}"));
        McpClientManager.McpServerChange refreshed = m.refreshServer("extra");
        assertEquals(2, refreshed.toolCount);
        assertTrue(namesOf(m.callbacks()).contains("mcp__extra__add"));
        m.stop();
    }

    @Test
    @DisplayName("命令白名单：只放行精确的 npx 和 uvx")
    void commandAllowList() {
        assertNull(McpServerConfigValidator.checkCommand("npx"));
        assertNull(McpServerConfigValidator.checkCommand("uvx"));
        assertNotNull(McpServerConfigValidator.checkCommand("python"));
        assertNotNull(McpServerConfigValidator.checkCommand("npx.cmd"));
        assertNotNull(McpServerConfigValidator.checkCommand("C:\\tools\\npx"));
        assertNotNull(McpServerConfigValidator.checkCommand("../npx"));
        assertNotNull(McpServerConfigValidator.checkCommand(" npx"));
        assertNotNull(McpServerConfigValidator.checkCommand("npx "));
        assertNotNull(McpServerConfigValidator.checkCommand(""));
    }

    @Test
    @DisplayName("参数和环境变量的数量与长度限制")
    void argsAndEnvLimits() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < McpServerConfigValidator.MAX_ARGS + 1; i++) tooMany.add("a");
        assertNotNull(McpServerConfigValidator.checkArgs(tooMany));
        assertNotNull(McpServerConfigValidator.checkArgs(List.of("x".repeat(McpServerConfigValidator.MAX_ARG_LEN + 1))));
        assertNull(McpServerConfigValidator.checkArgs(List.of("-y", "pkg")));

        Map<String, String> badKey = new LinkedHashMap<>();
        badKey.put("HAS SPACE", "v");
        assertNotNull(McpServerConfigValidator.checkEnv(badKey));
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put("API_KEY", "secret-value");
        ok.put("ProgramFiles(x86)", "C:\\Program Files (x86)");
        assertNull(McpServerConfigValidator.checkEnv(ok));
        Map<String, String> injected = new LinkedHashMap<>();
        injected.put("PATH=C:\\evil", "v");
        assertNotNull(McpServerConfigValidator.checkEnv(injected));
    }

    @Test
    @DisplayName("环境变量的值不出现在日志摘要里")
    void envValueNeverLogged() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("API_KEY", "super-secret-value");
        env.put("LANG", "zh");
        String summary = McpServerConfigValidator.envKeysForLog(env);
        assertFalse(summary.contains("super-secret-value"), summary);
        assertTrue(summary.contains("API_KEY"), summary);
        assertTrue(summary.contains("LANG"), summary);
    }

    @Test
    @DisplayName("完整配置校验：合法通过，命令不在白名单内拒绝")
    void fullConfigCheck() {
        McpServerConfig ok = config("echo", "npx");
        assertNull(McpServerConfigValidator.check(ok));
        McpServerSpec spec = McpServerConfigValidator.toSpec(ok);
        assertEquals("npx", spec.getCommand());
        assertEquals(List.of("-y"), spec.getArgs());

        McpServerConfig bad = config("echo", "python");
        assertNotNull(McpServerConfigValidator.check(bad));
        McpServerConfig badName = config("Has_Upper", "npx");
        assertNotNull(McpServerConfigValidator.check(badName));
    }

    private static McpServerConfig config(String name, String command) {
        McpServerConfig c = new McpServerConfig();
        c.setName(name);
        c.setCommand(command);
        c.setArgsJson("[\"-y\"]");
        c.setEnvJson("{\"LANG\":\"zh\"}");
        c.setTrust("untrusted");
        return c;
    }

    private static List<String> namesOf(ToolCallback[] cbs) {
        List<String> out = new ArrayList<>();
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null) out.add(cb.getToolDefinition().name());
        }
        return out;
    }
}
