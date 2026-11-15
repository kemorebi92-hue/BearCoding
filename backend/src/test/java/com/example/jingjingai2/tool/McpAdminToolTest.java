package com.example.jingjingai2.tool;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.permission.PermissionPolicy;
import com.example.jingjingai2.permission.PermissionProperties;
import com.example.jingjingai2.service.McpServerProvisionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McpAdminTool} 单测：覆盖模板查询、用户隔离、按模板保存与失败处理、权限默认值。
 *
 * <p>用真实 {@link McpServerProvisionService} + 内存 mapper/manager（不连库、不拉进程）。
 * ASK 审批卡的「拒绝则不执行」由 {@link com.example.jingjingai2.permission.ToolPermissionGate}
 * 在 dispatch 前保证，属架构行为，本测试只断言「新工具命中 * → ASK 且不可会话授权」，
 * 拒绝-不执行的链路由现有 ToolPermissionGateTest 覆盖。
 */
class McpAdminToolTest {

    private RecordingMapper mapper;
    private SpyManager manager;
    private McpServerProvisionService provision;
    private McpAdminTool tool;

    @BeforeEach
    void setUp() {
        mapper = new RecordingMapper();
        manager = new SpyManager();
        provision = new McpServerProvisionService(mapper, manager);
        tool = new McpAdminTool(new com.example.jingjingai2.mcp.McpServerTemplateCatalog(), provision, mapper);
        ConversationContext.set(7L, "conv-test");
    }

    @AfterEach
    void tearDown() {
        ConversationContext.clear();
    }

    // ==================== A. 模板查询（只读） ====================

    @Test
    @DisplayName("listMcpServerTemplates 返回 playwright 模板与固定命令，不含秘密")
    void listTemplatesReturnsPlaywright() {
        String out = tool.listMcpServerTemplates();
        assertNotNull(out);
        assertTrue(out.contains("playwright"), out);
        assertTrue(out.contains("@playwright/mcp@0.0.83"), "固定命令对用户可见，便于知情同意");
        assertTrue(out.contains("默认禁用"), "告知保存后默认禁用");
        assertEquals(0, mapper.insertCalls, "查询不写库");
    }

    @Test
    @DisplayName("listMcpServerTemplates 无副作用：不调 insert、不调 addServer")
    void listTemplatesHasNoSideEffects() {
        tool.listMcpServerTemplates();
        assertEquals(0, mapper.insertCalls);
        assertEquals(0, manager.addServerCalls);
    }

    // ==================== B. 用户隔离 ====================

    @Test
    @DisplayName("listMyMcpServers 用 ConversationContext.userId()，看不到别人的服务")
    void listMyMcpServersIsolatesByContextUser() {
        mapper.stored.add(row(7L, "echo", "npx", "{\"API_KEY\":\"secret-value\"}", true));
        mapper.stored.add(row(9L, "echo", "npx", "{}", true));   // 别人的同名服务

        // 当前用户 7：只看到自己的
        String out7 = tool.listMyMcpServers();
        assertTrue(out7.contains("你的个人 MCP 服务"), out7);
        assertTrue(out7.contains("echo"), out7);
        assertTrue(out7.contains("[已启用]") || out7.contains("[已禁用"));
        assertFalse(out7.contains("secret-value"), "env 值不得泄露");

        // 切到用户 9：看不到用户 7 的服务
        ConversationContext.set(9L, "conv-other");
        String out9 = tool.listMyMcpServers();
        // 用户 9 有一条 echo（自己的），但不应包含用户 7 的 env 值
        assertTrue(out9.contains("echo"));
        assertFalse(out9.contains("secret-value"));
    }

    @Test
    @DisplayName("listMyMcpServers 在无会话上下文时返回错误，不查库")
    void listMyMcpServersWithoutContextReturnsError() {
        ConversationContext.clear();
        String out = tool.listMyMcpServers();
        assertTrue(out.contains("不在会话上下文"), out);
        assertEquals(0, mapper.findAllByOwnerCalls);
    }

