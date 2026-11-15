package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 管理保存工具的唯一解析入口。审批摘要、{@code executable} 和工具方法都调用这里，
 * 避免三套字段规则。校验失败不写库；本类不碰 Mapper，也不连接。
 *
 * <p>个人工具的原始参数 S 是外层对象，只允许一个键 {@code proposalJson}。
 * 审批记录里的参数是脱敏副本，不能拿来重放。
 */
public final class McpServerProposal {

    public static final String TEMPLATE_TOOL = "addMcpServerFromTemplate";
    public static final String PERSONAL_TOOL = "addPersonalMcpServer";
    public static final String NOT_SAVED = "不会保存：";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> OUTER_KEYS = Set.of("proposalJson");
    private static final Set<String> INNER_KEYS = Set.of(
            "serviceName", "transport", "command", "args", "baseUrl", "endpointPath", "authType");

    private McpServerProposal() {}

    public static boolean isAdminSaveTool(String toolName) {
        return TEMPLATE_TOOL.equals(toolName) || PERSONAL_TOOL.equals(toolName);
    }

    /** 摘要和门禁用：个人工具看完整 S；模板工具看完整 S 里的已知字段。 */
    public static Prepared prepare(String toolName, String rawArguments, McpServerTemplateCatalog catalog) {
        if (PERSONAL_TOOL.equals(toolName)) return preparePersonal(rawArguments);
        if (TEMPLATE_TOOL.equals(toolName)) return prepareTemplateArguments(catalog, rawArguments);
        return Prepared.ok(null);
    }

    /**
     * 工具方法用。{@code proposalJson} 是 Spring 拆出来的内层字符串，不是整段 S。
     * 外层多余字段由 {@link #preparePersonal} 拒绝，门禁因此不会调用本方法。
     */
    public static Prepared prepareInner(String proposalJson) {
        if (proposalJson == null || proposalJson.isBlank()) {
            return Prepared.input("proposalJson 不能为空");
        }
        JsonNode inner;
        try {
            inner = MAPPER.readTree(proposalJson);
        } catch (Exception e) {
            return Prepared.input("参数不是合法的 JSON 对象");
        }
        return prepareInnerNode(inner);
    }

    public static Prepared preparePersonal(String rawArguments) {
        JsonNode root = readObject(rawArguments);
        if (root == null) return Prepared.input("参数不是合法的 JSON 对象");
        String unknown = unknownKey(root, OUTER_KEYS);
        if (unknown != null) return Prepared.input("不能包含字段：" + unknown);
        JsonNode proposal = root.get("proposalJson");
        if (proposal == null || proposal.isNull()) return Prepared.input("proposalJson 不能为空");
        if (!proposal.isTextual()) return Prepared.input("字段类型不正确：proposalJson");
        return prepareInner(proposal.asText());
    }

    public static Prepared prepareTemplate(McpServerTemplateCatalog catalog,
                                           String templateId, String serviceName, String params) {
        if (catalog == null) return Prepared.input("模板目录不可用");
        McpServerTemplate template = catalog.findById(templateId);
        if (template == null) {
            return Prepared.input("模板不存在：" + templateId + "。请先用 listMcpServerTemplates 查可用模板。");
        }
        if (serviceName == null || serviceName.isBlank()) return Prepared.input("serviceName 不能为空");
        java.util.Map<String, String> paramMap = parseParams(params);
        if (paramMap == null) return Prepared.input("参数不是合法的 JSON 对象");
        for (String key : paramMap.keySet()) {
            if (template.allowedParams() == null || !template.allowedParams().contains(key)) {
                return Prepared.input("模板「" + templateId + "」不接受参数：" + key
                        + "。该模板的 command/args/env 全部固定，只需提供 serviceName。");
            }
        }
        McpServerConfig config = new McpServerConfig();
        config.setName(serviceName);
        config.setCommand(template.command());
        config.setArgsJson(writeJson(template.args()));
        config.setEnvJson(writeJson(template.env()));
        config.setDisabledToolsJson("[]");
        config.setTrust(template.trust());
        config.setConnectTimeoutSeconds(template.connectTimeoutSeconds());
        config.setMaxResultChars(template.maxResultChars());
        McpServerConfigValidator.normalizeForCreate(config);
        String err = McpServerConfigValidator.check(config);
        if (err != null) return Prepared.validation(err);
        return Prepared.ok(config);
    }

    private static Prepared prepareTemplateArguments(McpServerTemplateCatalog catalog, String rawArguments) {
        JsonNode root = readObject(rawArguments);
        if (root == null) return Prepared.input("参数不是合法的 JSON 对象");
        String templateId = text(root, "templateId");
        String serviceName = text(root, "serviceName");
        if (templateId == null || serviceName == null) {
            return Prepared.input("缺少 templateId 或 serviceName");
        }
        String params = null;
        JsonNode paramsNode = root.get("params");
        if (paramsNode != null && !paramsNode.isNull()) {
            if (paramsNode.isTextual()) params = paramsNode.asText();
            else params = paramsNode.toString();
        }
        return prepareTemplate(catalog, templateId, serviceName, params);
    }

