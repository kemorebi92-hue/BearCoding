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
 * Phase 4-3A：自动任务主题识别 vs 人工标签 vs 标签缺失/错误。
 *
 * <p>沿用 Phase 4-2 的 30 条合成标注，再加三类标签质量场景。
 * 四组对照用的是<b>同一批候选、同一个选择器</b>，唯一变量是任务标签从哪来，
 * 所以差异只能归因于标签质量，不能归因于检索算法。
 *
 * <p>自动识别用的词表是「候选条目的主题词」，模拟现实里能拿到的封闭词表
 * （用户记忆 memKey、工作区记忆文件名）。它不读人工标注的任务主题。
 */
class TaskTopicShadowEvalTest {

    private static final Long ME = 1L;
    private static final Long OTHER = 2L;

    /**
     * 词表模拟现实里能直接读到的封闭集合（用户记忆 memKey、工作区记忆文件名）。
     * 与候选条目的 topic 用同一套字符串：否则自动识别在构造上就不可能等于人工标签，对比没有意义。
     * 识别器只拿这个集合，不拿「这条查询的正确答案」。
     */
    private static final List<String> LEXICON = List.of(
            "billing", "logging", "deploy", "refund", "order", "session",
            "cache", "api", "database", "notify", "build", "query", "chat", "intro");

    /** 主题词到它在中文消息里的写法。只用于构造查询文本，不参与识别。 */
    private static final Map<String, String> SURFACE = Map.ofEntries(
            Map.entry("billing", "账单"), Map.entry("logging", "日志"), Map.entry("deploy", "部署"),
            Map.entry("refund", "退款"), Map.entry("order", "订单"), Map.entry("session", "会话"),
            Map.entry("cache", "缓存"), Map.entry("api", "接口"), Map.entry("database", "数据库"),
            Map.entry("notify", "通知"), Map.entry("build", "构建"), Map.entry("query", "查询"),
            Map.entry("chat", "聊聊"), Map.entry("intro", "介绍"));

    /** 一条影子候选：结构化属性是真值（用于选择），另记一个「错误标签」版本用于对照组。 */
    record ShadowRow(StructuredMemorySelector.Candidate truth, String wrongTopic,
                     Expectation expectation) {}

    record ShadowCase(String scenario, String message, String trueTopic, String workspace,
                      List<ShadowRow> rows, int budgetChars) {}

    private static MemoryItem mem(String id, String content, boolean protect) {
        MemoryItem.Source source = id.startsWith("user:") ? MemoryItem.Source.USER : MemoryItem.Source.WORKSPACE;
        return new MemoryItem(source, id, null, id, content, protect, null);
    }

    private static ShadowRow row(String id, String content, String ws, String topic,
                                 String wrongTopic, Expectation exp) {
        StructuredMemorySelector.Candidate c = new StructuredMemorySelector.Candidate(
                mem(id, content, false), ME, ws, topic,
                StructuredMemorySelector.Status.ACTIVE, null);
        return new ShadowRow(c, wrongTopic, exp);
    }

    private static ShadowRow constraint(String id, String content) {
        StructuredMemorySelector.Candidate c = new StructuredMemorySelector.Candidate(
                mem(id, content, true), ME, null, null,
                StructuredMemorySelector.Status.ACTIVE, null);
        return new ShadowRow(c, null, Expectation.CONSTRAINT);
    }

