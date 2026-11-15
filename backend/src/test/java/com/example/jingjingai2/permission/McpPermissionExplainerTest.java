package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.FakeMcpServers;
import com.example.jingjingai2.mcp.FakeMcpSession;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpListedTool;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.mcp.McpRemoteTool;
import com.example.jingjingai2.mcp.McpRoundSelector;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.mcp.McpSession;
import com.example.jingjingai2.mcp.McpToolAnnotations;
import com.example.jingjingai2.mcp.McpToolSearchCallback;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * explain 的层级表，以及 DENY / 注解 / 污染和网关实际执行必须一致。
 */
class McpPermissionExplainerTest {

    private static final long USER = 7L;
    private MemoryMcpUserPolicyStore store;
    private McpClientManager manager;
    private McpPermissionExplainer explain;
    private ApprovalService approvals;
    private ToolPermissionGate gate;

    @BeforeEach
    void setUp() {
        store = new MemoryMcpUserPolicyStore();
        manager = managerOf();
        explain = new McpPermissionExplainer(store, manager);
        approvals = new ApprovalService(2);
        gate = new ToolPermissionGate(new PermissionPolicy(new PermissionProperties()), approvals,
                new McpProperties(), explain);
        ToolDispatcher.installGate(gate);
    }

    @AfterEach
    void tearDown() {
        ToolDispatcher.installGate(null);
        approvals.clear();
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("判定顺序：每一层以及谁盖过谁")
    void orderTable() {
        assertEffect("brand_new", null, "ASK", true, "未配置");

        store.setServerDefault(USER, "alpha", "ASK");
        assertEffect("write_note", null, "ASK", true, "服务默认询问");
        store.setServerDefault(USER, "alpha", "ALLOW");
        assertEffect("write_note", null, "ALLOW", false, "服务默认允许");
        store.setServerDefault(USER, "alpha", "DENY");
        assertEffect("write_note", null, "DENY", false, "服务默认拒绝");

        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        assertEffect("write_note", null, "ALLOW", false, "单独设为允许");
        store.setTool(USER, "alpha", "write_note", "ASK", hash("write_note"));
        assertEffect("write_note", null, "ASK", true, "单独设为询问");
        store.setTool(USER, "alpha", "write_note", "DENY", hash("write_note"));
        assertEffect("write_note", null, "DENY", false, "该工具被设为拒绝");

        store.setServerDefault(USER, "alpha", "ALLOW");
        assertEffect("write_note", null, "DENY", false, "该工具被设为拒绝");

        store.setTool(USER, "alpha", "wipe_all", "ALLOW", hash("wipe_all"));
        store.setServerDefault(USER, "alpha", "ALLOW");
        McpPermissionExplainer.ExplainResult forced = explain.explain(USER, "alpha", "wipe_all", null);
        assertEquals("ASK", forced.effect.name());
        assertTrue(forced.sessionGrant, "强制询问可以本会话允许");
        assertTrue(forced.reason.contains("wipe"), forced.reason);

        store.setServerDefault(USER, "alpha", "ALLOW");
        McpPermissionExplainer.ExplainResult hinted = explain.explain(USER, "alpha", "archive_box", null);
        assertEquals("ASK", hinted.effect.name(), hinted.reason);
        assertTrue(hinted.sessionGrant, "只有破坏性标注把服务级放行收成 ASK 时，可以本会话允许");
        assertTrue(hinted.reason.contains("单独设置"), hinted.reason);
        store.setTool(USER, "alpha", "archive_box", "ALLOW", hash("archive_box"));
        McpPermissionExplainer.ExplainResult hintedAllow = explain.explain(USER, "alpha", "archive_box", null);
        assertEquals("ALLOW", hintedAllow.effect.name(), hintedAllow.reason);

        store.setTool(USER, "alpha", "wipe_all", "DENY", hash("wipe_all"));
        McpPermissionExplainer.ExplainResult denyHolds = explain.explain(USER, "alpha", "wipe_all", null);
        assertEquals("DENY", denyHolds.effect.name());
        assertFalse(denyHolds.reason.contains("强制询问"), denyHolds.reason);

        store.setServerDefault(USER, "alpha", "DENY");
        store.resetTools(USER, "alpha");
        McpPermissionExplainer.ExplainResult serverDeny = explain.explain(USER, "alpha", "delete_item", null);
        assertEquals("DENY", serverDeny.effect.name(), serverDeny.reason);
        assertTrue(serverDeny.reason.contains("服务默认拒绝"), serverDeny.reason);

        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        McpPermissionExplainer.ExplainResult url = explain.explain(
                USER, "alpha", "write_note", "{\"url\":\"http://127.0.0.1/secret\"}");
        assertEquals("DENY", url.effect.name());
        assertTrue(url.reason.contains("不能覆盖"), url.reason);
        assertFalse(url.sessionGrant);
    }

    @Test
    @DisplayName("readOnlyHint 不能放行；名字命中盖不过单独 ALLOW；无害名字的破坏性标注可以被单独 ALLOW 盖过")
    void annotationsAreUntrusted() {
        McpPermissionExplainer.ExplainResult read = explain.explain(USER, "alpha", "read_item", null);
        assertEquals("ASK", read.effect.name());
        assertFalse(read.reason.contains("放行"));

        store.setTool(USER, "alpha", "delete_item", "ALLOW", hash("delete_item"));
        McpPermissionExplainer.ExplainResult named = explain.explain(USER, "alpha", "delete_item", null);
        assertEquals("ASK", named.effect.name());
        assertTrue(named.sessionGrant);
        assertTrue(named.reason.contains("delete"), named.reason);
        assertTrue(explain.maybeForceAsk("delete_item"));

        store.setTool(USER, "alpha", "wipe_all", "ALLOW", hash("wipe_all"));
        McpPermissionExplainer.ExplainResult hint = explain.explain(USER, "alpha", "wipe_all", "{}");
        assertEquals("ASK", hint.effect.name());
        assertTrue(hint.sessionGrant);
        assertTrue(explain.maybeForceAsk("wipe_all"));
        store.setTool(USER, "alpha", "evaluate_note", "ALLOW", hash("evaluate_note"));
        McpPermissionExplainer.ExplainResult eval = explain.explain(USER, "alpha", "evaluate_note", null);
        assertEquals("ASK", eval.effect.name(), eval.reason);
        assertTrue(eval.sessionGrant);
        assertTrue(eval.reason.contains("eval"), eval.reason);

        store.setTool(USER, "alpha", "unsafe_ping", "ALLOW", hash("unsafe_ping"));
        McpPermissionExplainer.ExplainResult unsafe = explain.explain(USER, "alpha", "unsafe_ping", null);
        assertEquals("ASK", unsafe.effect.name(), unsafe.reason);
        assertTrue(unsafe.sessionGrant);
        assertTrue(unsafe.reason.contains("unsafe"), unsafe.reason);
    }

    @Test
    @DisplayName("「可能强制询问」只表示 ALLOW 也盖不过的名字词表，与生效结果不矛盾")
    void maybeForceAskMatchesEffectiveDecision() {
        // 破坏性标注 + 单独 ALLOW：生效就是 ALLOW，标签不能再说「可能强制询问」
        store.setTool(USER, "alpha", "archive_box", "ALLOW", hash("archive_box"));
        McpPermissionExplainer.ExplainResult allow =
                explain.explain(USER, "alpha", "archive_box", null);
        assertEquals("ALLOW", allow.effect.name(), allow.reason);
        assertFalse(explain.maybeForceAsk("archive_box"), "ALLOW 能盖过破坏性标注，不该标强制询问");

        // 同样自报破坏性、但没有单独 ALLOW：生效是 ASK 且有原因，也不需要「可能」这个模糊标签
        store.resetTools(USER, "alpha");
        McpPermissionExplainer.ExplainResult ask =
                explain.explain(USER, "alpha", "archive_box", null);
        assertEquals("ASK", ask.effect.name(), ask.reason);
        assertTrue(ask.reason.contains("单独设置"), ask.reason);
        assertFalse(explain.maybeForceAsk("archive_box"));

        // 名字命中词表才是真的「ALLOW 也盖不过」
        store.setTool(USER, "alpha", "delete_item", "ALLOW", hash("delete_item"));
        McpPermissionExplainer.ExplainResult named =
                explain.explain(USER, "alpha", "delete_item", null);
        assertEquals("ASK", named.effect.name(), named.reason);
        assertTrue(explain.maybeForceAsk("delete_item"));
    }

    @Test
    @DisplayName("参数里的高危命令和 SQL 把 ALLOW 收成 ASK，并且可以本会话允许")
    void argumentPatternsForceAsk() {
        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        McpPermissionExplainer.ExplainResult rm = explain.explain(
                USER, "alpha", "write_note", "{\"text\":\"rm -rf /tmp/x\"}");
        assertEquals("ASK", rm.effect.name());
        assertTrue(rm.sessionGrant);
        assertTrue(rm.argumentForce);
        assertTrue(rm.reason.contains("高危命令") || rm.reason.contains("危险语句"), rm.reason);

        McpPermissionExplainer.ExplainResult sql = explain.explain(
                USER, "alpha", "write_note", "{\"text\":\"please DROP TABLE users\"}");
        assertEquals("ASK", sql.effect.name());
        assertTrue(sql.sessionGrant);
        assertTrue(sql.argumentForce);
    }

    @Test
    @DisplayName("定义变了：单工具设置按未配置，definitionChanged=true，服务级 DENY 仍在")
    void definitionChangeResetsToolSetting() {
        store.setTool(USER, "alpha", "read_item", "ALLOW", "deadbeef");
        McpPermissionExplainer.ExplainResult changed = explain.explain(USER, "alpha", "read_item", null);
        assertTrue(changed.definitionChanged);
        assertEquals("ASK", changed.effect.name(), changed.reason);
        assertFalse(changed.sessionGrant);
        assertTrue(changed.reason.contains("工具定义已变更，需重新确认"), changed.reason);

        store.setServerDefault(USER, "alpha", "ALLOW");
        McpPermissionExplainer.ExplainResult serverCannotLift = explain.explain(USER, "alpha", "read_item", null);
        assertTrue(serverCannotLift.definitionChanged);
        assertEquals("ASK", serverCannotLift.effect.name(), serverCannotLift.reason);
        assertFalse(serverCannotLift.sessionGrant);

        store.setTool(USER, "alpha", "archive_box", "ALLOW", "deadbeef");
        McpPermissionExplainer.ExplainResult destructiveChanged =
                explain.explain(USER, "alpha", "archive_box", null);
        assertTrue(destructiveChanged.definitionChanged);
        assertEquals("ASK", destructiveChanged.effect.name(), destructiveChanged.reason);
        assertFalse(destructiveChanged.sessionGrant, "定义变了，破坏性标注不能单独打开本会话允许");

        store.setTool(USER, "alpha", "write_note", "DENY", "deadbeef");
        McpPermissionExplainer.ExplainResult denyStays = explain.explain(USER, "alpha", "write_note", null);
        assertTrue(denyStays.definitionChanged);
        assertEquals("DENY", denyStays.effect.name(), denyStays.reason);

        store.setTool(USER, "alpha", "read_item", "ASK", "deadbeef");
        McpPermissionExplainer.ExplainResult askStays = explain.explain(USER, "alpha", "read_item", null);
        assertTrue(askStays.definitionChanged);
        assertEquals("ASK", askStays.effect.name(), askStays.reason);

        store.setTool(USER, "alpha", "read_item", "ALLOW", hash("read_item"));
        McpPermissionExplainer.ExplainResult reset = explain.explain(USER, "alpha", "read_item", null);
        assertFalse(reset.definitionChanged);
        assertEquals("ALLOW", reset.effect.name(), reset.reason);

        store.setServerDefault(USER, "alpha", "DENY");
        store.setTool(USER, "alpha", "archive_box", "ALLOW", "deadbeef");
        McpPermissionExplainer.ExplainResult stillDeny = explain.explain(USER, "alpha", "archive_box", null);
        assertTrue(stillDeny.definitionChanged);
        assertEquals("DENY", stillDeny.effect.name());
    }

    @Test
    @DisplayName("DENY 不进下发名单、不进搜索；直接喊名字也被网关拒绝")
    void denyIsNotOfferedAndCannotBeCalled() throws Exception {
        store.setTool(USER, "alpha", "write_note", "DENY", hash("write_note"));
        ToolCallback[] full = manager.callbacks();
        assertTrue(names(full).contains("mcp__alpha__write_note"));
        ToolCallback[] visible = McpToolFilter.dropDenied(USER, full, explain);
        assertFalse(names(visible).contains("mcp__alpha__write_note"));
        assertNotNull(ToolGuard.rejectReason("mcp__alpha__write_note", names(visible)));

        McpProperties deferred = manager.properties();
        deferred.setToolLoading("deferred");
        List<Message> msgs = List.of(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("1", "mcpToolSearch",
                        "ACTIVATED: mcp__alpha__write_note\n"))));
        ToolCallback[] round = McpRoundSelector.forRound(visible, manager, msgs);
        assertFalse(names(round).contains("mcp__alpha__write_note"));
        String found = new McpToolSearchCallback(McpRoundSelector.mcpCatalogOf(visible), manager.nameMap(), 5)
                .call("{\"query\":\"write_note\"}");
        assertFalse(found.contains("mcp__alpha__write_note"), found);

