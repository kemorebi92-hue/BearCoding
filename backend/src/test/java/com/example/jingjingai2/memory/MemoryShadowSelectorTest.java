package com.example.jingjingai2.memory;

import com.example.jingjingai2.entity.UserMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子选择的规则测试。数据全部是合成的，不读用户真实记忆。
 */
class MemoryShadowSelectorTest {

    private static MemoryItem user(String category, String key, String content, boolean protect) {
        return new MemoryItem(MemoryItem.Source.USER, "user:" + category + ":" + key,
                category, key, content, protect, null);
    }

    private static MemoryItem workspace(String name, String content) {
        return MemoryShadowSelector.fromWorkspaceFile(name, content);
    }

    @Test
    @DisplayName("关键词重合的条目被选中，完全不重合的标为不确定而不是排除")
    void overlapSelectsAndMissIsUncertain() {
        List<MemoryItem> items = List.of(
                user("project_context", "billing", "账单模块用按量计费", false),
                user("profile", "city", "住在杭州", false));
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("账单怎么算", items, 0);
        assertEquals(MemoryShadowSelector.Decision.SELECTED, r.verdicts.get(0).decision);
        assertEquals(MemoryShadowSelector.REASON_OVERLAP, r.verdicts.get(0).reason);
        assertEquals(MemoryShadowSelector.Decision.UNCERTAIN, r.verdicts.get(1).decision);
        assertEquals(MemoryShadowSelector.REASON_NO_OVERLAP, r.verdicts.get(1).reason);
        assertTrue(r.uncertain);
    }

    @Test
    @DisplayName("排在后面的受保护约束仍然入选，且先于普通条目占预算")
    void protectedConstraintSurvivesOrderAndBudget() {
        MemoryItem noise = user("profile", "note", "账单账单账单" + "x".repeat(50), false);
        MemoryItem rule = user("constraint", "no-maven", "本机没有 Maven，用 test.sh", true);
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("账单", List.of(noise, rule), 40);
        MemoryShadowSelector.Verdict protectedVerdict = verdictFor(r, rule.id());
        assertEquals(MemoryShadowSelector.Decision.SELECTED, protectedVerdict.decision);
        assertEquals(MemoryShadowSelector.REASON_PROTECTED, protectedVerdict.reason);
        assertEquals(MemoryShadowSelector.Decision.EXCLUDED, verdictFor(r, noise.id()).decision);
        assertEquals(MemoryShadowSelector.REASON_BUDGET, verdictFor(r, noise.id()).reason);
    }

    @Test
    @DisplayName("同键不同正文标为冲突，不做真伪判断")
    void conflictingVersionsStayUncertain() {
        MemoryItem oldOne = user("project_context", "port", "服务端口是 8080", false);
        MemoryItem newOne = user("project_context", "port", "服务端口改成 9090", false);
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("端口", List.of(oldOne, newOne), 0);
        assertTrue(r.conflict);
        assertEquals(MemoryShadowSelector.REASON_CONFLICT, r.verdicts.get(0).reason);
        assertEquals(MemoryShadowSelector.Decision.UNCERTAIN, r.verdicts.get(0).decision);
        assertEquals(MemoryShadowSelector.Decision.UNCERTAIN, r.verdicts.get(1).decision);
    }

    @Test
    @DisplayName("关键词命中但来自另一个工作区引用的条目仍可被排除：靠 ref 不跨空间")
    void workspaceRefStaysInItsOwnId() {
        MemoryItem here = workspace("billing.md", "本工作区账单按量计费");
        MemoryItem there = workspace("other-billing.md", "另一个项目的账单按年费");
        assertNotEquals(here.id(), there.id());
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("年费", List.of(here, there), 0);
        assertEquals(MemoryShadowSelector.Decision.UNCERTAIN, verdictFor(r, here.id()).decision);
        assertEquals(MemoryShadowSelector.Decision.SELECTED, verdictFor(r, there.id()).decision);
    }