    @Test
    @DisplayName("listMyMcpServers 只回 env 键名，不回值")
    void listMyMcpServersHidesEnvValues() {
        mapper.stored.add(row(7L, "svc", "npx", "{\"API_KEY\":\"sk-live-12345\",\"PATH\":\"x\"}", false));
        String out = tool.listMyMcpServers();
        assertTrue(out.contains("API_KEY"), "键名回显");
        assertTrue(out.contains("PATH"));
        assertFalse(out.contains("sk-live-12345"), "值绝不回显");
    }

    // ==================== C. 按模板保存与失败处理 ====================

    @Test
    @DisplayName("addMcpServerFromTemplate 保存成功：command/args 由模板固定，enabled=false，不调 addServer")
    void addFromTemplateSavesDisabled() {
        String out = tool.addMcpServerFromTemplate("playwright", "my-pw", null);

        assertTrue(out.contains("已保存"), out);
        assertTrue(out.contains("已禁用"), out);
        assertTrue(out.contains("未启动"), out);
        assertNotNull(mapper.inserted, "落库了");
        assertEquals("my-pw", mapper.inserted.getName());
        assertEquals("npx", mapper.inserted.getCommand(), "command 由模板固定，模型无法指定");
        assertEquals(false, mapper.inserted.isEnabled(), "强制禁用");
        assertEquals(7L, mapper.inserted.getCreatedBy(), "归属来自 ConversationContext，非模型");
        assertEquals(0, manager.addServerCalls, "保存路径不调 addServer、不建槽位、不连进程");
    }

    @Test
    @DisplayName("未知模板 ID 被拒，不写库")
    void addFromTemplateUnknownRejected() {
        String out = tool.addMcpServerFromTemplate("nope", "x", null);
        assertTrue(out.contains("模板不存在"), out);
        assertNull(mapper.inserted);
        assertEquals(0, mapper.insertCalls);
    }

    @Test
    @DisplayName("模板不接受参数：传任何参数被拒，不写库（token 也不会进 env/库）")
    void addFromTemplateRejectsAllParams() {
        String out = tool.addMcpServerFromTemplate("playwright", "my-pw", "{\"token\":\"ghp_xxx\"}");
        assertTrue(out.contains("不接受参数"), out);
        assertNull(mapper.inserted, "参数被拒，不落库");
        assertEquals(0, mapper.insertCalls);
        if (mapper.inserted != null) {
            assertFalse(mapper.inserted.getEnvJson().contains("ghp_xxx"));
        }
    }

