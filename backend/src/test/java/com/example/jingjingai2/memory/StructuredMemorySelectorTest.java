package com.example.jingjingai2.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 结构化选择器的规则测试。数据全部合成。 */
class StructuredMemorySelectorTest {

    private static MemoryItem item(String id, String content, boolean protect) {
        return new MemoryItem(MemoryItem.Source.WORKSPACE, id, null, id, content, protect, null);
    }

    private static StructuredMemorySelector.Candidate c(MemoryItem item, Long user, String ws,
                                                        String topic, String supersedes) {
        return new StructuredMemorySelector.Candidate(item, user, ws, topic,
                StructuredMemorySelector.Status.ACTIVE, supersedes);
    }

    private static StructuredMemorySelector.TaskContext task(String ws, String... topics) {
        return new StructuredMemorySelector.TaskContext(1L, ws, List.of(topics), true);
    }

    private static StructuredMemorySelector.Verdict verdict(StructuredMemorySelector.Result r, String id) {
        for (StructuredMemorySelector.Verdict v : r.verdicts) {
            if (v.candidate.item.id().equals(id)) return v;
        }
        fail("缺判定 " + id);
        return null;
    }

    @Test
    @DisplayName("跨工作区条目直接排除，即使用词完全重合")
    void otherWorkspaceExcludedDespiteKeyword() {
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "billing"),
                List.of(c(item("blog-billing", "blog 账单按年费", false), 1L, "blog", "billing", null)),
                0);
        assertEquals(StructuredMemorySelector.Decision.EXCLUDED, r.verdicts.get(0).decision);
        assertEquals(StructuredMemorySelector.REASON_OTHER_WORKSPACE, r.verdicts.get(0).reason);
    }

    @Test
    @DisplayName("跨用户条目排除；用户级条目（无工作区）在任意工作区保留")
    void userIsolationAndUserLevelItems() {
        MemoryItem other = item("u2", "别人的偏好", false);
        MemoryItem mine = new MemoryItem(MemoryItem.Source.USER, "user:preference:lang",
                "preference", "lang", "回答用中文", false, null);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "reply-style"),
                List.of(c(other, 2L, null, "reply-style", null),
                        c(mine, 1L, null, "reply-style", null)), 0);
        assertEquals(StructuredMemorySelector.REASON_OTHER_USER, verdict(r, "u2").reason);
        assertEquals(StructuredMemorySelector.Decision.SELECTED, verdict(r, "user:preference:lang").decision);
    }

    @Test
    @DisplayName("显式替代关系：旧条目标 SUPERSEDED，新条目按主题选中")
    void explicitSupersession() {
        MemoryItem oldOne = item("port-old", "服务端口是 8080", false);
        MemoryItem newOne = item("port-new", "服务端口改成 9090", false);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "deploy"),
                List.of(c(oldOne, 1L, "shop", "deploy", null),
                        c(newOne, 1L, "shop", "deploy", "port-old")), 0);
        assertEquals(StructuredMemorySelector.Decision.SUPERSEDED, verdict(r, "port-old").decision);
        assertEquals(StructuredMemorySelector.Decision.SELECTED, verdict(r, "port-new").decision);
    }

    @Test
    @DisplayName("别的工作区的替代声明不影响本工作区")
    void supersessionDoesNotCrossWorkspace() {
        MemoryItem here = item("port", "本工作区端口 8080", false);
        MemoryItem there = item("port-blog", "blog 端口 9090", false);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "deploy"),
                List.of(c(here, 1L, "shop", "deploy", null),
                        c(there, 1L, "blog", "deploy", "port")), 0);
        assertEquals(StructuredMemorySelector.Decision.SELECTED, verdict(r, "port").decision);
        assertEquals(StructuredMemorySelector.Decision.EXCLUDED, verdict(r, "port-blog").decision);
    }

    @Test
    @DisplayName("同词不同主题：主题不符且词面重合时标 UNCERTAIN，不选中")
    void sameKeywordDifferentTopicStaysUncertain() {
        MemoryItem order = item("order-timeout", "订单超时 30 分钟自动取消", false);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "session"),
                List.of(c(order, 1L, "shop", "order", null)), 0);
        assertEquals(StructuredMemorySelector.Decision.EXCLUDED, verdict(r, "order-timeout").decision,
                "主题标签本身不出现在正文里，词面无重合，应排除");

        MemoryItem decoy = item("decoy", "session 会话管理与订单无关", false);
        StructuredMemorySelector.Result r2 = StructuredMemorySelector.select(
                task("shop", "session"),
                List.of(c(decoy, 1L, "shop", "order", null)), 0);
        assertEquals(StructuredMemorySelector.Decision.UNCERTAIN, verdict(r2, "decoy").decision);
        assertEquals(StructuredMemorySelector.REASON_TOPIC_MISMATCH, verdict(r2, "decoy").reason);
    }

    @Test
    @DisplayName("无主题标签的条目标 UNCERTAIN，不擅自选也不擅自丢")
    void unknownTopicStaysUncertain() {
        MemoryItem bare = item("note", "一段没有分类的笔记", false);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "billing"),
                List.of(c(bare, 1L, "shop", null, null)), 0);
        assertEquals(StructuredMemorySelector.Decision.UNCERTAIN, verdict(r, "note").decision);
        assertEquals(StructuredMemorySelector.REASON_NO_SIGNAL, verdict(r, "note").reason);
    }

    @Test
    @DisplayName("硬约束优先占预算，普通主题匹配被挤出")
    void constraintBeatsTopicMatchOnBudget() {
        MemoryItem rule = new MemoryItem(MemoryItem.Source.USER, "user:constraint:ro",
                "constraint", "ro", "只允许只读查询不许改数据", true, null);
        MemoryItem fact = item("billing", "账单按调用次数计费每月出账", false);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                task("shop", "billing"),
                List.of(c(fact, 1L, "shop", "billing", null),
                        c(rule, 1L, null, null, null)), 20);
        assertEquals(StructuredMemorySelector.Decision.SELECTED, verdict(r, "user:constraint:ro").decision);
        assertEquals(StructuredMemorySelector.Decision.EXCLUDED, verdict(r, "billing").decision);
        assertEquals(StructuredMemorySelector.REASON_BUDGET, verdict(r, "billing").reason);
    }

    @Test
    @DisplayName("任务没有主题标签时，普通条目全部 UNCERTAIN，约束仍保留")
    void missingTaskTopicDoesNotGuess() {
        StructuredMemorySelector.TaskContext bare =
                new StructuredMemorySelector.TaskContext(1L, "shop", List.of(), true);
        MemoryItem fact = item("billing", "账单按调用次数", false);
        MemoryItem rule = new MemoryItem(MemoryItem.Source.USER, "user:constraint:lang",
                "constraint", "lang", "回答用中文", true, null);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(
                bare, List.of(c(fact, 1L, "shop", "billing", null), c(rule, 1L, null, null, null)), 0);
        assertEquals(StructuredMemorySelector.REASON_NO_TASK_TOPIC, verdict(r, "billing").reason);
        assertEquals(StructuredMemorySelector.Decision.SELECTED, verdict(r, "user:constraint:lang").decision);
        assertTrue(r.labelsSupplied);
    }

    @Test
    @DisplayName("结果标记 labelsSupplied，防止把人工标签说成自动理解")
    void resultRecordsLabelSource() {
        StructuredMemorySelector.TaskContext auto =
                new StructuredMemorySelector.TaskContext(1L, "shop", List.of("billing"), false);
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(auto, List.of(), 0);
        assertFalse(r.labelsSupplied);
    }
}