    static List<ShadowCase> cases() {
        List<ShadowCase> all = new ArrayList<>();

        // ---- 复用 Phase 4-2 的八类，查询文本原样 ----
        all.add(sc("keyword-match", "账单模块的计费规则", "billing",
                r("shop-billing", "账单模块按调用次数计费，每月出账一次", "billing", Expectation.MUST),
                r("shop-deploy", "部署走内网跳板机", "deploy", Expectation.MUST_NOT)));
        all.add(sc("keyword-match", "日志采集用的是哪家", "logging",
                r("shop-logging", "日志采集用向量日志不是文件日志", "logging", Expectation.MUST),
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST_NOT)));
        all.add(sc("keyword-match", "跳板机怎么登录", "deploy",
                r("shop-deploy", "部署和登录都走内网跳板机，账号找运维", "deploy", Expectation.MUST),
                r("shop-logging", "日志采集用向量日志", "logging", Expectation.MUST_NOT)));
        all.add(sc("keyword-match", "退款谁来审", "refund",
                r("shop-refund", "订单退款走人工审核，审核人是财务", "refund", Expectation.MUST),
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST_NOT)));

        all.add(sc("paraphrase", "每个月怎么扣款", "billing",
                r("shop-billing", "按调用次数计费，每月出账一次", "billing", Expectation.MUST),
                r("shop-logging", "采集用向量日志", "logging", Expectation.MUST_NOT)));
        all.add(sc("paraphrase", "上线前要找谁开账号", "deploy",
                r("shop-deploy", "上线和登录都走内网跳板机，账号找运维", "deploy", Expectation.MUST),
                r("shop-billing", "按调用次数计费", "billing", Expectation.MUST_NOT)));
        all.add(sc("paraphrase", "出错了去哪看", "logging",
                r("shop-logging", "采集用向量日志，控制台地址在运维文档", "logging", Expectation.MUST),
                r("shop-billing", "按调用次数计费", "billing", Expectation.MUST_NOT)));
        all.add(sc("paraphrase", "钱怎么退回来", "refund",
                r("shop-refund", "退回走人工审核，审核人是财务", "refund", Expectation.MUST),
                r("shop-order", "超时三十分钟自动取消", "order", Expectation.MUST_NOT)));

        all.add(sc("same-word-diff-topic", "登录会话多久失效", "session",
                r("shop-session", "登录会话超时两小时", "session", Expectation.MUST),
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST_NOT)));
        all.add(sc("same-word-diff-topic", "订单超时怎么处理", "order",
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST),
                r("shop-session", "登录会话超时两小时", "session", Expectation.MUST_NOT)));
        all.add(sc("same-word-diff-topic", "缓存的过期策略", "cache",
                r("shop-cache", "缓存过期时间五分钟", "cache", Expectation.MUST),
                r("shop-session", "登录会话超时两小时后过期", "session", Expectation.MUST_NOT)));
        all.add(sc("same-word-diff-topic", "接口的超时设置", "api",
                r("shop-api", "接口超时设置为三秒", "api", Expectation.MUST),
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST_NOT)));

        all.add(sc("multi-project", "shop 项目的账单规则", "billing",
                r("shop-billing", "shop 账单按调用次数计费", "billing", Expectation.MUST),
                rx("blog-billing", "blog 账单按年费计费", "blog", "billing", Expectation.MUST_NOT)));
        all.add(new ShadowCase("multi-project", "blog 的部署方式", "deploy", "blog", List.of(
                rx("blog-deploy", "blog 部署用静态托管", "blog", "deploy", Expectation.MUST),
                rx("shop-deploy", "shop 部署走内网跳板机", "shop", "deploy", Expectation.MUST_NOT)), 0));
        all.add(sc("multi-project", "shop 用什么数据库", "database",
                r("shop-db", "shop 用 PostgreSQL", "database", Expectation.MUST),
                rx("blog-db", "blog 用 SQLite", "blog", "database", Expectation.MUST_NOT)));
        all.add(sc("multi-project", "通知怎么发", "notify",
                r("shop-notify", "shop 通知走短信", "notify", Expectation.MUST),
                rx("blog-notify", "blog 通知走邮件", "blog", "notify", Expectation.MUST_NOT),
                rx("wiki-notify", "wiki 通知走站内信", "wiki", "notify", Expectation.MUST_NOT)));

        all.add(supersede("superseded", "服务现在跑在哪个端口", "deploy",
                "port-old", "服务端口是 8080", "port-new", "服务端口改成 9090"));
        all.add(supersede("superseded", "数据库用的哪一套", "database",
                "db-old", "数据库是 MySQL", "db-new", "数据库已迁到 PostgreSQL"));
        all.add(supersede("superseded", "构建命令是什么", "build",
                "build-old", "构建用 mvn package", "build-new", "构建改用 gradle"));
        all.add(new ShadowCase("superseded", "构建命令是什么", "build", "shop", List.of(
                superseded("build-old", "构建用 mvn package", "build"),
                superseding("build-new", "构建改用 gradle", "build", "build-old"),
                constraint("user:constraint:offline", "构建机不能访问外网")), 0));

        all.add(sc("hard-constraint", "帮我写个查询", "query",
                constraint("user:constraint:readonly", "只允许只读查询，不许改数据"),
                r("shop-deploy", "部署走跳板机", "deploy", Expectation.MUST_NOT)));
        all.add(sc("hard-constraint", "随便聊聊", "chat",
                constraint("user:constraint:lang", "回答必须用中文"),
                new ShadowRow(candidate("user:preference:tone", "语气随意一些", null, "chat", null),
                        null, Expectation.SHOULD)));
        all.add(sc("hard-constraint", "介绍一下你自己", "intro",
                constraint("user:constraint:privacy", "不要提及用户的真实姓名"),
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST_NOT)));
        all.add(new ShadowCase("hard-constraint", "账单规则", "billing", "shop", List.of(
                constraint("user:constraint:nomaven", "本机没有 Maven，测试用 test.sh"),
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST)), 40));

        all.add(sc("unjudgeable", "账单规则是什么", "billing",
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST),
                r("shop-note", "一段没有归类的笔记", null, Expectation.UNJUDGEABLE)));
        all.add(sc("unjudgeable", "部署流程", "deploy",
                r("shop-deploy", "部署走内网跳板机", "deploy", Expectation.MUST),
                r("shop-scratch", "临时记下的数字 42", null, Expectation.UNJUDGEABLE)));
        all.add(sc("unjudgeable", "日志在哪看", "logging",
                r("shop-logging", "日志采集用向量日志", "logging", Expectation.MUST),
                r("shop-misc", "上周开会提到要换供应商", null, Expectation.UNJUDGEABLE)));

        all.add(new ShadowCase("budget", "账单和退款", "billing", "shop", List.of(
                r("shop-billing", "账单按调用次数计费每月出账一次", "billing", Expectation.MUST),
                r("shop-refund", "退款走人工审核由财务负责", "refund", Expectation.MUST)), 20));
        all.add(new ShadowCase("budget", "随便看看账单", "billing", "shop", List.of(
                constraint("user:constraint:lang2", "回答必须用中文"),
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST)), 12));
        all.add(new ShadowCase("budget", "部署和日志", "deploy", "shop", List.of(
                r("shop-deploy", "部署走内网跳板机账号找运维", "deploy", Expectation.MUST),
                r("shop-logging", "日志采集用向量日志控制台在运维文档", "logging", Expectation.MUST),
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST_NOT)), 30));

        // ---- 9. 自动标签缺失：消息里没有任何词表词 ----
        all.add(sc("auto-label-missing", "这个功能最近怎么样", "billing",
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST),
                r("shop-deploy", "部署走跳板机", "deploy", Expectation.MUST_NOT)));
        all.add(sc("auto-label-missing", "帮我看看进度", "deploy",
                r("shop-deploy", "部署走内网跳板机", "deploy", Expectation.MUST),
                r("shop-logging", "日志采集用向量日志", "logging", Expectation.MUST_NOT)));
        all.add(sc("auto-label-missing", "还有什么没做完", "order",
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST),
                r("shop-refund", "退款走人工审核", "refund", Expectation.MUST_NOT)));

        // ---- 10. 错误标签：消息里出现的词表词不是任务真正的主题 ----
        // 真主题 billing，消息里却只有 deploy 这个词
        all.add(sc("wrong-label", "部署之后出账对不上", "billing",
                r("shop-billing", "账单按调用次数计费，每月出账一次", "billing", Expectation.MUST),
                r("shop-deploy", "部署走内网跳板机", "deploy", Expectation.MUST_NOT)));
        all.add(sc("wrong-label", "上线以后部署控制台里全是报错", "logging",
                r("shop-logging", "日志采集用向量日志", "logging", Expectation.MUST),
                r("shop-deploy", "部署和上线都走内网跳板机", "deploy", Expectation.MUST_NOT)));
        all.add(sc("wrong-label", "订单的超时怎么算", "refund",
                r("shop-refund", "退款走人工审核，审核人是财务", "refund", Expectation.MUST),
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST_NOT)));

        // ---- 11. 模糊任务：一句话里命中两个主题词，识别器应拒绝选择 ----
        all.add(sc("ambiguous-task", "账单和退款分别谁负责", "billing",
                r("shop-billing", "账单按调用次数计费", "billing", Expectation.MUST),
                r("shop-refund", "退款走人工审核", "refund", Expectation.MUST_NOT)));
        all.add(sc("ambiguous-task", "部署完去哪看日志", "deploy",
                r("shop-deploy", "部署走内网跳板机", "deploy", Expectation.MUST),
                r("shop-logging", "日志采集用向量日志", "logging", Expectation.MUST_NOT)));
        all.add(sc("ambiguous-task", "订单超时和会话超时是一回事吗", "order",
                r("shop-order", "订单超时三十分钟自动取消", "order", Expectation.MUST),
                r("shop-session", "登录会话超时两小时", "session", Expectation.MUST_NOT)));

        return all;
    }

    private enum Expectation { MUST, SHOULD, MUST_NOT, SUPERSEDED, CONSTRAINT, UNJUDGEABLE }

    private static ShadowRow r(String id, String content, String topic, Expectation exp) {
        return row(id, content, "shop", topic, null, exp);
    }

    private static ShadowRow rx(String id, String content, String ws, String topic, Expectation exp) {
        return row(id, content, ws, topic, null, exp);
    }

    private static ShadowCase sc(String scenario, String message, String topic, ShadowRow... rows) {
        return new ShadowCase(scenario, message, topic, "shop", List.of(rows), 0);
    }

    private static StructuredMemorySelector.Candidate candidate(String id, String content, String ws,
                                                                String topic, String supersedes) {
        return new StructuredMemorySelector.Candidate(mem(id, content, false), ME, ws, topic,
                StructuredMemorySelector.Status.ACTIVE, supersedes);
    }

    private static ShadowRow superseded(String id, String content, String topic) {
        return new ShadowRow(candidate(id, content, "shop", topic, null), null, Expectation.SUPERSEDED);
    }

    private static ShadowRow superseding(String id, String content, String topic, String oldId) {
        return new ShadowRow(candidate(id, content, "shop", topic, oldId), null, Expectation.MUST);
    }

    private static ShadowCase supersede(String scenario, String message, String topic,
                                         String oldId, String oldText, String newId, String newText) {
        return new ShadowCase(scenario, message, topic, "shop", List.of(
                superseded(oldId, oldText, topic),
                superseding(newId, newText, topic, oldId)), 0);
    }

    /** 任务标签的四种来源。 */
    enum LabelSource { MANUAL, AUTO, MISSING, WRONG }

    private StructuredMemorySelector.TaskContext contextFor(ShadowCase c, LabelSource source) {
        return switch (source) {
            case MANUAL -> new StructuredMemorySelector.TaskContext(ME, c.workspace, List.of(c.trueTopic), true);
            case AUTO -> TaskTopicIdentifier.identify(surface(c.message), LEXICON).toContext(ME, c.workspace);
            case MISSING -> new StructuredMemorySelector.TaskContext(ME, c.workspace, List.of(), false);
            case WRONG -> new StructuredMemorySelector.TaskContext(
                    ME, c.workspace, List.of(c.scenario.equals("wrong-label") ? wrongTopic(c) : c.trueTopic), false);
        };
    }

    /** 把消息里的中文说法换成词表里的写法，模拟 memKey/文件名与消息用同一套词。 */
    private static String surface(String message) {
        String out = message;
        for (Map.Entry<String, String> e : SURFACE.entrySet()) out = out.replace(e.getValue(), e.getKey());
        return out;
    }

    /** 错误标签：消息里第一个出现、且不是真主题的词表词。 */
    private static String wrongTopic(ShadowCase c) {
        String surfaced = surface(c.message);
        int earliest = Integer.MAX_VALUE;
        String found = null;
        for (String term : LEXICON) {
            int at = surfaced.indexOf(term);
            if (at >= 0 && at < earliest) { earliest = at; found = term; }
        }
        if (found == null || found.equals(c.trueTopic)) {
            throw new IllegalStateException("用例没有可用的错误标签: " + c.message);
        }
        return found;
    }

    private List<StructuredMemorySelector.Candidate> candidatesOf(ShadowCase c, LabelSource source) {
        List<StructuredMemorySelector.Candidate> out = new ArrayList<>();
        for (ShadowRow row : c.rows) {
            if (source == LabelSource.WRONG && row.wrongTopic != null) {
                StructuredMemorySelector.Candidate t = row.truth;
                out.add(new StructuredMemorySelector.Candidate(t.item, t.ownerUserId, t.workspace,
                        row.wrongTopic, t.status, t.supersedesId));
            } else {
                out.add(row.truth);
            }
        }
        return out;
    }

    @Test
    @DisplayName("自动识别：命中一个才算识别，多个算歧义，零个算未知")
    void identificationKinds() {
        assertEquals(TaskTopicIdentifier.Kind.IDENTIFIED,
                TaskTopicIdentifier.identify("账单规则是什么", List.of("账单", "部署")).kind());
        TaskTopicIdentifier.Identification ambiguous =
                TaskTopicIdentifier.identify("账单和部署都看一下", List.of("账单", "部署"));
        assertEquals(TaskTopicIdentifier.Kind.AMBIGUOUS, ambiguous.kind());
        assertEquals(2, ambiguous.topics().size());
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify("帮我看看进度", List.of("账单", "部署")).kind());
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify("   ", List.of("账单")).kind());
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify("账单", List.of()).kind());
        // 单字词不参与，避免把任何含该字的句子都判中
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify("账单规则", List.of("账")).kind());
    }

    @Test
    @DisplayName("歧义与未知都不产出可用主题，且不标记为人工标签")
    void noForcedClassification() {
        for (TaskTopicIdentifier.Kind kind : List.of(
                TaskTopicIdentifier.Kind.AMBIGUOUS, TaskTopicIdentifier.Kind.UNKNOWN)) {
            TaskTopicIdentifier.Identification id = kind == TaskTopicIdentifier.Kind.AMBIGUOUS
                    ? TaskTopicIdentifier.identify("账单和部署", List.of("账单", "部署"))
                    : TaskTopicIdentifier.identify("看看进度", List.of("账单"));
            StructuredMemorySelector.TaskContext ctx = id.toContext(ME, "shop");
            assertTrue(ctx.topics.isEmpty(), kind.toString());
            assertFalse(ctx.labelsSupplied, "自动识别不得标记为人工标签");
        }
    }

    @Test
    @DisplayName("候选转换只填现有字段：主题、状态、替代关系留空")
    void adapterDoesNotInventFields() {
        StructuredMemorySelector.Candidate user = MemoryCandidateAdapter.fromUserMemory(
                new MemoryCandidateAdapter.UserMemoryRow(ME, "constraint", "reply_lang", "回答必须用中文"));
        assertEquals(ME, user.ownerUserId);
        assertNull(user.workspace, "用户记忆没有工作区归属");
        assertNull(user.topic, "没有 topic 列，不能填");
        assertEquals(StructuredMemorySelector.Status.UNKNOWN, user.status, "看不出有效状态");
        assertNull(user.supersedesId, "没有替代关系");
        assertTrue(user.item.protectedItem(), "constraint 类别是可证明的硬约束");

        StructuredMemorySelector.Candidate pref = MemoryCandidateAdapter.fromUserMemory(
                new MemoryCandidateAdapter.UserMemoryRow(ME, "preference", "tone", "语气随意"));
        assertFalse(pref.item.protectedItem(), "preference 不是硬约束");

        StructuredMemorySelector.Candidate file =
                MemoryCandidateAdapter.fromWorkspaceFile("shop", "MEMORY.md", "进度：账单模块做到一半");
        assertEquals("shop", file.workspace);
        assertNull(file.ownerUserId, "工作区文件不按用户隔离");
        assertNull(file.topic, "文件名不是主题");
        assertEquals(StructuredMemorySelector.Status.UNKNOWN, file.status);
        assertNull(file.supersedesId);
        assertFalse(file.item.protectedItem());
    }

    @Test
    @DisplayName("从真实字段转出的候选，在没有主题时全部走不确定而不是被选中")
    void realFieldsProduceUncertaintyNotSelection() {
        List<StructuredMemorySelector.Candidate> candidates = List.of(
                MemoryCandidateAdapter.fromUserMemory(
                        new MemoryCandidateAdapter.UserMemoryRow(ME, "constraint", "lang", "回答必须用中文")),
                MemoryCandidateAdapter.fromUserMemory(
                        new MemoryCandidateAdapter.UserMemoryRow(ME, "preference", "billing", "账单按月出账")),
                MemoryCandidateAdapter.fromWorkspaceFile("shop", "billing.md", "账单按调用次数计费"),
                MemoryCandidateAdapter.fromUserMemory(
                        new MemoryCandidateAdapter.UserMemoryRow(OTHER, "preference", "tone", "别人的偏好")));
        TaskTopicIdentifier.Identification id = TaskTopicIdentifier.identify("账单规则", List.of("billing"));
        StructuredMemorySelector.Result result =
                StructuredMemorySelector.select(id.toContext(ME, "shop"), candidates, 0);

        Map<String, StructuredMemorySelector.Verdict> byId = new LinkedHashMap<>();
        for (StructuredMemorySelector.Verdict v : result.verdicts) byId.put(v.candidate.item.id(), v);

        assertEquals(StructuredMemorySelector.Decision.SELECTED,
                byId.get("user:constraint:lang").decision, "硬约束不依赖主题");
        assertEquals(StructuredMemorySelector.Decision.UNCERTAIN,
                byId.get("user:preference:billing").decision, "没有主题字段，不能因为 key 像主题就选");
        assertEquals(StructuredMemorySelector.Decision.UNCERTAIN,
                byId.get("workspace:billing.md").decision, "文件名不是主题");
        assertEquals(StructuredMemorySelector.Decision.EXCLUDED,
                byId.get("user:preference:tone").decision, "别的用户直接排除");
        assertFalse(result.labelsSupplied);
    }

    @Test
    @DisplayName("四组对照：人工标签、自动识别、标签缺失、错误标签")
    void compareLabelSources() {
        List<ShadowCase> all = cases();
        assertTrue(all.size() >= 39, "实际 " + all.size());

        Map<String, Integer> perScenario = new LinkedHashMap<>();
        for (ShadowCase c : all) perScenario.merge(c.scenario, 1, Integer::sum);
        for (String scenario : List.of("auto-label-missing", "wrong-label", "ambiguous-task")) {
            assertTrue(perScenario.getOrDefault(scenario, 0) >= 3, perScenario.toString());
        }

        for (LabelSource source : LabelSource.values()) {
            Map<String, int[]> by = new LinkedHashMap<>();
            int identified = 0, ambiguous = 0, unknown = 0;
            for (ShadowCase c : all) {
                if (source == LabelSource.AUTO) {
                    switch (TaskTopicIdentifier.identify(surface(c.message), LEXICON).kind()) {
                        case IDENTIFIED -> identified++;
                        case AMBIGUOUS -> ambiguous++;
                        case UNKNOWN -> unknown++;
                    }
                }
                StructuredMemorySelector.Result result =
                        StructuredMemorySelector.select(contextFor(c, source), candidatesOf(c, source), c.budgetChars);
                if (source == LabelSource.MANUAL) assertTrue(result.labelsSupplied);
                else assertFalse(result.labelsSupplied, source.toString());
                for (ShadowRow row : c.rows) {
                    StructuredMemorySelector.Verdict v = null;
                    for (StructuredMemorySelector.Verdict x : result.verdicts) {
                        if (x.candidate.item.id().equals(row.truth.item.id())) v = x;
                    }
                    assertNotNull(v, row.truth.item.id());
                    tally(by, c.scenario, row.expectation, classify(row.expectation,
                            v.decision == StructuredMemorySelector.Decision.SELECTED,
                            v.decision == StructuredMemorySelector.Decision.UNCERTAIN));
                }
            }
            System.out.println("== " + source + " ==");
            System.out.println(report(by));
            if (source == LabelSource.AUTO) {
                System.out.println("identification identified=" + identified
                        + " ambiguous=" + ambiguous + " unknown=" + unknown);
            }
        }
    }

    /** 自动识别在「消息不含任何主题词」时不得猜一个主题出来。 */
    @Test
    @DisplayName("缺失与歧义场景：自动识别产出零个主题")
    void missingAndAmbiguousYieldNoTopic() {
        for (ShadowCase c : cases()) {
            if (!c.scenario.equals("auto-label-missing") && !c.scenario.equals("ambiguous-task")
                    && !c.scenario.equals("paraphrase")) continue;
            TaskTopicIdentifier.Identification id = TaskTopicIdentifier.identify(surface(c.message), LEXICON);
            assertNotEquals(TaskTopicIdentifier.Kind.IDENTIFIED, id.kind(),
                    c.scenario + " / " + c.message + " 不应被强行识别成一个主题");
        }
    }

    /** 错误标签场景：自动识别确实会给错，且与真主题不同。这是对照组成立的前提。 */
    @Test
    @DisplayName("错误标签场景：自动识别命中的词不是真主题")
    void wrongLabelCasesAreActuallyWrong() {
        int wrong = 0;
        for (ShadowCase c : cases()) {
            if (!c.scenario.equals("wrong-label")) continue;
            TaskTopicIdentifier.Identification id = TaskTopicIdentifier.identify(surface(c.message), LEXICON);
            assertEquals(TaskTopicIdentifier.Kind.IDENTIFIED, id.kind(), c.message);
            assertFalse(id.topics().contains(c.trueTopic), c.message);
            wrong++;
        }
        assertTrue(wrong >= 3);
    }

    private enum Outcome { HIT, MISS, UNCERTAIN, WRONG, SUPERSEDED_HIT, CONSTRAINT_MISS, UNJUDGEABLE_SELECTED }

    private static Outcome classify(Expectation exp, boolean selected, boolean uncertain) {
        return switch (exp) {
            case MUST, SHOULD -> selected ? Outcome.HIT : (uncertain ? Outcome.UNCERTAIN : Outcome.MISS);
            case MUST_NOT -> selected ? Outcome.WRONG : Outcome.HIT;
            case SUPERSEDED -> selected ? Outcome.SUPERSEDED_HIT : Outcome.HIT;
            case CONSTRAINT -> selected ? Outcome.HIT : Outcome.CONSTRAINT_MISS;
            case UNJUDGEABLE -> selected ? Outcome.UNJUDGEABLE_SELECTED : Outcome.HIT;
        };
    }

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
            case MISS -> { }
        }
    }

    private static String report(Map<String, int[]> by) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : by.entrySet()) {
            int[] b = e.getValue();
            sb.append(String.format(Locale.ROOT,
                    "%-22s must=%d/%d should=%d/%d wrong=%d supersededSelected=%d constraintMiss=%d uncertain=%d unjudgeableSelected=%d crossWorkspace=%s%n",
                    e.getKey(), b[1], b[0], b[3], b[2], b[4], b[5], b[6], b[7], b[8], ""));
        }
        sb.append(String.format(Locale.ROOT,
                "TOTAL must=%d/%d should=%d/%d wrong=%d supersededSelected=%d constraintMiss=%d uncertain=%d unjudgeableSelected=%d%n",
                sum(by, 1), sum(by, 0), sum(by, 3), sum(by, 2), sum(by, 4), sum(by, 5), sum(by, 6), sum(by, 7), sum(by, 8)));
        return sb.toString();
    }

    private static int sum(Map<String, int[]> by, int idx) {
        int n = 0;
        for (int[] b : by.values()) n += b[idx];
        return n;
    }
}
