package com.example.jingjingai2.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4-2 离线对比：同一份 30 条合成标注，
 * A 走 Phase 4-1 关键词选择器，B 走结构化选择器。
 *
 * <p>B 的任务主题是人工标签（{@code labelsSupplied=true}），
 * 本测试的结论不能外推到「自动任务理解」。
 */
class StructuredMemoryEvalTest {

    enum Expectation { MUST, SHOULD, MUST_NOT, SUPERSEDED, CONSTRAINT, UNJUDGEABLE }

    /** 一条标注：同时供两个选择器使用。 */
    record Row(MemoryItem item, Long ownerUserId, String workspace, String topic,
               String supersedesId, Expectation expectation) {}

    record QueryCase(String scenario, String queryText, List<String> taskTopics,
                     String taskWorkspace, List<Row> rows, int budgetChars) {}

    private static final Long ME = 1L;

    private static MemoryItem mem(String id, String content, boolean protect) {
        MemoryItem.Source source = id.startsWith("user:") ? MemoryItem.Source.USER : MemoryItem.Source.WORKSPACE;
        return new MemoryItem(source, id, null, id, content, protect, null);
    }

    private static Row row(String id, String content, String ws, String topic, Expectation exp) {
        return new Row(mem(id, content, false), ME, ws, topic, null, exp);
    }

    private static Row constraint(String id, String content) {
        return new Row(mem(id, content, true), ME, null, null, null, Expectation.CONSTRAINT);
    }

