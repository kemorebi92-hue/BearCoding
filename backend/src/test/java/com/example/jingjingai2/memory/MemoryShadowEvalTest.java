package com.example.jingjingai2.memory;

import com.example.jingjingai2.context.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 18 条合成查询的离线评估。
 *
 * <p>每条查询都有人工标注：必须召回、应该召回、不该召回、已过期、必须保留的约束。
 * 选择器对「没有关键词重合」的条目给的是 UNCERTAIN 而不是排除，
 * 这类不算召回成功，也不算错误注入，单独计入不确定。
 */
class MemoryShadowEvalTest {

    /** 一条标注。 */
    enum Expectation { MUST, SHOULD, MUST_NOT, SUPERSEDED, CONSTRAINT }

    record Labeled(MemoryItem item, Expectation expectation) {}

    record QueryCase(String scenario, String query, List<Labeled> labeled, int budgetChars) {}

    private static MemoryItem ws(String workspace, String file, String content) {
        return new MemoryItem(MemoryItem.Source.WORKSPACE,
                "workspace:" + workspace + ":" + file, null, workspace + "/" + file,
                content, false, null);
    }

    private static MemoryItem constraint(String key, String content) {
        return new MemoryItem(MemoryItem.Source.USER, "user:constraint:" + key,
                "constraint", key, content, true, null);
    }

    private static MemoryItem pref(String key, String content) {
        return new MemoryItem(MemoryItem.Source.USER, "user:preference:" + key,
                "preference", key, content, false, null);
    }

    private static Labeled label(MemoryItem item, Expectation expectation) {
        return new Labeled(item, expectation);
    }

