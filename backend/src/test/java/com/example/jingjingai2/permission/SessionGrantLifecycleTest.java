package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「本会话允许」的键与清理。纯内存：不连库、不起 MCP 进程、不碰网络。
 *
 * <p>意图由 {@code decide(APPROVE_SESSION)} 记下，写入走网关复查通过后的
 * {@link ApprovalService#commitSessionGrant}。这里不经过网关，所以助手方法在 decide 之后显式提交，
 * 测的是键和清理，不是复查本身。
 */
class SessionGrantLifecycleTest {

    private static final long U1 = 7L;
    private static final long U2 = 9L;

    private ApprovalService approvals;

    @BeforeEach
    void setUp() {
        approvals = new ApprovalService(1);
    }

    /** 建一条允许「本会话允许」的审批并批准，再按复查通过后的入口写入。 */
    private void grant(long userId, String conversationId, String tool) {
        ApprovalService.Record r = approvals.create(userId, conversationId, tool, "MCP", "{}", "测试", false, true);
        assertEquals(200, approvals.decide(r.id, userId, "APPROVE_SESSION").http);
        assertTrue(approvals.commitSessionGrant(r));
    }

    @Test
    @DisplayName("A0：decide 只记意图，复查提交之前没有授权；未批准不能提交")
    void grantIsWrittenOnlyAfterCommit() {
        ApprovalService.Record pending = approvals.create(U1, "c1", "mcp__alpha__read_item", "MCP", "{}", "测试", false, true);
        assertFalse(approvals.commitSessionGrant(pending), "还没批准不能记");
        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"));

        assertEquals(200, approvals.decide(pending.id, U1, "APPROVE_SESSION").http);
        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"), "decide 本身不写授权");
        assertTrue(approvals.commitSessionGrant(pending));
        assertTrue(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"));

        ApprovalService.Record once = approvals.create(U1, "c1", "mcp__alpha__write_item", "MCP", "{}", "测试", false, true);
        approvals.decide(once.id, U1, "APPROVE");
        assertFalse(approvals.commitSessionGrant(once), "普通批准没有本会话允许的意图");
        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__write_item"));
    }

    @Test
    @DisplayName("A1：用户 / 会话 / 工具任一不同都不命中，三者相同才命中")
    void keyIsolatesAllThreeDimensions() {
        grant(U1, "c1", "mcp__alpha__read_item");

        assertTrue(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"), "同三元组应命中");
        assertFalse(approvals.sessionGranted(U2, "c1", "mcp__alpha__read_item"), "跨用户不命中");
        assertFalse(approvals.sessionGranted(U1, "c2", "mcp__alpha__read_item"), "跨会话不命中");
        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__write_item"), "跨工具不命中");
        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__beta__read_item"), "跨服务不命中");
        assertFalse(approvals.sessionGranted(U1, "c1", "writeFile"), "MCP 授权不落到同名的内置工具上");
    }

    @Test
    @DisplayName("A2：ask 时不许本会话授权（canSessionGrant=false）就一个都不记")
    void nothingRecordedWhenSessionGrantNotAllowed() {
        ApprovalService.Record r = approvals.create(U1, "c1", "executeCommand", "内置", "{}", "高危", false, false);
        approvals.decide(r.id, U1, "APPROVE_SESSION");
        assertFalse(approvals.sessionGranted(U1, "c1", "executeCommand"), "F 类强制询问不能被这样放开");
    }

    @Test
    @DisplayName("B1：forgetServer 只清这个用户的这个服务；别人的同名服务、其他服务、内置工具都不动")
    void forgetServerOnlyTouchesThatUserAndServer() {
        grant(U1, "c1", "mcp__alpha__read_item");
        grant(U2, "c2", "mcp__alpha__read_item");      // 另一个用户的同名服务
        grant(U1, "c1", "mcp__beta__peek");
        grant(U1, "c1", "writeFile");

        assertEquals(1, approvals.forgetServer(U1, "alpha"), "只清 U1 的 alpha");

        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"));
        assertTrue(approvals.sessionGranted(U2, "c2", "mcp__alpha__read_item"),
                "U2 的同名服务不能受影响");
        assertTrue(approvals.sessionGranted(U1, "c1", "mcp__beta__peek"), "别的服务不能动");
        assertTrue(approvals.sessionGranted(U1, "c1", "writeFile"), "内置工具不能动");
    }

    @Test
    @DisplayName("B2：服务名精确匹配，前缀相同的服务不互相误删")
    void forgetServerDoesNotPrefixMatch() {
        grant(U1, "c1", "mcp__alpha__read_item");
        grant(U1, "c1", "mcp__alpha2__read_item");

        assertEquals(1, approvals.forgetServer(U1, "alpha"));

        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"));
        assertTrue(approvals.sessionGranted(U1, "c1", "mcp__alpha2__read_item"), "alpha 不能顺手带走 alpha2");
    }

    @Test
    @DisplayName("B3：清掉之后，同名重建的服务拿到的是干净状态")
    void recreatedServerDoesNotInheritGrant() {
        grant(U1, "c1", "mcp__alpha__read_item");
        assertEquals(1, approvals.forgetServer(U1, "alpha"));

        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"),
                "同名服务的同一个对外名也拿不到旧授权");
    }

    @Test
    @DisplayName("B4：服务段被截断时不清（无法精确归属，宁可留着也不误删别人）")
    void truncatedServerSegmentIsLeftAlone() {
        String longServer = "s".repeat(30);      // 超过 25 字符，服务段会被截
        String longTool = "t".repeat(40);
        String exposed = McpNames.exposed(longServer, longTool);

        assertTrue(exposed.length() <= McpNames.MAX_LEN, exposed);
        assertNotEquals(longServer, McpNames.serverOf(exposed), "前提：服务段确实被截断");

        grant(U1, "c1", exposed);
        assertEquals(0, approvals.forgetServer(U1, longServer), "截断名还原不出服务名，本次不清理");
        assertTrue(approvals.sessionGranted(U1, "c1", exposed), "留下的也只是这一个用户的授权，不是误删");
    }

    @Test
    @DisplayName("B5：服务名短、只有工具名长时，截断不影响清理")
    void longToolNameStillCleaned() {
        String exposed = McpNames.exposed("alpha", "t".repeat(80));
        assertTrue(exposed.length() <= McpNames.MAX_LEN, exposed);
        assertEquals("alpha", McpNames.serverOf(exposed));

        grant(U1, "c1", exposed);
        assertEquals(1, approvals.forgetServer(U1, "alpha"));
        assertFalse(approvals.sessionGranted(U1, "c1", exposed));
    }

    @Test
    @DisplayName("C1：forgetConversation 只清这个用户这条会话")
    void forgetConversationIsScopedToUserAndConversation() {
        grant(U1, "c1", "mcp__alpha__read_item");
        grant(U1, "c1", "writeFile");
        grant(U1, "c2", "mcp__alpha__read_item");
        grant(U2, "c1", "mcp__alpha__read_item");

        assertEquals(2, approvals.forgetConversation(U1, "c1"));

        assertFalse(approvals.sessionGranted(U1, "c1", "mcp__alpha__read_item"));
        assertFalse(approvals.sessionGranted(U1, "c1", "writeFile"));
        assertTrue(approvals.sessionGranted(U1, "c2", "mcp__alpha__read_item"), "同用户别的会话不动");
        assertTrue(approvals.sessionGranted(U2, "c1", "mcp__alpha__read_item"), "别的用户同名字符串不动");
    }

    @Test
    @DisplayName("C2：清理可重复执行，第二次返回 0；空参数不炸")
    void forgetIsIdempotent() {
        grant(U1, "c1", "writeFile");

        assertEquals(1, approvals.forgetConversation(U1, "c1"));
        assertEquals(0, approvals.forgetConversation(U1, "c1"));
        assertEquals(0, approvals.forgetServer(U1, "nope"));
        assertEquals(0, approvals.forgetServer(U1, null));
        assertEquals(0, approvals.forgetServer(U1, "  "));
        assertEquals(0, approvals.forgetConversation(null, "c1"));
        assertEquals(0, approvals.forgetConversation(U1, null));
    }
}
