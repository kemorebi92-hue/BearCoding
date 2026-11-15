package com.example.jingjingai2.agent;

import com.example.jingjingai2.config.AgentPrompts;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.utils.FileTool;
import com.example.jingjingai2.utils.TerminalTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子代理房间的**契约测试**。
 *
 * 这个类里的断言就是"子代理到底是什么"的定义，改了它们就等于改了产品语义：
 *  ① 房间干净（模型只看到"短系统提示 + 这一件事"，没有聊天历史、没有计划）；
 *  ② 工具白名单（explore 只读、implement 才能写，两种模式都没有 createPlan/updatePlanStep/spawnSubagent）；
 *  ③ 结束条件与 {@link SubagentResult.FailureReason} 一一对应；
 *  ④ 工具结果在房间里被截断（隔离省下来的 token 不会又被烧回去）。
 */
class SubagentRunnerTest {

    private static final String CONV = "conv-1";

    private final SubagentProperties props = new SubagentProperties();
    private final AgentCancellationRegistry registry = new AgentCancellationRegistry();

    private SubagentRunner runner(ToolCallback[] all) {
        return new SubagentRunner(props, registry, all);
    }

    private static SubagentSpec explore(String goal) {
        return new SubagentSpec(goal, SubagentSpec.Mode.EXPLORE, 0, CONV);
    }

    private static Set<String> toolNamesOffered(Prompt p) {
        assertTrue(p.getOptions() instanceof ToolCallingChatOptions,
                "子代理必须用 ToolCallingChatOptions 下发工具");
        Set<String> names = new HashSet<>();
        for (ToolCallback cb : ((ToolCallingChatOptions) p.getOptions()).getToolCallbacks()) {
            names.add(cb.getToolDefinition().name());
        }
        return names;
    }