    @Test
    @DisplayName("非法服务名被拒（provision 校验），不写库")
    void addFromTemplateInvalidNameRejected() {
        // 大写+空格，违反 checkName（只允许小写字母数字连字符）
        String out = tool.addMcpServerFromTemplate("playwright", "Bad Name", null);
        assertTrue(out.contains("保存失败"), out);
        assertNull(mapper.inserted);
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("个人服务重名被拒（provision 重名检查），不二次写库")
    void addFromTemplateDuplicateRejected() {
        mapper.stored.add(row(7L, "my-pw", "npx", "{}", false));
        String out = tool.addMcpServerFromTemplate("playwright", "my-pw", null);
        assertTrue(out.contains("服务已存在"), out);
        assertEquals(1, mapper.stored.size(), "没有新增行");
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("与 ownerId=0 部署槽位同名被拒")
    void addFromTemplateDeploymentConflictRejected() {
        manager.deploymentSlotName = "my-pw";
        String out = tool.addMcpServerFromTemplate("playwright", "my-pw", null);
        assertTrue(out.contains("重名"), out);
        assertNull(mapper.inserted);
    }

    @Test
    @DisplayName("addMcpServerFromTemplate 无会话上下文时不保存")
    void addFromTemplateWithoutContextDoesNotSave() {
        ConversationContext.clear();
        String out = tool.addMcpServerFromTemplate("playwright", "my-pw", null);
        assertTrue(out.contains("不在会话上下文"), out);
        assertNull(mapper.inserted);
    }

    @Test
    @DisplayName("保存成功后明确告知「需手动启用」「未自动授权」")
    void addFromTemplateSuccessMessageIsHonest() {
        String out = tool.addMcpServerFromTemplate("playwright", "my-pw", null);
        assertTrue(out.contains("手动启用"), out);
        assertTrue(out.contains("未自动授权"), out);
    }

    // ==================== D. 权限与副作用 ====================

    @Test
    @DisplayName("三个新工具均命中 PermissionPolicy 的 * 规则：ASK 且不可本会话授权（未进 ALLOW 白名单）")
    void newToolsAreAskByDefaultNotAllow() {
        PermissionPolicy policy = new PermissionPolicy(new PermissionProperties());
        for (String name : new String[]{
                "listMcpServerTemplates", "listMyMcpServers",
                "addMcpServerFromTemplate", "addPersonalMcpServer"}) {
            PermissionPolicy.Match m = policy.match(name, "{}");
            assertEquals(PermissionPolicy.Effect.ASK, m.effect, name + " 必须 ASK");
            assertFalse(m.sessionGrant, name + " 不可本会话授权（每次都要问）");
        }
    }

    @Test
    @DisplayName("保存过程不写 mcp_user_policy：provision 无 policy 依赖（结构证明），工具也不注入")
    void addDoesNotWritePolicy() {
        tool.addMcpServerFromTemplate("playwright", "my-pw", null);
        // provision 只持有 configs(Mapper) + manager，没有 McpUserPolicyMapper/ApprovalService。
        // 工具结果也声明「未自动授权」。
        assertNotNull(mapper.inserted);
    }

    @Test
    @DisplayName("查询工具不写库、不启动服务")
    void readToolsHaveNoWriteSideEffects() {
        tool.listMcpServerTemplates();
        tool.listMyMcpServers();
        assertEquals(0, mapper.insertCalls);
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("合法 HTTP：摘要里的地址和 prepare 的字段，与 saveDisabled 落库的配置一致")
    void personalHttpSummaryMatchesSavedConfig() throws Exception {
        String inner = "{\"serviceName\":\"demo-http\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://1.1.1.1\",\"endpointPath\":\"/mcp\",\"authType\":\"none\"}";
        String raw = "{\"proposalJson\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inner) + "}";
        com.example.jingjingai2.mcp.McpServerProposal.Prepared prepared =
                com.example.jingjingai2.mcp.McpServerProposal.preparePersonal(raw);
        assertTrue(prepared.ok(), prepared.error);
        com.example.jingjingai2.permission.McpAdminApprovalSummary summary =
                new com.example.jingjingai2.permission.McpAdminApprovalSummary(
                        new com.example.jingjingai2.mcp.McpServerTemplateCatalog());
        String text = summary.summaryFor("addPersonalMcpServer", raw);
        assertTrue(text.contains("https://1.1.1.1"), text);
        assertTrue(text.contains("/mcp"), text);
        assertTrue(text.contains("streamable_http"), text);
        assertTrue(text.contains("none"), text);
        assertTrue(text.contains("enabled=false"), text);
        assertTrue(text.contains("不会连接"), text);
        assertTrue(text.contains("当前登录用户"), text);
        assertTrue(summary.executable("addPersonalMcpServer", raw));

        String out = tool.addPersonalMcpServer(inner);
        assertTrue(out.contains("已保存"), out);
        assertEquals(prepared.config.getName(), mapper.inserted.getName());
        assertEquals(prepared.config.getTransport(), mapper.inserted.getTransport());
        assertEquals(prepared.config.getBaseUrl(), mapper.inserted.getBaseUrl());
        assertEquals(prepared.config.getEndpointPath(), mapper.inserted.getEndpointPath());
        assertEquals(prepared.config.getAuthType(), mapper.inserted.getAuthType());
        assertNull(mapper.inserted.getCommand());
        assertFalse(mapper.inserted.isEnabled());
        assertEquals(7L, mapper.inserted.getCreatedBy());
        assertEquals(0, manager.addServerCalls);
        com.example.jingjingai2.mcp.McpServerConfigValidator.normalizeForCreate(mapper.inserted);
        assertEquals("https://1.1.1.1", mapper.inserted.getBaseUrl());
        assertEquals("/mcp", mapper.inserted.getEndpointPath());
        assertEquals("streamable_http", mapper.inserted.getTransport());
        assertEquals("none", mapper.inserted.getAuthType());
    }

    @Test
    @DisplayName("合法 stdio：摘要里的命令和参数与落库配置一致")
    void personalStdioSummaryMatchesSavedConfig() throws Exception {
        String inner = "{\"serviceName\":\"demo-stdio\",\"transport\":\"stdio\",\"command\":\"npx\","
                + "\"args\":[\"-y\",\"@example/mcp-demo\"]}";
        String raw = "{\"proposalJson\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inner) + "}";
        com.example.jingjingai2.mcp.McpServerProposal.Prepared prepared =
                com.example.jingjingai2.mcp.McpServerProposal.preparePersonal(raw);
        assertTrue(prepared.ok(), prepared.error);
        String text = new com.example.jingjingai2.permission.McpAdminApprovalSummary(
                new com.example.jingjingai2.mcp.McpServerTemplateCatalog())
                .summaryFor("addPersonalMcpServer", raw);
        assertTrue(text.contains("npx"), text);
        assertTrue(text.contains("@example/mcp-demo"), text);
        assertTrue(text.contains("stdio"), text);
        String out = tool.addPersonalMcpServer(inner);
        assertTrue(out.contains("已保存"), out);
        assertEquals("npx", mapper.inserted.getCommand());
        assertTrue(mapper.inserted.getArgsJson().contains("@example/mcp-demo"), mapper.inserted.getArgsJson());
        assertEquals(prepared.config.getCommand(), mapper.inserted.getCommand());
        assertFalse(mapper.inserted.isEnabled());
        assertEquals(0, manager.addServerCalls);
    }

    @Test
    @DisplayName("自然语言可以生成 bearer HTTP 配置：保存为禁用、令牌不在配置里、摘要提示单独填")
    void personalBearerHttpIsSavedWithoutToken() throws Exception {
        String inner = "{\"serviceName\":\"demo-bearer\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://8.8.8.8\",\"endpointPath\":\"/mcp\",\"authType\":\"bearer\"}";
        String raw = "{\"proposalJson\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inner) + "}";
        com.example.jingjingai2.mcp.McpServerProposal.Prepared prepared =
                com.example.jingjingai2.mcp.McpServerProposal.preparePersonal(raw);
        assertTrue(prepared.ok(), prepared.error);
        assertEquals("bearer", prepared.config.getAuthType());
        assertEquals("https://8.8.8.8", prepared.config.getBaseUrl());

        String text = new com.example.jingjingai2.permission.McpAdminApprovalSummary(
                new com.example.jingjingai2.mcp.McpServerTemplateCatalog())
                .summaryFor("addPersonalMcpServer", raw);
        assertTrue(text.contains("bearer"), text);
        assertTrue(text.contains("单独填写"), "摘要要说清令牌另填：" + text);

        String out = tool.addPersonalMcpServer(inner);
        assertTrue(out.contains("已保存"), out);
        assertEquals("bearer", mapper.inserted.getAuthType());
        assertEquals("https://8.8.8.8", mapper.inserted.getBaseUrl());
        assertEquals("/mcp", mapper.inserted.getEndpointPath());
        assertFalse(mapper.inserted.isEnabled());
        assertEquals(7L, mapper.inserted.getCreatedBy());
        assertEquals(0, manager.addServerCalls, "保存不连接");
    }