        String blocked;
        try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
            blocked = ToolDispatcher.dispatch(full, "mcp__alpha__write_note", "{}");
        }
        assertTrue(blocked.contains("已拒绝"), blocked);
        assertFalse(blocked.startsWith("ok:"), blocked);
    }

    @Test
    @DisplayName("网关执行和 explain 一致：ALLOW 直接跑，ASK 等审批，DENY 不跑")
    void gatewayMatchesExplain() throws Exception {
        store.setTool(USER, "alpha", "read_item", "ALLOW", hash("read_item"));
        store.setTool(USER, "alpha", "write_note", "ASK", hash("write_note"));
        store.setTool(USER, "alpha", "secret_tool", "DENY", hash("secret_tool"));

        assertRuns("mcp__alpha__read_item", "read_item");
        assertAsks("mcp__alpha__write_note", "write_note", true);
        assertDenies("mcp__alpha__secret_tool", "secret_tool");

        store.setTool(USER, "alpha", "wipe_all", "ALLOW", hash("wipe_all"));
        assertAsks("mcp__alpha__wipe_all", "wipe_all", true);
    }

    @Test
    @DisplayName("只因破坏性标注而 ASK：本会话允许记下来，同三元组第二次直接执行")
    void destructiveHintAloneCanBeSessionGranted() throws Exception {
        store.setServerDefault(USER, "alpha", "ALLOW");
        approveSessionThenAgain("mcp__alpha__archive_box", "{}", true);
    }

    @Test
    @DisplayName("已记下的破坏性标注授权不覆盖随后的高危参数")
    void destructiveSessionGrantDoesNotCoverDangerousArgs() throws Exception {
        store.setServerDefault(USER, "alpha", "ALLOW");
        approveSessionThenAgain("mcp__alpha__archive_box", "{}", true);

        String args = "{\"text\":\"rm -rf /tmp/x\"}";
        String[] danger = new String[1];
        Thread t = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                danger[0] = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__archive_box", args);
            }
        });
        t.start();
        ApprovalService.Record rec = awaitPending();
        assertTrue(rec.canSessionGrant, rec.risk);
        assertTrue(rec.risk.contains("强制询问"), rec.risk);
        approvals.decide(rec.id, USER, "REJECT");
        t.join(3000);
        assertTrue(danger[0].contains("用户拒绝"), danger[0]);
    }

    @Test
    @DisplayName("危险名称点本会话允许后，同工具下次不再问")
    void dangerousNameCanBeSessionGranted() throws Exception {
        store.setServerDefault(USER, "alpha", "ALLOW");
        store.setTool(USER, "alpha", "delete_item", "ALLOW", hash("delete_item"));
        approveSessionThenAgain("mcp__alpha__delete_item", "{}", true);
    }

    @Test
    @DisplayName("高危参数的本会话允许只覆盖高危参数，不写到普通调用的键上")
    void dangerousArgsSessionGrantIsSeparate() throws Exception {
        store.setServerDefault(USER, "alpha", "ALLOW");
        String args = "{\"text\":\"rm -rf /tmp/x\"}";
        String[] first = new String[1];
        Thread t = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                first[0] = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__archive_box", args);
            }
        });
        t.start();
        ApprovalService.Record rec = awaitPending();
        assertTrue(rec.canSessionGrant, rec.risk);
        approvals.decide(rec.id, USER, "APPROVE_SESSION");
        t.join(3000);
        assertTrue(first[0].startsWith("ok:"), String.valueOf(first[0]));
        assertFalse(approvals.sessionGranted(USER, "conv", "mcp__alpha__archive_box"));
        assertTrue(approvals.sessionGranted(USER, "conv",
                ApprovalService.argGrantName("mcp__alpha__archive_box")));
        try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
            String second = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__archive_box", args);
            assertTrue(second.startsWith("ok:"), second);
        }
    }

    @Test
    @DisplayName("破坏性标注加上危险名称时，本会话允许仍然有效")
    void destructivePlusDangerousNameCannotBeSessionGranted() throws Exception {
        store.setServerDefault(USER, "alpha", "ALLOW");
        store.setTool(USER, "alpha", "wipe_all", "ALLOW", hash("wipe_all"));
        approveSessionThenAgain("mcp__alpha__wipe_all", "{}", true);
    }

    @Test
    @DisplayName("定义变了的破坏性工具不能靠本会话允许跳过重新确认")
    void definitionChangeBlocksDestructiveSessionGrant() throws Exception {
        store.setServerDefault(USER, "alpha", "ALLOW");
        store.setTool(USER, "alpha", "archive_box", "ALLOW", "deadbeef");
        approveSessionThenAgain("mcp__alpha__archive_box", "{}", false);
    }

    @Test
    @DisplayName("污染后写文件仍逐次问；别的 MCP 服务强制询问；同服务不额外拦")
    void taintRules() throws Exception {
        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        store.setTool(USER, "beta", "peek", "ALLOW", hashOf("beta", "peek"));
        manager.markTainted("conv", "alpha");
        ToolCallContext tainted = new ToolCallContext(USER, "conv", true, false, events(),
                () -> manager.isConversationTainted("conv"));

        try (ToolCallContext.Scope ignored = ToolCallContext.open(tainted)) {
            McpPermissionExplainer.ExplainResult same = explain.explain(USER, "alpha", "write_note", null);
            assertEquals("ALLOW", same.effect.name(), same.reason);
            McpPermissionExplainer.ExplainResult other = explain.explain(USER, "beta", "peek", null);
            assertEquals("ASK", other.effect.name(), other.reason);
            assertTrue(other.sessionGrant);
            assertTrue(other.crossServiceTaint);
        }

        String[] first = new String[1];
        Thread t1 = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(tainted)) {
                first[0] = ToolDispatcher.dispatch(manager.callbacks(), "mcp__beta__peek", "{}");
            }
        });
        t1.start();
        ApprovalService.Record rec = awaitPending();
        assertTrue(rec.canSessionGrant);
        assertTrue(rec.risk.contains("其他 MCP"), rec.risk);
        approvals.decide(rec.id, USER, "APPROVE_SESSION");
        t1.join(3000);
        assertTrue(first[0].startsWith("ok:"), first[0]);
        assertTrue(approvals.sessionGranted(USER, "conv", "mcp__beta__peek"));

        try (ToolCallContext.Scope ignored = ToolCallContext.open(tainted)) {
            String ran = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__write_note", "{}");
            assertTrue(ran.startsWith("ok:"), ran);
        }

        AtomicReference<String> file = new AtomicReference<>();
        ToolCallContext fileCtx = new ToolCallContext(USER, "conv", true, false, events(), () -> true);
        Thread write = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(fileCtx)) {
                file.set(ToolDispatcher.dispatch(new ToolCallback[]{stubWrite()}, "writeFile",
                        "{\"path\":\"a.txt\"}"));
            }
        });
        write.start();
        ApprovalService.Record w = awaitPending();
        assertTrue(w.canSessionGrant);
        assertTrue(String.valueOf(w.risk).contains("网页内容诱发"), w.risk);
        approvals.decide(w.id, USER, "APPROVE_SESSION");
        write.join(3000);
        assertEquals("wrote", file.get());
        assertTrue(approvals.sessionGranted(USER, "conv", "writeFile"));

        try (ToolCallContext.Scope ignored = ToolCallContext.open(fileCtx)) {
            String second = ToolDispatcher.dispatch(new ToolCallback[]{stubWrite()}, "writeFile",
                    "{\"path\":\"b.txt\"}");
            assertEquals("wrote", second);
        }
        assertTrue(approvals.pending().isEmpty());
    }

    @Test
    @DisplayName("优先级表：单工具规则 > 服务级默认 > 未配置")
    void priorityTable() {
        // ① 服务级 DENY + 单工具 ALLOW → ALLOW
        store.setServerDefault(USER, "alpha", "DENY");
        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        assertEffect("write_note", null, "ALLOW", false, "该工具单独设为允许");

        // ② 服务级 DENY + 另一个工具没有单工具规则 → DENY
        assertEffect("read_item", null, "DENY", false, "服务默认拒绝");

        // ③ 服务级 ALLOW + 单工具 DENY → DENY
        store.resetTools(USER, "alpha");
        store.setServerDefault(USER, "alpha", "ALLOW");
        store.setTool(USER, "alpha", "write_note", "DENY", hash("write_note"));
        assertEffect("write_note", null, "DENY", false, "该工具被设为拒绝");

        // ④ 服务级 ASK + 单工具 ALLOW → ALLOW
        store.setServerDefault(USER, "alpha", "ASK");
        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        assertEffect("write_note", null, "ALLOW", false, "该工具单独设为允许");

        // ⑤ 服务级 ASK + 单工具 DENY → DENY
        store.setTool(USER, "alpha", "write_note", "DENY", hash("write_note"));
        assertEffect("write_note", null, "DENY", false, "该工具被设为拒绝");

        // ⑥ 服务级 ASK + 没有单工具规则 → ASK
        store.resetTools(USER, "alpha");
        assertEffect("write_note", null, "ASK", true, "服务默认询问");

        // ⑦ 都没配置 → 现有默认行为（ASK，可本会话授权）
        store.clearServerDefault(USER, "alpha");
        assertEffect("write_note", null, "ASK", true, "未配置");
    }

    @Test
    @DisplayName("单工具 ALLOW 越过服务级 DENY 时，执行层确实执行（解释与执行一致）")
    void toolAllowBeatsServerDenyInGateway() {
        store.setServerDefault(USER, "alpha", "DENY");
        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        assertRuns("mcp__alpha__write_note", "write_note");
    }

    @Test
    @DisplayName("服务级 ALLOW 盖不过单工具 DENY，执行层确实不跑")
    void serverAllowDoesNotOverrideToolDeny() {
        store.setServerDefault(USER, "alpha", "ALLOW");
        store.setTool(USER, "alpha", "write_note", "DENY", hash("write_note"));
        assertDenies("mcp__alpha__write_note", "write_note");
    }

    @Test
    @DisplayName("单工具 ALLOW 也打不开强制询问：名字命中仍要问，但可以本会话允许")
    void toolAllowDoesNotBypassForcedAsk() {
        store.setServerDefault(USER, "alpha", "DENY");
        store.setTool(USER, "alpha", "delete_item", "ALLOW", hash("delete_item"));
        McpPermissionExplainer.ExplainResult er = explain.explain(USER, "alpha", "delete_item", null);
        assertEquals("ASK", er.effect.name(), er.reason);
        assertTrue(er.sessionGrant);
        assertTrue(er.reason.contains("delete"), er.reason);
    }

    @Test
    @DisplayName("不同用户的授权互不影响")
    void userPoliciesAreIsolated() {
        long other = 9L;
        store.setServerDefault(USER, "alpha", "ALLOW");
        store.setTool(USER, "alpha", "write_note", "ALLOW", hash("write_note"));
        store.setTool(other, "alpha", "write_note", "DENY", hash("write_note"));

        assertEquals("ALLOW", explain.explain(USER, "alpha", "write_note", null).effect.name());
        McpPermissionExplainer.ExplainResult otherTool =
                explain.explain(other, "alpha", "write_note", null);
        assertEquals("DENY", otherTool.effect.name(), otherTool.reason);

        // 用户 9 没有服务级规则：不受用户 7 的服务级 ALLOW 影响
        assertEquals("ALLOW", explain.explain(USER, "alpha", "read_item", null).effect.name());
        assertEquals("ASK", explain.explain(other, "alpha", "read_item", null).effect.name());
    }

    @Test
    @DisplayName("单工具 ALLOW 和已存在的会话授权都不能绕过 URL 硬限制")
    void allowAndSessionGrantCannotBypassUrl() throws Exception {
        store.setTool(USER, "alpha", "read_item", "ASK", hash("read_item"));

        // 先走一次正常流程，拿到「本会话允许」
        String[] first = new String[1];
        Thread t = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                first[0] = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__read_item", "{}");
            }
        });
        t.start();
        ApprovalService.Record rec = awaitPending();
        approvals.decide(rec.id, USER, "APPROVE_SESSION");
        t.join(3000);
        assertTrue(first[0].startsWith("ok:"), first[0]);
        assertTrue(approvals.sessionGranted(USER, "conv", "mcp__alpha__read_item"),
                "前置条件：会话授权已生效");

        // 已授权之后，带内网地址的调用仍必须在网关第一道就被拒
        try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
            String blocked = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__read_item",
                    "{\"url\":\"http://169.254.169.254/latest/meta-data/\"}");
            assertTrue(blocked.contains("地址不被允许"), blocked);
            assertFalse(blocked.startsWith("ok:"), blocked);
        }
    }

    @Test
    @DisplayName("服务被删时清掉会话授权，同名重建后同一个工具会重新询问")
    void forgetServerMakesRecreatedServerAskAgain() throws Exception {
        store.setTool(USER, "alpha", "read_item", "ASK", hash("read_item"));

        String[] first = new String[1];
        Thread t1 = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                first[0] = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__read_item", "{}");
            }
        });
        t1.start();
        approvals.decide(awaitPending().id, USER, "APPROVE_SESSION");
        t1.join(3000);
        assertTrue(first[0].startsWith("ok:"), first[0]);
        assertTrue(approvals.sessionGranted(USER, "conv", "mcp__alpha__read_item"), "前置：授权已记下");

        // 删除服务这一步（数据库部分由阶段 2B 负责，这里只验内存部分）
        assertEquals(1, approvals.forgetServer(USER, "alpha"));
        assertFalse(approvals.sessionGranted(USER, "conv", "mcp__alpha__read_item"));

        // 同名重建后，同一个对外名不能再被旧授权放行
        String[] second = new String[1];
        Thread t2 = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                second[0] = ToolDispatcher.dispatch(manager.callbacks(), "mcp__alpha__read_item", "{}");
            }
        });
        t2.start();
        ApprovalService.Record again = awaitPending();
        approvals.decide(again.id, USER, "REJECT");
        t2.join(3000);
        assertTrue(String.valueOf(second[0]).contains("用户拒绝了"), String.valueOf(second[0]));
    }

    @Test
    @DisplayName("单工具 ALLOW 打不开被 disabled-tools 排除的工具：执行层根本没有它")
    void allowCannotUnlockExcludedTool() {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpServerSpec spec = spec("echo");
        spec.setDisabledTools(List.of("write_note"));
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("alpha", spec);
        props.setServers(servers);
        FakeMcpSession alpha = new FakeMcpSession("alpha", List.of(
                tool("read_item", "read", null),
                tool("write_note", "write", null)));
        McpClientManager mgr = FakeMcpServers.start(props, Map.of("alpha", alpha));
        try {
            McpListedTool listed = mgr.findListed("alpha", "write_note");
            assertNotNull(listed);
            assertFalse(listed.exposed, "被排除的工具仍然是「已发现但未暴露」");

            // 即使库里就有一条 ALLOW（绕过接口直接写），规则层也确实是 ALLOW
            MemoryMcpUserPolicyStore s2 = new MemoryMcpUserPolicyStore();
            s2.setTool(USER, "alpha", "write_note", "ALLOW", listed.definitionHash());
            McpPermissionExplainer ex2 = new McpPermissionExplainer(s2, mgr);
            assertEquals("ALLOW", ex2.explain(USER, "alpha", "write_note", null).effect.name());

            // 但执行层没有这个回调，模型喊名字也过不去
            List<String> callbacks = names(mgr.callbacks());
            assertFalse(callbacks.contains("mcp__alpha__write_note"), callbacks.toString());
            assertNotNull(ToolGuard.rejectReason("mcp__alpha__write_note", callbacks));
        } finally {
            mgr.stop();
        }
    }

    @Test
    @DisplayName("全新工具没有任何权限设置时默认 ASK，且允许本会话授权")
    void newToolDefaultsToAsk() {
        // 权限存储全空：没有该工具的单工具设置，也没有服务级默认，服务本身都没配置过。
        // 这条不能靠「把服务默认设成 ASK」来间接证明。
        McpPermissionExplainer.ExplainResult er =
                explain.explain(USER, "brand_new_server", "brand_new_tool", null);
        assertEquals("ASK", er.effect.name(), er.reason);
        assertTrue(er.sessionGrant, "未配置的 MCP 工具按设计允许本会话授权：" + er.reason);
        assertFalse(er.definitionChanged, er.reason);
        assertTrue(er.reason.contains("未配置"), er.reason);
    }

    private void assertEffect(String tool, String args, String effect, boolean grant, String reasonPart) {
        McpPermissionExplainer.ExplainResult er = explain.explain(USER, "alpha", tool, args);
        assertEquals(effect, er.effect.name(), er.reason);
        assertEquals(grant, er.sessionGrant, er.reason);
        assertTrue(er.reason.contains(reasonPart), er.reason);
    }

    private void assertRuns(String exposed, String original) {
        McpPermissionExplainer.ExplainResult er = explain.explain(USER, "alpha", original, "{}");
        assertEquals("ALLOW", er.effect.name(), er.reason);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
            String ran = ToolDispatcher.dispatch(manager.callbacks(), exposed, "{}");
            assertTrue(ran.startsWith("ok:"), ran);
        }
    }

    private void assertDenies(String exposed, String original) {
        McpPermissionExplainer.ExplainResult er = explain.explain(USER, "alpha", original, "{}");
        assertEquals("DENY", er.effect.name(), er.reason);
        try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
            String blocked = ToolDispatcher.dispatch(manager.callbacks(), exposed, "{}");
            assertTrue(blocked.contains("已拒绝"), blocked);
            assertFalse(blocked.startsWith("ok:"), blocked);
        }
    }

    private void assertAsks(String exposed, String original, boolean grant) throws Exception {
        McpPermissionExplainer.ExplainResult er = explain.explain(USER, "alpha", original, "{}");
        assertEquals("ASK", er.effect.name(), er.reason);
        assertEquals(grant, er.sessionGrant);
        AtomicReference<String> out = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                out.set(ToolDispatcher.dispatch(manager.callbacks(), exposed, "{}"));
            }
        });
        worker.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals(grant, rec.canSessionGrant);
        assertTrue(rec.risk.contains(reasonCore(er.reason)), rec.risk);
        approvals.decide(rec.id, USER, "REJECT");
        worker.join(3000);
        assertTrue(out.get().contains("用户拒绝"), out.get());
    }

    /**
     * 第一次走审批并请求本会话允许。sticks 为真时第二次同参数直接执行；
     * 为假时授权不得记下，第二次仍要弹卡。
     */
    private void approveSessionThenAgain(String exposed, String args, boolean sticks) throws Exception {
        String[] first = new String[1];
        Thread t = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                first[0] = ToolDispatcher.dispatch(manager.callbacks(), exposed, args);
            }
        });
        t.start();
        ApprovalService.Record rec = awaitPending();
        assertEquals(sticks, rec.canSessionGrant, rec.risk);
        approvals.decide(rec.id, USER, "APPROVE_SESSION");
        t.join(3000);
        assertTrue(first[0].startsWith("ok:"), String.valueOf(first[0]));
        assertEquals(sticks, approvals.sessionGranted(USER, "conv", exposed), exposed);

        if (sticks) {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                String second = ToolDispatcher.dispatch(manager.callbacks(), exposed, args);
                assertTrue(second.startsWith("ok:"), second);
            }
            assertTrue(approvals.pending().isEmpty(), "第二次不该再挂起审批");
            return;
        }

        String[] second = new String[1];
        Thread again = new Thread(() -> {
            try (ToolCallContext.Scope ignored = ToolCallContext.open(clean())) {
                second[0] = ToolDispatcher.dispatch(manager.callbacks(), exposed, args);
            }
        });
        again.start();
        ApprovalService.Record next = awaitPending();
        assertFalse(next.canSessionGrant, next.risk);
        approvals.decide(next.id, USER, "REJECT");
        again.join(3000);
        assertTrue(String.valueOf(second[0]).contains("用户拒绝"), String.valueOf(second[0]));
    }

    private static String reasonCore(String reason) {
        int cut = reason.indexOf('；');
        return cut < 0 ? reason : reason.substring(0, cut);
    }

    private ApprovalService.Record awaitPending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (ApprovalService.Record r : approvals.pending()) {
                if (r.status() == ApprovalService.Status.PENDING) return r;
            }
            Thread.sleep(20);
        }
        fail("没有等到审批");
        return null;
    }

    private ToolCallContext clean() {
        return new ToolCallContext(USER, "conv", true, false, events(), () -> false);
    }

    private static ToolCallContext.Events events() {
        return new ToolCallContext.Events() {
            @Override public void approval(Map<String, Object> payload) {}
            @Override public void approvalResult(Map<String, Object> payload) {}
        };
    }

    private String hash(String tool) {
        McpListedTool listed = manager.findListed("alpha", tool);
        assertNotNull(listed, tool);
        return listed.definitionHash();
    }

    private String hashOf(String server, String tool) {
        McpListedTool listed = manager.findListed(server, tool);
        assertNotNull(listed);
        return listed.definitionHash();
    }

    private static List<String> names(ToolCallback[] cbs) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (cbs == null) return out;
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null) out.add(cb.getToolDefinition().name());
        }
        return out;
    }

    private static ToolCallback stubWrite() {
        return new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("writeFile").description("w").inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String arguments) { return "wrote"; }
        };
    }

    private static McpToolAnnotations ann(Boolean readOnly, Boolean destructive) {
        return new McpToolAnnotations(readOnly, destructive, null, null);
    }

    private static McpClientManager managerOf() {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        Map<String, McpServerSpec> servers = new LinkedHashMap<>();
        servers.put("alpha", spec("echo"));
        servers.put("beta", spec("peek"));
        props.setServers(servers);
        FakeMcpSession alpha = new FakeMcpSession("alpha", List.of(
                tool("read_item", "read", ann(true, null)),
                tool("write_note", "write", null),
                tool("delete_item", "delete one", ann(true, null)),
                tool("wipe_all", "wipe", ann(null, true)),
                tool("archive_box", "archive", ann(null, true)),
                tool("evaluate_note", "score", null),
                tool("unsafe_ping", "ping", null),
                tool("secret_tool", "secret", null)));
        FakeMcpSession beta = new FakeMcpSession("beta", List.of(tool("peek", "peek", null)));
        Map<String, FakeMcpSession> fakes = Map.of("alpha", alpha, "beta", beta);
        return FakeMcpServers.start(props, fakes);
    }

    private static McpServerSpec spec(String description) {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        spec.setDescription(description);
        spec.setTrust("untrusted");
        return spec;
    }

    private static McpRemoteTool tool(String name, String description, McpToolAnnotations annotations) {
        return new McpRemoteTool(name, description, "{\"type\":\"object\"}", annotations);
    }
}
