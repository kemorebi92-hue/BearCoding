package com.example.jingjingai2.agent;

import com.example.jingjingai2.service.AgentCancellationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度入口：**闸门在进房间之前**。
 *
 * 这里要钉死的是"什么情况下**根本不进房间**"—— 功能关闭、同一目标超出重派上限、第二个 implement。
 * 三种都必须**不花一分钱**（模型调用次数不增），并且给主 agent 一句明确的话（而不是含糊的失败）。
 */
class SubagentServiceTest {

    private static final String CONV = "conv-svc";

    private final SubagentProperties props = new SubagentProperties();
    private final SubagentMemory memory = new SubagentMemory();
    private final SubagentSessions sessions = new SubagentSessions();
    private final SubagentTelemetry telemetry = new SubagentTelemetry();
    private final SubagentPool pool = new SubagentPool(props);
    private final AgentCancellationRegistry cancellation = new AgentCancellationRegistry();
    private final SubagentService service = new SubagentService(props,
            new SubagentRunner(props, cancellation,
                    ToolStubs.fullSet(null, null, null, null)),
            pool, memory, sessions, telemetry);

    @AfterEach
    void tearDown() {
        pool.destroy();
        props.setMaxRounds(8);
        cancellation.clear(CONV);
        RetryingChatModel.sleeper = Thread::sleep;
    }

    private static AssistantMessage.ToolCall spawn(String id, String goal, String mode) {
        return new AssistantMessage.ToolCall(id, "function", "spawnSubagent",
                "{\"goal\":\"" + goal + "\",\"mode\":\"" + mode + "\"}");
    }

    // ==================== 单个派出 ====================

    @Test
    @DisplayName("正常派一次：回给模型的是「子代理 explore 完成] + 原因：SUCCESS + 结构化结论」")
    void spawnOnceReturnsModelText() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：这个包负责计划状态流转", 1);

        String text = service.spawnOnce("调研 plan 包", "explore", model, CONV);

