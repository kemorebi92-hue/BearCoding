package com.example.jingjingai2.tool;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.controller.McpServerAdminController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Agent 工具：修改、启停、连接控制、删除<strong>当前登录用户自己的</strong>个人 MCP 服务。
 *
 * <p>与管理页走同一组 controller 方法（{@code updateAsUser} / {@code setEnabledAsUser} /
 * {@code controlAsUser} / {@code deleteAsUser}），归属校验、配置校验、授权清理都不另写一份。
 * userId 只从 {@link ConversationContext} 取，不接受模型在参数里指定。
 *
 * <p>凭证不经过这里：env 与令牌都可能含密钥，一律让用户到 MCP 管理页填写，
 * 避免密钥进入工具参数、审批摘要和日志。
 *
 * <p>四个工具都不在 {@code PermissionPolicy.builtin()} 的 ALLOW 表里，默认 ASK；
 * 删除还要求 {@code confirm} 与服务名完全一致，防止模型顺手删错。
 */
@Component
public class McpAdminWriteTool {

    /** 可改字段。故意不含 env（可能含密钥），也不含 enabled（走启停工具）。 */
    private static final Set<String> EDITABLE = Set.of(
            "command", "args", "disabledTools", "trust", "connectTimeoutSeconds", "maxResultChars",
            "transport", "baseUrl", "endpointPath", "authType");

    private static final String NOT_IN_CONTEXT = "当前不在会话上下文中，无法管理个人 MCP 服务。";

    private final McpServerAdminController admin;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpAdminWriteTool(McpServerAdminController admin) {
        this.admin = admin;
    }

    @Tool(description = "修改当前登录用户自己的个人 MCP 服务配置。只能改已有的个人服务，"
            + "不能改部署配置里的服务或系统共享服务。patchJson 是 JSON 对象字符串，"
            + "可含 command、args、disabledTools、trust、connectTimeoutSeconds、maxResultChars、"
            + "transport、baseUrl、endpointPath、authType；没写的字段保持原值。"
            + "不接受 env 或任何凭证字段——令牌和环境变量请让用户到 MCP 管理页填写。"
            + "调用会弹审批卡。")
    public String updateMyMcpServer(
            @ToolParam(description = "服务名，必须是该用户已有的个人服务") String serviceName,
            @ToolParam(description = "要修改的字段，JSON 对象字符串，例如 {\"trust\":\"untrusted\"}") String patchJson) {
        Long userId = ConversationContext.userId();
        if (userId == null) return NOT_IN_CONTEXT;
        if (serviceName == null || serviceName.isBlank()) return "服务名不能为空";
        Map<String, Object> patch;
        try {
            patch = patchJson == null || patchJson.isBlank()
                    ? new LinkedHashMap<>()
                    : mapper.readValue(patchJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return "patchJson 不是合法的 JSON 对象";
        }
        if (patch.isEmpty()) return "没有要修改的字段";
        for (String key : patch.keySet()) {
            if ("env".equals(key)) {
                return "不接受 env：环境变量可能含密钥，请让用户到 MCP 管理页修改";
            }
            if (!EDITABLE.contains(key)) {
                return "不能修改字段：" + key + "。可改的是 " + String.join("、", EDITABLE);
            }
        }
        return render(admin.updateAsUser(userId, serviceName, patch));
    }

    @Tool(description = "启用或停用当前登录用户自己的个人 MCP 服务。启用会拉起连接（stdio 会启动子进程），"
            + "停用会断开。不能操作部署配置或系统共享服务。调用会弹审批卡。")
    public String setMyMcpServerEnabled(
            @ToolParam(description = "服务名") String serviceName,
            @ToolParam(description = "true 启用，false 停用") boolean enabled) {
        Long userId = ConversationContext.userId();
        if (userId == null) return NOT_IN_CONTEXT;
        if (serviceName == null || serviceName.isBlank()) return "服务名不能为空";
        return render(admin.setEnabledAsUser(userId, serviceName, enabled));
    }

    @Tool(description = "对当前登录用户自己的个人 MCP 服务做连接控制。"
            + "action 只能是 connect（连接）、disconnect（断开）、reconnect（重建会话，换了凭证后用）、"
            + "refresh（重新拉工具列表）。不能操作部署配置或系统共享服务。调用会弹审批卡。")
    public String controlMyMcpServer(
            @ToolParam(description = "服务名") String serviceName,
            @ToolParam(description = "connect、disconnect、reconnect 或 refresh") String action) {
        Long userId = ConversationContext.userId();
        if (userId == null) return NOT_IN_CONTEXT;
        if (serviceName == null || serviceName.isBlank()) return "服务名不能为空";
        String a = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if (!a.equals("connect") && !a.equals("disconnect") && !a.equals("reconnect") && !a.equals("refresh")) {
            return "action 只能是 connect、disconnect、reconnect、refresh";
        }
        return render(admin.controlAsUser(userId, serviceName, a));
    }

    @Tool(description = "删除当前登录用户自己的个人 MCP 服务，同时清理该用户在这个服务上的授权记录和凭证。"
            + "不可恢复。为防止误删，必须把 serviceName 原样填进 confirm；两者不一致不执行。"
            + "不能删除部署配置里的服务或系统共享服务。调用会弹审批卡。")
    public String deleteMyMcpServer(
            @ToolParam(description = "要删除的服务名") String serviceName,
            @ToolParam(description = "确认标记，必须与服务名完全一致，例如 {\"serviceName\":\"github\",\"confirm\":\"github\"}") String confirm) {
        Long userId = ConversationContext.userId();
        if (userId == null) return NOT_IN_CONTEXT;
        if (serviceName == null || serviceName.isBlank()) return "服务名不能为空";
        if (confirm == null || !confirm.trim().equals(serviceName.trim())) {
            return "未删除：confirm 必须与服务名完全一致（要删的是 " + serviceName + "）";
        }
        return render(admin.deleteAsUser(userId, serviceName));
    }

    /** 把 controller 的响应收成给模型看的一行结论，不回声任何密钥或配置全文。 */
    private static String render(ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = res == null ? null : res.getBody();
        int status = res == null ? 500 : res.getStatusCode().value();
        if (status >= 400 || body == null) {
            Object msg = body == null ? null : body.get("message");
            return "操作失败：" + (msg == null ? status : msg);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("操作完成：").append(body.getOrDefault("action", "已处理"));
        Object name = body.get("name");
        if (name != null) sb.append("，服务 ").append(name);
        if (Boolean.TRUE.equals(body.get("connected"))) {
            sb.append("，已连接，发现 ").append(body.getOrDefault("toolCount", 0)).append(" 个工具");
        }
        if (Boolean.FALSE.equals(body.get("configSaved"))) {
            sb.append("，配置未保存");
        }
        Object err = body.get("lastError");
        if (err != null) sb.append("，运行时未生效：").append(err);
        Object cleared = body.get("policiesCleared");
        if (cleared instanceof Number n && n.intValue() > 0) {
            sb.append("，清理 ").append(n.intValue()).append(" 条授权记录");
        }
        return sb.toString();
    }
}