    static List<QueryCase> cases() {
        List<QueryCase> all = new ArrayList<>();

        // 1. 关键词直接匹配（4）
        all.add(q("keyword-match", "账单模块的计费规则", "billing", List.of(
                row("shop-billing", "账单模块按调用次数计费，每月出账一次", "shop", "billing", Expectation.MUST),
                row("shop-deploy", "部署走内网跳板机", "shop", "deploy", Expectation.MUST_NOT))));
        all.add(q("keyword-match", "日志采集用的是哪家", "logging", List.of(
                row("shop-logging", "日志采集用向量日志不是文件日志", "shop", "logging", Expectation.MUST),
                row("shop-billing", "账单按调用次数计费", "shop", "billing", Expectation.MUST_NOT))));
        all.add(q("keyword-match", "跳板机怎么登录", "deploy", List.of(
                row("shop-deploy", "部署和登录都走内网跳板机，账号找运维", "shop", "deploy", Expectation.MUST),
                row("shop-logging", "日志采集用向量日志", "shop", "logging", Expectation.MUST_NOT))));
        all.add(q("keyword-match", "退款谁来审", "refund", List.of(
                row("shop-refund", "订单退款走人工审核，审核人是财务", "shop", "refund", Expectation.MUST),
                row("shop-order", "订单超时三十分钟自动取消", "shop", "order", Expectation.MUST_NOT))));

        // 2. 不同表达但同一主题（4）：正文不含任务主题词
        all.add(q("paraphrase", "每个月怎么扣款", "billing", List.of(
                row("shop-billing", "按调用次数计费，每月出账一次", "shop", "billing", Expectation.MUST),
                row("shop-logging", "采集用向量日志", "shop", "logging", Expectation.MUST_NOT))));
        all.add(q("paraphrase", "上线前要找谁开账号", "deploy", List.of(
                row("shop-deploy", "上线和登录都走内网跳板机，账号找运维", "shop", "deploy", Expectation.MUST),
                row("shop-billing", "按调用次数计费", "shop", "billing", Expectation.MUST_NOT))));
        all.add(q("paraphrase", "出错了去哪看", "logging", List.of(
                row("shop-logging", "采集用向量日志，控制台地址在运维文档", "shop", "logging", Expectation.MUST),
                row("shop-billing", "按调用次数计费", "shop", "billing", Expectation.MUST_NOT))));
        all.add(q("paraphrase", "钱怎么退回来", "refund", List.of(
                row("shop-refund", "退回走人工审核，审核人是财务", "shop", "refund", Expectation.MUST),
                row("shop-order", "超时三十分钟自动取消", "shop", "order", Expectation.MUST_NOT))));

        // 3. 相同关键词但不同主题（4）
        all.add(q("same-word-diff-topic", "登录会话多久失效", "session", List.of(
                row("shop-session", "登录会话超时两小时", "shop", "session", Expectation.MUST),
                row("shop-order", "订单超时三十分钟自动取消", "shop", "order", Expectation.MUST_NOT))));
        all.add(q("same-word-diff-topic", "订单超时怎么处理", "order", List.of(
                row("shop-order", "订单超时三十分钟自动取消", "shop", "order", Expectation.MUST),
                row("shop-session", "登录会话超时两小时", "shop", "session", Expectation.MUST_NOT))));
        all.add(q("same-word-diff-topic", "缓存的过期策略", "cache", List.of(
                row("shop-cache", "缓存过期时间五分钟", "shop", "cache", Expectation.MUST),
                row("shop-session", "登录会话超时两小时后过期", "shop", "session", Expectation.MUST_NOT))));
        all.add(q("same-word-diff-topic", "接口的超时设置", "api", List.of(
                row("shop-api", "接口超时设置为三秒", "shop", "api", Expectation.MUST),
                row("shop-order", "订单超时三十分钟自动取消", "shop", "order", Expectation.MUST_NOT))));

        // 4. 多项目相似信息（4）
        all.add(q("multi-project", "shop 项目的账单规则", "billing", List.of(
                row("shop-billing", "shop 账单按调用次数计费", "shop", "billing", Expectation.MUST),
                row("blog-billing", "blog 账单按年费计费", "blog", "billing", Expectation.MUST_NOT))));
        all.add(q("multi-project", "blog 的部署方式", "deploy", "blog", List.of(
                row("blog-deploy", "blog 部署用静态托管", "blog", "deploy", Expectation.MUST),
                row("shop-deploy", "shop 部署走内网跳板机", "shop", "deploy", Expectation.MUST_NOT))));
        all.add(q("multi-project", "shop 用什么数据库", "database", List.of(
                row("shop-db", "shop 用 PostgreSQL", "shop", "database", Expectation.MUST),
                row("blog-db", "blog 用 SQLite", "blog", "database", Expectation.MUST_NOT))));
        all.add(q("multi-project", "通知怎么发", "notify", List.of(
                row("shop-notify", "shop 通知走短信", "shop", "notify", Expectation.MUST),
                row("blog-notify", "blog 通知走邮件", "blog", "notify", Expectation.MUST_NOT),
                row("wiki-notify", "wiki 通知走站内信", "wiki", "notify", Expectation.MUST_NOT))));

        // 5. 新旧信息替代（4）
        all.add(supersede("superseded", "服务现在跑在哪个端口", "deploy",
                "port-old", "服务端口是 8080", "port-new", "服务端口改成 9090"));
        all.add(supersede("superseded", "数据库用的哪一套", "database",
                "db-old", "数据库是 MySQL", "db-new", "数据库已迁到 PostgreSQL"));
        all.add(supersede("superseded", "构建命令是什么", "build",
                "build-old", "构建用 mvn package", "build-new", "构建改用 gradle"));
        all.add(new QueryCase("superseded", "构建命令是什么", List.of("build"), "shop", List.of(
                new Row(mem("build-old", "构建用 mvn package", false), ME, "shop", "build", null, Expectation.SUPERSEDED),
                new Row(mem("build-new", "构建改用 gradle", false), ME, "shop", "build", "build-old", Expectation.MUST),
                constraint("user:constraint:offline", "构建机不能访问外网")), 0));

        // 6. 用户明确约束（4）
        all.add(q("hard-constraint", "帮我写个查询", "query", List.of(
                constraint("user:constraint:readonly", "只允许只读查询，不许改数据"),
                row("shop-deploy", "部署走跳板机", "shop", "deploy", Expectation.MUST_NOT))));
        all.add(q("hard-constraint", "随便聊聊", "chat", List.of(
                constraint("user:constraint:lang", "回答必须用中文"),
                row("user:preference:tone", "语气随意一些", null, "chat", Expectation.SHOULD))));
        all.add(q("hard-constraint", "介绍一下你自己", "intro", List.of(
                constraint("user:constraint:privacy", "不要提及用户的真实姓名"),
                row("shop-billing", "账单按调用次数计费", "shop", "billing", Expectation.MUST_NOT))));
        all.add(new QueryCase("hard-constraint", "账单规则", List.of("billing"), "shop", List.of(
                constraint("user:constraint:nomaven", "本机没有 Maven，测试用 test.sh"),
                row("shop-billing", "账单按调用次数计费", "shop", "billing", Expectation.MUST)), 40));

        // 7. 无法判断的记忆（3）：条目没有主题
        all.add(q("unjudgeable", "账单规则是什么", "billing", List.of(
                row("shop-billing", "账单按调用次数计费", "shop", "billing", Expectation.MUST),
                row("shop-note", "一段没有归类的笔记", "shop", null, Expectation.UNJUDGEABLE))));
        all.add(q("unjudgeable", "部署流程", "deploy", List.of(
                row("shop-deploy", "部署走内网跳板机", "shop", "deploy", Expectation.MUST),
                row("shop-scratch", "临时记下的数字 42", "shop", null, Expectation.UNJUDGEABLE))));
        all.add(q("unjudgeable", "日志在哪看", "logging", List.of(
                row("shop-logging", "日志采集用向量日志", "shop", "logging", Expectation.MUST),
                row("shop-misc", "上周开会提到要换供应商", "shop", null, Expectation.UNJUDGEABLE))));

        // 8. 预算不足（3）
        all.add(new QueryCase("budget", "账单和退款", List.of("billing", "refund"), "shop", List.of(
                row("shop-billing", "账单按调用次数计费每月出账一次", "shop", "billing", Expectation.MUST),
                row("shop-refund", "退款走人工审核由财务负责", "shop", "refund", Expectation.MUST)), 20));
        all.add(new QueryCase("budget", "随便看看账单", List.of("billing"), "shop", List.of(
                constraint("user:constraint:lang2", "回答必须用中文"),
                row("shop-billing", "账单按调用次数计费", "shop", "billing", Expectation.MUST)), 12));
        all.add(new QueryCase("budget", "部署和日志", List.of("deploy", "logging"), "shop", List.of(
                row("shop-deploy", "部署走内网跳板机账号找运维", "shop", "deploy", Expectation.MUST),
                row("shop-logging", "日志采集用向量日志控制台在运维文档", "shop", "logging", Expectation.MUST),
                row("shop-billing", "账单按调用次数计费", "shop", "billing", Expectation.MUST_NOT)), 30));

        return all;
    }