        assertTrue(text.contains("[子代理 explore 完成]"), "实际：" + text);
        assertTrue(text.contains("原因：SUCCESS"));
        assertTrue(text.contains("完整性：COMPLETE"));
        assertTrue(text.contains("【已确认结论】这个包负责计划状态流转"), "实际：" + text);
        assertEquals(1, model.callCount());
    }

    @Test
    @DisplayName("摘要受 max-answer-chars 约束：子代理说了一大段，回给主 agent 的必须被截短")
    void summaryIsTrimmed() {
        String longText = "很长的结论。".repeat(500);   // 3000 字
        FakeChatModel model = new FakeChatModel().thenReply(longText, 1);

        String text = service.spawnOnce("写一份很长的报告", "explore", model, CONV);

        assertTrue(text.contains("已截断"), "超出 " + props.getMaxAnswerChars() + " 字必须截断");
        assertTrue(text.length() < longText.length() / 2, "回给主 agent 的应当明显更短，实际 " + text.length());
    }

    @Test
    @DisplayName("enabled=false → 明确拒绝（REJECTED），且**一次模型调用都不发生**")
    void disabledMeansNoRoomAtAll() {
        props.setEnabled(false);
        FakeChatModel model = new FakeChatModel().thenReply("不该被调用", 1);

        String text = service.spawnOnce("调研", "explore", model, CONV);

        assertTrue(text.contains("原因：REJECTED"), "实际：" + text);
        assertTrue(text.contains("未启用"));
        assertEquals(0, model.callCount(), "闸门必须挡在进房间之前，不能先花钱再拒绝");
    }

    @Test
    @DisplayName("同一目标第 4 次派出 → RETRY_LIMIT，不进房间（上限 = 1 + max-retries-per-goal = 3）")
    void retryLimitBlocksFourthAttempt() {
        FakeChatModel model = new FakeChatModel().thenReply("结论", 10);

        assertEquals(3, props.totalAttemptsAllowed());
        for (int i = 1; i <= 3; i++) {
            String text = service.spawnOnce("同一个目标", "explore", model, CONV);
            assertFalse(text.contains("RETRY_LIMIT"), "第 " + i + " 次不该被拦：实际 " + text);
        }
        int callsBefore = model.callCount();

        String blocked = service.spawnOnce("同一个目标", "explore", model, CONV);

        assertTrue(blocked.contains("原因：RETRY_LIMIT"), "实际：" + blocked);
        assertTrue(blocked.contains("自己做"), "要明确告诉主 agent 下一步怎么办");
        assertEquals(callsBefore, model.callCount(), "被闸门拦住时不该再进房间花钱");
    }

    @Test
    @DisplayName("重派上限按【规范化后】的目标算：「调研 plan 包。」和「调研  plan 包」算同一个目标")
    void retryLimitIsPerNormalizedGoal() {
        FakeChatModel model = new FakeChatModel().thenReply("结论", 10);
        for (int i = 0; i < 3; i++) service.spawnOnce("调研 plan 包", "explore", model, CONV);

        String blocked = service.spawnOnce("  调研  PLAN 包。", "explore", model, CONV);

        assertTrue(blocked.contains("RETRY_LIMIT"),
                "大小写/空白/标点不同的同一个目标必须撞上限，否则闸门形同虚设：实际 " + blocked);
    }

    @Test
    @DisplayName("换一个目标 → 不受上一个目标的计数影响")
    void differentGoalHasItsOwnBudget() {
        FakeChatModel model = new FakeChatModel().thenReply("结论", 10);
        for (int i = 0; i < 3; i++) service.spawnOnce("目标 A", "explore", model, CONV);

        String text = service.spawnOnce("目标 B", "explore", model, CONV);

        assertFalse(text.contains("RETRY_LIMIT"), "不同目标应当各有一份预算：实际 " + text);
    }

    @Test
    @DisplayName("第二个 implement 在跑 → 直接拒绝（写操作互斥），explore 不受影响")
    void secondImplementRejected() {
        FakeChatModel model = new FakeChatModel().thenReply("改完了", 10);
        assertTrue(pool.tryAcquireImplement(), "先占住写锁，模拟已有一个 implement 在跑");

        String text = service.spawnOnce("改一处配置", "implement", model, CONV);

        assertTrue(text.contains("原因：REJECTED"), "实际：" + text);
        assertTrue(text.contains("写操作不允许并行"));
        assertEquals(0, model.callCount(), "被拒的 implement 不该进房间");

        FakeChatModel exploreModel = new FakeChatModel()
                .thenReply("结论：ok\n未覆盖：无\n下一步建议：无", 1);
        String explore = service.spawnOnce("看看预算", "explore", exploreModel, CONV);
        assertFalse(explore.contains("写操作不允许并行"), "explore 不受写锁影响：实际 " + explore);
        assertTrue(exploreModel.callCount() > 0, "explore 应当进房间");
        assertTrue(pool.isImplementRunning(), "explore 不得释放别人持有的写锁");
    }

    @Test
    @DisplayName("上次派出的活留下 PARTIAL → 便条注入下一次同目标的新房间（不是从零重来）")
    void previousNoteIsInjectedIntoTheNextRoom() {
        props.setMaxRounds(2);   // 第 1 轮正常、第 2 轮收尾：必然以 PARTIAL 收场
        FakeChatModel first = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(FakeChatModel.call("c1", "readFile", "{\"path\":\"A.java\"}")))
                .thenReply("结论：只看到 A 的一半\n未覆盖：B 还没查", 1);
        FakeChatModel second = new FakeChatModel().thenReply("第二趟接着看完了", 1);

        String firstText = service.spawnOnce("调研 plan 包", "explore", first, CONV);
        assertTrue(firstText.contains("完整性：PARTIAL"), "第一趟应当是 PARTIAL：实际 " + firstText);
        assertTrue(memory.partialNoteCount(CONV) >= 1, "PARTIAL 必须留下便条（续跑/重派都要用）");

        service.spawnOnce("调研 plan 包", "explore", second, CONV);

        String secondRoomTask = second.prompts().get(0).getInstructions().get(1).getText();
        assertTrue(secondRoomTask.contains("上次尝试"), "第二个房间要带上上次的便条：实际 " + secondRoomTask);
        assertTrue(secondRoomTask.contains("ROUNDS_EXHAUSTED"), "便条里带上次的停因枚举名");
        assertTrue(secondRoomTask.contains("B 还没查"), "便条要带上还差什么（缺口清单）：实际 " + secondRoomTask);
    }

    @Test
    @DisplayName("COMPLETE 的结果**不留**便条（留着只会在下次注入里当噪音）")
    void completeResultLeavesNoNote() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：查清楚了", 1);

        service.spawnOnce("调研 plan 包", "explore", model, CONV);

        assertEquals(0, memory.noteCount(CONV));
    }

    @Test
    @DisplayName("结论一到：COMPLETE 的便条/房间放掉，PARTIAL 的留着（可能马上续跑）")
    void releaseOnConclusionReleasesOnlyCompletedOnes() {
        props.setMaxRounds(2);
        service.spawnOnce("目标 A", "explore", partialModel("A.java", "B 还没查"), CONV);
        assertEquals(1, memory.partialNoteCount(CONV), "PARTIAL 先留一张便条");

        service.releaseOnConclusion(CONV);

        assertEquals(1, memory.noteCount(CONV),
                "PARTIAL 的便条不能放 —— 主 agent 完全可能紧接着就 resume 它");
        assertEquals(1, sessions.sessionCount(CONV), "房间历史同理");

        // 同一目标后来又跑了一趟，这次完整了 → 旧便条必须被清掉（过期便条比不注入还糟）
        service.spawnOnce("目标 A", "explore",
                new FakeChatModel().thenReply("结论：这次查清了", 1), CONV);

        assertEquals(0, memory.noteCount(CONV));
    }

    @Test
    @DisplayName("结论出来**不重置派出计数**：放掉等于把『同一目标最多派几次』的闸门拆了")
    void releaseOnConclusionKeepsAttemptCounter() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：查清楚了", 10);
        for (int i = 0; i < props.totalAttemptsAllowed(); i++) {
            assertFalse(service.spawnOnce("目标 A", "explore", model, CONV).contains("RETRY_LIMIT"),
                    "第 " + (i + 1) + " 次仍在预算内（上限 " + props.totalAttemptsAllowed() + " 次）");
        }

        service.releaseOnConclusion(CONV);

        assertTrue(service.spawnOnce("目标 A", "explore", model, CONV).contains("RETRY_LIMIT"),
                "释放便条 ≠ 重新发预算，否则主 agent 可以无限重派、成本无上限");
    }

    @Test
    @DisplayName("新用户请求开始时也释放（beginUserRequest）")
    void beginUserRequestResetsBudget() {
        FakeChatModel model = new FakeChatModel().thenReply("结论", 10);
        for (int i = 0; i < 3; i++) service.spawnOnce("目标 A", "explore", model, CONV);

        service.beginUserRequest(CONV);

        assertFalse(service.spawnOnce("目标 A", "explore", model, CONV).contains("RETRY_LIMIT"));
    }

    // ==================== 批量派出 ====================

    @Test
    @DisplayName("一轮里两个 explore → 按 toolCallId 各拿一份结果，并且推了配对的 start/done 事件")
    void runBatchReturnsResultPerCallId() {
        FakeChatModel model = new FakeChatModel().thenReply("调研结论", 4);
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());

        Map<String, SubagentService.SpawnOutcome> out = service.runBatch(
                List.of(spawn("call-1", "调研 A 模块", "explore"),
                        spawn("call-2", "调研 B 模块", "explore")),
                model, CONV, (name, payload) -> events.add(Map.of("event", name, "payload", payload)));

        assertEquals(2, out.size());
        assertTrue(out.get("call-1").ok());
        assertTrue(out.get("call-2").ok());
        assertTrue(out.get("call-1").text().contains("调研 A 模块"));
        assertTrue(out.get("call-2").text().contains("调研 B 模块"));

        long starts = events.stream().filter(e -> "subagent_start".equals(e.get("event"))).count();
        long dones = events.stream().filter(e -> "subagent_done".equals(e.get("event"))).count();
        assertEquals(2, starts, "每个子代理一张 start 卡");
        assertEquals(2, dones, "每个子代理一张 done 卡");
    }

    @Test
    @DisplayName("批量里的非 spawnSubagent 调用被忽略（它走普通派发路径）")
    void runBatchIgnoresNonSpawnCalls() {
        FakeChatModel model = new FakeChatModel().thenReply("结论", 2);

        Map<String, SubagentService.SpawnOutcome> out = service.runBatch(
                List.of(new AssistantMessage.ToolCall("c0", "function", "readFile", "{}"),
                        spawn("call-1", "调研", "explore")),
                model, CONV, null);

        assertEquals(1, out.size());
        assertNull(out.get("c0"));
        assertNotNull(out.get("call-1"));
    }

    @Test
    @DisplayName("只读判定：explore 可以并行，implement 不行（写互斥，必须串行）")
    void readOnlySpawnOnlyForExplore() {
        assertTrue(service.isReadOnlySpawn(spawn("c1", "调研", "explore"), "conv"), "explore 是只读");
        assertFalse(service.isReadOnlySpawn(spawn("c2", "改代码", "implement"), "conv"),
                "implement 会写文件，必须串行，否则第二个会撞 implement 锁被拒");
        assertTrue(service.isReadOnlySpawn(new AssistantMessage.ToolCall("c3", "function",
                "spawnSubagent", "{\"goal\":\"没写 mode\"}"), "conv"), "不填 mode 默认 explore");
        assertFalse(service.isReadOnlySpawn(new AssistantMessage.ToolCall("c4", "function",
                "readFile", "{}"), "conv"), "非 spawn 工具不参与并行派发");
    }

    @Test
    @DisplayName("一轮里两个 explore 真的重叠跑：并发峰值 2，且两张 start 卡都先到")
    void runBatchOverlapsInsteadOfSerializing() {
        OverlapModel model = new OverlapModel();
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());

        service.runBatch(
                List.of(spawn("call-1", "调研 A 模块", "explore"),
                        spawn("call-2", "调研 B 模块", "explore")),
                model, CONV, (name, payload) -> events.add(Map.of("event", name, "payload", payload)));

        assertEquals(2, model.peak.get(), "两个房间必须同时在跑；峰值 1 说明又退回一前一后了");
        assertEquals(4, events.size(), "两张 start + 两张 done：实际 " + events);
        assertEquals("subagent_start", events.get(0).get("event"));
        assertEquals("subagent_start", events.get(1).get("event"), "第二张 start 必须在任何 done 之前");
        assertEquals("subagent_done", events.get(2).get("event"));
        assertEquals("subagent_done", events.get(3).get("event"));
    }

    /**
     * 记录"同时在跑几次模型调用"的假模型。
     *
     * <p>{@link FakeChatModel#call} 是 synchronized 的，用它测不出并发（峰值恒为 1），
     * 所以这里单独实现一个不做同步的版本：进入时 +1、退出时 -1，留下峰值。</p>
     */
    private static final class OverlapModel implements ChatModel {
        private final java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peak =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(300);   // 给第二个房间足够时间进入，避免偶发峰值 1
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return FakeChatModel.reply("调研结论");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("子代理内核只用 call()");
        }
    }

    @Test
    @DisplayName("批量里被闸门拒绝的那一个也有结果（不会让主 agent 收到空的工具结果）")
    void runBatchStillAnswersRejectedCalls() {
        props.setEnabled(false);
        FakeChatModel model = new FakeChatModel().thenReply("不该被调用", 2);

        Map<String, SubagentService.SpawnOutcome> out = service.runBatch(
                List.of(spawn("call-1", "调研", "explore")), model, CONV, null);

        assertFalse(out.get("call-1").ok());
        assertTrue(out.get("call-1").text().contains("REJECTED"));
        assertEquals(0, model.callCount());
    }

    // ==================== PARTIAL 工具结果模板 ====================

    /** 一趟必然以 PARTIAL 收场的房间：第 1 轮读一个文件、第 2 轮（收尾轮）给出带缺口的报告 */
    private static FakeChatModel partialModel(String path, String unresolved) {
        return new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"" + path + "\"}")))
                .thenReply("结论：" + path + " 只查了一半\n未覆盖：" + unresolved + "\n下一步建议：查 " + unresolved, 1);
    }

    private static SubagentSpec resumeSpec(String goal, String resumeId) {
        return new SubagentSpec(goal, null, null, null, SubagentSpec.Mode.EXPLORE, 0, CONV, null, resumeId);
    }

    @Test
    @DisplayName("PARTIAL 的工具结果模板（V1 默认）：结论 / 已读清单 / 未覆盖 / 可复制的续跑调用，一个都不能少")
    void partialToolResultHasAllSections() {
        props.setMaxRounds(2);
        FakeChatModel model = partialModel("A.java", "B 还没查");

        String text = service.spawnOnce("调研 A 与 B", "explore", model, CONV);

        assertTrue(text.contains("[子代理 explore 未完成]"), "实际：" + text);
        assertTrue(text.contains("原因：ROUNDS_EXHAUSTED"));
        assertTrue(text.contains("完整性：PARTIAL"), "轮数用尽但有结论 → PARTIAL，不是 NONE");
        assertTrue(text.contains("已走收尾轮"));
        assertTrue(text.contains("【已确认结论】"), "要有结论段：实际 " + text);
        assertTrue(text.contains("A.java 只查了一半"));
        assertTrue(text.contains("【已读清单】"), "要有内核生成的已读清单");
        assertTrue(text.contains("- A.java") && text.contains("readFile"),
                "台账要写清哪个文件、用哪个工具读的：实际 " + text);
        assertTrue(text.contains("【未覆盖】") && text.contains("B 还没查"), "要有缺口清单");
        assertTrue(text.contains("不要整份重读") || text.contains("整份重读台账文件是错的"),
                "要明确禁止整份重读：实际 " + text);
        assertTrue(text.contains("当完整结论对用户断言"), "要禁止把 PARTIAL 当成完整结论讲给用户");
        assertTrue(text.contains("spawnSubagent(resumeTaskId="),
                "V1 必须给出可复制的续跑调用：实际 " + text);
        assertTrue(text.contains("goal=\"调研 A 与 B\""), "goal 必须是原目标：实际 " + text);
        assertTrue(text.contains("(a)") && text.contains("(c)"), "结论够用 / 自己补读两条路还在");
        assertFalse(text.contains("(b)"), "V1 不再把续跑标成选项 (b)");
        assertEquals(2, model.callCount());
    }

    @Test
    @DisplayName("V0（partialResumeFirst=false）：PARTIAL 仍是旧的三选一，续跑是选项 (b)")
    void partialToolResultV0KeepsAbc() {
        props.setMaxRounds(2);
        props.setPartialResumeFirst(false);
        FakeChatModel model = partialModel("A.java", "B 还没查");

        String text = service.spawnOnce("调研 A 与 B", "explore", model, CONV);

        assertTrue(text.contains("完整性：PARTIAL"), "实际：" + text);
        assertTrue(text.contains("(a)") && text.contains("(b)") && text.contains("(c)"), "三选一都要在");
        assertTrue(text.contains("resumeTaskId=sa-"), "可续跑时 (b) 要给出续跑凭据");
        assertFalse(text.contains("spawnSubagent(resumeTaskId="),
                "V0 不给可复制调用（那是 V1 才加的）：实际 " + text);
    }

    @Test
    @DisplayName("不可续跑时模板如实写「不可续跑」，且只给 (a)(c) 两条路")
    void partialTemplateSaysNotResumableWhenNoHistory() {
        props.setMaxRounds(2);
        props.setMaxResumesPerGoal(0);   // 关掉续跑
        FakeChatModel model = partialModel("A.java", "B 还没查");

        String text = service.spawnOnce("调研 A 与 B", "explore", model, CONV);

        assertTrue(text.contains("完整性：PARTIAL"), "实际：" + text);
        assertTrue(text.contains("此类型不可续跑"), "要如实说清不可续跑：实际 " + text);
        assertTrue(text.contains("**不可用**"), "选项 (b) 要明确标成不可用");
        assertFalse(text.contains("resumeTaskId=sa-"), "没有保留历史就绝不能给出凭据（给了就是骗人）");
        assertEquals(0, sessions.sessionCount(CONV));
    }

    // ==================== 续跑 ====================

    @Test
    @DisplayName("续跑：历史前缀**原样不变**，只在末尾追加「接着补缺口」那一句")
    void resumeKeepsHistoryPrefixAndAppendsAtTheEnd() {
        props.setMaxRounds(2);
        FakeChatModel first = partialModel("A.java", "B 还没查");
        service.spawnOnce("调研 A 与 B", "explore", first, CONV);
        String resumeId = sessions.resumeIds(CONV).get(0);
        List<Message> history = sessions.find(CONV, resumeId).historySnapshot();
        assertEquals(4, history.size(),
                "历史 = 系统提示 + 任务 + 助手(tool_calls) + 工具结果（末尾的预算脚手架必须去掉）");

        FakeChatModel second = new FakeChatModel().thenReply("结论：B 也查清了\n未覆盖：无", 1);
        String text = service.spawnOnce(resumeSpec("调研 A 与 B", resumeId), second);

        List<Message> sent = second.prompts().get(0).getInstructions();
        assertTrue(sent.size() > history.size(), "续跑要在历史**后面**追加，不是重开");
        for (int i = 0; i < history.size(); i++) {
            assertSame(history.get(i), sent.get(i),
                    "第 " + i + " 条必须是原样的历史对象（前缀不变，prompt 缓存才可能命中）");
        }
        String appended = ((UserMessage) sent.get(history.size())).getText();
        assertTrue(appended.contains("续跑"), "实际：" + appended);
        assertTrue(appended.contains("B 还没查"), "要把它上次登记的缺口原样带过去");
        assertEquals(1, memory.resumesOf(CONV, "调研 a 与 b"));
        assertTrue(text.contains("这是续跑"), "工具结果要如实写这是续跑：实际 " + text);
        assertTrue(text.contains("续跑方式："), "要写清续跑方式（接历史 / 降级）");
    }

    @Test
    @DisplayName("凭据失效 → 明确返回失效，不降级成新房间")
    void staleCredentialDegradesToFreshRoomWithNote() {
        props.setMaxRounds(2);
        service.spawnOnce("调研 A 与 B", "explore", partialModel("A.java", "B 还没查"), CONV);
        String resumeId = sessions.resumeIds(CONV).get(0);
        sessions.releaseConversation(CONV);

        FakeChatModel second = new FakeChatModel().thenReply("结论：重新查了一遍", 1);
        String text = service.spawnOnce(resumeSpec("调研 A 与 B", resumeId), second);

        assertTrue(text.contains("失效"), "失效凭据必须明确返回：实际 " + text);
        assertTrue(text.contains("REJECTED") || text.contains("拒绝"), text);
        assertEquals(0, second.callCount(), "失效凭据不能再开一个房间");
    }

    @Test
    @DisplayName("同一目标最多续跑 max-resumes-per-goal 次：第一次放行，超了直接拒（不进房间、不花钱）")
    void resumeBeyondCapIsRefused() {
        props.setMaxRounds(2);
        props.setResumeRounds(1);   // 续跑预算 = 1 轮 + 1 收尾轮
        service.spawnOnce("调研 A 与 B", "explore", partialModel("A.java", "B 还没查"), CONV);
        String resumeId = sessions.resumeIds(CONV).get(0);

        FakeChatModel second = partialModel("B.java", "C 还没查");
        String t2 = service.spawnOnce(resumeSpec("调研 A 与 B", resumeId), second);
        assertTrue(t2.contains("这是续跑"), "第一次续跑应当放行：实际 " + t2);

        FakeChatModel third = new FakeChatModel().thenReply("不该被调用", 1);
        String t3 = service.spawnOnce(resumeSpec("调研 A 与 B", resumeId), third);

        assertTrue(t3.contains("原因：REJECTED"), "实际：" + t3);
        assertTrue(t3.contains("最多续跑 " + props.getMaxResumesPerGoal() + " 次"), "实际：" + t3);
        assertEquals(0, third.callCount(), "被闸门拦住时不该再进房间花钱");
    }

    // ==================== 宽泛目标闸 ====================

    @Test
    @DisplayName("宽泛目标闸：同一个宽泛目标**第一次拦**（要求补 scope），第二次放行（否则死循环）")
    void broadGoalGateBlocksFirstTimeOnly() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：扫完了", 1);
        String goal = "找出项目里所有与重试有关的逻辑";

        String blocked = service.spawnOnce(goal, "explore", model, CONV);

        assertTrue(blocked.contains("原因：REJECTED"), "实际：" + blocked);
        assertTrue(blocked.contains("目标太宽泛"), "实际：" + blocked);
        assertTrue(blocked.contains("scope"), "要告诉它怎么收窄");
        assertTrue(blocked.contains("再调一次"), "要告诉它第二次能过，否则它会以为自己被永久禁了");
        assertEquals(0, model.callCount(), "被拦时一次模型调用都不该发生");
        assertEquals(0, memory.attemptsOf(CONV, SubagentMemory.normalize(goal)),
                "被拦不算一次派出（不该吃掉预算）");

        String passed = service.spawnOnce(goal, "explore", model, CONV);

        assertFalse(passed.contains("目标太宽泛"),
                "第二次必须放行：主 agent 换个措辞还是会被拦，就变成死循环了。实际 " + passed);
        assertEquals(1, model.callCount());
    }

    @Test
    @DisplayName("填了 scope 的宽泛目标不被拦（已经收窄过，不需要再提醒）")
    void broadGoalWithScopeIsNotBlocked() {
        FakeChatModel model = new FakeChatModel().thenReply("结论：扫完了", 1);
        SubagentSpec spec = new SubagentSpec("找出所有与重试有关的逻辑", "重试逻辑集中在哪里",
                "src/main/java/com/example/jingjingai2", "能列出文件与行号", SubagentSpec.Mode.EXPLORE,
                0, CONV, null, null);

        String text = service.spawnOnce(spec, model);

        assertFalse(text.contains("目标太宽泛"), "实际：" + text);
        assertEquals(1, model.callCount());
    }

    // ==================== 并发上限 ====================

    @Test
    @DisplayName("并发已满 → 返回**错误 tool result**（不是排队等着），主 agent 读得到、能自己决策")
    void concurrencyCapReturnsErrorResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeChatModel model = new FakeChatModel().thenReply("不该被调用", 1);
        try {
            for (int i = 0; i < props.getMaxConcurrent(); i++) {
                String g = "占位 " + i;
                assertNotNull(pool.trySubmit(new SubagentSpec(g, SubagentSpec.Mode.EXPLORE, 0, CONV), () -> {
                    release.await(5, TimeUnit.SECONDS);
                    return SubagentResult.success(g, SubagentSpec.Mode.EXPLORE, "占位", 1, 1, 0, Set.of());
                }), "上限之内必须被接受");
            }

            String text = service.spawnOnce("再多派一个", "explore", model, CONV);

            assertTrue(text.contains("原因：REJECTED"), "实际：" + text);
            assertTrue(text.contains("已达上限"), "要写清是并发满，而不是含糊的失败：实际 " + text);
            assertTrue(text.contains("自己做"), "要告诉主 agent 下一步怎么办");
            assertEquals(0, model.callCount());
            // 一次"过了闸门但没进房间"的尝试**照样吃一次重派预算**：
            // 并发满了就无限试下去的话，主 agent 一轮里能刷出几十个 spawn，
            // 池子永远满、日志永远响 —— 这条闸门比"省一次预算"重要。
            assertEquals(0, memory.attemptsOf(CONV, "再多派一个"),
                    "没拿到执行资格不计重派次数，避免锁拒绝白扣额度");
        } finally {
            release.countDown();
        }
    }

    // ==================== 便条生命周期 ====================

    @Test
    @DisplayName("PARTIAL 便条留着；同一目标后来完整了就清掉那张过期便条")
    void noteLifetimeFollowsCompleteness() {
        props.setMaxRounds(2);
        service.spawnOnce("目标 A", "explore", partialModel("A.java", "还有一半"), CONV);
        assertEquals(1, memory.partialNoteCount(CONV), "PARTIAL 必须留下便条");

        service.spawnOnce("目标 A", "explore",
                new FakeChatModel().thenReply("结论：这次查清了", 1), CONV);

        assertEquals(0, memory.noteCount(CONV),
                "同一目标这次完整了 → 过期的 PARTIAL 便条必须清掉（否则下次注入的是假消息）");

        service.spawnOnce("目标 B", "explore", partialModel("B.java", "还差一点"), CONV);
        service.releaseOnConclusion(CONV);

        assertEquals(1, memory.noteCount(CONV), "结论出来时 PARTIAL 的便条要留着（可能马上续跑）");
        // 房间历史同理：目标 A **第一趟**（PARTIAL）的房间留着，目标 B（PARTIAL）也留着。
        // A 的第二趟是 COMPLETE —— 它压根不开房间（只有 PARTIAL 才值得留历史给人续跑），
        // 所以这里不是"1 个 A + 1 个 B 被合并成一个"，而是两个各自独立的 PARTIAL 房间。
        assertEquals(2, sessions.sessionCount(CONV), "只释放已完整的；COMPLETE 的那趟不留房间");
    }

    // ==================== 纯函数 ====================

    @Test
    @DisplayName("工具名判定：兼容下划线写法，别的工具不会被误判")
    void spawnToolDetection() {
        assertTrue(SubagentService.isSpawnTool("spawnSubagent"));
        assertTrue(SubagentService.isSpawnTool("spawn_subagent"));
        assertTrue(SubagentService.isSpawnTool("SPAWNSUBAGENT"));
        assertFalse(SubagentService.isSpawnTool("readFile"));
        assertFalse(SubagentService.isSpawnTool(null));
    }

    @Test
    @DisplayName("isImplementSpawn：只有 implement 那次派发才算「可能改了文件」（主循环据此跑编译校验）")
    void implementSpawnDetection() {
        assertTrue(service.isImplementSpawn(spawn("c1", "改配置", "implement")));
        assertTrue(service.isImplementSpawn(spawn("c2", "改配置", "写入")));
        assertFalse(service.isImplementSpawn(spawn("c3", "看看", "explore")));
        assertFalse(service.isImplementSpawn(spawn("c4", "看看", null)));
        assertFalse(service.isImplementSpawn(
                new AssistantMessage.ToolCall("c5", "function", "readFile", "{}")));
    }

    // ==================== Phase 3：写锁释放 / filesModified 跳过 ====================

    private void assertLockReleasedAfter(String goal, String mode, FakeChatModel model) {
        service.spawnOnce(goal, mode, model, CONV);
        assertFalse(pool.isImplementRunning(), "跑完必须释放写锁，否则下一趟 implement 会被永久拒绝");
    }

    @Test
    @DisplayName("写锁：SUCCESS / PARTIAL / TOOL_FAILURES / 异常 / 取消 之后都释放，第二个 implement 才能再进")
    void implementLockReleasedOnEveryExitPath() {
        assertLockReleasedAfter("改成功", "implement",
                new FakeChatModel().thenReply("改完了", 1));

        props.setMaxRounds(2);
        assertLockReleasedAfter("改一半", "implement",
                new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"A.java\"}"))));
        props.setMaxRounds(8);

        assertLockReleasedAfter("改失败", "implement",
                new FakeChatModel().then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "executeCommand", "{\"command\":\"x\"}"))));

        FakeChatModel boom = new FakeChatModel().then(p -> { throw new RuntimeException("boom"); });
        String crashed = service.spawnOnce("改炸了", "implement", boom, CONV);
        assertTrue(crashed.contains("TOOL_FAILURES") || crashed.contains("异常") || crashed.contains("未完成"),
                "房间异常应收成失败结果：实际 " + crashed);
        assertFalse(pool.isImplementRunning());

        cancellation.arm(CONV);
        cancellation.cancel(CONV);
        String cancelled = service.spawnOnce("改取消", "implement",
                new FakeChatModel().thenReply("不该被调用", 1), CONV);
        assertTrue(cancelled.contains("CANCELLED"), "实际：" + cancelled);
        assertFalse(pool.isImplementRunning());
        cancellation.clear(CONV);

        FakeChatModel next = new FakeChatModel().thenReply("又改完了", 1);
        String ok = service.spawnOnce("再改一处", "implement", next, CONV);
        assertFalse(ok.contains("写操作不允许并行"), "锁已释放，第二个 implement 不该被拒：实际 " + ok);
        assertFalse(pool.isImplementRunning());
    }

    @Test
    @DisplayName("并发已满时 implement 被拒，刚拿到的写锁必须还回去（否则池子焊死）")
    void implementLockNotLeakedWhenPermitsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < props.getMaxConcurrent(); i++) {
                String g = "占位锁 " + i;
                assertNotNull(pool.trySubmit(new SubagentSpec(g, SubagentSpec.Mode.EXPLORE, 0, CONV), () -> {
                    release.await(5, TimeUnit.SECONDS);
                    return SubagentResult.success(g, SubagentSpec.Mode.EXPLORE, "占位", 1, 1, 0, Set.of());
                }));
            }
            String text = service.spawnOnce("满了还要写", "implement",
                    new FakeChatModel().thenReply("不该被调用", 1), CONV);
            assertTrue(text.contains("原因：REJECTED"), "实际：" + text);
            assertFalse(pool.isImplementRunning(), "trySubmit 返回 null 时必须释放刚拿到的写锁");
            assertTrue(pool.tryAcquireImplement(), "锁已还，新的 implement 应能再拿");
            pool.releaseImplement();
        } finally {
            release.countDown();
        }
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

    @Test
    @DisplayName("PARTIAL 续跑漏写 mode：沿用原 implement，房间仍是可写工具集，不是 EXPLORE 回落")
    void resumeOmittingModeKeepsOriginalImplementWhitelist() {
        props.setMaxRounds(2);
        String goal = "改配置并验证";
        FakeChatModel first = new FakeChatModel()
                .then(p -> FakeChatModel.toolCalls(
                        FakeChatModel.call("c1", "readFile", "{\"path\":\"A.java\"}")))
                .thenReply("结论：改了一半\n未覆盖：还要 javac\n下一步建议：编译", 1);

        String firstText = service.spawnOnce(goal, "implement", first, CONV);
        assertTrue(firstText.contains("完整性：PARTIAL"), firstText);
        String resumeId = sessions.resumeIds(CONV).get(0);
        assertEquals(SubagentSpec.Mode.IMPLEMENT, sessions.find(CONV, resumeId).mode());
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse(null),
                "新派出漏写 mode 仍回落 EXPLORE；续跑不得走这条回落");

        FakeChatModel second = new FakeChatModel()
                .then(p -> {
                    Set<String> names = toolNamesOffered(p);
                    assertTrue(names.contains("executeCommand"), "续跑必须沿用 implement 白名单: " + names);
                    assertTrue(names.contains("writeFile"), "续跑必须能写: " + names);
                    return FakeChatModel.toolCalls(
                            FakeChatModel.call("c2", "readFile", "{\"path\":\"A.java\"}"));
                })
                .thenReply("结论：还差验证\n未覆盖：javac\n下一步建议：编译", 1);
        props.setResumeRounds(1);
        // resumeSpec 故意写成 EXPLORE：模拟父代理只抄 resumeTaskId+goal、漏写 mode
        String text = service.spawnOnce(resumeSpec(goal, resumeId), second);
        assertTrue(text.contains("[子代理 implement"),
                "漏写 mode 也必须沿用 implement，不能掉成 explore: " + text);
        assertTrue(text.contains("这是续跑"), text);
        assertFalse(text.contains(HandoffResult.TOOLSET_MISMATCH),
                "mode 已纠正时安全网不该误报: " + text);

        AssistantMessage.ToolCall omitted = new AssistantMessage.ToolCall(
                "c", "function", "spawnSubagent",
                "{\"goal\":\"" + goal + "\",\"resumeTaskId\":\"" + resumeId + "\"}");
        assertTrue(service.isImplementSpawn(omitted, CONV),
                "父循环漏写 mode 时 isImplementSpawn 应从原房间记录恢复");
    }

    @Test
    @DisplayName("房间首轮 toolCalls=0：自动重派一次，成功则不落到父代理接手")
    void firstRoundEmptyCrashRedispatchesOnceAndSucceeds() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> { throw new RuntimeException("Connection reset"); })
                .thenReply("结论：隔离成功", 1);

        String text = service.spawnOnce("调研 plan 包", "explore", model, CONV);

        assertTrue(text.contains("完整性：COMPLETE"), text);
        assertTrue(text.contains("原因：SUCCESS"), text);
        assertFalse(text.contains("自己做"), "重派成功不该让父代理接手: " + text);
        assertEquals(2, model.callCount(), "第一次抛、第二次成功");
        assertEquals(1, telemetry.childRuns(CONV), "空失败那次不记 childRuns");
        assertEquals(1, memory.attemptsOf(CONV,
                new SubagentSpec("调研 plan 包", SubagentSpec.Mode.EXPLORE, 0, CONV).goalKey()),
                "内部重派不占同 goal 重派上限");
    }

    @Test
    @DisplayName("自动重派后仍 toolCalls=0：才落到父代理接手兜底")
    void firstRoundEmptyCrashRedispatchStillFailsFallsToParent() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> { throw new RuntimeException("reset-1"); })
                .then(p -> { throw new RuntimeException("reset-2"); });

        String text = service.spawnOnce("调研 plan 包", "explore", model, CONV);

        assertTrue(text.contains("完整性：NONE"), text);
        assertTrue(text.contains("原因：TOOL_FAILURES"), text);
        assertTrue(text.contains("自己做"), "第二次仍失败才落到父代理接手: " + text);
        assertEquals(2, model.callCount(), "只自动重派一次");
        assertEquals(1, telemetry.childRuns(CONV), "只记最终那次空失败");
    }

    @Test
    @DisplayName("内部重派不消耗同 goal 的 3 次派出预算")
    void autoRedispatchDoesNotConsumeRetryLimit() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> { throw new RuntimeException("Connection reset"); })
                .thenReply("结论", 20);

        for (int i = 1; i <= 3; i++) {
            String text = service.spawnOnce("同一个目标", "explore", model, CONV);
            assertFalse(text.contains("RETRY_LIMIT"), "第 " + i + " 次（含内部重派）不该被拦：" + text);
            assertTrue(text.contains("原因：SUCCESS"), text);
        }
        int callsBefore = model.callCount();
        String blocked = service.spawnOnce("同一个目标", "explore", model, CONV);
        assertTrue(blocked.contains("原因：RETRY_LIMIT"), blocked);
        assertEquals(callsBefore, model.callCount(), "被闸门拦住时不该再进房间");
    }

    @Test
    @DisplayName("runBatch 路径同样对首轮空失败自动重派")
    void runBatchVirginCrashRedispatches() {
        FakeChatModel model = new FakeChatModel()
                .then(p -> { throw new RuntimeException("Connection reset"); })
                .thenReply("结论：批量重派成功", 1);

        Map<String, SubagentService.SpawnOutcome> out = service.runBatch(
                List.of(spawn("call-1", "调研 A", "explore")),
                model, CONV, null);

        SubagentService.SpawnOutcome o = out.get("call-1");
        assertNotNull(o);
        assertTrue(o.ok(), o.text());
        assertTrue(o.text().contains("完整性：COMPLETE"), o.text());
        assertEquals(1, telemetry.childRuns(CONV));
    }

    @Test
    @DisplayName("房间内 I/O 重试成功：同一房间消化，不触发自动重派")
    void ioRetryInsideRoomDoesNotCountAsRedispatch() {
        RetryingChatModel.sleeper = ms -> {};
        FakeChatModel model = new FakeChatModel()
                .then(p -> {
                    throw new org.springframework.web.client.ResourceAccessException(
                            "I/O error", new java.net.SocketException("Connection reset"));
                })
                .thenReply("结论：房间内重试成功", 1);

        String text = service.spawnOnce("调研 plan 包", "explore", model, CONV);

        assertTrue(text.contains("原因：SUCCESS"), text);
        assertEquals(2, model.callCount());
        assertEquals(1, telemetry.childRuns(CONV));
        assertEquals(1, memory.attemptsOf(CONV,
                new SubagentSpec("调研 plan 包", SubagentSpec.Mode.EXPLORE, 0, CONV).goalKey()));
    }
}
