package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.tool.ToolDispatcher;

import java.util.List;

/**
 * 测试专用：<b>显式</b>安装一个「所有工具都 ALLOW」的权限网关。
 *
 * <p>为什么需要它：{@code ToolDispatcher} 以前在 {@code gate == null} 时直接执行工具，
 * 于是一批只验证「派发 / MCP 路由 / 记忆落库」这类管道行为的测试就靠「不装网关」跑起来了。
 * 那条路径本身就是 fail-open 漏洞，已经改成 fail-closed（见 {@code ToolDispatcher#NO_GATE}）。
 *
 * <p>正确的替代不是「偷偷恢复放行」，而是把测试的意图写清楚：<b>这个测试环境授予一切权限</b>。
 * 这里用的是真实的生产类（{@link ToolPermissionGate} + {@link PermissionPolicy} + {@link ApprovalService}），
 * 只是把策略配置成一条 {@code * → ALLOW} 的规则 —— 等同于运维把权限表配成"全放行"，
 * 走的是同一条判定代码路径，不引入任何测试专用的放行分支。
 *
 * <p>需要验证真实 ASK / DENY 行为的测试不要用它，请安装带真实策略的网关并打开上下文。
 */
public final class AllowAllGate {

    private AllowAllGate() {}

    /** 安装放行一切的网关。用完必须调用 {@link #uninstall()}（或 installGate(null)）。 */
    public static void install() {
        PermissionProperties props = new PermissionProperties();
        PermissionProperties.Rule all = new PermissionProperties.Rule();
        all.setPattern("*");
        all.setEffect("ALLOW");
        all.setSessionGrant(false);
        props.setRules(List.of(all));
        ToolDispatcher.installGate(new ToolPermissionGate(
                new PermissionPolicy(props), new ApprovalService(1), new McpProperties()));
    }

    /** 卸载网关，回到「没有网关」的状态（该状态下受控工具会被拒绝）。 */
    public static void uninstall() {
        ToolDispatcher.installGate(null);
    }
}