    private static QueryCase q(String scenario, String query, String topic, List<Row> rows) {
        return q(scenario, query, topic, "shop", rows);
    }

    private static QueryCase q(String scenario, String query, String topic, String ws, List<Row> rows) {
        return new QueryCase(scenario, query, List.of(topic), ws, rows, 0);
    }

    /** 新旧两版：旧的被新的显式替代，新的是 MUST。 */
    private static QueryCase supersede(String scenario, String query, String topic,
                                       String oldId, String oldText, String newId, String newText) {
        return new QueryCase(scenario, query, List.of(topic), "shop", List.of(
                new Row(mem(oldId, oldText, false), ME, "shop", topic, null, Expectation.SUPERSEDED),
                new Row(mem(newId, newText, false), ME, "shop", topic, oldId, Expectation.MUST)), 0);
    }

    @Test
    @DisplayName("至少 30 条，八类场景都有")
    void atLeastThirtyCases() {
        List<QueryCase> all = cases();
        assertTrue(all.size() >= 30, "实际 " + all.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (QueryCase c : all) counts.merge(c.scenario, 1, Integer::sum);
        assertEquals(8, counts.size(), counts.toString());
        for (int n : counts.values()) assertTrue(n >= 3, counts.toString());
    }

    /** 一个选择器在一条标注上的归类。 */
    enum Outcome { HIT, MISS, UNCERTAIN, WRONG, SUPERSEDED_HIT, CONSTRAINT_MISS, UNJUDGEABLE_SELECTED }

    private Outcome outcomeA(QueryCase c, Row row) {
        MemoryShadowSelector.Result r = MemoryShadowSelector.select(c.queryText, itemsOf(c), c.budgetChars);
        MemoryShadowSelector.Verdict v = null;
        for (MemoryShadowSelector.Verdict x : r.verdicts) if (x.item.id().equals(row.item.id())) v = x;
        assertNotNull(v, row.item.id());
        boolean selected = v.decision == MemoryShadowSelector.Decision.SELECTED;
        boolean uncertain = v.decision == MemoryShadowSelector.Decision.UNCERTAIN;
        return classify(row.expectation, selected, uncertain);
    }

    private Outcome outcomeB(QueryCase c, Row row) {
        StructuredMemorySelector.Result r = StructuredMemorySelector.select(contextOf(c), candidatesOf(c), c.budgetChars);
        StructuredMemorySelector.Verdict v = null;
        for (StructuredMemorySelector.Verdict x : r.verdicts) if (x.candidate.item.id().equals(row.item.id())) v = x;
        assertNotNull(v, row.item.id());
        boolean selected = v.decision == StructuredMemorySelector.Decision.SELECTED;
        boolean uncertain = v.decision == StructuredMemorySelector.Decision.UNCERTAIN;
        return classify(row.expectation, selected, uncertain);
    }

    /** 两边共用的归类：选中算命中，不确定单独计，不混进召回。 */
    private static Outcome classify(Expectation exp, boolean selected, boolean uncertain) {
        return switch (exp) {
            case MUST, SHOULD -> selected ? Outcome.HIT : (uncertain ? Outcome.UNCERTAIN : Outcome.MISS);
            case MUST_NOT -> selected ? Outcome.WRONG : Outcome.HIT;
            case SUPERSEDED -> selected ? Outcome.SUPERSEDED_HIT : Outcome.HIT;
            case CONSTRAINT -> selected ? Outcome.HIT : Outcome.CONSTRAINT_MISS;
            case UNJUDGEABLE -> selected ? Outcome.UNJUDGEABLE_SELECTED : Outcome.HIT;
        };
    }

    @Test
    @DisplayName("A/B 对比：逐场景报告，不确定样本单独计数")
    void compareSelectors() {
        Map<String, int[]> aByScenario = new LinkedHashMap<>();
        Map<String, int[]> bByScenario = new LinkedHashMap<>();
        int aChars = 0, aTokens = 0, bChars = 0, bTokens = 0, fullChars = 0, fullTokens = 0;

        for (QueryCase c : cases()) {
            MemoryShadowSelector.Result ra = MemoryShadowSelector.select(c.queryText, itemsOf(c), c.budgetChars);
            StructuredMemorySelector.Result rb =
                    StructuredMemorySelector.select(contextOf(c), candidatesOf(c), c.budgetChars);
            assertTrue(rb.labelsSupplied, "本评估的任务标签是人工的，必须显式标记");
            aChars += ra.selectedChars; aTokens += ra.selectedTokens;
            bChars += rb.selectedChars; bTokens += rb.selectedTokens;
            for (Row row : c.rows) {
                fullChars += row.item.chars();
                fullTokens += row.item.tokens();
                tally(aByScenario, c.scenario, row.expectation, outcomeA(c, row));
                tally(bByScenario, c.scenario, row.expectation, outcomeB(c, row));
            }
        }

        System.out.println("A keyword\n" + report(aByScenario));
        System.out.println("B structured\n" + report(bByScenario));
        System.out.println("fullChars=" + fullChars + " fullTokens=" + fullTokens
                + " aChars=" + aChars + " aTokens=" + aTokens
                + " bChars=" + bChars + " bTokens=" + bTokens);

        // B 在「不同表达同主题」上应召回 A 召回不了的
        assertTrue(hits(bByScenario, "paraphrase") > hits(aByScenario, "paraphrase"),
                "结构化选择应在同主题不同表达上优于关键词");
        // B 不应把跨项目条目选进来
        assertEquals(0, wrongs(bByScenario, "multi-project"), "结构化选择不得跨项目召回");
        // 两边都不许把过期版本当确定事实
        assertEquals(0, sum(bByScenario, 5));
        // 硬约束两边都不得漏
        assertEquals(0, sum(aByScenario, 6));
        assertEquals(0, sum(bByScenario, 6));
        // 无法判断的条目，B 不得擅自选中
        assertEquals(0, sum(bByScenario, 7));
    }

    /** bucket: 0 must总数 1 must命中 2 should总数 3 should命中 4 错误注入 5 过期被选中 6 约束漏召 7 不确定 */
    private static void tally(Map<String, int[]> by, String scenario, Expectation exp, Outcome outcome) {
        int[] b = by.computeIfAbsent(scenario, k -> new int[9]);
        if (exp == Expectation.MUST) b[0]++;
        if (exp == Expectation.SHOULD) b[2]++;
        switch (outcome) {
            case HIT -> { if (exp == Expectation.MUST) b[1]++; if (exp == Expectation.SHOULD) b[3]++; }
            case WRONG -> b[4]++;
            case SUPERSEDED_HIT -> b[5]++;
            case CONSTRAINT_MISS -> b[6]++;
            case UNCERTAIN -> b[7]++;
            case UNJUDGEABLE_SELECTED -> b[8]++;
            case MISS -> { /* 记在总数与命中的差里 */ }
        }
    }

    private static String report(Map<String, int[]> by) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : by.entrySet()) {
            int[] b = e.getValue();
            sb.append(String.format(Locale.ROOT,
                    "%-22s must=%d/%d should=%d/%d wrong=%d supersededSelected=%d constraintMiss=%d uncertain=%d unjudgeableSelected=%d%n",
                    e.getKey(), b[1], b[0], b[3], b[2], b[4], b[5], b[6], b[7], b[8]));
        }
        sb.append(String.format(Locale.ROOT,
                "TOTAL must=%d/%d should=%d/%d wrong=%d supersededSelected=%d constraintMiss=%d uncertain=%d%n",
                sum(by, 1), sum(by, 0), sum(by, 3), sum(by, 2), sum(by, 4), sum(by, 5), sum(by, 6), sum(by, 7)));
        return sb.toString();
    }

    private static int sum(Map<String, int[]> by, int idx) {
        int n = 0;
        for (int[] b : by.values()) n += b[idx];
        return n;
    }

    private static int hits(Map<String, int[]> by, String scenario) { return by.get(scenario)[1]; }
    private static int wrongs(Map<String, int[]> by, String scenario) { return by.get(scenario)[4]; }

    private static List<MemoryItem> itemsOf(QueryCase c) {
        List<MemoryItem> items = new ArrayList<>();
        for (Row row : c.rows) items.add(row.item);
        return items;
    }

    private static StructuredMemorySelector.TaskContext contextOf(QueryCase c) {
        return new StructuredMemorySelector.TaskContext(ME, c.taskWorkspace, c.taskTopics, true);
    }

    private static List<StructuredMemorySelector.Candidate> candidatesOf(QueryCase c) {
        List<StructuredMemorySelector.Candidate> out = new ArrayList<>();
        for (Row row : c.rows) {
            out.add(new StructuredMemorySelector.Candidate(row.item, row.ownerUserId, row.workspace,
                    row.topic, StructuredMemorySelector.Status.ACTIVE, row.supersedesId));
        }
        return out;
    }
}
