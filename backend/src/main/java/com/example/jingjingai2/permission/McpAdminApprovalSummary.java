package com.example.jingjingai2.permission;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mcp.McpServerProposal;
import com.example.jingjingai2.mcp.McpServerTemplate;
import com.example.jingjingai2.mcp.McpServerTemplateCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 为 {@code addMcpServerFromTemplate} 生成可信审批摘要。
 *
 * <p>摘要由后端从 {@link McpServerTemplateCatalog} 的真实模板 + 本次调用参数构造，
 * 不采信模型传入的自由文本。校验失败时返回「不会保存：」加原因，不返回 null，
 * 门禁据此不放行工具方法。
 *
 * <p>摘要与最终保存配置同源：都来自 {@link McpServerProposal#prepare}。
 */
@Component
public class McpAdminApprovalSummary implements ApprovalSummaryProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerTemplateCatalog catalog;

    public McpAdminApprovalSummary(McpServerTemplateCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String summaryFor(String toolName, String arguments) {
        if (!McpServerProposal.isAdminSaveTool(toolName)) return null;
        McpServerProposal.Prepared prepared = McpServerProposal.prepare(toolName, arguments, catalog);
        if (!prepared.ok()) return prepared.notSaved();
        if (McpServerProposal.TEMPLATE_TOOL.equals(toolName)) return templateSummary(prepared.config, arguments);
        return McpServerProposal.successSummary(prepared.config);
    }

    @Override
    public boolean executable(String toolName, String arguments) {
        if (!McpServerProposal.isAdminSaveTool(toolName)) return true;
        return McpServerProposal.prepare(toolName, arguments, catalog).ok();
    }

    /**
     * 模板摘要仍列出固定包和启动参数，短语与原来一致。配置本身来自同一次 prepare。
     * 不读取模型塞进来的自由文本。
     */
    private String templateSummary(McpServerConfig config, String arguments) {
        JsonNode root = parse(arguments);
        String templateId = root == null ? null : text(root, "templateId");
        McpServerTemplate template = catalog.findById(templateId);
        if (template == null || config == null) return McpServerProposal.NOT_SAVED + "模板不存在";
        String pkg = packageArg(template.args());
        StringBuilder sb = new StringBuilder();
        sb.append("将添加个人 MCP 服务配置（仅保存，默认禁用，不启动进程，不自动授权工具）。\n");
        sb.append("确认后才会保存。服务归属当前登录用户。保存后 enabled=false，不会连接远端。\n");
        sb.append("服务名：").append(config.getName()).append('\n');
        sb.append("模板：").append(template.displayName()).append("（").append(template.id()).append("）\n");
        if (pkg != null) sb.append("固定包：").append(pkg).append('\n');
        sb.append("固定命令：").append(config.getCommand())
                .append(' ').append(String.join(" ", template.args())).append('\n');
        if (template.env() != null && !template.env().isEmpty()) {
            sb.append("环境变量键：").append(String.join("、", template.env().keySet()))
                    .append("（不含值）\n");
        }
        sb.append("trust：").append(config.getTrust())
                .append("　超时：").append(config.getConnectTimeoutSeconds())
                .append(" 秒　结果长度：").append(config.getMaxResultChars());
        return sb.toString();
    }

    /** args 里第一个以 @ 开头的参数视为包名（如 @playwright/mcp@0.0.83）。没有返回 null。 */
    private static String packageArg(List<String> args) {
        if (args == null) return null;
        for (String a : args) {
            if (a != null && a.startsWith("@")) return a;
        }
        return null;
    }

    private static JsonNode parse(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) return null;
        String s = n.asText();
        return s.isBlank() ? null : s;
    }
}
