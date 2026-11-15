package com.example.jingjingai2.tool;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mcp.McpServerProposal;
import com.example.jingjingai2.mcp.McpServerTemplate;
import com.example.jingjingai2.mcp.McpServerTemplateCatalog;
import com.example.jingjingai2.service.McpServerProvisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具：自然语言管理<strong>当前登录用户自己</strong>的个人 MCP 服务。
 *
 * <p>三个能力，全部走 {@link ConversationContext#userId()} 取可信身份——<strong>不</strong>接受模型
 * 在工具参数里传 userId/ownerId/createdBy。所有「保存」都委托给阶段 1A 的
 * {@link McpServerProvisionService#saveDisabled}：强制禁用、不启动子进程、不写工具授权。
 *
 * <p>确认流程：新增的内置工具名不在 {@code PermissionPolicy.builtin()} 的 ALLOW 白名单里，
 * 命中末尾的 {@code *} 规则 → ASK 且不可本会话授权。{@link com.example.jingjingai2.permission.ToolPermissionGate}
 * 在 dispatch 前阻塞，弹审批卡，卡上 {@code arguments} 经 {@link com.example.jingjingai2.permission.SensitiveRedactor}
 * 按键名脱敏（token/secret/authorization 等 → ***）。用户「允许本次」才执行工具方法；
 * 「拒绝」则方法不执行、不写库。所以这张 ASK 卡就是「保存一个新服务」的最终确认——
 * 卡上展示的 {@code templateId + serviceName + params} 即待保存配置摘要。
 *
 * <p>查询工具（listTemplates / listMyMcpServers）也不进 ALLOW，默认 ASK——这是有意的安全默认，
 * 不为减少审批次数而改 {@code PermissionPolicy}。UX 代价：每次查询都弹一次卡。
 */
@Component
public class McpAdminTool {

    private final McpServerTemplateCatalog catalog;
    private final McpServerProvisionService provision;
    private final McpServerConfigMapper configs;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpAdminTool(McpServerTemplateCatalog catalog,
                        McpServerProvisionService provision,
                        McpServerConfigMapper configs) {
        this.catalog = catalog;
        this.provision = provision;
        this.configs = configs;
    }

    // ==================== 能力 A：查询模板（只读） ====================

    @Tool(description = "列出当前可添加的个人 MCP 服务模板。只读，不写库、不启动服务。"
            + "返回每个模板的 ID、用途、固定命令与参数、可填参数、是否需要凭证。"
            + "用 addMcpServerFromTemplate 实际添加前，先用这个工具看有哪些模板。")
    public String listMcpServerTemplates() {
        try {
            List<McpServerTemplate> all = catalog.all();
            if (all == null || all.isEmpty()) {
                return "当前没有可用的 MCP 服务模板。";
            }
            StringBuilder sb = new StringBuilder("可用 MCP 服务模板（").append(all.size()).append(" 个）：\n");
            for (McpServerTemplate t : all) {
                sb.append("- ID: ").append(t.id())
                        .append("  名称: ").append(t.displayName()).append('\n');
                sb.append("  用途: ").append(t.description()).append('\n');
                sb.append("  固定命令: ").append(t.command())
                        .append(' ').append(String.join(" ", t.args())).append('\n');
                sb.append("  trust: ").append(t.trust())
                        .append("  超时: ").append(t.connectTimeoutSeconds())
                        .append("s  结果长度: ").append(t.maxResultChars()).append('\n');
                // env 只给键名，不给值（值是系统路径，非秘密，但与现有管理 API 一致只回键）。
                sb.append("  固定 env 键: ")
                        .append(t.env() == null || t.env().isEmpty() ? "（无）" : String.join(", ", t.env().keySet()))
                        .append('\n');
                sb.append("  可填参数: ")
                        .append(t.allowedParams() == null || t.allowedParams().isEmpty()
                                ? "（该模板不接受任何参数，只需提供 serviceName）" : String.join(", ", t.allowedParams()))
                        .append('\n');
                sb.append("  保存后默认禁用、不启动；需到 MCP 管理页手动启用。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询模板失败：" + e.getMessage();
        }
    }

    // ==================== 能力 B：查询我的服务（只读） ====================

    @Tool(description = "列出当前登录用户自己的个人 MCP 服务。只读，不写库、不启动服务。"
            + "返回每个服务的名称、固定命令、是否已启用、env 键名（不含值）。"
            + "userId 由系统绑定，不接受模型指定。看不到别人的服务，也看不到部署级/YAML 服务。")
    public String listMyMcpServers() {
        try {
            Long userId = ConversationContext.userId();
            if (userId == null) {
                return "当前不在会话上下文中，无法查询个人 MCP 服务。";
            }
            List<McpServerConfig> mine = configs.findAllByOwner(userId);
            if (mine == null || mine.isEmpty()) {
                return "你还没有个人 MCP 服务。用 listMcpServerTemplates 看可添加的模板，再用 addMcpServerFromTemplate 添加。";
            }
            StringBuilder sb = new StringBuilder("你的个人 MCP 服务（").append(mine.size()).append(" 个）：\n");
            int i = 1;
            for (McpServerConfig c : mine) {
                if (c == null) continue;
                sb.append(i++).append(". ").append(c.getName())
                        .append(c.isEnabled() ? "  [已启用]" : "  [已禁用，未启动]").append('\n');
                sb.append("   命令: ").append(c.getCommand()).append('\n');
                // env 只回键名，绝不回值（可能是密钥）。
                List<String> envKeys = envKeys(c.getEnvJson());
                sb.append("   env 键: ").append(envKeys.isEmpty() ? "（无）" : String.join(", ", envKeys)).append('\n');
                sb.append("   trust: ").append(c.getTrust())
                        .append("  超时: ").append(c.getConnectTimeoutSeconds())
                        .append("s  结果长度: ").append(c.getMaxResultChars()).append('\n');
            }
            sb.append("已禁用的服务需到 MCP 管理页手动启用才会启动。");
            return sb.toString();
        } catch (Exception e) {
            return "查询个人 MCP 服务失败：" + e.getMessage();
        }
    }

    // ==================== 能力 C：按模板添加个人服务（写，默认禁用） ====================

    @Tool(description = "按受控模板添加当前用户的个人 MCP 服务。模板固定命令和参数，模型只能选模板 ID 和填模板允许的非敏感参数。"
            + "保存后服务默认禁用、不启动子进程，需用户到 MCP 管理页手动启用。"
            + "调用本工具会弹审批卡，用户「允许本次」才会保存；「拒绝」则不保存。不自动授权该服务的工具。")
    public String addMcpServerFromTemplate(
            @ToolParam(description = "模板 ID，必须来自 listMcpServerTemplates 的结果，不能自己编") String templateId,
            @ToolParam(description = "服务名，小写字母数字和连字符，最长 32，会作为保存后的服务名") String serviceName,
            @ToolParam(description = "模板参数 JSON 对象字符串，可选；第一版模板都不接受参数，留空或传 {} 即可",
                    required = false) String params) {
        try {
            Long userId = ConversationContext.userId();
            if (userId == null) {
                return "当前不在会话上下文中，无法添加个人 MCP 服务。";
            }
            McpServerProposal.Prepared prepared = McpServerProposal.prepareTemplate(
                    catalog, templateId, serviceName, params);
            if (!prepared.ok()) {
                if (prepared.validation) {
                    return "保存失败：" + prepared.error + "。未写库，未启动。";
                }
                return prepared.error;
            }
            McpServerProvisionService.SaveResult r = provision.saveDisabled(userId, prepared.config);
            if (!r.saved()) {
                return "保存失败：" + r.error() + "。未写库，未启动。";
            }
            return "已保存个人 MCP 服务「" + r.name() + "」（模板 " + templateId + "，已禁用，未启动子进程）。"
                    + "该服务的工具默认需要逐个审批，未自动授权。"
                    + "请到 MCP 管理页面手动启用，启用后才会连接并发现工具。";
        } catch (Exception e) {
            return "添加个人 MCP 服务失败：" + e.getMessage() + "。未写库。";
        }
    }

    @Tool(description = "按当前用户的描述添加个人 MCP 服务。只接受 proposalJson 这一个参数，其值是 JSON 对象字符串。"
            + "允许的字段只有 serviceName、transport（stdio 或 streamable_http）、stdio 的 command 和 args、"
            + "HTTP 的 baseUrl、endpointPath、authType。authType 只能是 none 或 bearer；"
            + "Bearer 的令牌不能放进这个 JSON，保存后由用户在 MCP 管理页单独输入。"
            + "令牌、env、trust、enabled、userId 都不能出现。"
            + "调用会先给出服务端整理的配置摘要，用户确认后才保存。保存后默认禁用，不连接，不授权工具。"
            + "用户拒绝、取消或校验失败都不会保存。")
    public String addPersonalMcpServer(
            @ToolParam(description = "服务配置 JSON 对象的字符串。不要把令牌或环境变量放进来。") String proposalJson) {
        try {
            Long userId = ConversationContext.userId();
            if (userId == null) {
                return "当前不在会话上下文中，无法添加个人 MCP 服务。";
            }
            McpServerProposal.Prepared prepared = McpServerProposal.prepareInner(proposalJson);
            if (!prepared.ok()) {
                return prepared.notSaved() + "。未写库，未启动。";
            }
            McpServerProvisionService.SaveResult r = provision.saveDisabled(userId, prepared.config);
            if (!r.saved()) {
                return "保存失败：" + r.error() + "。未写库，未启动。";
            }
            return "已保存个人 MCP 服务「" + r.name() + "」（已禁用，未连接）。"
                    + "该服务的工具默认需要逐个审批，未自动授权。"
                    + "请到 MCP 管理页面手动启用，启用后才会连接并发现工具。";
        } catch (Exception e) {
            return "添加个人 MCP 服务失败：" + e.getMessage() + "。未写库。";
        }
    }

    // ==================== helpers ====================

    private List<String> envKeys(String envJson) {
        if (envJson == null || envJson.isBlank()) return List.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = mapper.readValue(envJson, Map.class);
            return m == null ? List.of() : new java.util.ArrayList<>(m.keySet());
        } catch (Exception e) {
            return List.of();
        }
    }
}