    /** Spring AI 的 MethodToolCallback 会把 @Tool 返回值再 JSON 包一层；直接调 bean 没有这一层。 */
    private static String unwrapToolResult(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(t, String.class);
            } catch (Exception ignored) {
                return t.substring(1, t.length() - 1).replace("\\\\", "\\");
            }
        }
        return s;
    }

    private static String firstToolResult(Prompt p) {
        for (Message m : p.getInstructions()) {
            if (m instanceof ToolResponseMessage trm) {
                return trm.getResponses().get(0).responseData();
            }
        }
        return null;
    }

    // ==================== ① 房间干净 ====================

    @Test
    @DisplayName("房间只有三段：短系统提示 + 这一件事 + 末尾的预算消息；没有聊天历史、没有计划清单")
    void roomIsClean() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：X", 1);

        runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("看看 plan 包的职责"), model, null);

        Prompt first = model.prompts().get(0);
        List<Message> msgs = first.getInstructions();
        assertEquals(3, msgs.size(),
                "房间只该有 [SystemMessage, UserMessage(任务), UserMessage(预算)]，实际 " + msgs);
        assertTrue(msgs.get(0) instanceof SystemMessage);
        assertTrue(msgs.get(1) instanceof UserMessage);
        assertEquals(AgentPrompts.SUBAGENT_PROMPT, msgs.get(0).getText(),
                "子房间必须用那份【短的】子代理提示词，而不是主提示词");
        assertTrue(msgs.get(1).getText().contains("看看 plan 包的职责"), "目标要原样带进去");
        assertEquals(1, model.callCount(), "模型自然收尾后不该再问第二轮");
    }

    @Test
    @DisplayName("子代理提示词明显短于主提示词（房间里不需要规划/交互那一大段规则）")
    void subagentPromptIsMuchShorter() {
        assertTrue(AgentPrompts.SUBAGENT_PROMPT.length() * 4 <= AgentPrompts.SYSTEM_PROMPT.length(),
                "子提示词 " + AgentPrompts.SUBAGENT_PROMPT.length() + " 字，主提示词 "
                        + AgentPrompts.SYSTEM_PROMPT.length() + " 字 —— 子提示词应当不超过主提示词的 1/4");
        assertTrue(AgentPrompts.SUBAGENT_PROMPT.contains("Windows"), "环境事实必须带过去（它照样在 Windows 上跑）");
        assertTrue(AgentPrompts.SUBAGENT_PROMPT.contains("不能规划"), "必须明确告诉它没有规划权限");
    }

    // ==================== ② 工具白名单 ====================

    @Test
    @DisplayName("explore 房间下发的工具只有 5 个只读工具——写类、命令、规划、派子代理一个都看不到")
    void exploreWhitelistIsReadOnly() {
        FakeChatModel model = new FakeChatModel().thenReply("结论", 1);

        runner(ToolStubs.fullSet(null, null, null, null)).run(explore("调研"), model, null);

        Set<String> offered = toolNamesOffered(model.prompts().get(0));
        assertEquals(Set.of("readFile", "listDirectory", "globFile", "grepFile", "webSearch"), offered);
        assertFalse(offered.contains("spawnSubagent"), "禁止套娃：子代理不能再派子代理");
        assertFalse(offered.contains("createPlan"), "计划是主 agent 的事，子房间不该有");
        assertFalse(offered.contains("updatePlanStep"));
        assertFalse(offered.contains("saveUserMemory"), "用户记忆不下发到子房间");
        assertFalse(offered.contains("forgetUserMemory"));
        assertFalse(offered.contains("mcp__echo__echo"), "第一期 MCP 工具不得进 explore 房间");
        assertFalse(offered.contains("mcp__fs__read_file"));
        assertFalse(offered.contains("mcpToolSearch"), "mcpToolSearch 不得进 explore 房间");
    }

    @Test
    @DisplayName("implement 房间多了写类与 executeCommand，但【仍然】没有规划工具和 spawnSubagent")
    void implementWhitelistHasWritesButNoPlanning() {
        FakeChatModel model = new FakeChatModel().thenReply("改完了", 1);
        SubagentSpec spec = new SubagentSpec("改一处配置", SubagentSpec.Mode.IMPLEMENT, 0, CONV);

        runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null);

        Set<String> offered = toolNamesOffered(model.prompts().get(0));
        assertTrue(offered.contains("writeFile"));
        assertTrue(offered.contains("editFile"));
        assertTrue(offered.contains("executeCommand"));
        assertFalse(offered.contains("createPlan"));
        assertFalse(offered.contains("updatePlanStep"));
        assertFalse(offered.contains("spawnSubagent"));
        assertFalse(offered.contains("saveUserMemory"), "用户记忆不下发到 implement 房间");
        assertFalse(offered.contains("forgetUserMemory"));
        assertFalse(offered.contains("mcp__echo__echo"), "第一期 MCP 工具不得进 implement 房间");
        assertFalse(offered.contains("mcp__fs__read_file"));
        assertFalse(offered.contains("mcpToolSearch"), "mcpToolSearch 不得进 implement 房间");
    }

    @Test
    @DisplayName("filter 是白名单而不是黑名单：主循环将来加的新工具，子房间默认拿不到")
    void filterDefaultsToDeny() {
        ToolCallback[] all = ToolStubs.fullSet(null, null, null, null);
        Set<String> offered = new HashSet<>();
        for (ToolCallback cb : SubagentRunner.filter(all, SubagentRunner.EXPLORE_TOOLS)) {
            offered.add(cb.getToolDefinition().name());
        }
        assertEquals(5, offered.size());
        assertTrue(offered.contains("readFile"));
        assertFalse(offered.contains("saveUserMemory"), "白名单没列的记忆工具默认拒绝");
        assertFalse(offered.contains("forgetUserMemory"));
        assertFalse(offered.contains("mcp__echo__echo"), "白名单没列的 MCP 工具默认拒绝");
        assertFalse(offered.contains("mcpToolSearch"), "白名单没列的 mcpToolSearch 默认拒绝");
    }

    @Test
    @DisplayName("explore 房间喊 writeFile → 被明确拒绝，且写操作一次都没真正执行")
    void exploreCannotWriteEvenIfItAsks() {
        AtomicInteger writes = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "writeFile", "{\"path\":\"a.java\",\"content\":\"x\"}")))
                .thenReply("好吧，我只汇报不动手", 1);

        SubagentResult r = runner(ToolStubs.fullSet(null, writes, null, null))
                .run(explore("顺便帮我改个文件"), model, null);

        assertEquals(0, writes.get(), "写类工具绝不能被子代理执行 —— 这是白名单的全部意义");
        assertEquals(SubagentResult.FailureReason.SUCCESS, r.failureReason());
        assertEquals(1, r.toolCalls(), "被拒的那次也算一次工具调用");
        String rejection = firstToolResult(model.prompts().get(1));
        assertNotNull(rejection, "拒绝说明必须作为工具结果回灌给模型，让它自我纠正");
        assertTrue(rejection.contains("writeFile") && rejection.contains("不可用"), "实际：" + rejection);
    }

    @Test
    @DisplayName("续跑房间 mode 错成 explore、executeCommand 被拒 → 不得当 COMPLETE 交接，报告工具集不符")
    void resumedWrongModeIsNotACompleteHandoff() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> {
                    assertFalse(toolNamesOffered(p).contains("executeCommand"),
                            "错成 explore 时不该下发 executeCommand: " + toolNamesOffered(p));
                    return FakeChatModel.toolCalls(
                            FakeChatModel.call("c1", "executeCommand", "{\"command\":\"javac x.java\"}"));
                })
                .thenReply("结论：改完了\n未覆盖：无\n下一步建议：无", 1);
        List<Message> history = List.of(
                new SystemMessage("短提示"),
                new UserMessage("改配置并验证"));
        SubagentRunner.Room room = SubagentRunner.Room.resume(
                history, List.of("还要编译"), List.of(), SubagentSpec.Mode.IMPLEMENT);
        SubagentSpec spec = new SubagentSpec("改配置并验证", SubagentSpec.Mode.EXPLORE, 3, CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null))
                .run(spec, model, null, room);

        assertEquals(SubagentReport.Status.PARTIAL, r.completeness(),
                "错 mode 续跑不能当 COMPLETE，否则父代理会 updatePlanStep");
        String text = r.toModelText(2000);
        assertTrue(text.contains(HandoffResult.TOOLSET_MISMATCH), text);
        assertFalse(HandoffResult.looksLikeVerifiedSuccess(text));
        assertTrue(room.writeToolRefused);
    }

    @Test
    @DisplayName("explore 想套娃派子代理 → 同样被拒绝，spawnSubagent 一次都没被调用")
    void noNestedSubagents() {
        AtomicInteger spawns = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "spawnSubagent", "{\"goal\":\"再派一个\"}")))
                .thenReply("自己看完了", 1);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, spawns))
                .run(explore("调研"), model, null);

        assertEquals(0, spawns.get(), "禁止套娃：子代理不能再派子代理");
        assertEquals(SubagentResult.FailureReason.SUCCESS, r.failureReason());
    }

    @Test
    @DisplayName("反复喊同一个非法工具 → 连续失败 3 次后提前结束（TOOL_FAILURES），不再空转")
    void repeatedRejectionsEndTheRoom() {
        // 每轮用不同的 tool_call_id：真实服务商不会跨轮复用同一个 id，复用会被协议校验拦下。
        AtomicInteger ids = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c" + ids.incrementAndGet(), "writeFile", "{}")));

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("硬要写文件"), model, null);

        assertEquals(SubagentResult.FailureReason.TOOL_FAILURES, r.failureReason());
        assertEquals(3, r.toolCalls(), "连失败 3 次就该停，不该把 8 轮预算烧光");
        assertFalse(r.detail().isBlank(), "要让主 agent 看到卡在哪");
    }

    // ==================== ③ 结束条件 ====================

    @Test
    @DisplayName("模型自然收尾 → SUCCESS，摘要就是它最后那段话")
    void naturalStopIsSuccess() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：这个包负责计划步骤的状态流转", 1);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("调研"), model, null);

        assertEquals(SubagentResult.FailureReason.SUCCESS, r.failureReason());
        assertTrue(r.ok());
        assertFalse(r.truncated());
        assertTrue(r.summary().contains("状态流转"));
        assertEquals(1, r.roundsUsed());
    }

    @Test
    @DisplayName("轮数用尽 → ROUNDS_EXHAUSTED + truncated=true（主 agent 据此收窄目标再派）")
    void roundsExhausted() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")));
        SubagentSpec spec = new SubagentSpec("读很多文件", SubagentSpec.Mode.EXPLORE, 2, CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null);

        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason());
        assertTrue(r.truncated());
        assertEquals(2, r.roundsUsed(), "spec 里要求 2 轮就必须只跑 2 轮");
        assertEquals(2, r.maxRounds());
        assertEquals(2, model.callCount());
        assertTrue(r.filesRead().contains("a.java"), "已读文件要带回来，主 agent 可以自己去读");
    }

    @Test
    @DisplayName("轮数请求被硬顶夹住：要求 100 轮也只会跑 hard-cap 轮")
    void maxRoundsIsClampedByHardCap() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{}")));
        props.setHardCap(3);
        SubagentSpec spec = new SubagentSpec("跑飞试试", SubagentSpec.Mode.EXPLORE, 100, CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null);

        assertEquals(3, r.roundsUsed(), "不能因为模型要求就真的跑 100 轮");
        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason());
    }

    @Test
    @DisplayName("用户点了停止 → CANCELLED，且一轮都不跑")
    void cancelledBeforeFirstRound() {
        FakeChatModel model = new FakeChatModel().thenReply("不该被调用", 1);
        registry.arm(CONV);
        registry.cancel(CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("调研"), model, null);

        assertEquals(SubagentResult.FailureReason.CANCELLED, r.failureReason());
        assertTrue(r.cancelled());
        assertEquals(0, model.callCount(), "已经取消了就不该再花钱问模型");
    }

    @Test
    @DisplayName("跑到一半被停止 → 下一轮开头就停（取消点在内核里，不是装饰）")
    void cancelledMidRun() {
        registry.arm(CONV);
        FakeChatModel model = new FakeChatModel().then(p -> {
            registry.cancel(CONV);   // 模型这一轮还没返回，用户已经点了停止
            return FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{}"));
        });

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("调研"), model, null);

        assertEquals(SubagentResult.FailureReason.CANCELLED, r.failureReason());
        assertEquals(1, r.roundsUsed());
    }

    @Test
    @DisplayName("同一个工具连续失败 3 次 → TOOL_FAILURES，并带上最后一次报错")
    void toolFailuresAfterThreeConsecutive() {
        AtomicInteger ids = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c" + ids.incrementAndGet(),
                        "readFile", "{\"path\":\"missing.java\"}")));

        SubagentResult r = runner(new ToolCallback[]{
                        ToolStubs.of("readFile", "读取失败: 文件不存在: missing.java")})
                .run(explore("读不存在的文件"), model, null);

        assertEquals(SubagentResult.FailureReason.TOOL_FAILURES, r.failureReason());
        assertEquals(3, r.roundsUsed());
        assertTrue(r.detail().contains("读取失败"), "最后一次报错要带回来，否则主 agent 不知道卡在哪");
    }

    @Test
    @DisplayName("失败一次后成功 → 连失败计数清零（不能因为历史失败就提前掐死）")
    void failureStreakResetsOnSuccess() {
        AtomicInteger round = new AtomicInteger();
        ToolCallback flaky = ToolStubs.of("readFile", args ->
                round.get() % 2 == 1 ? "读取失败: 偶发" : "正常内容");
        FakeChatModel model = new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                FakeChatModel.call("r" + round.incrementAndGet(), "readFile", "{}")));
        SubagentSpec spec = new SubagentSpec("时好时坏", SubagentSpec.Mode.EXPLORE, 6, CONV);

        SubagentResult r = runner(new ToolCallback[]{flaky}).run(spec, model, null);

        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason(),
                "失败/成功交替时不该判成 TOOL_FAILURES（那要的是【连续】失败）");
        assertEquals(5, r.toolCalls(),
                "预算是 6 轮 = 5 轮干活 + 1 轮收尾；收尾轮不下发工具，那次调用不该被算进去");
    }

    // ==================== ④ 截断 ====================

    @Test
    @DisplayName("房间里的工具结果被截断到 max-tool-result-chars（读 2 万字不会把房间撑爆）")
    void toolResultsAreClippedInsideTheRoom() {
        String huge = "行".repeat(10000);   // 10000 字
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{\"path\":\"big.java\"}")))
                .thenReply("读完了", 1);

        SubagentResult r = runner(new ToolCallback[]{ToolStubs.of("readFile", huge)})
                .run(explore("读大文件"), model, null);

        assertEquals(SubagentResult.FailureReason.SUCCESS, r.failureReason());
        String stored = firstToolResult(model.prompts().get(1));
        assertNotNull(stored);
        assertTrue(stored.length() <= props.getMaxToolResultChars(),
                "存进房间的工具结果必须 ≤ " + props.getMaxToolResultChars() + " 字，实际 " + stored.length());
        assertTrue(stored.contains("已截断"));
        assertTrue(r.summary().contains("读完了"));
    }

    @Test
    @DisplayName("max-tool-result-chars<=0 → 房间也不截断（配置能一键关掉这道闸）")
    void clippingCanBeTurnedOff() {
        props.setMaxToolResultChars(0);
        String huge = "行".repeat(10000);
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{}")))
                .thenReply("读完了", 1);

        runner(new ToolCallback[]{ToolStubs.of("readFile", huge)}).run(explore("读大文件"), model, null);

        assertEquals(10000, firstToolResult(model.prompts().get(1)).length());
    }

    // ==================== ⑤ 上次笔记 ====================

    @Test
    @DisplayName("重派同一目标时，新房间注入上次的短笔记（含 failureReason 枚举名）")
    void previousNoteIsInjected() {
        FakeChatModel model = new FakeChatModel().thenReply("接着上次看完了 X", 1);
        SubagentResult previous = SubagentResult.roundsExhausted(
                "调研 plan 包", SubagentSpec.Mode.EXPLORE, "看到 TaskPlanService 就没轮数了",
                8, 8, 12, new java.util.LinkedHashSet<>(List.of("TaskPlanService.java")), "轮数用尽");
        LastRunNote note = LastRunNote.of(previous, props.getMaxSummaryChars());

        runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("调研 plan 包"), model, note);

        String task = model.prompts().get(0).getInstructions().get(1).getText();
        assertTrue(task.contains("上次尝试"), "要注入上次笔记：实际 " + task);
        assertTrue(task.contains("ROUNDS_EXHAUSTED"), "必须带枚举名，便于日志/主 agent 直接扫");
        assertTrue(task.contains("TaskPlanService.java"), "已读文件要带上，避免重复读");
    }

    // ==================== ⑥ 第二轮：预算感知（每轮末尾一条，不插历史中间） ====================

    /** 每一轮实际发出去的消息列表（FakeChatModel 记下的原始 Prompt） */
    private static List<Message> sentMessages(FakeChatModel model, int round) {
        return model.prompts().get(round).getInstructions();
    }

    @Test
    @DisplayName("预算消息每轮追加在**末尾**，且历史里始终只有一条（上一轮的先摘掉，不堆积）")
    void budgetMessageIsAppendedAtTheEnd() {
        AtomicInteger ids = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c" + ids.incrementAndGet(), "readFile", "{}")));
        SubagentSpec spec = new SubagentSpec("看很多文件", SubagentSpec.Mode.EXPLORE, 5, CONV);
        SubagentRunner.Room room = SubagentRunner.Room.fresh();

        runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null, room);

        assertEquals(5, room.budgetTexts.size(), "每一轮都要有一条预算消息");
        for (int r = 0; r < 5; r++) {
            List<Message> msgs = sentMessages(model, r);
            Message last = msgs.get(msgs.size() - 1);
            assertTrue(last instanceof UserMessage, "第 " + (r + 1) + " 轮的最后一条应当是预算消息");
            assertEquals(room.budgetTexts.get(r), last.getText(),
                    "预算消息必须是**最后一条**（追加在末尾，不插历史中间）");
            long budgetCount = msgs.stream()
                    .filter(m -> m instanceof UserMessage um
                            && um.getText() != null && um.getText().startsWith("[预算]")).count();
            assertEquals(1, budgetCount,
                    "第 " + (r + 1) + " 轮历史里只该有 1 条预算消息（上一轮的已摘掉），实际 " + budgetCount);
        }
        assertEquals("[预算] 已用 1/5，剩余 4 轮", room.budgetTexts.get(0));
    }

    @Test
    @DisplayName("剩 3 轮起追加软提醒（停止扩大搜索、准备写结论）")
    void softWarningAppearsWithThreeRoundsLeft() {
        SubagentRunner r = runner(ToolStubs.fullSet(null, null, null, null));

        assertFalse(r.budgetText(1, 6).contains("收尾提醒"), "还剩 5 轮不该催");
        assertTrue(r.budgetText(2, 6).contains("剩余 4 轮"));
        assertFalse(r.budgetText(2, 6).contains("收尾提醒"), "还剩 4 轮仍不该催");
        assertTrue(r.budgetText(3, 6).contains("剩余 3 轮"));
        assertTrue(r.budgetText(3, 6).contains("收尾提醒"), "剩 3 轮开始提醒（阈值 warn-rounds-left 可配）");
        assertTrue(r.budgetText(4, 6).contains("收尾提醒"), "剩 2 轮继续提醒");
    }

    // ==================== ⑦ 第二轮：收尾轮（不给工具）与保底摘要 ====================

    @Test
    @DisplayName("最后一轮**不提供任何工具**：toolsPerRound 末位为 0，发出的 Prompt 里工具清单为空")
    void lastRoundOffersNoTools() {
        AtomicInteger ids = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c" + ids.incrementAndGet(), "readFile", "{}")));
        SubagentSpec spec = new SubagentSpec("读到没轮数", SubagentSpec.Mode.EXPLORE, 4, CONV);
        SubagentRunner.Room room = SubagentRunner.Room.fresh();

        runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null, room);

        assertEquals(List.of(5, 5, 5, 0), room.toolsPerRound, "只有最后一轮是 0 个工具");
        Prompt lastPrompt = model.prompts().get(3);
        assertTrue(((ToolCallingChatOptions) lastPrompt.getOptions()).getToolCallbacks().isEmpty(),
                "收尾轮不该下发任何工具（模型看不到工具，就不容易再喊工具）");
        assertTrue(room.budgetTexts.get(3).contains("不再提供任何工具"));
        assertTrue(room.budgetTexts.get(3).contains(SubagentReport.MARK_ANSWER), "收尾指令要给出三行标记");
        assertTrue(room.budgetTexts.get(3).contains(SubagentReport.MARK_UNRESOLVED));
    }

    @Test
    @DisplayName("收尾轮还在喊工具 → 工具一律不执行，内核按台账生成保底摘要，状态 PARTIAL（不是 NONE）")
    void wrapUpRoundIgnoresToolCallsAndFallsBack() {
        AtomicInteger reads = new AtomicInteger();
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")));
        SubagentSpec spec = new SubagentSpec("读不完", SubagentSpec.Mode.EXPLORE, 2, CONV);
        SubagentRunner.Room room = SubagentRunner.Room.fresh();

        SubagentResult r = runner(ToolStubs.fullSet(reads, null, null, null))
                .run(spec, model, null, room);

        assertEquals(1, reads.get(), "收尾轮的工具调用必须被**忽略**（只执行了第 1 轮那一次）");
        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason());
        assertTrue(room.wrapUpUsed, "必须走了收尾轮");
        assertTrue(room.fallbackSummary, "收尾轮没给出报告 → 走保底摘要");
        assertEquals(SubagentReport.Status.PARTIAL, r.completeness(),
                "轮数用尽但有台账可用 → PARTIAL（可用的部分结果），不是 NONE");
        assertTrue(r.answer().contains("内核保底摘要"), "保底摘要要如实标注，不能假装是模型结论：实际 " + r.answer());
        assertTrue(r.answer().contains("a.java"), "保底摘要要基于**实际工具调用记录**");
        assertEquals(2, r.roundsUsed());
    }

    @Test
    @DisplayName("收尾轮写了「未覆盖：无」→ COMPLETE（停因仍是 ROUNDS_EXHAUSTED）")
    void wrapUpWithEmptyUnresolvedIsComplete() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")))
                .thenReply("结论：查清了\n未覆盖：无\n下一步建议：无", 1);
        SubagentSpec spec = new SubagentSpec("读完", SubagentSpec.Mode.EXPLORE, 2, CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null);

        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason(),
                "收尾轮的停因永远是 ROUNDS_EXHAUSTED，与完整性正交");
        assertTrue(r.wrapUp());
        assertEquals(SubagentReport.Status.COMPLETE, r.completeness(),
                "未覆盖为空才允许 COMPLETE：实际 " + r.completeness() + " / " + r.answer());
        assertTrue(r.report().unresolved().isEmpty());
    }

    @Test
    @DisplayName("收尾轮写了非空未覆盖 → 一律 PARTIAL（即使结论看起来完整）")
    void wrapUpWithUnresolvedIsPartial() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")))
                .thenReply("结论：一半\n未覆盖：B 还没查\n下一步建议：查 B", 1);
        SubagentSpec spec = new SubagentSpec("读不完", SubagentSpec.Mode.EXPLORE, 2, CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null);

        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason());
        assertTrue(r.wrapUp());
        assertEquals(SubagentReport.Status.PARTIAL, r.completeness());
        assertTrue(r.report().unresolved().stream().anyMatch(u -> u.contains("B 还没查")),
                "实际 unresolved=" + r.report().unresolved());
    }

    @Test
    @DisplayName("收尾轮没写「未覆盖：」标记 → PARTIAL（不敢当完整结论）")
    void wrapUpWithoutUnresolvedMarkIsPartial() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"a.java\"}")))
                .thenReply("结论：看起来查清了，但没按标记写未覆盖", 1);
        SubagentSpec spec = new SubagentSpec("读不完", SubagentSpec.Mode.EXPLORE, 2, CONV);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null);

        assertEquals(SubagentResult.FailureReason.ROUNDS_EXHAUSTED, r.failureReason());
        assertEquals(SubagentReport.Status.PARTIAL, r.completeness(),
                "没写未覆盖标记就不能标 COMPLETE：实际 " + r.completeness());
    }

    @Test
    @DisplayName("收尾轮离开时不留悬空的 tool_calls（否则续跑时那段历史接不上）")
    void wrapUpDropsDanglingToolCalls() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{}")));
        SubagentSpec spec = new SubagentSpec("读不完", SubagentSpec.Mode.EXPLORE, 2, CONV);
        SubagentRunner.Room room = SubagentRunner.Room.fresh();

        runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null, room);

        Message last = room.messages.get(room.messages.size() - 1);
        assertFalse(last instanceof AssistantMessage,
                "最后一条不能是没人回应的 assistant(tool_calls)，实际 " + last);
    }

    @Test
    @DisplayName("续跑用的历史要去掉末尾的预算脚手架（否则模型会看到「最后一轮」+「请继续」自相矛盾）")
    void resumeHistoryDropsTrailingBudget() {
        List<Message> raw = List.of(new SystemMessage("s"), new UserMessage("任务"),
                new UserMessage("[预算] 已用 2/2，剩余 0 轮 —— 这是最后一轮"));

        List<Message> cleaned = SubagentRunner.historyForResume(raw);

        assertEquals(2, cleaned.size());
        assertEquals("任务", cleaned.get(1).getText());
    }

    // ==================== ⑧ 第二轮：已读台账由内核生成 ====================

    @Test
    @DisplayName("readLedger 来自**真实工具调用记录**（路径 + 行范围 + 返回体量），模型自称的一律不算")
    void readLedgerComesFromToolCallsNotTheModel() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile",
                        "{\"path\":\"src/A.java\",\"offset\":10,\"limit\":20}")))
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c2", "grepFile",
                        "{\"pattern\":\"foo\",\"path\":\"src/plan\"}")))
                .thenReply("结论：A 里 foo 有 3 处（我还读了 ghost.java）", 1);
        SubagentSpec spec = new SubagentSpec("查 foo", SubagentSpec.Mode.EXPLORE, 5, CONV);
        SubagentRunner.Room room = SubagentRunner.Room.fresh();

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null)).run(spec, model, null, room);

        List<ReadLedgerEntry> ledger = r.report().readLedger();
        assertEquals(2, ledger.size(), "台账条数必须等于实际读类工具调用次数（去重后）：" + ledger);
        assertEquals("src/A.java", ledger.get(0).path());
        assertEquals("10-29", ledger.get(0).range(), "行范围要按 offset/limit 如实算出来");
        assertEquals("src/plan", ledger.get(1).path());
        assertTrue(ledger.get(0).line().contains("readFile"));
        assertFalse(ledger.stream().anyMatch(e -> e.path().contains("ghost")),
                "模型自称读过的文件**不许**进台账（那正是被截断时最不可靠的部分）");
        assertEquals(List.of("src/A.java", "src/plan"), r.filesRead());
    }

    @Test
    @DisplayName("answer 超过 max-answer-chars 会被截断并标注（防止多个子代理把主上下文又撑爆）")
    void longAnswerIsTruncated() {
        props.setMaxAnswerChars(60);
        String longText = "结论：" + "很长的结论。".repeat(100);
        FakeChatModel model = new FakeChatModel().thenReply(longText, 1);

        SubagentResult r = runner(ToolStubs.fullSet(null, null, null, null))
                .run(explore("写一份长报告"), model, null);

        assertTrue(r.report().answerTruncated(), "超长结论必须被标记为截断");
        assertTrue(r.answer().length() <= 60, "answer 硬顶 60 字，实际 " + r.answer().length());
        assertTrue(r.answer().contains("已截断"), "截断要标注出来，实际：" + r.answer());
    }

    // ==================== ⑥ 纯函数 ====================

    @Test
    @DisplayName("looksLikeToolFailure：只认各工具自己的错误前缀，正常内容不能被误判成失败")
    void failureDetection() {
        assertTrue(SubagentRunner.looksLikeToolFailure("读取失败: 文件不存在"));
        assertTrue(SubagentRunner.looksLikeToolFailure("工具 writeFile 本轮不可用（它不在本轮下发的工具清单里）"));
        assertTrue(SubagentRunner.looksLikeToolFailure("未知的工具: xxx"));
        assertTrue(SubagentRunner.looksLikeToolFailure("工具调用失败: boom"));
        assertTrue(SubagentRunner.looksLikeToolFailure("修改失败: oldStr 在文件中匹配到 0 处"));
        assertTrue(SubagentRunner.looksLikeToolFailure("新建失败: 文件已存在 -> a.txt"));
        assertTrue(SubagentRunner.looksLikeToolFailure("创建目录失败: 拒绝访问"));
        assertTrue(SubagentRunner.looksLikeToolFailure("创建失败: 同名文件已存在"));
        assertFalse(SubagentRunner.looksLikeToolFailure("正常的文件内容，里面提到了读取失败这个词"));
        assertFalse(SubagentRunner.looksLikeToolFailure(""));
        assertFalse(SubagentRunner.looksLikeToolFailure(null));
    }

    // ==================== 子房间提示词 ====================

    @Test
    @DisplayName("GP 关闭：implement 仍用旧的短提示词，任务 hint 仍是「动手改」")
    void gpOffImplementStillUsesLegacyPrompt() {
        FakeChatModel model = new FakeChatModel().thenReply("改完了", 1);
        runner(ToolStubs.fullSet(null, null, null, null))
                .run(new SubagentSpec("改一处配置", SubagentSpec.Mode.IMPLEMENT, 0, CONV), model, null);
        List<Message> msgs = model.prompts().get(0).getInstructions();
        assertEquals(AgentPrompts.SUBAGENT_PROMPT, msgs.get(0).getText());
        assertTrue(msgs.get(1).getText().contains("implement（动手改）"));
        assertFalse(msgs.get(0).getText().contains("STATUS: SUCCEEDED"));
    }

    private static String handoff(String status, String verification) {
        return "STATUS: " + status + "\n"
                + "SUMMARY: 改完了\n"
                + "CHANGES: Foo.java — 加了方法\n"
                + "VERIFICATION: " + verification + "\n"
                + "DISCOVERIES: None\n"
                + "RISKS: None\n"
                + "REMAINING: None";
    }

    private static ToolCallback[] toolsWithOkCommand(java.util.concurrent.atomic.AtomicInteger n) {
        ToolCallback[] base = ToolStubs.fullSet(null, null, n, null);
        ToolCallback[] out = new ToolCallback[base.length];
        for (int i = 0; i < base.length; i++) {
            if ("executeCommand".equals(base[i].getToolDefinition().name())) {
                out[i] = ToolStubs.of("executeCommand", "compiled ok", n);
            } else {
                out[i] = base[i];
            }
        }
        return out;
    }

    private static ToolCallback[] toolsWithCommandFn(java.util.function.Function<String, String> body) {
        ToolCallback[] base = ToolStubs.fullSet(null, null, null, null);
        ToolCallback[] out = new ToolCallback[base.length];
        for (int i = 0; i < base.length; i++) {
            if ("executeCommand".equals(base[i].getToolDefinition().name())) {
                out[i] = ToolStubs.of("executeCommand", body);
            } else {
                out[i] = base[i];
            }
        }
        return out;
    }

}
