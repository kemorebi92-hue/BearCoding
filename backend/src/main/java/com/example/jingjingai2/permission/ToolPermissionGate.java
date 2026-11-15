package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpNames;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpUntrustedGuard;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.utils.PlanNudgePolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 所有 {@link ToolDispatcher#dispatch} 在真正 {@code call} 之前都问它。
 * DENY 不能靠审批绕过；ASK 在当前线程上等用户，批准后用原始参数执行。
 * 批准返回后会再用原始参数查一次当前权限：仍是 ASK 不会因此再拦一次；
 * 已经变成 DENY，或等待期间工具定义才被判定为变更的，不执行，也不记本会话允许。
 */
@Component
public class ToolPermissionGate {

    static final String NEED_STREAM = "{\"status\":\"approval_required\",\"executed\":false,\"resumable\":false,\"message\":\"该操作需要用户审批，请使用流式聊天。工具未执行；可直接交付正文，但不得声称文件已写入。\"}";
    static final String NEED_PARENT = "该操作需要用户审批，子代理无法请求，请告知主代理。";
    static final String REJECTED = "用户拒绝了该操作，请不要原样重试，向用户说明或改用其他方案。";
    static final String EXPIRED = "审批已超时，操作未执行。请不要原样重试，向用户说明或改用其他方案。";
    static final String CANCELLED = "审批已取消，操作未执行。请不要原样重试。";
    static final String REVOKED_DEFINITION =
            "已拒绝：审批期间工具定义已变更，该操作未执行。请向用户说明，不要沿用刚才的批准。";
    /**
     * 没有调用上下文、且该工具本来需要审批时的拒绝文案。
     * 与 {@link #NEED_STREAM} 分开：那种情况是「有身份但没 SSE 通道」，
     * 这种是「连身份都没有」，属于结构性异常，不能让模型误以为换个通道就能做。
     */
    public static final String NO_CONTEXT =
            "工具未执行：本次调用没有安全上下文，无法确认该操作是否已被授权。请不要原样重试，向用户说明情况。";

    private final PermissionPolicy policy;
    private final ApprovalService approvals;
    private final McpProperties mcpProperties;
    private final McpPermissionExplainer explainer;
    /** 可选注入：为特定工具生成可信审批摘要。null 时卡片行为与之前完全一致。 */
    private ApprovalSummaryProvider summaryProvider;
    /**
     * 当前会话的权限模式。可选：单测直接 new 时为空，网关保持原有判定。
     * 只在 MCP 分流之后的普通工具分支里读，读不到或非法时按 ASK。
     */
    private ConversationService conversations;

    /** WORKSPACE_EDIT 免审批的普通文件写操作。deletePath 和 executeCommand 不在里面。 */
    private static final Set<String> WORKSPACE_WRITES = Set.of(
            "writeFile", "editFile", "createFile", "appendFile",
            "createDirectory", "movePath", "copyPath");
    /** FULL_ACCESS 免审批的普通本地文件和命令工具。MCP 和 MCP 管理工具不在里面。 */
    private static final Set<String> FULL_ACCESS_TOOLS = Set.of(
            "writeFile", "editFile", "createFile", "appendFile",
            "createDirectory", "movePath", "copyPath", "deletePath", "executeCommand");

    @Autowired
    public ToolPermissionGate(PermissionProperties permissionProperties,
                              ApprovalService approvals,
                              McpProperties mcpProperties,
                              McpPermissionExplainer explainer) {
        this(new PermissionPolicy(permissionProperties), approvals, mcpProperties, explainer);
    }

    /** 单测：自带策略和审批表，不经过 Spring。explainer 为空时 MCP 仍走内置表兜底。 */
    public ToolPermissionGate(PermissionPolicy policy, ApprovalService approvals, McpProperties mcpProperties) {
        this(policy, approvals, mcpProperties, null);
    }

    public ToolPermissionGate(PermissionPolicy policy, ApprovalService approvals,
                              McpProperties mcpProperties, McpPermissionExplainer explainer) {
        this.policy = policy;
        this.approvals = approvals;
        this.mcpProperties = mcpProperties;
        this.explainer = explainer;
    }

    /** 可选注入：没有 provider 时不改变任何现有审批卡行为。 */
    @Autowired(required = false)
    public void setSummaryProvider(ApprovalSummaryProvider summaryProvider) {
        this.summaryProvider = summaryProvider;
    }

    /** 可选注入：没有会话服务时不读权限模式，普通工具保持原有审批。 */
    @Autowired(required = false)
    public void setConversationService(ConversationService conversations) {
        this.conversations = conversations;
    }

    public ApprovalService approvals() { return approvals; }

    @PostConstruct
    public void install() {
        ToolDispatcher.installGate(this);
    }

    @PreDestroy
    public void uninstall() {
        ToolDispatcher.installGate(null);
    }

    /**
     * @return null 表示可以执行；否则是直接交回模型的说明，调用方不得再 call。
     *
     * <p><b>没有调用上下文时不再默认放行。</b>旧的 {@code if (ctx == null) return null;} 是结构性
     * fail-open：写文件、终端执行、删除、MCP 调用都会因为「没人问过」而被真的执行。
     * 现在改成继续算策略 —— 表里无条件 ALLOW 的工具照常执行（它们本来就不需要审批），
     * 需要审批的工具拿不到 userId / SSE 通道，走下面同一条 fail-closed 出口：
     * 不执行、不记「本会话允许」、把本轮工具执行闸门关上。
     */
    public String authorize(String toolName, String arguments) {
        ToolCallContext ctx = ToolCallContext.current();
        com.example.jingjingai2.agent.TaskExecution task = com.example.jingjingai2.agent.TaskExecution.current();
        if (task != null && !task.acceptsTools()) {
            audit(ctx, true, false);
            return "工具未执行：请求已停止、审批阻塞或进入最终交付。";
        }
        String denied = UrlArgumentScanner.reject(arguments);
        if (denied != null) {
            audit(ctx, true, false);
            return "已拒绝：地址不被允许（" + denied + "）。该决定不能通过审批绕过。";
        }
        PermissionPolicy.Effect effect;
        boolean sessionGrant;
        String grantName = toolName;
        String risk;
        boolean taintedCard;
        boolean definitionChangedAtCard = false;
        if (ctx != null && explainer != null && McpNames.isMcp(toolName)) {
            McpPermissionExplainer.ExplainResult er = explainer.explain(
                    ctx.userId, McpNames.serverOf(toolName), McpNames.originalOf(toolName), arguments);
            if (er.effect == McpPermissionExplainer.Effect.DENY) {
                audit(ctx, true, false);
                return "已拒绝：" + er.reason;
            }
            effect = er.effect == McpPermissionExplainer.Effect.ALLOW
                    ? PermissionPolicy.Effect.ALLOW : PermissionPolicy.Effect.ASK;
            sessionGrant = er.sessionGrant;
            if (er.argumentForce) grantName = ApprovalService.argGrantName(toolName);
            risk = er.reason;
            taintedCard = er.crossServiceTaint;
            definitionChangedAtCard = er.definitionChanged;
        } else {
            PermissionPolicy.Match match = policy.match(toolName, arguments);
            boolean dangerous = PlanNudgePolicy.isDangerousBuiltin(toolName);
            boolean taintUpgrade = dangerous && ctx != null && ctx.tainted() && askOnTaint();
            // 网页污点仍要问这一次，但用户点了本会话允许就不再重复问。
            // 高危命令和 deletePath 的 sessionGrant 本来就是 false，这里不打开。
            sessionGrant = match.sessionGrant;
            effect = match.effect;
            if (taintUpgrade) effect = PermissionPolicy.Effect.ASK;
            risk = PermissionPolicy.riskText(toolName, match, taintUpgrade);
            taintedCard = taintUpgrade;
            // 会话三档权限只作用于普通工具，且在 MCP 分流之后。
            // 读不到模式、模式非法、或上下文没有 userId / conversationId 时保持上面的原判定。
            // 污点强制 ASK（taintUpgrade）必须保留：网页内容诱发的危险操作不能因为会话模式免审批。
            if (!taintUpgrade && effect == PermissionPolicy.Effect.ASK && autoAllowed(ctx, toolName)) {
                effect = PermissionPolicy.Effect.ALLOW;
            }
        }

        if (effect == PermissionPolicy.Effect.ALLOW) {
            audit(ctx, false, true);
            return null;
        }
        // 上下文缺失时不能查「本会话允许」：没有 userId 就无从归属，也不能拿别人的授权。
        if (ctx != null && sessionGrant && approvals.sessionGranted(ctx.userId, ctx.conversationId, grantName)) {
            audit(ctx, true, true);
            return null;
        }
        if (ctx == null || ctx.events == null || ctx.userId == null) {
            audit(ctx, true, false);
            if (task != null) task.blockApproval("当前通道无法处理新的审批，工具未执行");
            if (ctx == null) return NO_CONTEXT;
            return ctx.fromSubagent ? NEED_PARENT : NEED_STREAM;
        }

        String shownArgs = SensitiveRedactor.redact(arguments);
        String source = PermissionPolicy.sourceOf(toolName);
        // 可信摘要（可选）：由后端 provider 按工具名 + 原始参数生成，不采信模型自由文本。
        // provider 返回 null 时不放 summary，卡片按原有方式显示。
        String summary = summaryProvider == null ? null : summaryProvider.summaryFor(toolName, arguments);
        ApprovalService.Record rec = approvals.create(ctx.userId, ctx.conversationId, toolName, source,
                shownArgs, risk, taintedCard, sessionGrant);
        rec.definitionChangedAtCard = definitionChangedAtCard;
        if (!grantName.equals(toolName)) rec.sessionGrantKey = grantName;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "approval");
        card.put("approvalId", rec.id);
        card.put("toolName", toolName);
        card.put("source", source);
        card.put("arguments", shownArgs);
        card.put("risk", risk);
        if (summary != null && !summary.isBlank()) card.put("summary", summary);
        card.put("executable", summaryProvider == null || summaryProvider.executable(toolName, arguments));
        card.put("tainted", taintedCard);
        card.put("canSessionGrant", sessionGrant);
        card.put("expiresAt", rec.expiresAt.toString());
        if (ctx.subagentLabel != null && !ctx.subagentLabel.isBlank()) {
            card.put("subagentLabel", ctx.subagentLabel);
        }
        if (task != null && task.cancelled()) {
            approvals.cancelConversation(ctx.userId, ctx.conversationId);
        } else {
            ctx.events.approval(card);
        }

        ApprovalService.Status status = approvals.await(rec);
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("type", "approval_result");
        done.put("approvalId", rec.id);
        done.put("status", status.name());
        ctx.events.approvalResult(done);

        if (status == ApprovalService.Status.APPROVED) {
            if (task != null && !task.acceptsTools()) {
                audit(ctx, true, false);
                return CANCELLED;
            }
            String again = UrlArgumentScanner.reject(arguments);
            if (again != null) {
                audit(ctx, true, false);
                return "已拒绝：地址不被允许（" + again + "）。该决定不能通过审批绕过。";
            }
            String revoked = recheckAfterApproval(ctx, toolName, arguments, rec);
            if (revoked != null) {
                audit(ctx, true, false);
                return revoked;
            }
            if (summaryProvider != null && !summaryProvider.executable(toolName, arguments)) {
                audit(ctx, true, false);
                String refused = summaryProvider.summaryFor(toolName, arguments);
                if (refused != null && !refused.isBlank()) return refused;
                return "不会保存";
            }
            if (task != null && !task.acceptsTools()) {
                audit(ctx, true, false);
                return CANCELLED;
            }
            audit(ctx, true, true);
            return null;
        }
        audit(ctx, true, false);
        if (task != null) task.blockApproval("审批结果：" + status.name() + "，工具未执行");
        if (status == ApprovalService.Status.REJECTED) return REJECTED;
        if (status == ApprovalService.Status.EXPIRED) return EXPIRED;
        return CANCELLED;
    }

    /**
     * 批准之后、执行之前再看当前权限。参数用这次调用的原文，不用卡片上打过星的展示值。
     *
     * <p>仍是 ASK 放行：用户已经批过这一次。DENY 不放行。strip-dangerous 下「已污染 + 危险内置工具」
     * 是硬条件，批准后不再成立时同样不放行（与 {@code ToolGuard} 的判定保持一致）。
     * 定义变更只在「弹卡时还没有、现在有」时作废，
     * 弹卡时就已经变更的，这次批准就是确认，不能再拦一次。本会话允许也要现在仍成立才写入。
     *
     * @return null 表示可以执行
     */
    private String recheckAfterApproval(ToolCallContext ctx, String toolName, String arguments,
                                        ApprovalService.Record rec) {
        if (explainer == null || ctx == null || !McpNames.isMcp(toolName)) {
            // strip-dangerous：等待期间本会话新增污点后，危险内置工具必须按"已污染"处理。
            // 与 ToolGuard 用同一策略、同一危险集合、同一句拒绝文案；否则同一次调用会因为
            // "弹卡时还没污染"而在批准后绕过剥离规则。检查必须排在提交会话授权之前。
            if (stripDangerousOnTaint(ctx, toolName)) {
                return McpUntrustedGuard.REJECT_MESSAGE;
            }
            if (rec.sessionGrant) approvals.commitSessionGrant(rec);
            return null;
        }
        McpPermissionExplainer.ExplainResult now = explainer.explain(
                ctx.userId, McpNames.serverOf(toolName), McpNames.originalOf(toolName), arguments);
        if (now.effect == McpPermissionExplainer.Effect.DENY) {
            return "已拒绝：审批期间权限已变为拒绝（" + now.reason + "）。该操作未执行，不能通过审批绕过。";
        }
        if (now.definitionChanged && !rec.definitionChangedAtCard) {
            return REVOKED_DEFINITION;
        }
        if (rec.sessionGrant && now.sessionGrant) {
            approvals.commitSessionGrant(rec);
        }
        return null;
    }

    /**
     * strip-dangerous 模式下的硬条件：当前会话已污染，且工具属于危险内置集合。
     *
     * <p>只在「批准后复查」用：批准前由 {@code ToolGuard} 用同一策略挡掉整次调用。
     * 策略名与危险集合都复用现有实现，不引入第二套判定。{@code mcpProperties} 允许为空
     * （三参构造器给单测用），为空时不启用这条规则。
     */
    private boolean stripDangerousOnTaint(ToolCallContext ctx, String toolName) {
        if (ctx == null || mcpProperties == null) return false;
        if (!"strip-dangerous".equals(mcpProperties.effectiveUntrustedPolicy())) return false;
        return PlanNudgePolicy.isDangerousBuiltin(toolName) && ctx.tainted();
    }

    /**
     * 当前会话是否让这个普通工具免审批。只在 MCP 分流之后的普通工具分支里调用。
     * WORKSPACE_EDIT 只放行指定的文件写操作；FULL_ACCESS 放行普通本地文件和命令工具。
     * 读不到模式、模式非法、或上下文缺 userId / conversationId 时返回 false，保持原判定。
     */
    private boolean autoAllowed(ToolCallContext ctx, String toolName) {
        if ("saveUserMemory".equals(toolName) || "forgetUserMemory".equals(toolName)) return false;
        if (conversations == null || ctx == null || ctx.userId == null || ctx.conversationId == null) {
            return false;
        }
        String mode = conversations.permissionMode(ctx.userId, ctx.conversationId);
        if (ConversationService.MODE_WORKSPACE_EDIT.equals(mode)) {
            return WORKSPACE_WRITES.contains(toolName);
        }
        if (ConversationService.MODE_FULL_ACCESS.equals(mode)) {
            return FULL_ACCESS_TOOLS.contains(toolName);
        }
        return false;
    }

    private boolean askOnTaint() {
        if (mcpProperties == null) return true;
        return "ask".equals(mcpProperties.effectiveUntrustedPolicy());
    }

    private static void audit(ToolCallContext ctx, boolean highRisk, boolean approved) {
        if (ctx != null) ctx.setAudit(highRisk, approved);
    }
}