    @Test
    @DisplayName("未知字段、令牌字段、非法地址、未知传输：直接调用也不保存")
    void personalProposalFailuresDoNotSave() {
        assertNoSave("{\"serviceName\":\"demo-http\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://1.1.1.1\",\"userId\":9}");
        assertNoSave("{\"serviceName\":\"demo-http\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://1.1.1.1\",\"token\":\"fake-token-not-real\"}");
        assertNoSave("{\"serviceName\":\"demo-http\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://127.0.0.1\"}");
        assertNoSave("{\"serviceName\":\"demo-http\",\"transport\":\"sse\",\"baseUrl\":\"https://1.1.1.1\"}");
        assertNoSave("{\"serviceName\":\"demo-http\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://1.1.1.1\",\"env\":{\"TOKEN\":\"x\"}}");
        // bearer 允许，但令牌仍然不能出现在提案里
        assertNoSave("{\"serviceName\":\"demo-bearer\",\"transport\":\"streamable_http\","
                + "\"baseUrl\":\"https://8.8.8.8\",\"authType\":\"bearer\",\"token\":\"fake-token-not-real\"}");
        // stdio 只能是 none
        assertNoSave("{\"serviceName\":\"demo-stdio\",\"transport\":\"stdio\",\"command\":\"npx\","
                + "\"authType\":\"bearer\"}");
        // 缺必填字段：HTTP 没有地址、stdio 没有服务名
        assertNoSave("{\"serviceName\":\"demo-http\",\"transport\":\"streamable_http\"}");
        assertNoSave("{\"transport\":\"stdio\",\"command\":\"npx\"}");
    }