    private static Prepared prepareInnerNode(JsonNode inner) {
        if (inner == null || !inner.isObject()) return Prepared.input("参数不是合法的 JSON 对象");
        String unknown = unknownKey(inner, INNER_KEYS);
        if (unknown != null) return Prepared.input("不能包含字段：" + unknown);
        String serviceName = text(inner, "serviceName");
        if (serviceName == null) return Prepared.input("serviceName 不能为空");
        String transport = text(inner, "transport");
        if (transport == null
                || (!McpServerConfig.TRANSPORT_STDIO.equals(transport)
                && !McpServerConfig.TRANSPORT_STREAMABLE_HTTP.equals(transport))) {
            return Prepared.input("transport 只能是 stdio 或 streamable_http");
        }
        boolean http = McpServerConfig.TRANSPORT_STREAMABLE_HTTP.equals(transport);
        String command = text(inner, "command");
        String baseUrl = text(inner, "baseUrl");
        String endpointPath = text(inner, "endpointPath");
        String authType = text(inner, "authType");
        if (inner.has("command") && !inner.get("command").isNull() && !inner.get("command").isTextual()) {
            return Prepared.input("字段类型不正确：command");
        }
        if (inner.has("baseUrl") && !inner.get("baseUrl").isNull() && !inner.get("baseUrl").isTextual()) {
            return Prepared.input("字段类型不正确：baseUrl");
        }
        if (inner.has("endpointPath") && !inner.get("endpointPath").isNull() && !inner.get("endpointPath").isTextual()) {
            return Prepared.input("字段类型不正确：endpointPath");
        }
        if (inner.has("authType") && !inner.get("authType").isNull() && !inner.get("authType").isTextual()) {
            return Prepared.input("字段类型不正确：authType");
        }
        List<String> args = readArgs(inner);
        if (args == null) return Prepared.input("字段类型不正确：args");
        if (http) {
            if (command != null) return Prepared.input("streamable_http 配置不能填写 command");
            if (!args.isEmpty()) return Prepared.input("streamable_http 配置不能填写 args");
        } else if (baseUrl != null || endpointPath != null) {
            return Prepared.input(baseUrl != null
                    ? "stdio 配置不能填写 baseUrl"
                    : "stdio 配置不能填写 endpointPath");
        }
        if (authType != null
                && !McpServerConfig.AUTH_NONE.equals(authType)
                && !McpServerConfig.AUTH_BEARER.equals(authType)) {
            return Prepared.input("authType 只能是 none 或 bearer");
        }
        McpServerConfig config = new McpServerConfig();
        config.setName(serviceName);
        config.setTransport(transport);
        config.setCommand(command);
        config.setArgsJson(args.isEmpty() ? null : writeJson(args));
        config.setBaseUrl(baseUrl);
        config.setEndpointPath(endpointPath);
        config.setAuthType(authType);
        config.setDisabledToolsJson("[]");
        config.setConnectTimeoutSeconds(0);
        config.setMaxResultChars(0);
        McpServerConfigValidator.normalizeForCreate(config);
        String err = McpServerConfigValidator.check(config);
        if (err != null) return Prepared.validation(err);
        return Prepared.ok(config);
    }

    /** 给摘要用的成功文本。只含规范化之后的字段，不含 env 值或令牌。 */
    public static String successSummary(McpServerConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("确认后才会保存。服务归属当前登录用户。保存后 enabled=false，不会连接远端。\n");
        sb.append("服务名：").append(config.getName()).append('\n');
        sb.append("传输：").append(config.normalizedTransport()).append('\n');
        if (config.isStreamableHttp()) {
            sb.append("地址：").append(config.getBaseUrl()).append('\n');
            sb.append("路径：").append(config.getEndpointPath()).append('\n');
            sb.append("认证：").append(config.normalizedAuthType()).append('\n');
            if (McpServerConfig.AUTH_BEARER.equals(config.normalizedAuthType())) {
                sb.append("Bearer 令牌不在这份配置里：保存后到 MCP 管理页的服务卡片单独填写，再启用。\n");
            }
        } else {
            sb.append("命令：").append(config.getCommand()).append('\n');
            sb.append("参数：").append(config.getArgsJson() == null ? "[]" : config.getArgsJson()).append('\n');
        }
        sb.append("trust：").append(config.getTrust());
        return sb.toString();
    }

    private static JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String unknownKey(JsonNode object, Set<String> allowed) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = object.fieldNames();
        while (it.hasNext()) names.add(it.next());
        for (String name : names) {
            if (!allowed.contains(name)) return name;
        }
        return null;
    }

    private static String text(JsonNode object, String field) {
        JsonNode n = object.get(field);
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) return null;
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    /** 缺省或空数组返回空列表。类型不对返回 null。 */
    private static List<String> readArgs(JsonNode object) {
        if (!object.has("args") || object.get("args").isNull()) return List.of();
        JsonNode args = object.get("args");
        if (!args.isArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode item : args) {
            if (item == null || !item.isTextual()) return null;
            out.add(item.asText());
        }
        return out;
    }

    private static java.util.Map<String, String> parseParams(String params) {
        if (params == null || params.isBlank()) return java.util.Map.of();
        try {
            JsonNode node = MAPPER.readTree(params);
            if (node == null || !node.isObject()) return null;
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                JsonNode value = node.get(key);
                out.put(key, value == null || value.isNull() ? "" : value.isTextual() ? value.asText() : value.toString());
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String writeJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj == null ? "" : obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static final class Prepared {
        public final McpServerConfig config;
        /** 给用户看的原因，不含「不会保存：」前缀。 */
        public final String error;
        /** true 表示已经过了形状检查，失败来自 {@link McpServerConfigValidator#check}。 */
        public final boolean validation;

        private Prepared(McpServerConfig config, String error, boolean validation) {
            this.config = config;
            this.error = error;
            this.validation = validation;
        }

        public static Prepared ok(McpServerConfig config) { return new Prepared(config, null, false); }
        public static Prepared input(String error) { return new Prepared(null, error, false); }
        public static Prepared validation(String error) { return new Prepared(null, error, true); }

        public boolean ok() { return error == null && config != null; }

        public String notSaved() { return NOT_SAVED + (error == null ? "配置未通过校验" : error); }
    }
}