    @Test
    @DisplayName("空查询不把普通条目判死，受保护条目照样保留")
    void emptyQueryKeepsOnlyProtected() {
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("  ",
                List.of(user("profile", "name", "jing", false),
                        user("constraint", "lang", "回答用中文", true)), 0);
        assertEquals(MemoryShadowSelector.REASON_EMPTY_QUERY, verdictFor(r, "user:profile:name").reason);
        assertEquals(MemoryShadowSelector.Decision.UNCERTAIN, verdictFor(r, "user:profile:name").decision);
        assertEquals(MemoryShadowSelector.Decision.SELECTED, verdictFor(r, "user:constraint:lang").decision);
        assertEquals(MemoryShadowSelector.REASON_PROTECTED, verdictFor(r, "user:constraint:lang").reason);
    }

    @Test
    @DisplayName("空列表、空正文、超长条目都不抛")
    void emptyAndLongInputsDoNotThrow() {
        assertTrue(MemoryShadowSelector.select("x", List.of(), 10).verdicts.isEmpty());
        assertTrue(MemoryShadowSelector.select(null, null, 10).verdicts.isEmpty());
        MemoryItem blank = user("profile", "empty", "   ", false);
        assertTrue(MemoryShadowSelector.select("empty", List.of(blank), 10).verdicts.isEmpty());
        MemoryItem huge = user("profile", "huge", "账单" + "y".repeat(100_000), false);
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("账单", List.of(huge), 1000);
        assertEquals(MemoryShadowSelector.REASON_BUDGET, r.verdicts.get(0).reason);
    }

    @Test
    @DisplayName("用户记忆转换：constraint 受保护，id 含类别和 key，不带正文以外的字段")
    void userMemoryConversionMarksConstraint() {
        UserMemory row = new UserMemory();
        row.setCategory("constraint");
        row.setMemKey("build");
        row.setContent("本机没有 Maven");
        row.setUpdatedAt(new Date(1_700_000_000_000L));
        List<MemoryItem> items = MemoryShadowSelector.fromUserMemories(List.of(row));
        assertEquals(1, items.size());
        assertEquals("user:constraint:build", items.get(0).id());
        assertTrue(items.get(0).protectedItem());
        assertEquals(1_700_000_000_000L, items.get(0).updatedAtMillis());
    }

    @Test
    @DisplayName("观测日志行不含正文")
    void observationLogOmitsBody() {
        MemoryItemObservation o = new MemoryItemObservation(
                "user", "user:constraint:build", "constraint", "build",
                12, 6, true, "", true);
        String line = o.toLogLine();
        assertFalse(line.contains("本机没有"));
        assertTrue(line.contains("id=user:constraint:build"));
        assertTrue(line.contains("protected=true"));
    }

    @Test
    @DisplayName("顺序不影响普通条目的判定")
    void orderDoesNotChangeOrdinaryVerdicts() {
        MemoryItem a = user("profile", "a", "账单模块", false);
        MemoryItem b = user("profile", "b", "日志模块", false);
        MemoryShadowSelector.Result forward = MemoryShadowSelector.select("账单", List.of(a, b), 0);
        MemoryShadowSelector.Result backward = MemoryShadowSelector.select("账单", List.of(b, a), 0);
        assertEquals(verdictFor(forward, a.id()).decision, verdictFor(backward, a.id()).decision);
        assertEquals(verdictFor(forward, b.id()).decision, verdictFor(backward, b.id()).decision);
    }

    private static MemoryShadowSelector.Verdict verdictFor(MemoryShadowSelector.Result r, String id) {
        for (MemoryShadowSelector.Verdict v : r.verdicts) if (v.item.id().equals(id)) return v;
        fail("没有这条判定: " + id);
        return null;
    }

    @Test
    @DisplayName("选择结果的字符与 token 只统计被选中的条目")
    void budgetCountsOnlySelected() {
        MemoryItem kept = user("profile", "a", "账单", false);
        MemoryItem dropped = user("profile", "b", "无关内容", false);
        MemoryShadowSelector.Result r = MemoryShadowSelector.select("账单", List.of(kept, dropped), 0);
        assertEquals(kept.chars(), r.selectedChars);
        assertEquals(kept.tokens(), r.selectedTokens);
        assertTrue(new ArrayList<>(r.notSelected()).contains(dropped));
    }
}