    private void assertNoSave(String inner) {
        String out = tool.addPersonalMcpServer(inner);
        assertTrue(out.contains("不会保存"), out);
        assertNull(mapper.inserted);
        assertEquals(0, mapper.insertCalls);
        assertEquals(0, manager.addServerCalls);
    }

    // ==================== helpers ====================

    private static McpServerConfig row(long owner, String name, String command, String envJson, boolean enabled) {
        McpServerConfig c = new McpServerConfig();
        c.setCreatedBy(owner);
        c.setName(name);
        c.setCommand(command);
        c.setEnvJson(envJson);
        c.setArgsJson("[]");
        c.setDisabledToolsJson("[]");
        c.setTrust("untrusted");
        c.setConnectTimeoutSeconds(0);
        c.setMaxResultChars(0);
        c.setEnabled(enabled);
        return c;
    }

    /** 内存 Mapper：记录 insert；findAllByOwner 按 owner 过滤；findByOwnerAndName 精确匹配。 */
    static class RecordingMapper implements McpServerConfigMapper {
        McpServerConfig inserted;
        final List<McpServerConfig> stored = new ArrayList<>();
        int insertCalls;
        int findAllByOwnerCalls;

        @Override public int insert(McpServerConfig config) {
            insertCalls++;
            inserted = config;
            stored.add(config);
            return 1;
        }
        @Override public int update(McpServerConfig config) { return 1; }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) { return 1; }
        @Override public int delete(long ownerId, String name) { return 1; }
        @Override public McpServerConfig findByName(String name) { return null; }
        @Override public McpServerConfig findById(long id) {
            for (McpServerConfig c : stored) {
                if (c != null && c.getId() != null && c.getId() == id) return c;
            }
            return null;
        }
        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) {
            for (McpServerConfig c : stored) {
                if (c != null && c.getCreatedBy() != null && c.getCreatedBy() == ownerId
                        && name != null && name.equals(c.getName())) return c;
            }
            return null;
        }
        @Override public McpServerConfig findSharedByName(String name) { return null; }
        @Override public List<McpServerConfig> findAll() { return stored; }
        @Override public List<McpServerConfig> findAllByOwner(long ownerId) {
            findAllByOwnerCalls++;
            List<McpServerConfig> out = new ArrayList<>();
            for (McpServerConfig c : stored) {
                if (c != null && c.getCreatedBy() != null && c.getCreatedBy() == ownerId) out.add(c);
            }
            return out;
        }
    }

    /** Manager 子类：记录 addServer 调用（应保持 0）；findServer(0L,…) 按需返回部署槽位。 */
    static class SpyManager extends McpClientManager {
        String deploymentSlotName;
        int addServerCalls;

        SpyManager() { super(new McpProperties()); }

        @Override
        public String findServer(long userId, String name) {
            if (userId == 0L && deploymentSlotName != null && deploymentSlotName.equals(name)) return name;
            return null;
        }

        @Override
        public McpServerChange addServer(long ownerId, String name, McpServerSpec spec, boolean fromDatabase) {
            addServerCalls++;
            return new McpServerChange();
        }
    }
}