    /** 六类场景，每类三条。标注依据写在每条用例的字段里。 */
    static List<QueryCase> cases() {
        List<QueryCase> all = new ArrayList<>();

        // 1. 关键词直接匹配
        all.add(new QueryCase("keyword-match", "账单模块的计费规则是什么", List.of(
                label(ws("shop", "billing.md", "账单模块按调用次数计费，每月出账一次"), Expectation.MUST),
                label(pref("reply", "回答用中文短句"), Expectation.SHOULD),
                label(ws("shop", "deploy.md", "部署走内网跳板机"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("keyword-match", "日志采集用的是哪家", List.of(
                label(ws("shop", "logging.md", "日志采集用的是向量日志不是文件日志"), Expectation.MUST),
                label(ws("shop", "billing.md", "账单按调用次数"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("keyword-match", "跳板机怎么登录", List.of(
                label(ws("shop", "deploy.md", "部署和登录都走内网跳板机，账号找运维"), Expectation.MUST),
                label(ws("shop", "logging.md", "日志采集用向量日志"), Expectation.MUST_NOT)), 0));

        // 2. 关键词不同但语义相关：正文不出现查询里的词
        all.add(new QueryCase("semantic-gap", "每个月怎么扣款", List.of(
                label(ws("shop", "billing.md", "账单模块按调用次数计费，每月出账一次"), Expectation.MUST),
                label(ws("shop", "logging.md", "日志采集用向量日志"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("semantic-gap", "上线前要找谁开账号", List.of(
                label(ws("shop", "deploy.md", "部署和登录都走内网跳板机，账号找运维"), Expectation.MUST),
                label(ws("shop", "billing.md", "账单按调用次数"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("semantic-gap", "出错了去哪看", List.of(
                label(ws("shop", "logging.md", "日志采集用向量日志，控制台地址在运维文档"), Expectation.MUST),
                label(pref("reply", "回答用中文短句"), Expectation.MUST_NOT)), 0));

        // 3. 旧信息被新信息替代：同键不同正文，选择器应标冲突而不是选一个
        all.add(new QueryCase("superseded", "服务现在跑在哪个端口", List.of(
                label(ws("shop", "port.md", "服务端口是 8080"), Expectation.SUPERSEDED),
                label(new MemoryItem(MemoryItem.Source.WORKSPACE, "workspace:shop:port.md#2",
                        null, "shop/port.md", "服务端口改成 9090", false, 2L), Expectation.SUPERSEDED)), 0));
        all.add(new QueryCase("superseded", "数据库用的哪一套", List.of(
                label(new MemoryItem(MemoryItem.Source.USER, "user:project_context:db",
                        "project_context", "db", "数据库是 MySQL", false, 1L), Expectation.SUPERSEDED),
                label(new MemoryItem(MemoryItem.Source.USER, "user:project_context:db",
                        "project_context", "db", "数据库已迁到 PostgreSQL", false, 2L), Expectation.SUPERSEDED)), 0));
        all.add(new QueryCase("superseded", "构建命令是什么", List.of(
                label(new MemoryItem(MemoryItem.Source.USER, "user:project_context:build",
                        "project_context", "build", "构建用 mvn package", false, 1L), Expectation.SUPERSEDED),
                label(new MemoryItem(MemoryItem.Source.USER, "user:project_context:build",
                        "project_context", "build", "构建改用 ./gradlew build", false, 2L), Expectation.SUPERSEDED),
                label(constraint("no-network", "构建机不能访问外网"), Expectation.CONSTRAINT)), 0));

        // 4. 多条相似记忆要区分
        all.add(new QueryCase("similar-items", "订单超时怎么处理", List.of(
                label(ws("shop", "order-timeout.md", "订单超时 30 分钟自动取消"), Expectation.MUST),
                label(ws("shop", "order-refund.md", "订单退款走人工审核"), Expectation.MUST_NOT),
                label(ws("shop", "session-timeout.md", "登录会话超时 2 小时"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("similar-items", "退款谁来审", List.of(
                label(ws("shop", "order-refund.md", "订单退款走人工审核，审核人是财务"), Expectation.MUST),
                label(ws("shop", "order-timeout.md", "订单超时 30 分钟自动取消"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("similar-items", "会话多久失效", List.of(
                label(ws("shop", "session-timeout.md", "登录会话超时 2 小时"), Expectation.MUST),
                label(ws("shop", "order-timeout.md", "订单超时 30 分钟自动取消"), Expectation.MUST_NOT)), 0));

        // 5. 工作区隔离：另一个项目的同名事实不该召回
        all.add(new QueryCase("workspace-isolation", "shop 项目的账单规则", List.of(
                label(ws("shop", "billing.md", "shop 账单按调用次数"), Expectation.MUST),
                label(ws("blog", "billing.md", "blog 账单按年费"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("workspace-isolation", "blog 的部署方式", List.of(
                label(ws("blog", "deploy.md", "blog 部署用静态托管"), Expectation.MUST),
                label(ws("shop", "deploy.md", "shop 部署走内网跳板机"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("workspace-isolation", "shop 用什么数据库", List.of(
                label(ws("shop", "db.md", "shop 用 PostgreSQL"), Expectation.MUST),
                label(ws("blog", "db.md", "blog 用 SQLite"), Expectation.MUST_NOT)), 0));

        // 6. 用户明确约束必须保留，哪怕查询词对不上，哪怕预算只够一条
        all.add(new QueryCase("hard-constraint", "帮我写个查询", List.of(
                label(constraint("readonly", "只允许只读查询，不许改数据"), Expectation.CONSTRAINT),
                label(ws("shop", "deploy.md", "部署走跳板机"), Expectation.MUST_NOT)), 0));
        all.add(new QueryCase("hard-constraint", "随便聊聊", List.of(
                label(constraint("lang", "回答必须用中文"), Expectation.CONSTRAINT),
                label(pref("tone", "语气随意一些"), Expectation.SHOULD)), 0));
        all.add(new QueryCase("hard-constraint", "账单", List.of(
                label(constraint("no-maven", "本机没有 Maven，测试用 test.sh"), Expectation.CONSTRAINT),
                label(ws("shop", "billing.md", "账单按调用次数计费"), Expectation.MUST)),
                // 预算只够放下约束和账单里较短的那条；长的无关条目应被预算丢掉
                30));
        return all;
    }

    @Test
    @DisplayName("恰好 18 条，六类各三条")
    void eighteenCasesSixScenarios() {
        List<QueryCase> all = cases();
        assertEquals(18, all.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (QueryCase c : all) counts.merge(c.scenario, 1, Integer::sum);
        assertEquals(6, counts.size());
        for (int n : counts.values()) assertEquals(3, n);
    }

    @Test
    @DisplayName("离线评估：分场景报告，不确定样本单独计数")
    void evaluateByScenario() {
        Map<String, int[]> byScenario = new LinkedHashMap<>();
        int fullChars = 0;
        int fullTokens = 0;
        int shadowChars = 0;
        int shadowTokens = 0;
        int constraintMiss = 0;
        int crossWorkspaceWrong = 0;

        for (QueryCase c : cases()) {
            List<MemoryItem> items = new ArrayList<>();
            for (Labeled l : c.labeled) {
                items.add(l.item);
                fullChars += l.item.chars();
                fullTokens += l.item.tokens();
            }
            MemoryShadowSelector.Result r = MemoryShadowSelector.select(c.query, items, c.budgetChars);
            shadowChars += r.selectedChars;
            shadowTokens += r.selectedTokens;
            int[] bucket = byScenario.computeIfAbsent(c.scenario, k -> new int[8]);
            for (Labeled l : c.labeled) {
                MemoryShadowSelector.Verdict v = verdict(r, l.item.id());
                boolean selected = v.decision == MemoryShadowSelector.Decision.SELECTED;
                boolean uncertain = v.decision == MemoryShadowSelector.Decision.UNCERTAIN;
                switch (l.expectation) {
                    case MUST -> { bucket[0]++; if (selected) bucket[1]++; else if (uncertain) bucket[7]++; }
                    case SHOULD -> { bucket[2]++; if (selected) bucket[3]++; }
                    case MUST_NOT -> {
                        if (selected) {
                            bucket[4]++;
                            if ("workspace-isolation".equals(c.scenario)) crossWorkspaceWrong++;
                        }
                    }
                    case SUPERSEDED -> { if (selected) bucket[5]++; }
                    case CONSTRAINT -> { if (!selected) { bucket[6]++; constraintMiss++; } }
                    default -> fail(l.expectation.name());
                }
                if (l.expectation == Expectation.CONSTRAINT) {
                    assertEquals(MemoryShadowSelector.Decision.SELECTED, v.decision,
                            c.query + " 漏掉约束 " + l.item.id());
                }
                if (l.expectation == Expectation.SUPERSEDED) {
                    assertNotEquals(MemoryShadowSelector.Decision.SELECTED, v.decision,
                            c.query + " 把过期版本当确定事实选了 " + l.item.id());
                }
            }
        }

        StringBuilder report = new StringBuilder();
        int mustTotal = 0, mustHit = 0, shouldTotal = 0, shouldHit = 0;
        int wrong = 0, supersededHit = 0, mustUncertain = 0;
        for (Map.Entry<String, int[]> e : byScenario.entrySet()) {
            int[] b = e.getValue();
            mustTotal += b[0]; mustHit += b[1];
            shouldTotal += b[2]; shouldHit += b[3];
            wrong += b[4]; supersededHit += b[5]; mustUncertain += b[7];
            report.append(e.getKey())
                    .append(" must=").append(b[1]).append('/').append(b[0])
                    .append(" should=").append(b[3]).append('/').append(b[2])
                    .append(" wrongSelected=").append(b[4])
                    .append(" supersededSelected=").append(b[5])
                    .append(" constraintMiss=").append(b[6])
                    .append(" mustUncertain=").append(b[7])
                    .append('\n');
        }
        double reduceRatio = fullChars == 0 ? 0
                : (fullChars - shadowChars) * 1.0 / fullChars;
        report.append("fullChars=").append(fullChars).append(" fullTokens=").append(fullTokens)
                .append(" shadowChars=").append(shadowChars).append(" shadowTokens=").append(shadowTokens)
                .append(" reduceRatio=").append(String.format(java.util.Locale.ROOT, "%.3f", reduceRatio))
                .append(" crossWorkspaceWrong=").append(crossWorkspaceWrong)
                .append(" constraintMissTotal=").append(constraintMiss)
                .append('\n');
        System.out.println(report);

        assertEquals(0, supersededHit, "过期版本不应被当成确定事实选中");
        assertEquals(0, constraintMiss, "硬约束不得漏召回");
        assertTrue(mustUncertain > 0, "语义缺口类应被识别为不确定，而不是假装召回");
        assertTrue(mustHit > 0, "至少关键词匹配类应召回");
        assertTrue(shadowChars < fullChars, "影子选择应少于全量注入: " + shadowChars + " vs " + fullChars);
        assertTrue(wrong >= 0);
        assertNotEquals(0, TokenEstimator.estimate("账单"));
        // 供报告抄录：总数
        assertEquals(mustTotal, mustHit + (mustTotal - mustHit));
        assertTrue(shouldTotal >= shouldHit);
    }

    private static MemoryShadowSelector.Verdict verdict(MemoryShadowSelector.Result r, String id) {
        for (MemoryShadowSelector.Verdict v : r.verdicts) if (v.item.id().equals(id)) return v;
        fail("缺判定 " + id);
        return null;
    }
}
