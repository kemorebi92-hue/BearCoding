package com.example.jingjingai2.agent;

import com.example.jingjingai2.config.AgentPrompts;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolCallContext.ApprovalLink;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.utils.FileTool;
import com.example.jingjingai2.utils.TerminalTool;
import com.example.jingjingai2.utils.ToolGuard;
import com.example.jingjingai2.utils.WebSearchTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子代理房间：**干净上下文 + 更窄的工具白名单 + 自己的一轮内核**。
 *
 * 三条硬约束（都在代码里，不靠提示词自觉）：
 *  1. **干净房间**：messages 由本类自建 —— 没有聊天历史、没有计划清单、没有 nudge 注入。
 *     房间跑完即丢，工具原文**不写** conversation_message。唯一例外是 **resume**：
 *     上一个房间的历史由 {@link SubagentSessions} 保管，续跑时原样接上（前缀不变）。
 *  2. **白名单**：explore 只有读类 + 联网；implement 才多出写类 + executeCommand。
 *     两种模式都**没有** createPlan / updatePlanStep / spawnSubagent（计划是主 agent 的，且禁止套娃）。
 *     构造清单时就把它们排除掉，执行时再由 {@link ToolGuard} 兜一道 —— 和主循环同一把闸。
 *  3. **只调内核**：一轮 = {@link ToolLoopStep#run}。**禁止**在这里再抄一份 for-loop 调模型/执行工具，
 *     也**禁止**调 ChatController 的 doStream（那会把 SSE 和审批一起拖进来）。
 *
 * 第二轮改造（本文件的主要变化）—— 修的是"子代理轮数用尽 → 主 agent 自己重做一遍"这个 ROI 漏洞：
 *  - **每轮末尾**追加一条动态预算消息（`[预算] 已用 k/N，剩余 r 轮`），剩 3 轮起加软提醒；
 *    追加在**末尾**、且下一轮先摘掉上一条 —— 不碰 system prompt、不插历史中间，前缀保持稳定（利于 prompt 缓存）。
 *  - **收尾轮**（第 N 轮）：**不提供任何工具**，强制模型输出带标记的最终报告。
 *    即使这样它还是喊了工具调用，也一律**忽略**（executor 直接 abort），由内核按台账生成保底摘要，状态 PARTIAL。
 *  - **台账由内核生成**（{@link ReadLedgerEntry}）：读了哪个文件、哪几行、返回多大，全部来自真实工具调用记录，
 *    **不让模型自己填** —— 它正是"没来得及汇报"才被截断的。
 *
 * 结束条件（谁先到谁停），一一对应 {@link SubagentResult.FailureReason}：
 *  - 模型不再调工具 → SUCCESS
 *  - 轮数用尽（且收尾轮也拿不到结论） → ROUNDS_EXHAUSTED（truncated=true）
 *  - 同一个工具连续失败 3 次 → TOOL_FAILURES
 *  - 取消登记表命中 → CANCELLED
 * （RETRY_LIMIT / REJECTED 由 {@link SubagentService} 在**进房间之前**判定）
 */
@Component
public class SubagentRunner {

    /** explore 能用的工具：只读 + 联网 */
    public static final Set<String> EXPLORE_TOOLS = Set.of(
            "readFile", "listDirectory", "globFile", "grepFile", "webSearch");

    /** implement 在 explore 基础上多出：写类 + 命令执行 */
    public static final Set<String> IMPLEMENT_TOOLS = Set.of(
            "readFile", "listDirectory", "globFile", "grepFile", "webSearch",
            "writeFile", "createFile", "editFile", "appendFile", "deletePath",
            "createDirectory", "movePath", "copyPath", "executeCommand");

    /** 同一个工具连续失败多少次就停 */
    private static final int MAX_CONSECUTIVE_TOOL_FAILURES = 3;

    /**
     * 预算消息的前缀。它只是**每轮的脚手架**（下一轮先摘上一条），所以：
     *  - 不该进入保留给续跑的历史（否则续跑时模型会看到"这是最后一轮，不再提供工具"，
     *    紧接着又收到"请继续补缺口"，自相矛盾）；
     *  - 也不该被当成对话内容统计。
     */
    static final String BUDGET_PREFIX = "[预算]";

    /** 估算 token 用的字符/token 比（与 ChatController 保持一致，只做横向对照） */
    private static final double CHARS_PER_TOKEN = 1.5;

    /** 判定"这次工具调用算失败"的前缀（与各工具自己的返回文案对齐） */
    private static final String[] FAILURE_PREFIXES = {
            "未知的工具", "工具调用失败", "工具执行异常", "工具 ", "读取失败", "写入失败",
            "创建失败", "删除失败", "移动失败", "复制失败", "追加失败", "修改失败", "执行失败",
            "新建失败", "创建目录失败"};

    private final SubagentProperties props;
    private final AgentCancellationRegistry cancellationRegistry;
    /** 父会话的污点表。单测构造器不注入，此时子房间看不到污点。 */
    private McpClientManager mcpClientManager;
    /** 全量工具清单（fileTool + terminalTool + webSearchTool）；子房间只会看到它按白名单过滤后的子集 */
    private final ToolCallback[] allCallbacks;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 两种模式各过滤一次就够（工具清单是固定的） */
    private volatile ToolCallback[] exploreCallbacks;
    private volatile ToolCallback[] implementCallbacks;

    /**
     * Spring 用的构造器。
     *
     * **必须**标 @Autowired：本类还有第二个（给单测注入工具清单的）构造器，
     * 多个构造器且没有一个被标注时，Spring 会退回去找无参构造器然后启动失败。
     * 这个错单测发现不了 —— 单测只 new、不装配，只有真启动容器才暴露。
     */
    @Autowired
    public SubagentRunner(SubagentProperties props,
                          AgentCancellationRegistry cancellationRegistry,
                          FileTool fileTool,
                          TerminalTool terminalTool,
                          WebSearchTool webSearchTool,
                          McpClientManager mcpClientManager) {
        this(props, cancellationRegistry, buildAll(fileTool, terminalTool, webSearchTool));
        this.mcpClientManager = mcpClientManager;
    }

    /**
     * 直接注入全量工具清单（单测与将来扩展用）。
     * 这条缝让 {@link #filter} 这个纯函数可以被单测钉死 —— 尤其是"房间清单里
     * **永远不许**出现 spawnSubagent / createPlan / updatePlanStep"这条硬约束。
     */
    SubagentRunner(SubagentProperties props,
                   AgentCancellationRegistry cancellationRegistry,
                   ToolCallback[] allCallbacks) {
        this.props = props;
        this.cancellationRegistry = cancellationRegistry;
        this.allCallbacks = allCallbacks == null ? new ToolCallback[0] : allCallbacks;
    }

    /** 主循环之外的三个工具本体 → 全量 callbacks；没有搜索 Key 时不把 webSearch 放进来。 */
    private static ToolCallback[] buildAll(FileTool fileTool, TerminalTool terminalTool, WebSearchTool webSearchTool) {
        List<Object> tools = new ArrayList<>();
        tools.add(fileTool);
        tools.add(terminalTool);
        if (webSearchTool != null && webSearchTool.isEnabled()) tools.add(webSearchTool);
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build()
                .getToolCallbacks();
    }

    /**
     * 房间的对外把手。调度层拿它做三件事：保留历史（resume）、记成本、给主 agent 打冗余读标记。
     * 单测拿它验"预算消息在第几轮、是不是在末尾、最后一轮有没有工具"。
     */
    public static final class Room {
        /** 续跑时带进来的历史（空 = 全新房间） */
        public final List<Message> seed;
        /** 续跑时要它补的问题（来自上次报告的 unresolved） */
        public final List<String> resumeUnresolved;
        public final boolean resumed;

        // ===== 内核填 =====
        /** 本趟真实发出去的完整消息列表（续跑时含 seed） */
        public List<Message> messages = new ArrayList<>();
        /** 内核生成的已读台账（续跑时会先用上次的台账打底） */
        public final ReadLedgerEntry.Ledger ledger = new ReadLedgerEntry.Ledger();
        /** 本趟真实用量（成本核算） */
        public final SubagentUsage usage = new SubagentUsage();
        /** 每一轮发出去的最后一条消息（= 预算消息，用来断言"追加在末尾"） */
        public final List<String> budgetTexts = new ArrayList<>();
        /** 每一轮实际提供的工具数（最后一轮必须是 0） */
        public final List<Integer> toolsPerRound = new ArrayList<>();
        /** 是否走了收尾轮 */
        public boolean wrapUpUsed;
        /** 收尾轮也没给出文字 → 内核保底摘要（如实标注，不假装是模型结论） */
        public boolean fallbackSummary;
        /** 实际用了几轮 */
        public int roundsUsed;
        /** 固定开销估算（system prompt + 工具定义） */
        public int fixedOverheadTokens;
        /** 本趟实际发出的 executeCommand（核对 + 落盘共用；没有就空） */
        public final List<HandoffResult.ExecutedCommand> executedCommands = new ArrayList<>();
        /** 续跑前原始任务的 mode；null = 未知或非续跑。用来判断工具集是否被错成只读。 */
        public SubagentSpec.Mode originalMode;
        /** 续跑时因白名单拒掉了 implement 专属工具（写 / executeCommand） */
        public boolean writeToolRefused;

        public Room() { this(null, null, null, false); }

        /** 全新房间 */
        public static Room fresh() { return new Room(); }

        /** 续跑：接上上次的历史与台账，并带上要补的问题 */
        public static Room resume(List<Message> history, List<String> unresolved,
                                  List<ReadLedgerEntry> previousLedger) {
            return resume(history, unresolved, previousLedger, null);
        }

        /** 续跑，并记下原房间 mode（漏写 / 写错 mode 时用来触发工具集不符安全网） */
        public static Room resume(List<Message> history, List<String> unresolved,
                                  List<ReadLedgerEntry> previousLedger,
                                  SubagentSpec.Mode originalMode) {
            Room r = new Room(history, unresolved, previousLedger, true);
            r.originalMode = originalMode;
            return r;
        }

        private Room(List<Message> seed, List<String> unresolved,
                     List<ReadLedgerEntry> previousLedger, boolean resumed) {
            this.seed = seed == null ? List.of() : seed;
            this.resumeUnresolved = unresolved == null ? List.of() : unresolved;
            this.resumed = resumed;
            if (previousLedger != null) {
                for (ReadLedgerEntry e : previousLedger) ledger.add(e);
            }
        }
    }

    /** 本轮房间里的记账（避免到处传一堆局部变量） */
    private static final class Acc {
        int toolCalls;
        int failStreak;
        String lastFailedTool;
        String lastError = "";
    }

    /** 兼容旧调用：自建房间、不保留历史 */
    public SubagentResult run(SubagentSpec spec, ChatModel model, LastRunNote previous) {
        return run(spec, model, previous, new Room());
    }

    /**
     * 跑一个子代理房间。
     *
     * @param spec     目标 + 模式 + 轮数预算
     * @param model    主会话本次选定的模型（原样传下去，第一期不做跨模型调度）
     * @param previous 同一 goal 的上次笔记（可为 null）；不为 null 时注入新房间
     * @param room     房间把手（续跑时 seed 非空；内核把消息列表/台账/用量填回去）
     */
    public SubagentResult run(SubagentSpec spec, ChatModel model, LastRunNote previous, Room room) {
        // 子代理线程上没有父循环的 ThreadLocal。这里打开的上下文没有 SSE，
        // ASK 会直接变成「请告知主代理」，不会在池线程上等人。
        ApprovalLink link = ApprovalLink.attached();
        Long userId = link == null ? null : link.userId;
        String conversationId = link != null && link.conversationId != null
                ? link.conversationId : spec.conversationId();
        ToolCallContext.Events events = link == null ? null : link.events;
        String label = link == null ? null : link.subagentLabel;
        try (ToolCallContext.Scope ignored = ToolCallContext.open(new ToolCallContext(
                userId, conversationId, false, true, events,
                () -> mcpClientManager != null && mcpClientManager.isConversationTainted(conversationId),
                label))) {
            return runInContext(spec, model, previous, room);
        }
    }

    private SubagentResult runInContext(SubagentSpec spec, ChatModel model, LastRunNote previous, Room room) {
        // 只包子房间的同步 call()：瞬时 Connection reset 在房间内重试，不改父代理 SSE 路径。
        model = RetryingChatModel.wrap(model);
        int maxRounds = props.effectiveMaxRounds(spec.maxRounds());
        ToolCallback[] callbacks = callbacksFor(spec.mode());
        Set<String> allowed = namesOf(callbacks);
        Acc acc = new Acc();
        String roomPrompt = roomPrompt(spec);
        room.fixedOverheadTokens = estimateFixedOverhead(callbacks, roomPrompt);

        // ===== 干净房间：只有"短系统提示 + 这一件事"（可选：上次便条 / 续跑历史）=====
        List<Message> messages = new ArrayList<>();
        if (!room.seed.isEmpty()) {
            // 续跑：前缀**原封不动**，只在末尾追加一句"接着上次补缺口"
            messages.addAll(room.seed);
            messages.add(new UserMessage(resumeText(spec, room.resumeUnresolved)));
        } else {
            messages.add(new SystemMessage(roomPrompt));
            messages.add(new UserMessage(taskText(spec, previous)));
        }
        room.messages = messages;

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        // 收尾轮：**不带任何工具**。OpenAI 兼容客户端在 tools 为空时不发 tools 字段。
        ToolCallingChatOptions noTools = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        int budgetIndex = -1;
        String lastText = null;
        for (int i = 0; i < maxRounds; i++) {
            if (isCancelled(spec)) {
                return cancelled(spec, room, acc, i, maxRounds);
            }
            int used = i + 1;
            room.roundsUsed = used;
            boolean wrapUpRound = (i == maxRounds - 1);

            // 上一轮的预算消息先摘掉，再往**末尾**追加这一轮的 —— 保证它永远是"最后一条"，
            // 且历史里不会积一堆过期的预算数字（那会让模型按错误的剩余轮数做计划）。
            if (budgetIndex >= 0 && budgetIndex < messages.size()) {
                messages.remove(budgetIndex);
            }
            String budget = wrapUpRound ? wrapUpText(spec, maxRounds) : budgetText(spec, used, maxRounds);
            messages.add(new UserMessage(budget));
            budgetIndex = messages.size() - 1;
            room.budgetTexts.add(budget);
            room.toolsPerRound.add(wrapUpRound ? 0 : callbacks.length);

            ToolLoopStep.Result step = ToolLoopStep.run(
                    messages,
                    new Prompt(messages, wrapUpRound ? noTools : options),
                    model,
                    () -> isCancelled(spec),
                    wrapUpRound
                            // 收尾轮的工具调用一律**忽略**：abort 让内核在真正执行前停手
                            ? call -> ToolLoopStep.ToolExec.abort("收尾轮不再执行工具")
                            : call -> executeTool(callbacks, allowed, call, acc, room),
                    props.getMaxToolResultChars());
            room.usage.accept(step.response);

            if (step.outcome == ToolLoopStep.Outcome.CANCELLED) {
                return cancelled(spec, room, acc, used, maxRounds);
            }

            if (wrapUpRound) {
                room.wrapUpUsed = true;
                return finishWrapUp(spec, room, acc, step, used, maxRounds);
            }

            if (step.outcome == ToolLoopStep.Outcome.NATURAL_STOP) {
                String text = (step.assistant == null) ? null : step.assistant.getText();
                return finishNaturally(spec, room, acc, lastTextOf(text), used, maxRounds);
            }
            if (step.outcome == ToolLoopStep.Outcome.ABORTED) {
                // 子代理房间里没有"等人审批"这条路（白名单里 executeCommand 只在 implement 模式，
                // 而审批是主循环的协议层职责）。真出现就如实报停，不要伪装成"轮数用尽"。
                return withReport(spec, SubagentResult.toolFailures(spec.goal(), spec.mode(),
                        fallbackSummary(lastText, room, acc), used, maxRounds,
                        acc.toolCalls, pathsOf(room), "工具执行被中止: " + step.abortReason), room, acc);
            }

            // 这一轮模型说了话又继续调工具 —— 把这句留着，它是"轮数用尽 / 工具连失败"时
            // 最接近结论的东西（否则摘要就只剩"读了哪些文件"）
            if (step.assistant != null && step.assistant.getText() != null
                    && !step.assistant.getText().isBlank()) {
                lastText = step.assistant.getText();
            }

            // 同一个工具连续失败 3 次 → 停（再跑只是重复撞同一堵墙）
            if (acc.failStreak >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                SubagentLog.line("工具连续失败 " + acc.failStreak + " 次（"
                        + acc.lastFailedTool + "）→ 提前结束房间");
                return withReport(spec, SubagentResult.toolFailures(spec.goal(), spec.mode(),
                        fallbackSummary(lastText, room, acc), used, maxRounds,
                        acc.toolCalls, pathsOf(room), acc.lastError), room, acc);
            }
        }

        // 理论上到不了这里（最后一轮一定走 wrapUpRound 分支）—— 保底不留空摘要
        return withReport(spec, SubagentResult.roundsExhausted(spec.goal(), spec.mode(),
                fallbackSummary(lastText, room, acc), room.roundsUsed, maxRounds,
                acc.toolCalls, pathsOf(room),
                acc.lastError.isBlank() ? "轮数用尽时仍在调工具" : acc.lastError), room, acc);
    }

    // ==================== 收尾与结论 ====================

    /** 模型自然收尾（没调工具）：说了话 → COMPLETE；一个字都没说 → 保底摘要 + PARTIAL（不装完整） */
    private SubagentResult finishNaturally(SubagentSpec spec, Room room, Acc acc,
                                           String text, int used, int maxRounds) {
        if (text == null || text.isBlank()) {
            room.fallbackSummary = true;
            String fallback = fallbackSummary(null, room, acc);
            SubagentResult r = SubagentResult.success(spec.goal(), spec.mode(), fallback,
                    used, maxRounds, acc.toolCalls, pathsOf(room));
            r = r.withReport(SubagentReport.fallback(fallback, room.ledger.entries(),
                    props.getMaxAnswerChars()));
            return withReport(spec, r, room, acc);
        }
        SubagentResult r = SubagentResult.success(spec.goal(), spec.mode(), text,
                used, maxRounds, acc.toolCalls, pathsOf(room));
        r = r.withReport(SubagentReport.of(text, SubagentReport.Status.COMPLETE,
                room.ledger.entries(), props.getMaxAnswerChars()));
        return withReport(spec, r, room, acc);
    }

    /**
     * 收尾轮：**不管它返回什么，都不再执行工具**。
     *
     * 完整性规则（与停因正交；收尾轮的停因永远是 {@code ROUNDS_EXHAUSTED}）：
     *  <ul>
     *    <li>正文含「未覆盖：」且解析出的 unresolved <strong>为空</strong> → COMPLETE
     *        （它明确写了「未覆盖：无」之类，才敢当完整结论）；</li>
     *    <li>否则一律 PARTIAL（含：有缺口、没写未覆盖标记、还在喊工具、走内核保底摘要）。</li>
     *  </ul>
     * 也就是：只要因轮数用尽进入收尾轮，除非 unresolved 为空，否则一律 PARTIAL。
     */
    private SubagentResult finishWrapUp(SubagentSpec spec, Room room, Acc acc,
                                        ToolLoopStep.Result step, int used, int maxRounds) {
        // 收尾轮若仍带 tool_calls，那条助手消息会因为没人回应而不能留在历史里（续跑时会连不上）
        dropDanglingToolCalls(room.messages);

        String text = (step.assistant == null) ? null : step.assistant.getText();
        if (step.outcome == ToolLoopStep.Outcome.ABORTED || text == null || text.isBlank()) {
            room.fallbackSummary = true;
            SubagentLog.line("收尾轮未产出报告（outcome=" + step.outcome + "）→ 内核按台账生成保底摘要");
            String fallback = fallbackSummary(null, room, acc);
            SubagentResult r = SubagentResult.roundsExhausted(spec.goal(), spec.mode(), fallback,
                    used, maxRounds, acc.toolCalls, pathsOf(room),
                    "收尾轮未产出报告，已按台账生成保底摘要");
            r = r.withReport(SubagentReport.fallback(fallback, room.ledger.entries(),
                    props.getMaxAnswerChars()));
            return withReport(spec, r, room, acc);
        }

        // 它明确说了"未覆盖：无" → 敢当 COMPLETE；否则一律 PARTIAL（预算用尽，可能不完整）
        boolean claimsNothingMissing = text.contains(SubagentReport.MARK_UNRESOLVED)
                && SubagentReport.of(text, SubagentReport.Status.PARTIAL, List.of(),
                        props.getMaxAnswerChars()).unresolved().isEmpty();
        SubagentReport.Status status = claimsNothingMissing
                ? SubagentReport.Status.COMPLETE : SubagentReport.Status.PARTIAL;
        SubagentReport report = SubagentReport.of(text, status, room.ledger.entries(),
                props.getMaxAnswerChars());

        SubagentResult r = SubagentResult.roundsExhausted(spec.goal(), spec.mode(), report.answer(),
                used, maxRounds, acc.toolCalls, pathsOf(room),
                "轮数用尽（收尾轮已产出报告）");
        r = r.withReport(report);
        return withReport(spec, r, room, acc);
    }

    private SubagentResult cancelled(SubagentSpec spec, Room room, Acc acc, int used, int maxRounds) {
        SubagentResult r = SubagentResult.cancelled(spec.goal(), spec.mode(),
                used, maxRounds, acc.toolCalls, pathsOf(room));
        return withReport(spec, r, room, acc);
    }

    /** 统一在出口处挂上：用量、固定开销、收尾轮/续跑标记；错 mode 续跑不得当成交接 */
    private SubagentResult withReport(SubagentSpec spec, SubagentResult r, Room room, Acc acc) {
        r = r.withUsage(room.usage)
                .withRunInfo(room.wrapUpUsed, room.resumed, room.fixedOverheadTokens)
                .withExecutedCommands(room.executedCommands);
        return applyToolsetMismatch(spec, room, r);
    }

    /**
     * 续跑房间的工具集必须跟原始任务一致。漏写 mode 曾把 implement 续成 explore：
     * executeCommand 被拒，模型仍可能交一份 COMPLETE 报告，父代理据此 updatePlanStep。
     * 这种结果必须降成 PARTIAL，并写明「续跑房间的工具集与原始任务不符」。
     */
    private SubagentResult applyToolsetMismatch(SubagentSpec spec, Room room, SubagentResult r) {
        if (r == null || r.cancelled()) return r;
        if (!room.resumed) return r;
        if (room.originalMode == null || room.originalMode.isReadOnly()) return r;
        boolean modeWrong = spec == null || spec.mode() == null || spec.mode() != room.originalMode;
        if (!modeWrong && !room.writeToolRefused) return r;
        SubagentLog.line(HandoffResult.TOOLSET_MISMATCH
                + " original=" + room.originalMode.name().toLowerCase()
                + " actual=" + (spec == null || spec.mode() == null ? "?" : spec.mode().name().toLowerCase())
                + " writeRefused=" + room.writeToolRefused);
        SubagentReport old = r.report();
        List<String> unresolved = new ArrayList<>();
        unresolved.add(HandoffResult.TOOLSET_MISMATCH);
        if (old != null) unresolved.addAll(old.unresolved());
        String answer = (old == null || old.answer().isBlank())
                ? HandoffResult.TOOLSET_MISMATCH
                : HandoffResult.TOOLSET_MISMATCH + "\n" + old.answer();
        SubagentReport report = new SubagentReport(SubagentReport.Status.PARTIAL, answer, false,
                room.ledger.entries(), unresolved, old == null ? "" : old.suggestedNextGoal());
        return r.withReport(report);
    }

    private Set<String> pathsOf(Room room) {
        return new LinkedHashSet<>(room.ledger.paths());
    }

    /** 收尾轮没给出报告时，内核按**实际工具调用记录**拼一段保底摘要（不留空） */
    private String fallbackSummary(String lastText, Room room, Acc acc) {
        StringBuilder sb = new StringBuilder();
        if (lastText != null && !lastText.isBlank()) {
            sb.append(lastText.trim()).append('\n');
        }
        sb.append("（内核保底摘要，非模型结论：子代理在轮数用尽前没有给出完整报告）");
        List<ReadLedgerEntry> entries = room.ledger.entries();
        if (!entries.isEmpty()) {
            sb.append("\n已实际查看 ").append(entries.size()).append(" 处：");
            for (ReadLedgerEntry e : entries) sb.append("\n").append(e.line());
        }
        sb.append("\n工具调用共 ").append(acc.toolCalls).append(" 次。");
        if (!acc.lastError.isBlank()) {
            sb.append("\n最后一次报错：").append(acc.lastError);
        }
        return sb.toString();
    }

    /** 收尾轮若返回了 tool_calls，那条助手消息会把历史"挂在半空"（没人回应它）→ 摘掉 */
    private void dropDanglingToolCalls(List<Message> messages) {
        if (messages.isEmpty()) return;
        Message last = messages.get(messages.size() - 1);
        if (last instanceof AssistantMessage am
                && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
            messages.remove(messages.size() - 1);
        }
    }

    /**
     * 交给 {@link SubagentSessions} 保管的**历史**：去掉末尾的预算脚手架消息。
     *
     * 为什么必须去掉：收尾轮的预算消息写的是"这是最后一轮，不再提供任何工具"。
     * 续跑时把它留在历史末尾，紧接着又追加"请继续补缺口"，模型会收到自相矛盾的指令。
     */
    static List<Message> historyForResume(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages);
        stripTrailingBudget(out);
        dropTrailingIncompleteToolGroup(out);
        // 半组摘掉之后，原本被它压在后面的预算消息会重新露到末尾，再剥一次。
        stripTrailingBudget(out);
        return out;
    }

    private static void stripTrailingBudget(List<Message> out) {
        while (!out.isEmpty()) {
            Message last = out.get(out.size() - 1);
            if (last instanceof UserMessage um && um.getText() != null
                    && um.getText().startsWith(BUDGET_PREFIX)) {
                out.remove(out.size() - 1);
            } else {
                break;
            }
        }
    }

    /**
     * 末尾若是一组没配完的工具调用（取消发生在工具执行中途），整组摘掉。
     * 只处理末尾这一组；历史中间的协议错误不动，交给发请求前的校验拦下来。
     */
    private static void dropTrailingIncompleteToolGroup(List<Message> out) {
        if (out.isEmpty()) return;
        int end = out.size();
        int i = end - 1;
        java.util.Set<String> answered = new java.util.HashSet<>();
        while (i >= 0 && out.get(i) instanceof ToolResponseMessage trm) {
            if (trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r != null && r.id() != null) answered.add(r.id());
                }
            }
            i--;
        }
        if (i < 0) return;
        if (!(out.get(i) instanceof AssistantMessage am)
                || am.getToolCalls() == null || am.getToolCalls().isEmpty()) {
            return;
        }
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            if (tc == null || tc.id() == null || !answered.contains(tc.id())) {
                out.subList(i, end).clear();
                return;
            }
        }
    }

    private static String lastTextOf(String text) {
        return (text == null || text.isBlank()) ? null : text;
    }

    // ==================== 预算消息 ====================

    /**
     * 每轮追加在**末尾**的预算消息。
     * 只加一条、且下一轮先摘掉上一条 —— 不改 system prompt、不插历史中间，前缀稳定。
     */
    String budgetText(int used, int total) {
        return budgetText(null, used, total);
    }

    String budgetText(SubagentSpec spec, int used, int total) {
        int left = Math.max(0, total - used);
        StringBuilder sb = new StringBuilder();
        sb.append(BUDGET_PREFIX).append(" 已用 ").append(used).append('/').append(total)
          .append("，剩余 ").append(left).append(" 轮");
        int warnAt = props.getWarnRoundsLeft();
        if (left <= warnAt) {
            sb.append("\n【收尾提醒】不要再扩大搜索范围了：只补最关键的缺口，准备写结论。");
        }
        return sb.toString();
    }

    /** 收尾轮的消息：明确告诉它没有工具了、必须出报告 */
    String wrapUpText(int total) {
        return wrapUpText(null, total);
    }

    String wrapUpText(SubagentSpec spec, int total) {
        return BUDGET_PREFIX + " 已用 " + total + "/" + total + "，剩余 0 轮 —— **这是最后一轮，不再提供任何工具**。\n"
                + "现在必须直接输出最终报告，不要再调工具，也不要把没查到的东西编出来。\n"
                + "请逐字使用下面三行标记：\n"
                + SubagentReport.MARK_ANSWER + "<直接回答问题，能带上 文件:行号 就带上>\n"
                + SubagentReport.MARK_UNRESOLVED + "<一行一条没查清的；确实都查清了就写 无>\n"
                + SubagentReport.MARK_NEXT + "<如果还要继续查，写一个更具体的目标；没有就写 无>";
    }

    /** 房间里的那段交代（自包含：子代理看不到主对话，也不知道主 agent 已经读过什么） */
    private String taskText(SubagentSpec spec, LastRunNote previous) {
        String hint;
        if (spec.mode().isReadOnly()) {
            hint = "explore（只读调研）。只做这一件事，做完用一段话汇报结论，不要闲聊、不要试图修改文件。";
        } else {
            hint = "implement（动手改）。按目标完成改动，做完用一段话汇报你改了什么、结果如何。";
        }
        StringBuilder sb = new StringBuilder(spec.roomTaskText(hint));
        sb.append("\n【效率要求】每轮可以**一次并行发出多个**工具调用；先用 grepFile 带行号定位，"
                + "再按行范围 readFile（带 offset/limit），**不要**整份读大文件。"
                + "每轮结束前自问一句「现在能回答了吗」，能就立刻写结论、不要再查。");
        if (previous != null) {
            sb.append("\n\n").append(previous.injectText());
        }
        return sb.toString();
    }

    /** 续跑消息：历史前缀不变，只在**末尾**追加这一句 */
    String resumeText(SubagentSpec spec, List<String> unresolved) {
        StringBuilder sb = new StringBuilder();
        sb.append("【续跑】你上次在同一个目标上因**轮数用尽**被中断，上面的历史就是你上次的完整过程。\n")
          .append("请**只补**下面没查清的问题，不要从头再查一遍，也不要重读已经读过且结论仍然有效的文件。\n");
        if (unresolved.isEmpty()) {
            sb.append("上次没有登记明确的缺口，请直接给出你目前能确认的结论 + 仍然不确定的部分。\n");
        } else {
            sb.append("未覆盖的问题：\n");
            for (String u : unresolved) sb.append("- ").append(u).append('\n');
        }
        sb.append("同样：每轮可以并行发多个工具调用，先用 grep 定位再按行读；拿到能回答的证据就立刻写结论。");
        return sb.toString();
    }

    // ==================== 工具执行 ====================

    /** 一次工具调用：白名单校验 → 派发 → 记账 → 台账 */
    private ToolLoopStep.ToolExec executeTool(ToolCallback[] callbacks, Set<String> allowed,
                                             AssistantMessage.ToolCall call, Acc acc, Room room) {
        SubagentContext.enterRoom();
        try {
            return executeToolInRoom(callbacks, allowed, call, acc, room);
        } finally {
            SubagentContext.exitRoom();
        }
    }

    private ToolLoopStep.ToolExec executeToolInRoom(ToolCallback[] callbacks, Set<String> allowed,
                                                    AssistantMessage.ToolCall call, Acc acc, Room room) {
        String reject = ToolGuard.rejectReason(call.name(), allowed);
        String result;
        if (reject != null) {
            // 子代理喊了清单外的工具（幻觉 / 想套娃 / 想改文件）→ 拒绝，但循环继续
            SubagentLog.line("拒绝清单外工具: " + call.name());
            if (room.resumed && isImplementOnlyTool(call.name())) {
                room.writeToolRefused = true;
            }
            result = reject;
        } else {
            result = ToolDispatcher.dispatch(callbacks, call.name(), call.arguments());
        }
        acc.toolCalls++;
        // 台账：不管成功失败都记（"我碰过这个路径"本身就是主 agent 需要的信息），
        // 但只读类工具才算"读过"，写操作不该让主 agent 以为"结论在这里"。
        Map<String, Object> args = parseArgs(call.arguments());
        recordExecutedCommand(room, call.name(), args, result);
        if (isLedgerTool(call.name())) {
            room.ledger.add(ReadLedgerEntry.of(call.name(), args,
                    result == null ? 0 : result.length()));
        }
        if (looksLikeToolFailure(result)) {
            if (call.name().equals(acc.lastFailedTool)) {
                acc.failStreak++;
            } else {
                acc.lastFailedTool = call.name();
                acc.failStreak = 1;
            }
            acc.lastError = SubagentResult.trim(result, 300);
        } else {
            acc.failStreak = 0;
            acc.lastFailedTool = null;
        }
        return ToolLoopStep.ToolExec.ok(result);
    }

    /** 记下 executeCommand 的 command + 能抠到的退出码。没有就空，不另建大型日志。 */
    private static void recordExecutedCommand(Room room, String name, Map<String, Object> args, String result) {
        if (name == null || args == null) return;
        String n = name.toLowerCase();
        if (!n.equals("executecommand") && !n.equals("execute_command")) return;
        Object c = args.get("command");
        if (c == null) return;
        String s = c.toString().trim();
        if (s.isEmpty()) return;
        room.executedCommands.add(new HandoffResult.ExecutedCommand(s, HandoffResult.parseExitCode(result)));
    }

    /** 只有"看"类工具进台账：写操作的返回值不是结论，进台账会误导主 agent */
    private static boolean isLedgerTool(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.equals("readfile") || n.equals("read_file")
                || n.equals("grepfile") || n.equals("grep_file")
                || n.equals("globfile") || n.equals("glob_file")
                || n.equals("listdirectory") || n.equals("list_directory")
                || n.equals("websearch") || n.equals("web_search");
    }

    /** 一次工具调用算不算失败（纯函数，便于单测钉死；只认各工具自己的错误前缀） */
    public static boolean looksLikeToolFailure(String result) {
        if (result == null || result.isBlank()) return false;
        String t = result.trim();
        for (String prefix : FAILURE_PREFIXES) {
            if (t.startsWith(prefix)) return true;
        }
        return false;
    }

    private Map<String, Object> parseArgs(String arguments) {
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean isCancelled(SubagentSpec spec) {
        TaskExecution task = TaskExecution.current();
        return task == null ? cancellationRegistry.isCancelled(spec.conversationId()) : task.cancelled();
    }

    /**
     * 子代理的**固定开销**估算：system prompt + 本房间的工具定义（含参数 schema）。
     * 这是"派一次子代理最少要花多少"的下限 —— 派一个只读一个文件的子代理，
     * 光这一项就可能比主 agent 直接读还贵，这正是用户要的"什么时候别派"的量化依据。
     */
    int estimateFixedOverhead(ToolCallback[] callbacks) {
        return estimateFixedOverhead(callbacks, AgentPrompts.SUBAGENT_PROMPT);
    }

    int estimateFixedOverhead(ToolCallback[] callbacks, String prompt) {
        int chars = prompt == null ? 0 : prompt.length();
        for (ToolCallback cb : callbacks) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            var def = cb.getToolDefinition();
            if (def.name() != null) chars += def.name().length();
            if (def.description() != null) chars += def.description().length();
            if (def.inputSchema() != null) chars += def.inputSchema().length();
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /** explore 与 implement 共用同一份房间提示。mode 决定工具白名单，不换提示词。 */
    String roomPrompt(SubagentSpec spec) {
        return AgentPrompts.SUBAGENT_PROMPT;
    }

    private ToolCallback[] callbacksFor(SubagentSpec.Mode mode) {
        if (mode == SubagentSpec.Mode.EXPLORE) {
            if (exploreCallbacks == null) exploreCallbacks = filter(allCallbacks, EXPLORE_TOOLS);
            return exploreCallbacks;
        }
        if (implementCallbacks == null) implementCallbacks = filter(allCallbacks, IMPLEMENT_TOOLS);
        return implementCallbacks;
    }

    /**
     * 按白名单**过滤**工具清单（纯函数，便于单测）。
     *
     * 这样 createPlan / updatePlanStep / spawnSubagent 从一开始就不在房间里 —— 模型看不到，
     * 也就不容易"凭记忆喊出来"；真喊了还有 {@link ToolGuard} 兜底。
     * 注意是**白名单**：清单里没列的工具一律不进房间，而不是"列黑名单去排除"——
     * 将来主循环加了新工具，子房间默认也拿不到，安全的方向是默认拒绝。
     */
    public static ToolCallback[] filter(ToolCallback[] all, Set<String> allowed) {
        List<ToolCallback> kept = new ArrayList<>();
        if (all == null) return new ToolCallback[0];
        for (ToolCallback cb : all) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            String n = cb.getToolDefinition().name();
            if (n != null && allowed.contains(n)) kept.add(cb);
        }
        return kept.toArray(new ToolCallback[0]);
    }

    static Set<String> namesOf(ToolCallback[] callbacks) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolCallback cb : callbacks) {
            if (cb != null && cb.getToolDefinition() != null && cb.getToolDefinition().name() != null) {
                names.add(cb.getToolDefinition().name());
            }
        }
        return names;
    }

    /** implement 比 explore 多出来的那些：写类 + executeCommand。续跑错成只读时这些会被拒。 */
    static boolean isImplementOnlyTool(String name) {
        return name != null && IMPLEMENT_TOOLS.contains(name) && !EXPLORE_TOOLS.contains(name);
    }
}
