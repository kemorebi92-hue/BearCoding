package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ChatRequest;
import com.example.jingjingai2.DTO.ChatResponse;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.agent.DescribedToolCallback;
import com.example.jingjingai2.agent.DispatchSoftHint;
import com.example.jingjingai2.agent.MessageTokenBudget;
import com.example.jingjingai2.agent.SubagentContext;
import com.example.jingjingai2.agent.SubagentInjectionPolicy;
import com.example.jingjingai2.agent.SubagentProperties;
import com.example.jingjingai2.agent.SubagentService;
import com.example.jingjingai2.agent.SubagentTelemetry;
import com.example.jingjingai2.agent.ToolResultClipper;
import com.example.jingjingai2.agent.reconcile.StepToolLedger;
import com.example.jingjingai2.agent.reconcile.TaskReconciler;
import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.agent.turn.PromptCanonical;
import com.example.jingjingai2.agent.turn.TurnState;
import com.example.jingjingai2.config.AgentPrompts;
import com.example.jingjingai2.config.ChatModelFactory;
import com.example.jingjingai2.agent.LoopGuard;
import com.example.jingjingai2.context.ContextBudget;
import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.context.PromptView;
import com.example.jingjingai2.agent.TaskExecution;
import com.example.jingjingai2.agent.TaskModelCalls;
import com.example.jingjingai2.context.ContextAssetTool;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.context.ContextObservationLog;
import com.example.jingjingai2.context.ContextProjectionPolicy;
import com.example.jingjingai2.context.ContextSourceLedger;
import com.example.jingjingai2.context.ToolHistoryCompactor;
import com.example.jingjingai2.context.WindowGuard;
import com.example.jingjingai2.context.RoundCompactor;
import com.example.jingjingai2.entity.ModelConfig;
import com.example.jingjingai2.ratelimit.RateLimiter;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.memory.MemoryConsolidator;
import com.example.jingjingai2.memory.MemoryItemObservation;
import com.example.jingjingai2.memory.WorkspaceMemory;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.config.PlanProperties;
import com.example.jingjingai2.utils.MemoryTool;
import com.example.jingjingai2.utils.PlanCreateTool;
import com.example.jingjingai2.utils.PlanInjectionMessage;
import com.example.jingjingai2.utils.PlanInjectionPlanner;
import com.example.jingjingai2.utils.PlanNudgePolicy;
import com.example.jingjingai2.utils.PlanSummaryFormatter;
import com.example.jingjingai2.utils.PlanTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.jingjingai2.service.CodeVerifier;
import com.example.jingjingai2.service.AgentCancellationRegistry;
import com.example.jingjingai2.service.ConversationHistoryService;
import com.example.jingjingai2.service.ConversationService;
import com.example.jingjingai2.service.ModelConfigService;
import com.example.jingjingai2.service.ModelNotConfiguredException;
import com.example.jingjingai2.service.ToolCallLogService;
import com.example.jingjingai2.service.WorkspaceService;
import com.example.jingjingai2.tool.McpAdminTool;
import com.example.jingjingai2.tool.McpAdminWriteTool;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpHistoryPruner;
import com.example.jingjingai2.mcp.McpNames;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.mcp.McpRoundSelector;
import com.example.jingjingai2.mcp.McpToolBridge;
import com.example.jingjingai2.mcp.McpUntrustedGuard;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.permission.McpPermissionExplainer;
import com.example.jingjingai2.permission.McpToolFilter;
import com.example.jingjingai2.permission.SensitiveRedactor;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.usermemory.UserMemoryInjector;
import com.example.jingjingai2.usermemory.UserMemoryProperties;
import com.example.jingjingai2.usermemory.UserMemoryTool;
import com.example.jingjingai2.utils.SubagentTool;
import com.example.jingjingai2.utils.TerminalTool;
import com.example.jingjingai2.utils.ToolGuard;
import com.example.jingjingai2.utils.ContextUsage;
import com.example.jingjingai2.utils.UsageTracker;
import com.example.jingjingai2.utils.FileTool;
import com.example.jingjingai2.utils.WebSearchTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/ai")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class ChatController {

    private final ChatClient chatClient;
    private final OpenAiChatModel chatModel;
    private final ConversationHistoryService historyService;
    private final ConversationService conversationService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final TaskPlanService planService;
    private final PlanTool planTool;
    /** createPlan 的入参形态（flat / structured）由配置决定，Controller 只按接口持有 */
    private final PlanCreateTool planCreateTool;
    /** 规划机制的全部开关（steps.mode / injection.slim / nudge.mode …），只读 */
    private final PlanProperties planProperties;
    private final TerminalTool terminalTool;
    private final FileTool fileTool;
    private final ToolCallLogService toolCallLogService;
    private final RateLimiter rateLimiter;
    private final ContextCompactor contextCompactor;
    /** 压完后留下的历史量。和系统提示、工具定义分开算。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.history-max-tokens:${app.context.max-tokens:64000}}")
    private int historyMaxTokens = 64000;
    /** 本轮预计总输入超过真实窗口的这个百分比才压。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-window-percent:65}")
    private int compactWindowPercent = 65;
    /** 单次任务累计输入 token 上限。≤0 关掉。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.task-input-token-budget:2000000}")
    private long taskInputTokenBudget = 2_000_000L;
    private final ModelConfigService modelConfigService;
    private final ChatModelFactory chatModelFactory;
    private final WorkspaceService workspaceService;
    private final CodeVerifier codeVerifier;
    private final WebSearchTool webSearchTool;
    /** 子代理调度入口：派发闸门、并发、写互斥、短笔记全在它里面（本类只负责"什么时候派"） */
    private final SubagentService subagentService;
    /** 派子代理的工具（下发给模型的那一份就是它） */
    private final SubagentTool subagentTool;
    /** 子代理观测台：冗余读提示 + 请求结束时的成本汇总（只读观测，不改变走向） */
    private final SubagentTelemetry subagentTelemetry;
    /** 子代理的配置（成本汇总要用里面的价格表） */
    private final SubagentProperties subagentProperties;
    /** 旧上下文的统一只读入口：搜索短索引，再按 locator 读正文。 */
    private final ContextAssetTool contextAssetTool;
    /** 用户记忆工具：只在 app.user-memory.enabled=true 时下发 */
    private final UserMemoryTool userMemoryTool;
    private final UserMemoryProperties userMemoryProperties;
    /** 读路径：/chat 与 /stream 共用，插一条本轮内存里的【用户记忆】SystemMessage */
    private final UserMemoryInjector userMemoryInjector;
    /** MCP 客户端：默认关时 callbacks 为空数组，concat 不改原生清单 */
    private final McpClientManager mcpClientManager;
    private final ApprovalService approvalService;
    /** Agent 工具：自然语言管理当前用户自己的个人 MCP 服务（模板 + 查询 + 受控保存）。 */
    private final McpAdminTool mcpAdminTool;
    /** Agent 工具：改配置 / 启停 / 连接控制 / 删除个人 MCP 服务。单测里可能不注入。 */
    private McpAdminWriteTool mcpAdminWriteTool;
    private McpPermissionExplainer mcpPermissionExplainer;
    private McpRoundActivation mcpRoundActivation;
    /** 工作区 memory/*.md：注入与 saveMemory 落盘 */
    private final WorkspaceMemory workspaceMemory;
    private final MemoryTool memoryTool;
    private final MemoryProperties memoryProperties;
    private final MemoryConsolidator memoryConsolidator;
    /** 每轮模型调用的记录（agent_turn + prompt_snapshot）。旁路：写失败不影响对话。 */
    private final AgentTurnRecorder agentTurnRecorder;
    /** history：进循环前按历史判断一次。total：每轮按完整输入判断。测试里没注入时当作 history。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-scope:history}")
    private String compactScope;
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-threshold-total:32000}")
    private int compactThresholdTotal;
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-min-gap:3}")
    private int compactMinGap;
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-growth-ratio:0.30}")
    private double compactGrowthRatio;
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-min-message-budget:1000}")
    private int compactMinMessageBudget;
    /** summary：被压掉的旧消息交给模型摘要。digest：不调模型，改写成占位符。默认 summary。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.compact-strategy:summary}")
    private String compactStrategy;
    /** digest 在被压段里保留最近几组工具结果。默认 0：这一段全部换成占位符。最近消息由保留窗口负责。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.digest-keep-tool-groups:0}")
    private int digestKeepToolGroups;
    /** tiered 时，digest 后的估算不超过阈值的这个比例就采用 digest，否则改走摘要。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.tiered-accept-ratio:0.9}")
    private double tieredAcceptRatio;
    /** 真正启用工作集：每轮模型调用前从源账本重新投影，实际发出的消息会变少。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.working-set.enabled:true}")
    private boolean workingSetEnabled;
    /** 影子投影。只记录分类和估算，实际消息与工具定义保持原样。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.working-set.shadow-enabled:true}")
    private boolean workingSetShadowEnabled;
    /** 影子分类用的软预算。不是压缩触发线，也不是模型硬窗口。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.working-set.soft-budget-tokens:64000}")
    private int workingSetSoftBudget = 64000;
    /** 观测 JSONL。留空只留在请求内存；写出的记录不含 Prompt 明文。 */
    @org.springframework.beans.factory.annotation.Value("${app.context.working-set.observation-path:}")
    private String workingSetObservationPath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 条件注入的**每请求**状态。
     *
     * <p>为什么用 ThreadLocal 而不是实例字段：本类是 Spring 单例，
     * 多个用户的请求会并发跑在同一个 bean 上 —— 实例字段会被互相覆盖（和 WorkspaceContext /
     * ConversationContext 同一个道理，那两个也是 ThreadLocal）。用完在 finally 里清掉。
     */
    private static final class ConditionState {
        String userMessage;
        long contextTokens = -1;
    }

    private static final ThreadLocal<ConditionState> CONDITION_STATE = new ThreadLocal<>();
    /** 本请求内工具结果的执行序号。请求结束清掉，不跨请求。 */
    private static final ThreadLocal<AtomicInteger> TOOL_RESULT_SEQ = new ThreadLocal<>();

    /** 系统默认服务商的 endpoint（B1：给子代理换模型时复用同一个服务商，不额外配 key）。 */
    @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:}")
    private String sysOpenAiBaseUrl;
    @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.api-key:}")
    private String sysOpenAiApiKey;

    /**
     * 子代理关掉时用的提示词：派发规则与 spawnSubagent 那一行都不出现（见 AgentPrompts.systemPrompt）。
     * 当 {@code conditionalInjection=true} 且当前上下文未超阈值时，行为等同于关组。
     */
    private static final String RETRIEVAL_GUIDE =
            "历史检索：当前上下文够用时直接执行，不要搜索。"
                    + "涉及未携带的历史决定、旧编号、用户约束或过去的执行结果时，先用 searchContext 搜索，一次一个关键词。"
                    + "搜索结果里的片段够回答就直接用；不够再按 locator 分页读取。"
                    + "结果里 hasMore=true 表示还有未返回的命中，用 nextCursor 继续翻页；想找最早的旧原话用 order=asc。"
                    + "提示里出现 message|<id> 这种 locator 时，说明那条旧消息已知，直接用 operation=read 读它，"
                    + "不要拿 id 当关键词搜索。"
                    + "已经检索过但仍没找到精确旧原话时，继续换关键词或翻页检索；确实找不到就说明无法确认，不要编造。";

    private String systemPrompt(long userId) {
        boolean mcpOn = mcpClientManager.isEnabled();
        org.springframework.ai.tool.ToolCallback[] catalog = mcpClientManager.callbacks(userId);
        boolean hasMcp = catalog != null && catalog.length > 0;
        boolean deferred = (mcpOn || hasMcp) && McpRoundSelector.resolveMode(
                mcpClientManager.properties(), catalog) == McpRoundSelector.Mode.DEFERRED;
        String prompt = deferred
                ? AgentPrompts.systemPrompt(effectiveEnabled(),
                        subagentProperties.isPartialResumeFirst(),
                        false,
                        userMemoryProperties.isEnabled(),
                        memoryProperties.isEnabled(),
                        subagentProperties.isDispatchV2(),
                        subagentProperties.getProbeFileThreshold(),
                        subagentProperties.getProbeBytesThreshold(),
                        true, true, mcpClientManager.serverLines(userId))
                : AgentPrompts.systemPrompt(effectiveEnabled(),
                        subagentProperties.isPartialResumeFirst(),
                        false,
                        userMemoryProperties.isEnabled(),
                        memoryProperties.isEnabled(),
                        subagentProperties.isDispatchV2(),
                        subagentProperties.getProbeFileThreshold(),
                        subagentProperties.getProbeBytesThreshold(),
                        mcpOn, false, java.util.List.of());
        // 没有搜索 Key 时 webSearch 工具根本不下发，提示词里也不能再要求模型“先用 webSearch”。
        if (webSearchTool == null || !webSearchTool.isEnabled()) prompt = AgentPrompts.withoutWebSearch(prompt);
        return withRetrievalGuide(prompt);
    }

    /** 固定后缀。只在工作集真正生效时附加一次，不随轮次变化。 */
    private String withRetrievalGuide(String prompt) {
        if (!workingSetEnabled) return prompt;
        return prompt + "\n\n" + RETRIEVAL_GUIDE;
    }

    /** 诊断用：只关心提示词里有没有某几段，与 MCP 清单无关。 */
    private String systemPrompt() {
        return systemPrompt(0L);
    }

    /**
     * 系统提示 + 用户记忆。{@code /chat} 和 {@code /stream} 共用，避免两套组装逻辑漂移。
     *
     * <p>顺序钉死：先 {@code systemPrompt(userId)} 再 {@link UserMemoryInjector#injectUserMemory}，
     * 计划注入是循环里 append 到末尾的，不在这里。
     *
     * <p>和 Compactor 的分界是类型，不是约定：压缩吃的是 {@code historyService} 里的
     * {@code MessageRecord}（只落 user/assistant）；用户记忆插在压缩之后的内存
     * {@code List<Message>} 上，不走 {@code addMessage}，所以不会进
     * {@code conversation_message}，下一轮 compact 也看不见它。
     */
    private String prependSystemAndUserMemory(List<Message> messages, Long userId) {
        // 只生成并注入一次基础提示；返回同一个字符串，供本轮 recordTurn 作为静态提示使用。
        String baseSystemPrompt = systemPrompt(userId == null ? 0L : userId);
        messages.add(0, new SystemMessage(baseSystemPrompt));
        if (subagentProperties.isEvalForceDispatch()) {
            messages.add(1, new SystemMessage(AgentPrompts.EVAL_FORCE_DISPATCH_MSG));
        } else if (subagentProperties.isEvalForceNoSpawn()) {
            messages.add(1, new SystemMessage(AgentPrompts.EVAL_FORCE_NO_SPAWN_MSG));
        }
        userMemoryInjector.injectUserMemory(messages, userId);
        return baseSystemPrompt;
    }

    /**
     * 本轮下发给模型的工具清单。
     *
     * 子代理关掉时**整个工具都不下发**，而不是"照发、让闸门去拒"：留一个拒绝器在那儿，
     * 模型还是会照着提示词去派，拿回一条"功能未启用，请自己做"，白烧一轮往返，
     * 用户那边还闪一张红色失败卡（实测对照组多跑的那一轮就是这么来的）。
     * 工具压根不出现，模型连试都不会试。
     */
    /** 可选注入：写类 MCP 管理工具。不注入时工具清单里就没有这四个，单测不受影响。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMcpAdminWriteTool(McpAdminWriteTool tool) {
        this.mcpAdminWriteTool = tool;
    }

    private Object[] toolObjects() {
        List<Object> tools = new ArrayList<>();
        tools.add(terminalTool);
        tools.add(fileTool);
        // 没有搜索 Key 时不下发 webSearch：否则模型会调用一个必然失败的工具，白烧一轮。
        if (webSearchTool != null && webSearchTool.isEnabled()) tools.add(webSearchTool);
        tools.add(planTool);
        tools.add(planCreateTool);
        tools.add(contextAssetTool);
        if (effectiveEnabled() && !subagentProperties.isEvalForceNoSpawn()) tools.add(subagentTool);
        if (userMemoryProperties.isEnabled()) tools.add(userMemoryTool);
        // MCP 自然语言管理工具：只读 + 受控保存（默认禁用、不启动），不依赖 MCP 开关。
        // 工具名不在 PermissionPolicy 的 ALLOW 白名单里，默认 ASK，每次调用弹审批卡。
        tools.add(mcpAdminTool);
        if (mcpAdminWriteTool != null) tools.add(mcpAdminWriteTool);
        if (memoryProperties.isEnabled()) tools.add(memoryTool);
        return tools.toArray();
    }

    /**
     * 注解工具 → callbacks；general-purpose 打开时替换 spawnSubagent 的工具说明
     * （@Tool 文案保持旧的，关开关时模型看到的仍是改造前那一份）。
     */
    private ToolCallback[] bindToolCallbacks(long userId) {
        ToolCallback[] cbs = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects())
                .build()
                .getToolCallbacks();
        if (effectiveEnabled() && subagentProperties.isDispatchV2()) {
            cbs = DescribedToolCallback.relabel(
                    cbs, "spawnSubagent", AgentPrompts.SPAWN_TOOL_DESCRIPTION_V2);
        }
        // MCP 接在原生工具后面（prompt cache：稳定前缀在前）。关掉时 callbacks 为空，引用不变。
        // 只接当前登录用户能看到的：部署级 + 自己的个人服务。
        return McpToolBridge.concat(cbs, mcpClientManager, userId);
    }

    /**
     * 本轮真正下发给模型的清单：eager 就是 bind 全量；deferred 是原生 + mcpToolSearch + 已激活。
     * 非流式 / SSE 两套循环都走这里，再叠加 HARD stripWriteTools。
     * 这份清单同时用于 options、ToolGuard、派发，三者必须一致。
     */
    private ToolCallback[] computeRoundCallbacks(ToolCallback[] fullBind, List<Message> messages,
                                                 boolean forceThisRound, String conversationId,
                                                 Long userId, SseEmitter emitter) {
        // 没有登录身份时按「只看部署级服务」处理：绝不能退化成「看所有人的」。
        long uid = userId == null ? 0L : userId;
        McpHistoryPruner.pruneInPlace(messages, mcpClientManager.properties());
        McpUntrustedGuard.wrapUntrusted(messages, conversationId, mcpClientManager, uid);
        McpUntrustedGuard.ensureHistoryMarker(messages, conversationId, mcpClientManager, uid);
        ToolCallback[] visible = McpToolFilter.dropDenied(userId, fullBind, mcpPermissionExplainer);
        ToolCallback[] round = McpRoundSelector.forRound(visible, mcpClientManager, messages, uid);
        if (forceThisRound) {
            round = effectiveRoundCallbacks(round);
        }
        if (mcpClientManager.shouldStripDangerous(conversationId, messages)) {
            round = McpUntrustedGuard.stripDangerousBuiltins(round);
        }
        logToolStats(conversationId, round);
        noteActivated(emitter, conversationId, round);
        System.out.println("[Mcp] round tools=" + McpRoundSelector.joinNames(round));
        return round;
    }

    /** 每轮 tools 部分的估算 token。只记日志，不改变下发内容。 */
    private void logToolStats(String conversationId, ToolCallback[] round) {
        long tokens = McpRoundSelector.estimateSchemaTokens(round);
        int mcp = 0;
        if (round != null) {
            for (org.springframework.ai.tool.ToolCallback cb : round) {
                if (cb != null && cb.getToolDefinition() != null
                        && McpNames.isMcp(cb.getToolDefinition().name())) mcp++;
            }
        }
        System.out.println("[MCP] tools 估算 token=" + tokens + " mcp工具=" + mcp
                + " 会话=" + conversationId);
    }

    /** 本轮真正下发的 mcp__ 名字。集合没变就不再推一条 SSE。 */
    private void noteActivated(SseEmitter emitter, String conversationId, ToolCallback[] round) {
        if (mcpRoundActivation == null || conversationId == null) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        if (round != null) {
            for (org.springframework.ai.tool.ToolCallback cb : round) {
                if (cb == null || cb.getToolDefinition() == null) continue;
                String n = cb.getToolDefinition().name();
                if (McpNames.isMcp(n)) names.add(n);
            }
        }
        java.util.Set<String> before = mcpRoundActivation.names(conversationId);
        if (names.isEmpty() || before.containsAll(names)) return;
        mcpRoundActivation.note(conversationId, names);
        if (emitter == null) return;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "mcp_tools_activated");
        ev.put("conversationId", conversationId);
        ev.put("tools", names);
        sendJson(emitter, "mcp_tools_activated", ev);
    }

    private static ToolCallingChatOptions withTools(ToolCallback[] cbs) {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(cbs)
                .internalToolExecutionEnabled(false)
                .build();
    }

    /** dispatch-v2 时主循环工具结果硬顶；关开关或 ≤0 原样返回。 */
    private String maybeClipMainToolResult(String toolResult) {
        if (!subagentProperties.isDispatchV2()) return toolResult;
        return ToolResultClipper.clip(toolResult, subagentProperties.getMainToolResultChars());
    }

    private void applySoftHint(List<Message> messages, DispatchSoftHint hint) {
        if (hint == null) return;
        if (hint.maybeInject(messages, subagentProperties.softHintActive())) {
            System.out.println("[DispatchSoftHint] injected toolCum="
                    + DispatchSoftHint.currentToolTokens(messages)
                    + " threshold=" + hint.getThresholdTokens());
        }
    }

    /** 本轮 messages 的 token 粗分（系统/用户/助手/工具），证明峰值构成。 */
    private void logMsgBudget(int roundIndex, List<Message> messages) {
        MessageTokenBudget.Breakdown b = MessageTokenBudget.of(messages);
        System.out.println("[MsgBudget] round=" + (roundIndex + 1) + " " + b);
    }

    /**
     * 计算本轮是否应下发子代理工具+规则。
     *
     * <p>判定细节见 {@link SubagentInjectionPolicy#shouldInject}：条件注入关闭时行为与
     * 加这个功能之前完全一致（{@code enabled} 说了算）；开启后只有"请求命中宽泛特征"或
     * "上下文超阈值"才下发。条件关闭时，{@link AgentPrompts#systemPrompt(boolean)}
     * 会把派发规则从提示词中整段摘掉，避免"规则在但工具不在"导致白烧一轮 REJECTED。
     */
    private boolean effectiveEnabled() {
        ConditionState st = CONDITION_STATE.get();
        return SubagentInjectionPolicy.shouldInject(
                subagentProperties.isConditionalInjection(),
                subagentProperties.isEnabled(),
                subagentProperties.getConditionalPromptPatterns(),
                st == null ? null : st.userMessage,
                st == null ? -1 : st.contextTokens,
                subagentProperties.getConditionalContextThreshold());
    }

    /**
     * 每轮请求开始前调用，刷新条件注入缓存（估算上下文 token + 保存用户输入）。
     * 同一请求的后续轮次复用该缓存，避免重复访问历史。
     */
    void refreshConditionCache(Long uid, String convId, String userMessage) {
        ConditionState st = new ConditionState();
        st.userMessage = userMessage;
        CONDITION_STATE.set(st);
        if (!subagentProperties.isConditionalInjection()) {
            st.contextTokens = -1;
            return;
        }
        try {
            long total = 0;
            if (userMessage != null) {
                total += com.example.jingjingai2.context.TokenEstimator.estimate(userMessage);
            }
            java.util.List<com.example.jingjingai2.DTO.MessageRecord> history =
                    historyService.getHistory(uid, convId);
            for (com.example.jingjingai2.DTO.MessageRecord r : history) {
                total += com.example.jingjingai2.context.TokenEstimator.estimate(r.getContent());
            }
            // 新口径：加上本会话主 agent 已 readFile 的正文估算（跨请求保留）
            total += subagentTelemetry.estimatedMainReadTokens(convId);
            st.contextTokens = total;
        } catch (Exception e) {
            st.contextTokens = 0;
        }
    }
    /** 「编辑→编译校验→修正」闭环的最大重试轮数，超出后把最后一次报错原样交还用户 */
    private static final int MAX_VERIFY_ROUNDS = 3;
    /**
     * 单次对话里模型最多能连续调度多少轮工具（探索 + 编辑 + 自愈都要用）。
     *
     * <p>2026-11-11：18 → 40 → 256。自动续轮已随 c0a8fc8 失效，所以直接把上限抬高。
     * 这里的值会被 {@link com.example.jingjingai2.agent.TaskExecution} 的构造器再夹一次（硬顶 256），
     * 有计划时还会被 {@code TaskPlan.budgetFor} 抬高（计划里的上限仍是 60，所以取 max 后就是 256）。</p>
     */
    private static final int MAX_ITERATIONS = 256;
    /** 轮数用满时补在总结后面的一句：计划是持久化的，下一条消息能接着做。 */
    private static final String ROUND_LIMIT_NOTE =
            "\n\n---\n轮数预算已用完。计划还在：回我一句「继续」，我就从没做完的那一步接着做。";
    /** SSE 流式输出专用线程池（SseEmitter 需要在异步线程里持续推送） */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(8);

//    匹配捕获到的歌曲的信息用正则表达式匹配
    private static final Pattern MUSIC_BLOCK_PATTERN =
            Pattern.compile("【MUSIC_BLOCK([\\s\\S]*?)】");

    public ChatController(ChatClient chatClient,
                          OpenAiChatModel chatModel,
                          ConversationHistoryService historyService,
                          ConversationService conversationService,
                          AgentCancellationRegistry cancellationRegistry,
                          TaskPlanService planService,
                          PlanTool planTool,
                          PlanCreateTool planCreateTool,
                          PlanProperties planProperties,
                          TerminalTool terminalTool,
                          FileTool fileTool,
                          ToolCallLogService toolCallLogService,
                          RateLimiter rateLimiter,
                          ContextCompactor contextCompactor,
                          ModelConfigService modelConfigService,
                          ChatModelFactory chatModelFactory,
                          WorkspaceService workspaceService,
                          CodeVerifier codeVerifier,
                          WebSearchTool webSearchTool,
                          SubagentService subagentService,
                          SubagentTool subagentTool,
                          SubagentTelemetry subagentTelemetry,
                          SubagentProperties subagentProperties,
                          ContextAssetTool contextAssetTool,
                          UserMemoryTool userMemoryTool,
                          UserMemoryProperties userMemoryProperties,
                          UserMemoryInjector userMemoryInjector,
                          McpClientManager mcpClientManager,
                          ApprovalService approvalService,
                          McpAdminTool mcpAdminTool,
                          WorkspaceMemory workspaceMemory,
                          MemoryTool memoryTool,
                          MemoryProperties memoryProperties,
                          MemoryConsolidator memoryConsolidator,
                          AgentTurnRecorder agentTurnRecorder) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        this.historyService = historyService;
        this.conversationService = conversationService;
        this.cancellationRegistry = cancellationRegistry;
        this.planService = planService;
        this.planTool = planTool;
        this.planCreateTool = planCreateTool;
        this.planProperties = planProperties;
        this.terminalTool = terminalTool;
        this.fileTool = fileTool;
        this.toolCallLogService = toolCallLogService;
        this.rateLimiter = rateLimiter;
        this.contextCompactor = contextCompactor;
        this.modelConfigService = modelConfigService;
        this.chatModelFactory = chatModelFactory;
        this.workspaceService = workspaceService;
        this.codeVerifier = codeVerifier;
        this.webSearchTool = webSearchTool;
        this.subagentService = subagentService;
        this.subagentTool = subagentTool;
        this.subagentTelemetry = subagentTelemetry;
        this.subagentProperties = subagentProperties;
        this.contextAssetTool = contextAssetTool;
        this.userMemoryTool = userMemoryTool;
        this.userMemoryProperties = userMemoryProperties;
        this.userMemoryInjector = userMemoryInjector;
        this.mcpClientManager = mcpClientManager;
        this.approvalService = approvalService;
        this.mcpAdminTool = mcpAdminTool;
        this.workspaceMemory = workspaceMemory;
        this.memoryTool = memoryTool;
        this.memoryProperties = memoryProperties;
        this.memoryConsolidator = memoryConsolidator;
        this.agentTurnRecorder = agentTurnRecorder;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMcpPermissionExplainer(McpPermissionExplainer mcpPermissionExplainer) {
        this.mcpPermissionExplainer = mcpPermissionExplainer;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMcpRoundActivation(McpRoundActivation mcpRoundActivation) {
        this.mcpRoundActivation = mcpRoundActivation;
    }

    @PostMapping(value = "/chat", produces = "application/json")
    public ChatResponse chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");

        // ===== 多用户限流：防止单人刷爆模型额度 =====
        if (userId != null) {
            String limitMsg = rateLimiter.tryAcquire(userId);
            if (limitMsg != null) {
                System.out.println("[RateLimit] 用户 " + userId + " 被限流: " + limitMsg);
                return new ChatResponse("chat", "⏳ " + limitMsg);
            }
        }

        String userMessage = request.getMessage();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        if (!cancellationRegistry.tryArm(userId, conversationId))
            return new ChatResponse("chat", "该会话已有请求正在执行，请等待或停止后再试。");
        try {
            return chatExclusive(request, userId, userMessage, conversationId);
        } finally {
            cancellationRegistry.clear(userId, conversationId);
            WorkspaceContext.clear();
            ConversationContext.clear();
            CONDITION_STATE.remove();
        }
    }

    private ChatResponse chatExclusive(ChatRequest request, Long userId, String userMessage, String conversationId) {
        // 会话元数据打卡必须在解析工作区根之前：pending 会话第一次说话才落库并挂上 workspaceId，
        // 否则 resolveRoot 还看不到归属，会误进默认沙箱。
        conversationService.touch(userId, conversationId, userMessage, request.getWorkspaceId(),
                request.getPermissionMode());

        // 绑定本会话的工作区：文件工具与终端只在这个目录内操作
        Path workspaceRoot = workspaceService.currentRoot(userId, conversationId);
        WorkspaceContext.set(workspaceRoot);
        planService.revalidateFiles(userId, conversationId, null, null);
        // 上个任务的计划已无未完成步骤 → 这是新任务，清掉旧计划重新规划（否则会误判"已完成"直接收尾）
        planService.resetIfFinished(userId, conversationId);
        // 新任务 = 主 agent 已经翻篇：上一轮留给子代理的短笔记和重派计数全部释放
        subagentService.beginUserRequest(conversationId);
        // 条件注入：刷新上下文估算缓存（仅在 conditional-injection=true 时生效）
        refreshConditionCache(userId, conversationId, userMessage);
        // 会话上下文（供计划工具定位"当前是哪个会话的计划"）：ThreadLocal，用完必须清
        ConversationContext.set(userId, conversationId);

        historyService.addMessage(userId, conversationId,
                new MessageRecord("user", userMessage));

        // ---- 汇总日志 / 真实用量统计的容器 ----
        // 必须声明在 try **之外**：原来的汇总日志写在 try 内、只覆盖"正常走完"这一条路径，
        // 而流式异常、高危命令待审批、外层异常都会提前 return，于是日志里偶尔就缺 [PlanSummary]。
        // 现在改成「正常路径先打一次 + finally 兜底打一次」，用 markOnce 保证只输出一次。
        PlanNudgeState nudgeState = new PlanNudgeState();
        DispatchSoftHint softHint = new DispatchSoftHint(subagentProperties.getSoftHintThresholdTokens());
        int roundsUsed = 0;
        int planInjectChars = 0;
        int totalPromptChars = 0;
        int firstCreatePlanRound = -1;
        final boolean[] summaryLogged = new boolean[1];
        final UsageTracker usage = new UsageTracker();
        RoundCompactor.Guard compactionGuard = new RoundCompactor.Guard();
        PromptCursor promptCursor = new PromptCursor();
        // 主 agent 每轮输入 token 的曲线（成本核算用）：取轮前/轮后的用量差值 ——
        // 一轮里可能发多次请求（强制 createPlan、流式分片、编译自愈重试），差值才是"这一轮真实吃进去多少"。
        // 声明在 try 外，finally 的兜底日志才打得到。
        final List<Long> promptCurve = new ArrayList<>();
        final ToolCallContext.Scope[] toolScope = new ToolCallContext.Scope[1];
        // 审批补记要知道「现在是第几轮」。回调在进循环前就建好，轮次由每轮开头写入。
        final AtomicInteger approvalTurn = new AtomicInteger(-1);
        // 单次请求作用域：记录工具执行与步骤上报，请求结束只读对账，绝不修改计划状态。
        StepToolLedger stepLedger = new StepToolLedger(conversationId);
        TaskExecution task = newTaskExecution(userId, conversationId, request.getModelConfigId());
        TaskExecution.Scope taskScope = TaskExecution.attach(task);

        try {
            // 把 @Tool 注解的 TerminalTool 转成 ToolCallback，传给模型
            // bind 仍是全量 catalog（原生 + 全部 MCP）；本轮清单在循环里经 computeRoundCallbacks 裁
            ToolCallback[] toolCallbacks = bindToolCallbacks(userId);
            boolean toolsLogged = false;

            // ===== 多轮记忆 + 上下文压缩：历史还原成 Message，超预算时旧对话压成摘要 =====
            List<MessageRecord> loadedHistory = historyService.getHistory(userId, conversationId);
            // F1：先解析本次请求选中的模型，再装配历史——摘要调用必须用它，不能用系统兜底模型。
            OpenAiChatModel activeModel = resolveModel(request, userId);
            // M1：只有默认工作集路径带会话作用域（跨请求复用摘要）；其余口径维持原行为。
            String summaryKey = summaryScope(userId, conversationId);
            List<Message> messages = workingSetEnabled || totalScope()
                    ? contextCompactor.assemble(loadedHistory, userMessage, summaryKey, activeModel)
                    : contextCompactor.buildMessages(loadedHistory, userMessage, activeModel);
            ContextSourceLedger sourceLedger = newSourceLedger(conversationId, loadedHistory, userMessage);
            // M1：把摘要覆盖到的原文登记进来源账本，投影配对时跳过它们（摘要与原文不能同时发送）。
            sourceLedger.markSummarized(contextCompactor.lastHistorySummary().coveredIds());

            // 本轮是否刚发生过上下文压缩（见流式链路同名逻辑）
            boolean contextCompacted = isCompacted(messages);
            // 立刻取走：ContextCompactor 是单例，lastStats 会被下一个请求覆盖
            ContextCompactor.CompactStats compactStats = contextCompactor.lastStats();
            ContextCompactor.SummaryUsage historySummary = contextCompactor.takeSummaryUsage();

            // 注入系统提示词（让 Agent 真正按指令使用工具，而不是裸聊天）
            String baseSystemPrompt = prependSystemAndUserMemory(messages, userId);
            toolScope[0] = openPermissionScope(userId, conversationId, false, null, messages, approvalTurn);
            injectWorkspaceMemory(messages, workspaceRoot);

            // 解析本次对话要用的模型：指定了 modelConfigId 就动态构建用户模型，否则用系统默认 DeepSeek
            // （F1 后模型在装配历史之前解析，这里只做用量记账）
            accountSummary(usage, userId, conversationId, 0, activeModel, historySummary, compactStats);
            // 子代理工具（@Tool）拿不到 ChatModel，用 ThreadLocal 把"本轮用哪个模型"传下去：
            // 万一某次 spawnSubagent 没被下面的批量通道拦住（走了普通派发），它也能拿到正确的模型。
            // B1：子代理可用独立模型（subagent.model-id；留空 = 沿用主模型，行为与之前完全一致）。
            OpenAiChatModel childModel = resolveSubagentModel(activeModel);
            SubagentContext.set(childModel);

            List<ChatResponse.ToolCallRecord> toolCallRecords = new ArrayList<>();
            int maxIterations = task.limit();
            int verifyRounds = 0;          // 已用掉的自愈重试轮数
            boolean filesModified = false; // 本轮是否改过文件（决定是否需要编译校验）
            boolean finishedNaturally = false; // 是否是"模型主动收尾"结束，而不是被轮数上限打断
            boolean cancelled = false;     // 用户是否点了"停止"
            AssistantMessage lastAssistantMessage = null;

            PlanInjectState planInjectState = new PlanInjectState();   // 计划注入状态（slim 策略用）
            boolean budgetWarned = false;
            boolean[] budgetHold = {false};
            boolean budgetStop = false;
            boolean spinning = false;
            boolean protocolStop = false;
            LoopGuard.State repeatState = new LoopGuard.State();
            TaskPlan awaitingDelivery = planService.get(userId, conversationId);
            if (awaitingDelivery != null && !awaitingDelivery.allDone()) awaitingDelivery = null;
            for (int i = 0; i < maxIterations; i++) {
                try {
                // 安全点 ①：每轮开头检查取消标志
                if (task.cancelled()) {
                    cancelled = true;
                    System.out.println("[Cancel] 第 " + (i + 1) + " 轮开始前收到停止请求，退出循环");
                    break;
                }
                if (task.mustFinalize()) { task.end(task.approvalBlocked() ? "APPROVAL_BLOCKED" : "MODEL_CALL_RESERVE"); break; }
                roundsUsed = i + 1;   // 改动5：记录实际用掉的轮数
                approvalTurn.set(i);
                // 每轮注入计划进度（slim：常态一行摘要；版本变化 / 满 N 轮 / 刚压缩过才全量）
                planInjectChars += injectPlan(messages, planInjectState, userId, conversationId, i, contextCompacted);  // 改动5：累计注入字符数
                budgetWarned = warnExecutionBudget(messages, task, budgetWarned);
                // HARD 强制轮：**必须先于 applyPlanNudge 消费**（见 consumeForceRound 的注释）——
                // 否则本轮若又命中信号，刚设好的 forceRound 会被覆盖成再下一轮，强制轮永远轮不到。
                boolean forceThisRound = PlanNudgePolicy.consumeForceRound(i, nudgeState.forceRound);
                // 漂移信号 + 分级提醒：只在"会话还没有计划"时生效（信号 0 条 → 完全不打扰）
                applyPlanNudge(messages, nudgeState, toolCallRecords, userId, conversationId,
                        i, workspaceRoot, null);
                applySoftHint(messages, softHint);
                logMsgBudget(i, messages);

                totalPromptChars += promptChars(messages);   // 改动5：统计本轮 prompt 规模（估算规划占比用）

                // HARD：本轮若被判为该强制调用 createPlan，就带上 tool_choice；
                // 只在**这一轮**生效（forceRound 已在上面消费掉），异常自动回退普通请求。
                // roundCallbacks 是本轮真正下发给模型的清单：它同时决定"发给模型的 options"、
                // "白名单校验"和"实际派发范围"，三者必须一致（否则被摘掉的工具还能被喊出来执行）。
                ToolCallback[] roundCallbacks = computeRoundCallbacks(
                        toolCallbacks, messages, forceThisRound, conversationId, userId, null);
                guardRound(messages, roundCallbacks, usage.getPromptTokens(), i + 1, maxIterations, budgetHold);
                if (budgetHold[0]) { budgetStop = true; break; }
                if (!toolsLogged) {
                    logOfferedTools(roundCallbacks);
                    toolsLogged = true;
                }
                ToolCallingChatOptions options = withTools(roundCallbacks);
                org.springframework.ai.chat.model.ChatResponse response;
                ToolCallingChatOptions forced = null;
                if (forceThisRound) {
                    forced = forcedPlanOptions(roundCallbacks);
                }
                ContextCompactor.CompactStats totalRound = compactTotalIfEnabled(
                        messages, roundCallbacks, i, compactionGuard, userId, conversationId, activeModel, usage);
                long promptTokensBefore = usage.getPromptTokens();
                long completionTokensBefore = usage.getCompletionTokens();
                int responsesBefore = usage.getResponsesWithUsage();
                PromptView view = promptCursor.observe(this, sourceLedger, messages, roundCallbacks, i, false, false,
                        userId, conversationId);
                if (stoppedForHardWindow(view, null)) {
                    return new ChatResponse("chat", HARD_WINDOW_STOP);
                }
                if (rejectOverWindow(view.messages(), roundCallbacks, historySummary, null, totalRound)) {
                    return new ChatResponse("chat", "历史摘要失败，且完整请求估算超过配置的上下文预算。本次保留原始历史，未发送模型请求。");
                }
                if (refuseBrokenProtocol(view.messages())) {
                    protocolStop = true;
                    finishedNaturally = true;
                    break;
                }
                Prompt prompt = new Prompt(view.messages(), options);
                if (forced != null) {
                    try {
                        response = com.example.jingjingai2.agent.TaskModelCalls.call(activeModel, new Prompt(view.messages(), forced));
                        logForceOutcome("非流式", response);
                    } catch (Exception fx) {
                        System.out.println("[PlanNudge] 强制 tool_choice 请求异常，回退普通请求: " + fx.getMessage());
                        nudgeState.forceFailed = true;
                        response = com.example.jingjingai2.agent.TaskModelCalls.call(activeModel, prompt);
                    }
                } else {
                    response = com.example.jingjingai2.agent.TaskModelCalls.call(activeModel, prompt);
                }
                usage.accept(response);   // 真实用量（只读观测）
                logRoundDiag(response, messages, roundCallbacks);   // 诊断：单轮真实用量 + 上下文构成（只记日志）
                task.modelRound();
                Generation generation = response.getResult();
                lastAssistantMessage = generation.getOutput();

                List<AssistantMessage.ToolCall> toolCalls = lastAssistantMessage.getToolCalls();
                // 这一轮的记录：模型返回之后、工具执行之前。审批不再单独挂起（网关内联等待），
                // 所以这里只按"有没有工具调用"区分。
                boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
                recordTurn(conversationId, userId, i, activeModel, roundCallbacks, messages,
                        baseSystemPrompt,
                        totalRound != null ? totalRound : compactStats, totalRound != null,
                        responsesBefore, promptTokensBefore, completionTokensBefore, usage,
                        hasToolCalls ? AgentTurnRecord.END_TOOL_CALLING
                                     : AgentTurnRecord.END_NORMAL_STOP,
                        compactionGuard, response);
                // 改动5：记录 createPlan 首次被调用的轮号（汇总日志要回答"到底第几轮才开始规划"）
                if (firstCreatePlanRound < 0 && toolCalls != null && toolCalls.stream()
                        .anyMatch(tc -> "createPlan".equalsIgnoreCase(tc.name()))) {
                    firstCreatePlanRound = i;
                }
                if (toolCalls == null || toolCalls.isEmpty()) {
                    planService.revalidateFiles(userId, conversationId, null, null);
                    if (!task.acceptableAnswer(lastAssistantMessage.getText(),
                            () -> planService.stepsOf(userId, conversationId))) {
                        task.end("INVALID_ANSWER");
                        break;
                    }
                    // 模型认为已经完成了。若这一轮改过文件，先自动做一次编译校验再收尾
                    if (filesModified && !task.cancelled()) {
                        CodeVerifier.Result vr = verifyForDelivery(
                                workspaceRoot, userId, conversationId, toolCallRecords, i, null);
                        if (vr.ran() && !vr.success()) {
                            if (verifyRounds < MAX_VERIFY_ROUNDS) {
                                verifyRounds++;
                                // 把模型的说明保留进上下文，再把编译报错回灌，让它自己定位并修正
                                messages.add(lastAssistantMessage);
                                messages.add(new UserMessage(verifyFeedback(vr.output(), verifyRounds)));
                                continue;
                            }
                            task.end("VERIFY_FAILED");
                            lastAssistantMessage = new AssistantMessage(task.fallback(describeVerify(vr)));
                        }
                    }
                    finishedNaturally = true;
                    if (!"VERIFY_FAILED".equals(task.summary().get("endReason"))) {
                        task.end("NORMAL_STOP");
                        task.delivered();
                    }
                    if (budgetHold[0]) budgetStop = true;
                    promptCurve.add(Math.max(0, usage.getPromptTokens() - promptTokensBefore));
                    break; // 校验通过 / 无需校验 → 正常结束
                }

                String repeatSig = LoopGuard.signature(toolCalls);
                LoopGuard.Decision repeat = LoopGuard.observe(repeatState, repeatSig);
                LoopGuard.apply(repeatState, repeat);
                if (repeat.action == LoopGuard.Action.STOP) {
                    spinning = true;
                    System.out.println("[Guard] 触发=原地打转 " + repeatSig);
                    break;
                }
                if (repeat.action == LoopGuard.Action.HINT) {
                    System.out.println("[Guard] 触发=重复调用提示 " + repeatSig);
                    pushRepeatHint(messages, lastAssistantMessage, toolCalls);
                    continue;
                }

                messages.add(lastAssistantMessage);
                Map<String, SubagentService.SpawnOutcome> spawned = new java.util.HashMap<>();
                java.util.Set<String> batched = new java.util.HashSet<>();
                for (int spawnIndex = 0; spawnIndex < toolCalls.size(); spawnIndex++) {
                    AssistantMessage.ToolCall tc = toolCalls.get(spawnIndex);
                    // 安全点 ②：每个工具执行前检查取消标志（避免"点了停止还接着跑工具"）
                    if (task.cancelled()) {
                        cancelled = true;
                        System.out.println("[Cancel] 工具执行前收到停止请求: " + tc.name());
                        break;
                    }
                    if (!batched.contains(tc.id()) && subagentService.isReadOnlySpawn(tc, conversationId)) {
                        List<AssistantMessage.ToolCall> run =
                                consecutiveReadOnlySpawns(toolCalls, spawnIndex, conversationId);
                        for (AssistantMessage.ToolCall one : run) batched.add(one.id());
                        spawned.putAll(runSpawnBatch(run, childModel, conversationId, null, roundCallbacks));
                    }
                    // 执行 + 记账与非流式**共用同一处**实现（executeAndRecordTool）：
                    // success 由结构化执行结论决定，两条链路口径不会再分叉。
                    ChatResponse.ToolCallRecord record = executeAndRecordTool(
                            roundCallbacks, tc, conversationId, messages,
                            spawned.get(tc.id()),
                            userId, i, stepLedger, toolCallRecords);

                    // 记录"这一轮改过文件"，作为是否触发编译校验的依据。
                    // implement 子代理也能改工作区，所以默认同样立旗；GP 打开且完工单
                    // STATUS=SUCCEEDED（未降级）时跳过 —— 子代理已经自己编过，父代理不要再 javac 一遍。
                    if ((record.isSuccess() || SubagentService.isSpawnTool(tc.name()) && !task.approvalBlocked())
                            && shouldFlagFilesModified(tc, record.getResult(), conversationId)) {
                        filesModified = true;
                    }
                }
                // 这一轮主 agent 吃进去的输入 token（轮末结算，含本轮所有模型调用）
                promptCurve.add(Math.max(0, usage.getPromptTokens() - promptTokensBefore));
                if (cancelled) break;   // 工具循环里被取消 → 外层循环也退出
                if (budgetHold[0]) {
                    budgetStop = true;
                    break;
                }

                // ⭐ harness 层强制收尾（非流式链路）：计划全部完成 → 跳出循环，用一次"禁用工具"的调用产出总结
                if (planService.allStepsDone(userId, conversationId)
                        && (awaitingDelivery == null || task.completedPlan() != null
                            || planService.get(userId, conversationId) != awaitingDelivery)) {
                    task.end("PLAN_REPORTED_DONE");
                    System.out.println("[Plan] 计划全部完成 → 进入禁用工具的收尾轮（非流式）");
                    break;
                }
                } catch (TaskExecution.Cancelled stopped) { cancelled = true; break; }
                catch (TaskExecution.BudgetExceeded exhausted) { task.end("MODEL_BUDGET"); break; }
            }

            cancelled = cancelled || task.cancelled();
            if (!finishedNaturally && !cancelled && !protocolStop) {
                if ("RUNNING".equals(task.summary().get("endReason"))) task.end(spinning ? "REPEAT_GUARD" : budgetStop ? "INPUT_BUDGET" : "ROUND_LIMIT");
                if (task.approvalBlocked()) task.end("APPROVAL_BLOCKED");
                String reason = task.approvalBlocked() ? "审批阻塞，工具未执行" : budgetStop ? "累计输入预算不足" : "执行阶段结束";
                String delivered = completeExecution(activeModel, messages, task, usage, reason, budgetStop,
                        filesModified, workspaceRoot, userId, conversationId, toolCallRecords, roundsUsed, null,
                        sourceLedger, promptCursor, null);
                lastAssistantMessage = new AssistantMessage(delivered);
                finishedNaturally = true; // terminal response supplied; not a verified task-success flag
            }

            // 结论出来了（模型自然收尾 / 计划全部完成）→ 立刻释放本会话的子代理便条与重派计数。
            // 这是"结论一到就释放"的落点：**不等**用户说满意，而是"这一步有结论了"这个可观测事件。
            if (finishedNaturally) {
                subagentService.releaseOnConclusion(conversationId);
            }

            String aiText = lastAssistantMessage != null ? lastAssistantMessage.getText() : "";
            if (PlanSummaryFormatter.markOnce(summaryLogged)) {
                String exitReason = cancelled ? "CANCELLED" : protocolStop ? "PROTOCOL_ERROR" : String.valueOf(task.summary().get("endReason"));
                // 只有「轮数预算用满」才补记。取消、打转、预算、自然收尾、计划收尾都不记。
                // 补在收尾处，不改循环里那一行 TOOL_CALLING / NORMAL_STOP。
                if ("ROUND_LIMIT".equals(task.summary().get("endReason"))) {
                    recordIterationCap(conversationId, userId, roundsUsed, activeModel);
                }
                System.out.println("[Finish] 结束原因=" + exitReason
                        + " | 轮数=" + roundsUsed + "/" + maxIterations
                        + " | 累计输入token=" + usage.getPromptTokens());
                logSessionSummary(roundsUsed, firstCreatePlanRound, nudgeState, planInjectChars, totalPromptChars);
                logUsage(usage);
                logSubagentCost(conversationId, usage);
                logPromptCurve(promptCurve);
            }
            cancelled = cancelled || task.cancelled();
            if (cancelled || protocolStop) {
                task.end(cancelled ? "CANCELLED" : "PROTOCOL_ERROR");
                aiText = task.fallback(cancelled ? "用户已停止执行" : PROTOCOL_STOP_NOTE);
            }
            ChatResponse chatResponse = parseAiResponse(aiText, toolCallRecords);
            historyService.addMessage(userId, conversationId,
                    new MessageRecord("assistant", chatResponse.getMessage()));
            if (task.cancelled() && !cancelled) {
                task.end("CANCELLED");
                chatResponse = parseAiResponse(task.fallback("用户已停止执行"), toolCallRecords);
            }
            chatResponse.setExecution(task.summary());
            if (deliveryEligible(task))
                planService.noteDeliveryReturned(userId, conversationId, task.completedPlan());
            chatResponse.setConversationId(conversationId);
            return chatResponse;

        } catch (ModelNotConfiguredException e) {
            // 空库首次启动：不要把它当成“执行异常”，用户需要的是明确的配置指引。
            System.out.println("[Model] 未配置任何可用模型，已拒绝发送");
            task.end("MODEL_NOT_CONFIGURED");
            String errorMsg = e.getMessage();
            historyService.addMessage(userId, conversationId,
                    new MessageRecord("assistant", errorMsg));
            ChatResponse error = new ChatResponse("chat", errorMsg);
            error.setExecution(task.summary());
            return error;
        } catch (Exception e) {
            System.err.println("AI 调用出错: " + e.getMessage());
            e.printStackTrace();
            task.end("EXECUTION_ERROR");
            String errorMsg = task.fallback("执行异常，未确认交付");
            historyService.addMessage(userId, conversationId,
                    new MessageRecord("assistant", errorMsg));
            ChatResponse error = new ChatResponse("chat", errorMsg);
            error.setExecution(task.summary());
            return error;
        } finally {
            // 兜底：提前 return（强制请求异常之外的异常、轮数用满、取消…）也要留下汇总，
            // 否则"这次到底有没有规划、花了多少"就会在日志里消失。markOnce 保证正常路径不会重复打印。
            if (PlanSummaryFormatter.markOnce(summaryLogged)) {
                logSessionSummary(roundsUsed, firstCreatePlanRound, nudgeState, planInjectChars, totalPromptChars);
                logUsage(usage);
                logSubagentCost(conversationId, usage);
                logPromptCurve(promptCurve);
            }
            task.close();
            taskScope.close();
            System.out.println("[TaskExecution] " + task.summary());
            reconcileStepLedger(stepLedger, userId, conversationId);
            closePermissionScope(toolScope);
            WorkspaceContext.clear();
            ConversationContext.clear();
            // 只在本线程设置的模型上下文：不清会随着线程池复用串到别人的请求上
            SubagentContext.clear();
            // 条件注入的每请求缓存同理：线程池复用时不清理会带到下一轮请求上
            CONDITION_STATE.remove();
            TOOL_RESULT_SEQ.remove();
        }
    }

    // ============= SSE 流式输出：逐 token 返回最终回答 =============
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request, HttpServletRequest httpRequest,
                                 HttpServletResponse response) {
        // ⚠️ 这两条头是 SSE 的"防压缩/防缓冲"保险：
        // 中间层（dev-server 的 compress、nginx 等）一旦给响应加 Content-Encoding，压缩缓冲
        // 就会把逐字流攒成一大块，浏览器侧看到的就是"一次性蹦出来"。
        // no-transform 明确告诉任何中间层：不许转换（不许压缩）本响应。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        Long userId = (Long) httpRequest.getAttribute("userId");
        // ⚠️ 超时原来是 180 秒：实测这是"长任务做到一半界面就断了"的元凶 ——
        // 计划模式下任务动辄 5~10 分钟，3 分钟一到 AsyncRequestTimeoutException 触发、连接被掐断，
        // 前端文字停在半句，而后台循环还在继续跑（推送全进了死连接）。
        // 0 = 不设服务器侧超时；用户随时可以点"停止"主动结束。
        ChatStreamEmitter emitter = new ChatStreamEmitter();

        // 限流（与 /chat 一致）
        if (userId != null) {
            String limitMsg = rateLimiter.tryAcquire(userId);
            if (limitMsg != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", "error"); e.put("message", limitMsg);
                sendJson(emitter, "error", e);
                emitter.complete();
                return emitter;
            }
        }

        String userMessage = request.getMessage();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        if (!cancellationRegistry.tryArm(userId, conversationId)) {
            sendJson(emitter, "error", Map.of("type", "error", "message", "该会话已有请求正在执行，请等待或停止后再试。"));
            emitter.complete();
            return emitter;
        }
        boolean submitted = false;
        try {
            conversationService.touch(userId, conversationId, userMessage, request.getWorkspaceId(),
                    request.getPermissionMode());
            final Path workspace = workspaceService.currentRoot(userId, conversationId);
            WorkspaceContext.set(workspace);
            planService.revalidateFiles(userId, conversationId, null, null);
            planService.resetIfFinished(userId, conversationId);
            subagentService.beginUserRequest(conversationId);
            refreshConditionCache(userId, conversationId, userMessage);
            historyService.addMessage(userId, conversationId, new MessageRecord("user", userMessage));

            final String finalConversationId = conversationId;
            TaskExecution task = newTaskExecution(userId, finalConversationId, request.getModelConfigId());
            emitter.bind(() -> task.disconnect(() ->
                    approvalService.cancelConversation(userId, finalConversationId)));
            try (TaskExecution.Scope preparation = TaskExecution.attach(task)) {
                ToolCallback[] toolCallbacks = bindToolCallbacks(userId);
                ToolCallingChatOptions options = withTools(toolCallbacks);
                List<MessageRecord> loadedHistory = historyService.getHistory(userId, conversationId);
                // F1：先解析本次请求选中的模型，再装配历史（摘要调用用它，不用系统兜底模型）。
                final OpenAiChatModel activeModel = resolveModel(request, userId);
                final String summaryKey = summaryScope(userId, conversationId);
                List<Message> messages = workingSetEnabled || totalScope()
                        ? contextCompactor.assemble(loadedHistory, userMessage, summaryKey, activeModel)
                        : contextCompactor.buildMessages(loadedHistory, userMessage, activeModel);
                final ContextSourceLedger sourceLedger = newSourceLedger(conversationId, loadedHistory, userMessage);
                // M1：摘要覆盖到的原文在投影配对时跳过，避免摘要与同一段原文被当成两份信息。
                sourceLedger.markSummarized(contextCompactor.lastHistorySummary().coveredIds());
                // 本轮是否刚发生过上下文压缩：压缩会把旧对话换成 [历史摘要]，此时计划清单必须立刻全量重发
                final boolean contextCompacted = isCompacted(messages);
                // 立刻取走，避免异步线程里读到别的请求覆盖后的 lastStats
                final ContextCompactor.CompactStats compactStats = contextCompactor.lastStats();
                final ContextCompactor.SummaryUsage historySummary = contextCompactor.takeSummaryUsage();
                // 注入系统提示词（模型已在装配历史前解析，见上）
                final String baseSystemPrompt = prependSystemAndUserMemory(messages, userId);
                injectWorkspaceMemory(messages, workspace);

                // CONDITION_STATE 是 ThreadLocal：submit 到 streamExecutor 后必须在工作线程里恢复，
                // 否则 doStream 里 logOfferedTools → systemPrompt() → effectiveEnabled() 读到空状态
                // （条件注入场景下会把 promptHas 错判成 stripped，打出假阳性 WARN）。
                final ConditionState conditionSnapshot = CONDITION_STATE.get();
                // HTTP 线程用完即可摘掉；工作线程用快照恢复（避免占用 Tomcat 线程的 ThreadLocal）
                CONDITION_STATE.remove();
                streamExecutor.submit(() -> {
                    if (conditionSnapshot != null) {
                        CONDITION_STATE.set(conditionSnapshot);
                    }
                    try {
                        try (TaskExecution.Scope execution = TaskExecution.attach(task)) {
                        doStream(emitter, userId, finalConversationId, messages, options,
                                toolCallbacks, activeModel, workspace, contextCompacted, compactStats,
                                historySummary, baseSystemPrompt, sourceLedger);
                        }
                    } finally {
                        CONDITION_STATE.remove();
                        TOOL_RESULT_SEQ.remove();
                        task.close();
                        cancellationRegistry.clear(userId, finalConversationId);
                    }
                });
                submitted = true;
                return emitter;
            } catch (Exception failure) {
                task.close();
                String detail = failure.getMessage() == null ? "请求准备失败，未确认交付" : failure.getMessage();
                sendJson(emitter, "text", Map.of("type", "text", "content", detail));
                sendJson(emitter, "error", Map.of("type", "error", "message", detail));
                emitter.complete();
                return emitter;
            }
        } catch (RuntimeException failure) {
            sendJson(emitter, "error", Map.of("type", "error", "message", "请求准备失败，未确认交付"));
            emitter.complete();
            return emitter;
        } finally {
            WorkspaceContext.clear();
            CONDITION_STATE.remove();
            if (!submitted) cancellationRegistry.clear(userId, conversationId);
        }
    }

    public record DeliveryAcknowledgment(String conversationId, String receipt) {}

    @PostMapping("/delivery/ack")
    public Map<String, Boolean> acknowledgeDelivery(@RequestBody DeliveryAcknowledgment acknowledgment,
                                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String conversationId = acknowledgment.conversationId();
        if (userId == null || conversationId == null || acknowledgment.receipt() == null)
            return Map.of("accepted", false);
        try {
            WorkspaceContext.set(workspaceService.currentRoot(userId, conversationId));
            planService.revalidateFiles(userId, conversationId, null, null);
            return Map.of("accepted", planService.acknowledgeDelivery(userId, conversationId, acknowledgment.receipt()));
        } finally {
            WorkspaceContext.clear();
        }
    }

    /** 流式执行的真正逻辑：先阻塞循环处理工具，再用模型流式输出 逐 token 输出最终回答 */
    private void doStream(SseEmitter emitter, Long userId, String conversationId,
                          List<Message> messages, ToolCallingChatOptions options,
                          ToolCallback[] callbacks, OpenAiChatModel activeModel, Path workspace,
                          boolean contextCompacted, ContextCompactor.CompactStats compactStats,
                          ContextCompactor.SummaryUsage historySummary, String baseSystemPrompt,
                          ContextSourceLedger sourceLedger) {
        // 在异步线程里显式设置工作区上下文（ThreadLocal 不会自动跨线程传递）
        WorkspaceContext.set(workspace);
        // 会话上下文同理：计划工具靠它定位"当前会话的计划"
        ConversationContext.set(userId, conversationId);
        // 子代理工具靠它拿到"本轮用哪个模型"（流式线程是新线程，ThreadLocal 必须重设）
        // B1：子代理可用独立模型（subagent.model-id；留空 = 沿用主模型）。
        final OpenAiChatModel childModel = resolveSubagentModel(activeModel);
        SubagentContext.set(childModel);
        boolean toolsLogged = false;

        // ---- 汇总日志 / 真实用量统计的容器（与 /ai/chat 同策略：声明在 try 外，好让 finally 兜底）----
        PlanNudgeState nudgeState = new PlanNudgeState();
        DispatchSoftHint softHint = new DispatchSoftHint(subagentProperties.getSoftHintThresholdTokens());
        int roundsUsed = 0;
        int planInjectChars = 0;
        int totalPromptChars = 0;
        int firstCreatePlanRound = -1;
        final boolean[] summaryLogged = new boolean[1];
        final UsageTracker usage = new UsageTracker();
        RoundCompactor.Guard compactionGuard = new RoundCompactor.Guard();
        PromptCursor promptCursor = new PromptCursor();
        // 主 agent 每轮输入 token 的曲线（成本核算用）：取轮前/轮后的用量差值 ——
        // 一轮里可能发多次请求（强制 createPlan、流式分片、编译自愈重试），差值才是"这一轮真实吃进去多少"。
        // 声明在 try 外，finally 的兜底日志才打得到。
        final List<Long> promptCurve = new ArrayList<>();
        final ToolCallContext.Scope[] toolScope = new ToolCallContext.Scope[1];
        // 与非流式同一份口径：回调共享，轮次每轮开头写入，子代理并发时读到的仍是这一轮。
        final AtomicInteger approvalTurn = new AtomicInteger(-1);
        // 单次请求作用域：记录工具执行与步骤上报，请求结束只读对账，绝不修改计划状态。
        StepToolLedger stepLedger = new StepToolLedger(conversationId);
        TaskExecution task = TaskExecution.current();

        try {
            List<Message> msgs = new ArrayList<>(messages);
            toolScope[0] = openPermissionScope(userId, conversationId, true, emitter, msgs, approvalTurn);
            List<ChatResponse.ToolCallRecord> toolRecords = new ArrayList<>();

            // 每一轮都"边生成边推送"：文字 token 实时推给前端；本轮结束后若模型还要调工具就执行，
            // 然后进入下一轮。界面上就是「说一句 → 调工具 → 再接着说」交替出现，
            // 而不是像以前那样先把所有工具跑完、期间界面一片空白，最后才一次性把答案吐出来。
            int maxIterations = task.limit();
            int verifyRounds = 0;
            boolean filesModified = false;
            boolean finishedNaturally = false;
            boolean spinning = false;         // 模型在"原地打转"（连续几轮完全相同的工具调用）
            boolean cancelled = false;        // 用户点了"停止"
            boolean protocolStop = false;
            boolean[] budgetHold = {false};
            boolean budgetStop = false;
            LoopGuard.State repeatState = new LoopGuard.State();
            StringBuilder streamedAll = new StringBuilder();   // 累计推给前端的文字（落库用，保证与界面一致）
            // 已经**逐段推给前端**的正文（每轮开头清空、收尾轮再清空）。收尾时只补发"没推过"的那一段，
            // 否则逐段推送 + 末尾整段重发 = 界面上同一段文字出现两遍。
            LiveText live = new LiveText();
            PlanInjectState planInjectState = new PlanInjectState();   // 计划注入状态（slim 策略用）
            boolean budgetWarned = false; // 是否已给无计划任务注入过"预算将尽，请收尾"提醒
            java.util.Set<String> blockedSeen = new java.util.HashSet<>();   // 已推送过 blocked 的步骤
            // 进入本轮对话时就存在计划（比如上次被中断了继续做）→ 先把计划推给前端
            pushPlanEvent(emitter, userId, conversationId, 0,
                    task.limit());
            accountSummary(usage, userId, conversationId, 0, activeModel, historySummary, compactStats);
            TaskPlan awaitingDelivery = planService.get(userId, conversationId);
            if (awaitingDelivery != null && !awaitingDelivery.allDone()) awaitingDelivery = null;
            for (int i = 0; i < maxIterations; i++) {
                try {
                // 安全点 ①：每轮开头检查取消标志（"停止"按钮的第一时间响应点）
                if (task.cancelled()) {
                    cancelled = true;
                    System.out.println("[Cancel] 第 " + (i + 1) + " 轮开始前收到停止请求，退出循环");
                    break;
                }
                if (task.mustFinalize()) { task.end(task.approvalBlocked() ? "APPROVAL_BLOCKED" : "MODEL_CALL_RESERVE"); break; }
                roundsUsed = i + 1;
                approvalTurn.set(i);
                planInjectChars += injectPlan(msgs, planInjectState, userId, conversationId, i, contextCompacted);  // 改动5：累计注入字符数
                budgetWarned = warnExecutionBudget(msgs, task, budgetWarned);
                // HARD 强制轮：**必须先于 applyPlanNudge 消费**（否则本轮信号会把 forceRound 覆盖成再下一轮）
                boolean forceThisRound = PlanNudgePolicy.consumeForceRound(i, nudgeState.forceRound);
                // 漂移信号 + 分级提醒（只在"还没有计划"时生效；信号 0 条 → 完全不打扰）
                applyPlanNudge(msgs, nudgeState, toolRecords, userId, conversationId, i, workspace, emitter);
                applySoftHint(msgs, softHint);
                logMsgBudget(i, msgs);
                StringBuilder roundText = new StringBuilder();   // 本轮推给前端的文字
                live.reset();   // 逐段推送的记账按"轮"重置：收尾只补发本轮之后新增的部分
                // 轮与轮之间空一行：否则多轮叙述会粘成一大坨（界面上和刷新后的历史里都是）
                if (streamedAll.length() > 0) {
                    streamedAll.append("\n\n");
                    Map<String, Object> br = new LinkedHashMap<>();
                    br.put("type", "text");
                    br.put("content", "\n\n");
                    sendJson(emitter, "text", br);
                }
                // 流式分片里的工具调用是「按 index 拆开」下发的：第一个分片带 id+name，后续分片只带参数片段。
                // Spring AI 转成 AssistantMessage.ToolCall 后 index 丢了，所以用「id 变化 = 新的工具调用」来累积。
                List<String> tcIds = new ArrayList<>();
                List<String> tcNames = new ArrayList<>();
                List<StringBuilder> tcArgs = new ArrayList<>();

                // HARD 强制轮：若本轮被判为该强制调用 createPlan，就改走**非流式**发起
                // （便于捕获服务端对 tool_choice 的拒绝并立刻回退；流式一旦开始吐字就没法撤回了）。
                // 代价：强制的那一轮没有逐字效果 —— 对"强制规划"这种一次性动作可以接受。
                boolean forceHandled = false;
                // 本轮真正下发给模型的清单（deferred 子集 / stripWriteTools 子集）；执行时也用它，
                // 否则"被摘掉的工具"仍会被执行（实测漏洞）。与非流式共用 computeRoundCallbacks。
                ToolCallback[] roundCallbacks = computeRoundCallbacks(
                        callbacks, msgs, forceThisRound, conversationId, userId, emitter);
                guardRound(msgs, roundCallbacks, usage.getPromptTokens(), i + 1, maxIterations, budgetHold);
                if (budgetHold[0]) { budgetStop = true; break; }
                if (!toolsLogged) {
                    logOfferedTools(roundCallbacks);
                    toolsLogged = true;
                }
                options = withTools(roundCallbacks);
                ContextCompactor.CompactStats totalRound = compactTotalIfEnabled(
                        msgs, roundCallbacks, i, compactionGuard, userId, conversationId, activeModel, usage);
                long promptTokensBefore = usage.getPromptTokens();
                long completionTokensBefore = usage.getCompletionTokens();
                int responsesBefore = usage.getResponsesWithUsage();
                PromptView view = promptCursor.observe(this, sourceLedger, msgs, roundCallbacks, i, true, false,
                        userId, conversationId);
                if (stoppedForHardWindow(view, emitter)) return;
                if (rejectOverWindow(view.messages(), roundCallbacks, historySummary, emitter, totalRound)) return;
                if (refuseBrokenProtocol(view.messages())) {
                    protocolStop = true;
                    finishedNaturally = true;
                    break;
                }
                if (forceThisRound) {
                    ToolCallingChatOptions forced = forcedPlanOptions(roundCallbacks);
                    if (forced != null) {
                        try {
                            org.springframework.ai.chat.model.ChatResponse fr =
                                    com.example.jingjingai2.agent.TaskModelCalls.call(activeModel, new Prompt(view.messages(), forced));
                            usage.accept(fr);   // 真实用量（只读观测）
                            logForceOutcome("流式", fr);
                            AssistantMessage fam = (fr == null || fr.getResult() == null)
                                    ? null : fr.getResult().getOutput();
                            if (fam != null && fam.getText() != null && !fam.getText().isEmpty()) {
                                roundText.append(fam.getText());

                            }
                            List<AssistantMessage.ToolCall> ftcs = (fam == null || fam.getToolCalls() == null)
                                    ? new ArrayList<>() : fam.getToolCalls();
                            for (AssistantMessage.ToolCall tc : ftcs) {
                                tcIds.add(tc.id() == null ? "" : tc.id());
                                tcNames.add(tc.name() == null ? "" : tc.name());
                                tcArgs.add(new StringBuilder(tc.arguments() == null ? "" : tc.arguments()));
                            }
                            forceHandled = true;
                        } catch (Exception fx) {
                            System.out.println("[PlanNudge] 强制 tool_choice 请求异常，回退普通流式请求: "
                                    + fx.getMessage());
                            nudgeState.forceFailed = true;
                        }
                    }
                }

                totalPromptChars += promptChars(msgs);   // 改动5：统计本轮 prompt 规模（估算规划占比用）

                if (!forceHandled) {
                final ToolCallback[] diagRound = roundCallbacks;
                final List<Message> diagMsgs = msgs;
                final boolean[] diagLogged = {false};
                try {
                    com.example.jingjingai2.agent.TaskModelCalls.stream(activeModel, new Prompt(view.messages(), options))
                            .timeout(Duration.ofSeconds(120))
                            .toIterable()
                            .forEach(chunk -> {
                                usage.accept(chunk);   // 真实用量（只读观测；流式下多数分片不带，带了就累计）
                                if (!diagLogged[0] && chunk.getMetadata() != null
                                        && chunk.getMetadata().getUsage() != null
                                        && chunk.getMetadata().getUsage().getPromptTokens() != null
                                        && chunk.getMetadata().getUsage().getPromptTokens() > 0) {
                                    diagLogged[0] = true;
                                    logRoundDiag(chunk, diagMsgs, diagRound);
                                }
                                // 安全点 ②：流式生成过程中被取消 → 立刻停止累积与推送（不再往界面上吐字）
                                if (task.cancelled()) return;
                                Generation cg = chunk.getResult();
                                if (cg == null || cg.getOutput() == null) return;

                                // ① 文字：**边生成边推给前端** —— 这才是真正"一个字一个字"往外冒。
                                //    只累积不推送（之前那一版）会让整段回答攒到最后一次性出现。
                                //    LiveText 里有保留窗口：先确认不是伪工具调用再推。
                                String t = cg.getOutput().getText();
                                if (t != null && !t.isEmpty()) {
                                    roundText.append(t);
                                    live.accept(emitter, t, roundText);
                                }

                                // ② 工具调用：累积分片（id 非空 = 一个新的工具调用开始）
                                List<AssistantMessage.ToolCall> parts = cg.getOutput().getToolCalls();
                                if (parts == null) return;
                                for (AssistantMessage.ToolCall pc : parts) {
                                    boolean isNew = pc.id() != null && !pc.id().isEmpty();
                                    if (isNew || tcArgs.isEmpty()) {
                                        tcIds.add(pc.id() == null ? "" : pc.id());
                                        tcNames.add(pc.name() == null ? "" : pc.name());
                                        tcArgs.add(new StringBuilder(pc.arguments() == null ? "" : pc.arguments()));
                                    } else {
                                        int lastIdx = tcArgs.size() - 1;
                                        if (pc.arguments() != null) tcArgs.get(lastIdx).append(pc.arguments());
                                        if (tcNames.get(lastIdx).isEmpty() && pc.name() != null && !pc.name().isBlank())
                                            tcNames.set(lastIdx, pc.name());
                                        if (tcIds.get(lastIdx).isEmpty() && pc.id() != null && !pc.id().isEmpty())
                                            tcIds.set(lastIdx, pc.id());
                                    }
                                }
                            });
                } catch (Exception streamEx) {
                    if (streamEx instanceof TaskExecution.BudgetExceeded exhausted) throw exhausted;
                    if (streamEx instanceof TaskExecution.Cancelled stopped) throw stopped;
                    task.end("MODEL_ERROR");
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("type", "error");
                    e.put("message", task.fallback("流式生成失败，未确认交付"));
                    sendJson(emitter, "text", Map.of("type", "text", "content", task.fallback("流式生成失败，未确认交付")));
                    sendJson(emitter, "error", e);
                    emitter.complete();
                    return;
                }
                }   // end if (!forceHandled)

                // 本轮文字结束：把保留窗口里压着的尾巴推出去（否则最后几十个字要等下一轮才出现）
                live.flush(emitter);

                // 安全点 ③：本轮流式刚结束就被取消 → 不要再执行这一轮解析出的工具，直接退出
                if (task.cancelled()) {
                    cancelled = true;
                    System.out.println("[Cancel] 本轮生成结束后收到停止请求，不再执行工具");
                    break;
                }

                List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
                task.modelRound();
                for (int k = 0; k < tcArgs.size(); k++) {
                    String cid = tcIds.get(k);
                    tcs.add(new AssistantMessage.ToolCall(cid == null || cid.isEmpty() ? "call_" + k : cid,
                            "function", tcNames.get(k), tcArgs.get(k).toString()));
                }
                // 改动5：记录 createPlan 首次被调用的轮号（含 HARD 强制那一轮）
                if (firstCreatePlanRound < 0 && tcs.stream()
                        .anyMatch(tc -> "createPlan".equalsIgnoreCase(tc.name()))) {
                    firstCreatePlanRound = i;
                }
                // 兜底：这一轮"什么都没拿到"，或工具调用明显不完整（名字为空 / 参数不是合法 JSON）时，
                // 改用**非流式**再调一次（成功的话用户无感）。文字已经流式推过就不再重推，避免重复。
                boolean badTools = tcs.stream().anyMatch(tc -> tc.name() == null || tc.name().isBlank()
                        || !looksLikeJson(tc.arguments()));
                if ((roundText.length() == 0 && tcs.isEmpty()) || badTools) {
                    System.out.println("[Stream] 第 " + (i + 1) + " 轮流式未拿到可用内容（文字 "
                            + roundText.length() + " 字 / 工具 " + tcs.size() + " 个），改用非流式兜底重取");
                    try {
                        org.springframework.ai.chat.model.ChatResponse fb =
                                com.example.jingjingai2.agent.TaskModelCalls.call(activeModel, new Prompt(view.messages(), options));
                        usage.accept(fb);   // 真实用量（只读观测）
                        if (fb != null && fb.getResult() != null && fb.getResult().getOutput() != null) {
                            AssistantMessage fam = fb.getResult().getOutput();
                            tcs = fam.getToolCalls() == null
                                    ? new ArrayList<>() : new ArrayList<>(fam.getToolCalls());
                            if (roundText.length() == 0 && fam.getText() != null && !fam.getText().isEmpty()) {
                                roundText.append(fam.getText());

                            }
                        }
                    } catch (TaskExecution.BudgetExceeded | TaskExecution.Cancelled terminal) {
                        throw terminal;
                    } catch (Exception fbEx) {
                        System.out.println("[Stream] 非流式兜底也失败: " + fbEx.getMessage());
                    }
                    if (roundText.length() == 0 && tcs.isEmpty()) {
                        if (i == 0) continue;   // 第一轮就空 → 再试一次
                        break;
                    }
                }

                // 本轮"完整消息"：文字取本轮真实推出去的 roundText（Spring AI 聚合出的消息里文字常为空）
                AssistantMessage am = new AssistantMessage(roundText.toString(), Map.of(), tcs);
                // 记录这一轮：模型（流式或非流式兜底）已经返回，工具还没执行。
                // 流式分片经常不带 usage，usage 没有增长时 input/output 记 NULL。
                recordTurn(conversationId, userId, i, activeModel, roundCallbacks, msgs,
                        baseSystemPrompt,
                        totalRound != null ? totalRound : compactStats, totalRound != null,
                        responsesBefore, promptTokensBefore, completionTokensBefore, usage,
                        tcs.isEmpty() ? AgentTurnRecord.END_NORMAL_STOP
                                      : AgentTurnRecord.END_TOOL_CALLING,
                        compactionGuard, null);
                System.out.println("[Stream] 第 " + (i + 1) + " 轮：文字 " + roundText.length() + " 字，工具调用 "
                        + tcs.size() + " 个"
                        + (tcs.isEmpty() ? "" : " -> " + tcs.stream()
                                .map(AssistantMessage.ToolCall::name).toList()));
                if (tcs.isEmpty()) {
                    // 没有任何工具调用、而且一个字都没吐过 → 这是"空回答"，不能当成正常作答。
                    // （注意：不要用 am.getText() 判断 —— MessageAggregator 聚合出的消息里文字是空的，
                    //   只有分片回调里的文字才是真的，所以以 streamedAll 为准）
                    planService.revalidateFiles(userId, conversationId, null, null);
                    if (!task.acceptableAnswer(roundText.toString(), () -> planService.stepsOf(userId, conversationId))) {
                        System.out.println("[Stream] 第 " + (i + 1) + " 轮：无工具调用且无任何文字，视为空回答");
                        task.end("INVALID_ANSWER");
                        break;
                    }
                    // 轮到最终回答。若本轮改过文件，先自动编译校验一次（失败则回灌报错让它继续修）
                    if (filesModified && !task.cancelled()) {
                        CodeVerifier.Result vr = verifyForDelivery(workspace, userId, conversationId, toolRecords, i, emitter);

                        if (vr.ran() && !vr.success()) {
                            if (verifyRounds < MAX_VERIFY_ROUNDS) {
                                verifyRounds++;
                                msgs.add(am);
                                msgs.add(new UserMessage(verifyFeedback(vr.output(), verifyRounds)));
                                continue;
                            }
                            task.end("VERIFY_FAILED");
                            roundText.setLength(0);
                            roundText.append(task.fallback(describeVerify(vr)));
                        }
                    }
                    finishedNaturally = true;
                    streamedAll.setLength(0);
                    streamedAll.append(roundText);
                    if (!"VERIFY_FAILED".equals(task.summary().get("endReason"))) {
                        task.end("NORMAL_STOP");
                        task.delivered();
                    }
                    if (budgetHold[0]) budgetStop = true;
                    // 收尾轮也记入曲线：否则「一轮工具 + 一轮作答」时峰值只等于首轮基线，低估真实上下文
                    promptCurve.add(Math.max(0, usage.getPromptTokens() - promptTokensBefore));
                    break; // 轮到最终回答
                }

                sendJson(emitter, "text", Map.of("type", "text", "content", "\n正在处理工具请求，尚未确认交付。\n"));
                String repeatSig = LoopGuard.signature(tcs);
                LoopGuard.Decision repeat = LoopGuard.observe(repeatState, repeatSig);
                LoopGuard.apply(repeatState, repeat);
                if (repeat.action == LoopGuard.Action.STOP) {
                    System.out.println("[Guard] 触发=原地打转 " + repeatSig);
                    spinning = true;
                    break;
                }
                if (repeat.action == LoopGuard.Action.HINT) {
                    System.out.println("[Guard] 触发=重复调用提示 " + repeatSig);
                    pushRepeatHint(msgs, am, tcs);
                    continue;
                }
                if (budgetHold[0]) {
                    budgetStop = true;
                    finishedNaturally = true;
                    break;
                }

                msgs.add(am);
                Map<String, SubagentService.SpawnOutcome> spawned = new java.util.HashMap<>();
                java.util.Set<String> batched = new java.util.HashSet<>();
                for (int spawnIndex = 0; spawnIndex < tcs.size(); spawnIndex++) {
                    AssistantMessage.ToolCall tc = tcs.get(spawnIndex);
                    // 安全点 ④：每个工具执行前检查取消标志（避免"点了停止还接着跑工具"）
                    if (task.cancelled()) {
                        cancelled = true;
                        System.out.println("[Cancel] 工具执行前收到停止请求: " + tc.name());
                        break;
                    }
                    int planVersion = planService.versionOf(userId, conversationId);
                    if (!batched.contains(tc.id()) && subagentService.isReadOnlySpawn(tc, conversationId)) {
                        List<AssistantMessage.ToolCall> run =
                                consecutiveReadOnlySpawns(tcs, spawnIndex, conversationId);
                        for (AssistantMessage.ToolCall one : run) batched.add(one.id());
                        spawned.putAll(runSpawnBatch(run, childModel, conversationId,
                                (event, payload) -> sendJson(emitter, event, payload), roundCallbacks));
                    }
                    SubagentService.SpawnOutcome pre = spawned.get(tc.id());
                    if (pre == null) {
                        // 工具开跑前先推一个 tool_start 事件：让前端能"边跑边显示正在调用什么、在干什么"，
                        // 而不是等工具全跑完才一次性出现（Codex / WorkBuddy 那种体验）。
                        // spawnSubagent 不走这里 —— 它的进度由 subagent_start / subagent_done 两张卡负责。
                        Map<String, Object> startEvent = new LinkedHashMap<>();
                        startEvent.put("type", "tool_start");
                        startEvent.put("toolName", tc.name());
                        startEvent.put("input", SensitiveRedactor.redact(tc.arguments()));
                        sendJson(emitter, "tool_start", startEvent);
                    }

                    // 执行 + 记账与非流式**共用同一处**实现（executeAndRecordTool）：
                    // success 由结构化执行结论决定，两条链路口径不会再分叉。
                    ChatResponse.ToolCallRecord rec = executeAndRecordTool(
                            roundCallbacks, tc, conversationId, msgs, pre, userId, i, stepLedger, toolRecords);

                    // implement 子代理同样能改工作区；GP SUCCEEDED 未降级时跳过父循环 javac
                    if ((rec.isSuccess() || pre != null) && shouldFlagFilesModified(tc, rec.getResult(), conversationId)) {
                        filesModified = true;
                    }

                    if (pre == null) {
                        Map<String, Object> tp = new LinkedHashMap<>();
                        tp.put("type", "tool");
                        tp.put("toolName", tc.name());
                        tp.put("toolCalls", toolRecords);
                        sendJson(emitter, "tool", tp);
                    }

                    // 计划类工具执行完 → 推一次全量计划；若有步骤变成 blocked → 推"需人工介入"事件
                    if (isPlanTool(tc.name()) || planVersion != planService.versionOf(userId, conversationId)) {
                        pushPlanEvent(emitter, userId, conversationId, i + 1, task.limit());
                        pushBlockedEvents(emitter, userId, conversationId, blockedSeen);
                    }
                }
                // 这一轮主 agent 吃进去的输入 token（轮末结算，含本轮所有模型调用）
                promptCurve.add(Math.max(0, usage.getPromptTokens() - promptTokensBefore));
                if (cancelled) break;   // 工具循环里被取消 → 外层循环也退出
                if (budgetHold[0]) {
                    budgetStop = true;
                    break;
                }

                // ⭐ harness 层的强制收尾：计划里所有步骤都 done 了 → 立刻跳出循环，
                //   下一轮禁用工具，逼模型只能输出文字总结（不再靠提示词"劝"它收尾）
                if (planService.allStepsDone(userId, conversationId)
                        && (awaitingDelivery == null || task.completedPlan() != null
                            || planService.get(userId, conversationId) != awaitingDelivery)) {
                    task.end("PLAN_REPORTED_DONE");
                    System.out.println("[Plan] 计划全部完成 → 进入禁用工具的收尾轮");
                    Map<String, Object> note = new LinkedHashMap<>();
                    note.put("type", "text");
                    note.put("content", "\n\n计划已上报完成，正在核对并整理交付…\n");
                    sendJson(emitter, "text", note);
                    break;
                }
                } catch (TaskExecution.Cancelled stopped) { cancelled = true; break; }
                catch (TaskExecution.BudgetExceeded exhausted) { task.end("MODEL_BUDGET"); break; }
            }

            // ===== 强制收尾轮：不带任何工具，只让它说话 =====
            cancelled = cancelled || task.cancelled();
            if (!finishedNaturally && !cancelled && !protocolStop) {
                if ("RUNNING".equals(task.summary().get("endReason"))) task.end(spinning ? "REPEAT_GUARD" : budgetStop ? "INPUT_BUDGET" : "ROUND_LIMIT");
                if (task.approvalBlocked()) task.end("APPROVAL_BLOCKED");
                String reason = task.approvalBlocked() ? "审批阻塞，工具未执行" : budgetStop ? "累计输入预算不足" : "执行阶段结束";
                live.reset();   // 收尾轮重新记账：这一段正文同样是逐段推的
                String delivered = completeExecution(activeModel, msgs, task, usage, reason, budgetStop,
                        filesModified, workspace, userId, conversationId, toolRecords, roundsUsed, emitter,
                        sourceLedger, promptCursor, live);
                streamedAll.setLength(0);
                streamedAll.append(delivered);
                finishedNaturally = true;
            }

            cancelled = cancelled || task.cancelled();
            String finalText = streamedAll.toString();
            if (cancelled || protocolStop) {
                task.end(cancelled ? "CANCELLED" : "PROTOCOL_ERROR");
                finalText = task.fallback(cancelled ? "用户已停止执行" : PROTOCOL_STOP_NOTE);
            }
            // ===== 收尾诊断：排查"没有总结/不完整"问题统一从这里看 =====
            String exitReason = cancelled ? "CANCELLED" : protocolStop ? "PROTOCOL_ERROR" : String.valueOf(task.summary().get("endReason"));
            // 与非流式同一口径：只有「轮数预算用满」补记，空响应、取消、打转、预算、收尾都不记。
            if ("ROUND_LIMIT".equals(task.summary().get("endReason"))) {
                recordIterationCap(conversationId, userId, roundsUsed, activeModel);
            }
            String tailPreview = finalText.length() > 120
                    ? "…" + finalText.substring(finalText.length() - 120)
                    : finalText;
            System.out.println("[Finish] 结束原因=" + exitReason
                    + " | 轮数=" + roundsUsed + "/" + maxIterations
                    + " | 累计输入token=" + usage.getPromptTokens()
                    + " | 累计文字=" + streamedAll.length() + " 字"
                    + " | 工具调用=" + toolRecords.size() + " 次");
            System.out.println("[Finish] 最终文本长度=" + finalText.length() + " 字，结尾预览=「" + tailPreview + "」");

            historyService.addMessage(userId, conversationId,
                    new MessageRecord("assistant", finalText));

            // 结论出来了（模型自然收尾 / 计划全部完成）→ 释放本会话的子代理便条与重派计数
            if (finishedNaturally) {
                subagentService.releaseOnConclusion(conversationId);
            }

            // 单独推一个 stopped 事件：让前端知道"这不是正常答完，是被用户停下来的"（可加不同的提示样式）
            if (cancelled) {
                Map<String, Object> se = new LinkedHashMap<>();
                se.put("type", "stopped");
                se.put("conversationId", conversationId);
                sendJson(emitter, "stopped", se);
            }

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", "done");
            d.put("conversationId", conversationId);
            // 正常路径：在 done **之前**打印（前端收到 done 就能肯定日志已经落盘）
            if (PlanSummaryFormatter.markOnce(summaryLogged)) {
                logSessionSummary(roundsUsed, firstCreatePlanRound, nudgeState, planInjectChars, totalPromptChars);
                logUsage(usage);
                logSubagentCost(conversationId, usage);
                logPromptCurve(promptCurve);
            }
            if (task.cancelled()) {
                cancelled = true;
                task.end("CANCELLED");
                finalText = task.fallback("用户已停止执行");
            }
            // 正文在生成过程中已经逐段推给前端了，这里只补发"还没推过"的那一段：
            // 正常情况下就是空串（不再整段重发）；被替换成停止/兜底说明时才是新的一段。
            String unsent = unsentTail(finalText, live.pushed());
            if ((unsent.isEmpty() || sendJson(emitter, "text", Map.of("type", "text", "content", unsent)))
                    && deliveryEligible(task)) {
                String receipt = planService.offerDeliveryReceipt(userId, conversationId, task.completedPlan());
                if (receipt != null && !task.cancelled()) d.put("deliveryReceipt", receipt);
            }
            if (task.cancelled()) {
                cancelled = true;
                task.end("CANCELLED");
                task.fallback("用户已停止执行");
                d.remove("deliveryReceipt");
            }
            d.put("execution", task.summary());
            d.put("stopped", cancelled);
            d.put("toolCalls", toolRecords.isEmpty() ? null : toolRecords);
            putContextUsage(d, promptCurve);
            sendJson(emitter, "done", d);
            task.close();
            emitter.complete();
        } catch (Exception e) {
            Map<String, Object> er = new LinkedHashMap<>();
            task.end("EXECUTION_ERROR");
            String failureText = task.fallback("执行异常，未确认交付");
            er.put("type", "error"); er.put("message", failureText);
            sendJson(emitter, "text", Map.of("type", "text", "content", failureText));
            historyService.addMessage(userId, conversationId, new MessageRecord("assistant", failureText));
            sendJson(emitter, "error", er);
            emitter.complete();
        } finally {
            // 兜底：提前 return（流式异常、高危命令待审批）或外层异常时，保证汇总与用量仍被记录
            if (PlanSummaryFormatter.markOnce(summaryLogged)) {
                logSessionSummary(roundsUsed, firstCreatePlanRound, nudgeState, planInjectChars, totalPromptChars);
                logUsage(usage);
                logSubagentCost(conversationId, usage);
                logPromptCurve(promptCurve);
            }
            System.out.println("[TaskExecution] " + task.summary());
            reconcileStepLedger(stepLedger, userId, conversationId);
            closePermissionScope(toolScope);
            WorkspaceContext.clear();
            ConversationContext.clear();
            // 只在本线程设置的模型上下文：不清会随着线程池复用串到别的请求上
            SubagentContext.clear();
            // 条件注入的每请求缓存同理：线程池复用时不清理会带到下一轮请求上
            CONDITION_STATE.remove();
            TOOL_RESULT_SEQ.remove();
        }
    }

    /**
     * 解析本次对话要使用的模型：
     *  - 前端明确选择系统模型 → 使用 yml 中的系统 DeepSeek；
     *  - 前端传了 modelConfigId → 优先使用用户明确选择的模型；
     *  - 未传 modelConfigId → 使用数据库中该用户设为默认的模型；
     *  - 用户没有可用配置时 → 回退到系统默认模型。
     * 这样“多模型可配置”与“默认兜底”并存，向后兼容、也避免用户没配模型时对话直接挂掉。
     */
    /**
     * B1：子代理用哪个模型。
     *
     * <p>默认（{@code subagent.model-id} 为空）→ 原样返回主模型，行为与本改动之前**完全一致**。
     * 配了模型名 → 用**同一个服务商的 endpoint**（系统配置里的 base-url / api-key）构建一个
     * 只换了 model 名的 ChatModel —— 这正是"给子代理换一个更便宜的档位"的用法，
     * 不需要查数据库里的 ModelConfig（子代理的活是重复读文件，同一个 key 换个便宜模型就行）。
     *
     * <p>构建失败一律**回退主模型**并打日志：宁可多花钱，不可让子代理因为配置写错而全挂。
     */
    private OpenAiChatModel resolveSubagentModel(OpenAiChatModel fallback) {
        String modelId = subagentProperties.getModelId();
        if (modelId == null || modelId.isBlank()) return fallback;
        try {
            ModelConfig cfg = new ModelConfig();
            cfg.setModelName(modelId.trim());
            cfg.setBaseUrl(sysOpenAiBaseUrl);
            cfg.setApiKey(sysOpenAiApiKey);
            OpenAiChatModel m = chatModelFactory.build(cfg);
            System.out.println("[SubagentModel] 子代理使用独立模型=" + modelId.trim());
            return m;
        } catch (Exception e) {
            System.err.println("[SubagentModel] 构建子代理模型失败，回退主模型: " + e.getMessage());
            return fallback;
        }
    }

    @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.api-key:}")
    private String systemApiKey = "";

    private OpenAiChatModel resolveModel(ChatRequest request, Long userId) {
        if (request.isUseSystemModel()) {
            // 系统模型 = 真的配置了 spring.ai.openai.api-key。没配时 AiConfig 里那个
            // 指向 http://127.0.0.1 的占位 ChatModel 绝不能被当成可用模型。
            requireSystemModel();
            return chatModel;
        }
        if (userId != null) {
            try {
                ModelConfig cfg = request.getModelConfigId() == null
                        ? modelConfigService.getDefaultDecrypted(userId)
                        : modelConfigService.getDecrypted(request.getModelConfigId(), userId);
                if (cfg != null) {
                    return chatModelFactory.build(cfg);
                }
            } catch (Exception e) {
                System.err.println("[Model] 动态构建模型失败，回退默认: " + e.getMessage());
            }
        }
        requireSystemModel();
        return chatModel;
    }

    /** 系统模型不可用时抛专用异常，让聊天入口把“请先配置模型”原样交给用户。 */
    private void requireSystemModel() {
        if (systemApiKey == null || systemApiKey.isBlank()) {
            throw new ModelNotConfiguredException();
        }
    }

    private boolean warnExecutionBudget(List<Message> messages, TaskExecution task, boolean warned) {
        if (warned || task.remainingExecutionCalls() > 2) return warned;
        messages.add(new SystemMessage("本次剩余 " + task.remainingExecutionCalls()
                + " 次执行模型调用。请优先完成关键步骤，剩余事项可在后续请求继续。"));
        return true;
    }

    private static final int CONSERVATIVE_CONTEXT_WINDOW = 128_000;

    private TaskExecution newTaskExecution(Long userId, String conversationId, Long modelConfigId) {
        int initial = planService.budget(userId, conversationId, MAX_ITERATIONS);
        int configured = modelConfigService.contextWindowOf(userId, modelConfigId);
        int window = configured > 0 ? configured : CONSERVATIVE_CONTEXT_WINDOW;
        TaskExecution task = new TaskExecution(initial > 0 ? initial : MAX_ITERATIONS, taskInputTokenBudget, window);
        task.watchCancellation(() -> cancellationRegistry.isCancelled(userId, conversationId));
        return task;
    }

    /** 当前请求选定模型的窗口。没配模型时用 128000，不用全局 MCP 的 100 万。 */
    private int activeWindow() {
        TaskExecution task = TaskExecution.current();
        if (task != null && task.window() > 0) return task.window();
        return CONSERVATIVE_CONTEXT_WINDOW;
    }

    private boolean deliveryEligible(TaskExecution task) {
        return task.completedPlan() != null && !task.approvalBlocked() && !task.cancelled()
                && "TEXT_RETURNED".equals(task.summary().get("deliveryStatus"));
    }

    private String completeExecution(OpenAiChatModel model, List<Message> messages, TaskExecution task,
                                     UsageTracker usage, String reason, boolean budgetStop,
                                     boolean filesModified, Path workspace, Long userId, String conversationId,
                                     List<ChatResponse.ToolCallRecord> records, int roundsUsed, SseEmitter emitter,
                                     ContextSourceLedger sourceLedger, PromptCursor promptCursor,
                                     LiveText live) {
        if (filesModified && !task.cancelled()) {
            CodeVerifier.Result check = verifyForDelivery(workspace, userId, conversationId, records,
                    roundsUsed > 0 ? roundsUsed - 1 : null, emitter);
            if (!check.ran() && !check.success()) {
                if (!task.approvalBlocked()) task.end("VERIFY_UNVERIFIED");
                return task.fallback(describeVerify(check));
            }
            if (check.ran() && !check.success()) {
                if (!task.approvalBlocked()) task.end("VERIFY_FAILED");
                return task.fallback(describeVerify(check));
            }
        }
        if (task.cancelled()) return task.fallback("用户已停止执行");
        if (budgetStop) return task.fallback(reason);
        planService.revalidateFiles(userId, conversationId, null, null);
        return finalizeDelivery(model, messages, task, usage, emitter, reason, userId, conversationId,
                sourceLedger, promptCursor, live);
    }

    private CodeVerifier.Result verifyForDelivery(Path workspace, Long userId, String conversationId,
                                                   List<ChatResponse.ToolCallRecord> records,
                                                   Integer round, SseEmitter emitter) {
        if (TaskExecution.current().cancelled()) return CodeVerifier.Result.skipped("请求已停止");
        CodeVerifier.Result result = runVerifyCompile(workspace, userId, conversationId, records, round);
        if (emitter != null)
            sendJson(emitter, "tool", Map.of("type", "tool", "toolName", "verifyCompile", "toolCalls", records));
        return result;
    }

    private String finalizeDelivery(OpenAiChatModel model, List<Message> messages, TaskExecution task,
                                    UsageTracker usage, SseEmitter emitter, String reason,
                                    Long userId, String conversationId, ContextSourceLedger sourceLedger,
                                    PromptCursor promptCursor, LiveText live) {
        final boolean streaming = emitter != null;
        task.beginFinal(reason);
        if (refuseBrokenProtocol(PromptView.of(messages).messages())) return task.fallback(PROTOCOL_STOP_NOTE);
        messages.add(new SystemMessage("执行阶段已经结束。请直接提供用户要求的完整正文；禁止调用工具或更新计划。"
                + "计划DONE和先前的执行承诺不是实际交付证据。文件只有成功执行证据才能称已写入。"
                + "若文件未写入，可提供正文草稿但必须说明未落地；缺信息则明确缺项，不编造。原因：" + reason));
        PromptView view = promptCursor.observe(this, sourceLedger, messages, new ToolCallback[0],
                task.summary().get("modelRounds") instanceof Integer rounds ? rounds : -1,
                streaming, true, userId, conversationId);
        if (stoppedForHardWindow(view, null)) return task.fallback(HARD_WINDOW_STOP);
        ToolCallingChatOptions noTools = ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();
        try {
            StringBuilder answer = new StringBuilder();
            if (streaming) {
                TaskModelCalls.stream(model, new Prompt(view.messages(), noTools)).timeout(Duration.ofSeconds(120))
                        .toIterable().forEach(chunk -> {
                            usage.accept(chunk);
                            if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                                AssistantMessage output = chunk.getResult().getOutput();
                                if (output.getToolCalls() != null && !output.getToolCalls().isEmpty())
                                    throw new IllegalStateException("最终回答仍请求工具");
                                String piece = output.getText();
                                if (piece != null && !piece.isEmpty()) {
                                    answer.append(piece);
                                    // 最终回答也是流式产出：边生成边推，不要攒到最后一次性发
                                    if (live != null) live.accept(emitter, piece, answer);
                                }
                            }
                        });
                if (live != null) live.flush(emitter);   // 把保留窗口里压着的尾巴推出去
            } else {
                org.springframework.ai.chat.model.ChatResponse response = TaskModelCalls.call(model, new Prompt(view.messages(), noTools));
                usage.accept(response);
                if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                    AssistantMessage output = response.getResult().getOutput();
                    if (output.getToolCalls() != null && !output.getToolCalls().isEmpty())
                        throw new IllegalStateException("最终回答仍请求工具");
                    if (output.getText() != null) answer.append(output.getText());
                }
            }
            if (task.cancelled()) return task.fallback("用户已停止执行");
            String answerText = answer.toString();
            if (!task.acceptableAnswer(answerText, () -> planService.stepsOf(userId, conversationId))) {
                System.out.println("[Delivery] 最终回答未通过产物核对，长度=" + answerText.length()
                        + " 全文=「" + answerText.replace('\n', ' ') + "」");
                return task.fallback("最终回答为空、格式无效或缺少产物证据");
            }
            task.delivered();
            // 轮数用满时补一句"怎么接着做"：这句话对用户可见，所以流式链路也要推出去，
            // 不能只拼在返回值里（那边正文已经逐段推完了）。
            String note = "ROUND_LIMIT".equals(task.summary().get("endReason")) ? ROUND_LIMIT_NOTE : "";
            if (!note.isEmpty() && streaming) sendJson(emitter, "text", Map.of("type", "text", "content", note));
            return answer + note
                    + (task.approvalBlocked() ? "\n\n执行状态：存在审批阻塞，受阻工具未执行；上述正文不代表文件已落地。" : "");
        } catch (Exception failure) {
            System.err.println("[Delivery] 最终回答失败: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            return task.fallback(failure instanceof TaskExecution.BudgetExceeded ? failure.getMessage() : "最终回答失败");
        }
    }

    // ============= 任务计划（Plan-and-Execute）相关的辅助方法 =============

    /**
     * 一次请求内的"计划注入"状态。
     * 必须是**每次请求新建、方法内传递**（不能做成字段）—— 否则并发请求会互相串状态。
     */
    private static final class PlanInjectState {
        /** 当前注入的计划消息身份。null = 本轮没有注入。不保存列表下标。 */
        String planMessageId = null;
        /** 上一次注入时用的计划版本号（-1 = 从未注入过） */
        int lastVersion = -1;
        /** 上一次**全量**注入发生在第几轮（0 基） */
        int lastFullRound = 0;
    }

    /**
     * 注入本轮的计划内容（slim 策略的核心）。
     *
     * 三条约定（改动 3）：
     *  ① 说明"怎么用计划、怎么上报"的**固定文案已移入系统提示词**，这里只注入随状态变化的内容；
     *  ② slim=true（默认）时常态只注入**一行摘要**，只有 {@link PlanInjectionPlanner} 判定
     *     该给全量时才注入完整清单；slim=false 则每轮都注入完整清单（旧行为，供 A/B 对照）；
     *  ③ 动态内容一律 **append 到消息列表末尾** —— 不改 system prompt、不插进历史消息中间。
     */
    private int injectPlan(List<Message> msgs, PlanInjectState st, Long userId, String conversationId,
                           int iteration, boolean contextCompacted) {
        // 先按身份摘掉上一轮注入的那条。压缩若已把它换成摘要，这里找不到，不能按旧下标去删别的消息。
        PlanInjectionMessage.remove(msgs, st.planMessageId);
        st.planMessageId = null;

        if (!planService.hasPlan(userId, conversationId)) return 0;   // 没有计划就没有可注入的内容

        String text;
        if (!planProperties.getInjection().isSlim()) {
            text = planService.render(userId, conversationId, iteration);   // 旧行为：每轮全量
            st.lastFullRound = iteration;
        } else {
            int version = planService.versionOf(userId, conversationId);
            PlanInjectionPlanner.Mode mode = PlanInjectionPlanner.decide(
                    version, st.lastVersion, iteration - st.lastFullRound,
                    planProperties.getInjection().getFullRefreshEvery(), contextCompacted);
            if (mode == PlanInjectionPlanner.Mode.FULL) {
                text = planService.render(userId, conversationId, iteration);
                st.lastFullRound = iteration;
            } else {
                text = planService.renderSlim(userId, conversationId, iteration);
            }
            st.lastVersion = version;
        }

        if (text == null || text.isBlank()) return 0;
        String id = UUID.randomUUID().toString();
        msgs.add(new PlanInjectionMessage(id, text));      // ← 追加在末尾，正文与原来的系统消息相同
        st.planMessageId = id;
        return text.length();                   // 返回注入字符数，供"规划注入占比"汇总统计
    }

    private static final String PROTOCOL_STOP_NOTE =
            "\n\n---\n⚠️ 内部工具消息不完整，已停止本次请求，没有调用模型。";

    /** 协议不完整时返回 true。不改消息，日志里只有错误类型、id 和位置。 */
    private static boolean refuseBrokenProtocol(List<Message> messages) {
        MessageProtocolValidator.Result result = MessageProtocolValidator.check(messages);
        if (result.ok()) return false;
        System.err.println("[Protocol] 拒绝发请求: " + result.summary());
        return true;
    }

    /**
     * 判断本轮是否刚发生过上下文压缩/截断。
     *
     * ContextCompactor 压缩时会在消息列表最前面插入一条以 "[历史摘要]" 开头的 SystemMessage，
     * 据此识别即可 —— 无状态、天然线程安全，不必去改压缩器的接口。
     * 为什么要知道：历史被压缩后，原先注入在上下文里的计划清单可能已经被压掉，
     * 此时必须立刻重新给一份完整清单（见 PlanInjectionPlanner）。
     */
    private boolean isCompacted(List<Message> messages) {
        for (Message m : messages) {
            if (m instanceof SystemMessage sm
                    && sm.getText() != null && sm.getText().startsWith("[历史摘要]")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 漂移信号 + 分级提醒（改动 4） ====================

    /** 一次请求内的"催规划"状态。同样必须每次请求新建（不能做字段，避免并发互串）。 */
    private static final class PlanNudgeState {
        /** 上次触发的轮号（用极小值表示"还没触发过"） */
        int lastNudgeRound = Integer.MIN_VALUE / 2;
        /** 本请求内是否已经 SOFT 提醒过（提醒过还不建计划 → 升级 HARD） */
        boolean softAlreadyFired = false;
        /** HARD 要强制的轮号（-1 = 不强制）。只认准一个轮号 → 天然一次性。
         *  用长度为 1 的数组，是为了能按引用传给 {@link PlanNudgePolicy#consumeForceRound} 并原地清空。 */
        final int[] forceRound = {-1};
        /** 强制请求是否失败过（诊断/汇总日志用） */
        boolean forceFailed = false;
        int softCount = 0;
        int hardCount = 0;
        java.util.Set<String> lastSignals = new java.util.LinkedHashSet<>();
        /** 上一轮模型输出的自然语言（"隐含规划"信号要看它） */
        String lastAssistantText = null;
    }

    /** 把配置里的阈值搬进纯逻辑用的 Config（隔离 Spring 依赖，方便单测） */
    private PlanNudgePolicy.Config nudgeConfig() {
        PlanProperties.Nudge n = planProperties.getNudge();
        PlanNudgePolicy.Config c = new PlanNudgePolicy.Config();
        c.modifiedFilesThreshold = n.getModifiedFilesThreshold();
        c.verifyFailuresThreshold = n.getVerifyFailuresThreshold();
        c.roundsThreshold = n.getRoundsThreshold();
        c.enumerationMinLength = n.getEnumerationMinLength();
        c.enumerationMinMarkers = n.getEnumerationMinMarkers();
        c.cooldown = n.getCooldown();
        return c;
    }

    /**
     * 绑定当前工作区的"文件内容版本"解析器（mtime + size）。
     * 用于"重复读取"信号的兜底确认：解析不到（文件已不存在、或路径越界）就不算重复读。
     */
    private PlanNudgePolicy.FileVersionResolver versionResolver(Path workspace) {
        return path -> {
            try {
                Path p = workspace.resolve(path).normalize();
                if (!p.startsWith(workspace) || !java.nio.file.Files.isRegularFile(p)) return null;
                return java.nio.file.Files.getLastModifiedTime(p).toMillis()
                        + ":" + java.nio.file.Files.size(p);
            } catch (Exception e) {
                return null;
            }
        };
    }

    /** 从工具记录里按发生顺序提取文件类操作（写/读），供漂移信号判定 */
    private List<PlanNudgePolicy.FileOp> extractFileOps(List<ChatResponse.ToolCallRecord> records) {
        List<PlanNudgePolicy.FileOp> ops = new ArrayList<>();
        if (records == null) return ops;
        for (ChatResponse.ToolCallRecord r : records) {
            if (r == null || r.getToolName() == null) continue;
            String name = r.getToolName();
            if (!PlanNudgePolicy.WRITE_TOOLS.contains(name) && !PlanNudgePolicy.READ_TOOLS.contains(name)) {
                continue;
            }
            String path = null;
            try {
                JsonNode node = objectMapper.readTree(r.getInput() == null ? "{}" : r.getInput());
                if (node.hasNonNull("path")) path = node.get("path").asText();
                else if (node.hasNonNull("from")) path = node.get("from").asText();
            } catch (Exception ignored) {
                // 参数解析不出来就留空 —— 宁缺勿错，不因为一条脏记录把信号判歪
            }
            ops.add(new PlanNudgePolicy.FileOp(name, path));
        }
        return ops;
    }

    /** 编译/校验失败累计次数（verifyCompile 工具记录里的失败项） */
    private int countVerifyFailures(List<ChatResponse.ToolCallRecord> records) {
        int n = 0;
        if (records == null) return 0;
        for (ChatResponse.ToolCallRecord r : records) {
            if (r != null && "verifyCompile".equals(r.getToolName()) && !r.isSuccess()) n++;
        }
        return n;
    }

    /**
     * HARD 用的"强制调用 createPlan"选项（OpenAI 协议的 tool_choice）。
     *
     * 为什么是 OpenAiChatOptions 而不是 ToolCallingChatOptions：本机 javap 实测
     * spring-ai 1.0.0 里 ToolCallingChatOptions（接口）**没有** toolChoice，
     * 而 OpenAiChatOptions 有 toolChoice 字段 + Builder.toolChoice(Object)，
     * 且 OpenAiApi$ChatCompletionRequest 带该字段，会真正序列化进 HTTP 请求体。
     * 构造失败返回 null，调用方退回普通请求（不让整个对话挂掉）。
     */
    private ToolCallingChatOptions forcedPlanOptions(ToolCallback[] effectiveCallbacks) {
        try {
            OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                    .toolCallbacks(effectiveCallbacks)
                    .internalToolExecutionEnabled(false)
                    .toolChoice(Map.of("type", "function", "function", Map.of("name", "createPlan")));
            // DeepSeek 的思考模式会直接拒绝 tool_choice（400 Thinking mode does not support this tool_choice），
            // 关掉思考后同一个请求就能正常执行并照做。所以强制轮顺带带上 reasoning_effort=none。
            // 小米 MiMo 接受该字段但仍旧忽略 tool_choice（行为不变）；不认识的端点会报错 →
            // 由调用方的 try/catch 回退成普通请求，不会让对话挂掉。
            if (planProperties.getNudge().getHard().isDisableThinking()) {
                b.reasoningEffort("none");
            }
            return b.build();
        } catch (Exception e) {
            System.err.println("[PlanNudge] 构造强制 tool_choice 选项失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 本轮**真正下发给模型**的工具清单。
     *
     * 正常情况下就是全量；只有 HARD 逃生舱 `strategy=stripWriteTools` 生效时，
     * 才是"摘掉写类工具之后"的子集。
     *
     * ⚠️ 关键：这份清单**同时**要用于三处，缺一处就不一致 ——
     *   ① 构造发给模型的 options；② 模型请求工具时的白名单校验；③ 实际派发时的查找范围。
     * 之前只做了 ①，执行层仍拿全量 callbacks 派发，于是模型喊出被摘掉的 executeCommand 照样被执行（实测）。
     */
    private ToolCallback[] effectiveRoundCallbacks(ToolCallback[] callbacks) {
        if ("stripWriteTools".equalsIgnoreCase(planProperties.getNudge().getHard().getStrategy())) {
            ToolCallback[] kept = stripWriteTools(callbacks);
            System.out.println("[PlanNudge] HARD strategy=stripWriteTools：本轮临时摘掉写类工具，"
                    + "工具数 " + callbacks.length + " → " + kept.length);
            return kept;
        }
        return callbacks;
    }

    /** 从一份工具清单里取出工具名集合（白名单校验用） */
    private static java.util.Set<String> toolNamesOf(ToolCallback[] callbacks) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (callbacks == null) return names;
        for (ToolCallback cb : callbacks) {
            if (cb != null && cb.getToolDefinition() != null && cb.getToolDefinition().name() != null) {
                names.add(cb.getToolDefinition().name());
            }
        }
        return names;
    }

    /**
     * 带白名单校验的工具执行：模型**只能**调用"本轮下发给它的"工具。
     *
     * 清单之外的一律不执行，只回一条说明给模型。这既堵住了 stripWriteTools 的漏洞
     * （被摘掉的工具不再能被"凭记忆喊出来"执行），也是对付模型幻觉工具名的通用防御。
     */
    private String callToolChecked(ToolCallback[] roundCallbacks, String toolName, String arguments,
                                   String conversationId, List<Message> messages) {
        TaskExecution execution = TaskExecution.current();
        if (execution != null && !execution.acceptsTools()) {
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.DENIED);
            markAuditDenied();
            return "工具未执行：任务已进入审批阻塞或最终交付阶段。";
        }
        boolean tainted = mcpClientManager.shouldStripDangerous(conversationId, messages);
        String reject = ToolGuard.rejectReason(toolName, toolNamesOf(roundCallbacks), tainted);
        if (reject != null) {
            System.out.println("[ToolGuard] 拒绝执行清单外工具: " + toolName
                    + "（本轮清单 " + (roundCallbacks == null ? 0 : roundCallbacks.length) + " 个工具）");
            // 白名单拒绝也是一次"没有执行"：写下结构化结论，并复用网关既有的审计字段
            // （高危、未批准）。这条路径不经过 ToolPermissionGate，此前既不写 audit 也不区分
            // success，于是在 tool_call_log 里和"ALLOW 正常执行"完全一样。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.DENIED);
            markAuditDenied();
            return reject;
        }
        return callTool(roundCallbacks, toolName, arguments);
    }

    /** 绕过网关的拒绝路径（白名单）补一次结构化审计标记，口径与网关拒绝一致：高危、未批准。 */
    private static void markAuditDenied() {
        ToolCallContext ctx = ToolCallContext.current();
        if (ctx != null) ctx.setAudit(true, false);
    }

    /**
     * 执行一次工具调用并记账。**非流式与流式共用这一处实现**，所以两条链路的
     * "success 口径"只有一份，不会再次走偏（此前流式那份把 success 固定成 true）。
     *
     * <p>{@code success} 的唯一来源是派发层写下的结构化结论（{@link ToolExecutionStatus}）：
     * 不用默认 true 顶替执行结果，也不解析结果文案 —— 工具成功执行但返回合法业务错误文本时
     * 结论仍是 EXECUTED，不会被误判成"未执行"。
     *
     * @param pre 批量通道（spawnSubagent）已经算好的结论；非 null 时不派发，直接用它的 ok()
     * @param sink 本次请求的工具记录表；流式链路的 SSE {@code tool} 事件序列化的就是它
     * @return 本次调用的记录（success 已按真实执行结论填好）
     */
    private ChatResponse.ToolCallRecord executeAndRecordTool(
            ToolCallback[] roundCallbacks, AssistantMessage.ToolCall tc, String conversationId,
            List<Message> messages, SubagentService.SpawnOutcome pre, Long userId, int turnIndex,
            StepToolLedger ledger, List<ChatResponse.ToolCallRecord> sink) {
        long start = System.currentTimeMillis();
        String toolResult;
        boolean toolSuccess;
        ToolExecutionStatus.Kind executionKind;
        if (pre != null) {
            // 批量通道已经跑完的 spawnSubagent：直接用它的结论，不要再派一次子代理。
            // 它没有经过 ToolDispatcher，先清掉可能残留的结论，避免误用上一次调用的结果。
            ToolExecutionStatus.take();
            toolResult = pre.text();
            toolSuccess = pre.ok();
            executionKind = toolSuccess
                    ? ToolExecutionStatus.Kind.EXECUTED : ToolExecutionStatus.Kind.FAILED;
        } else {
            try {
                toolResult = callToolChecked(roundCallbacks, tc.name(), tc.arguments(),
                        conversationId, messages);
            } catch (Exception e) {
                toolResult = "工具执行异常: " + e.getMessage();
            }
            executionKind = ToolExecutionStatus.take();
            toolSuccess = successOf(executionKind);
        }
        long elapsed = System.currentTimeMillis() - start;
        // Ledger 只记录脱敏摘要；业务分类使用裁剪前的原始结果，避免前缀被提示/裁剪影响。
        if (ledger != null) {
            ledger.recordTool(tc.id(), turnIndex, tc.name(), tc.arguments(), toolResult, executionKind);
        }
        TaskExecution execution = TaskExecution.current();
        if (execution != null) {
            execution.record(tc.name(), tc.arguments(), toolResult, executionKind,
                    planService.stepsOf(userId, conversationId));
            planService.revalidateFiles(userId, conversationId, tc.name(), tc.arguments());
        }
        // 冗余读提示（**只加一行、不拦截**）：这个文件子代理已经读过、结论就在上文，
        // 主 agent 又整份读了一遍。内容照常返回 —— 拦下来反而会破坏"读不到就卡死"的既有行为。
        toolResult = withRedundantReadHint(conversationId, tc.name(), tc.arguments(), toolResult);
        toolResult = maybeClipMainToolResult(toolResult);
        // 审计：每一次工具调用都落库，便于复盘"agent 到底做了什么"
        boolean[] audit = auditFlags();
        toolCallLogService.log(userId, conversationId, tc.name(),
                SensitiveRedactor.redact(tc.arguments()),
                SensitiveRedactor.redact(toolResult), toolSuccess, elapsed,
                audit[0], audit[1], turnIndex);

        ChatResponse.ToolCallRecord record = new ChatResponse.ToolCallRecord();
        record.setToolName(tc.name());
        record.setInput(SensitiveRedactor.redact(tc.arguments()));
        record.setResult(toolResult);
        record.setSuccess(toolSuccess);
        record.setElapsedMs(elapsed);
        sink.add(record);

        messages.add(new ToolResponseMessage(
                List.of(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), toolResult))));
        observeToolResult(conversationId, turnIndex, tc, toolResult);
        mcpClientManager.noteToolResult(conversationId, tc.name(), userId == null ? 0L : userId);
        return record;
    }

    /**
     * 请求结束时对本次 Ledger 与当前计划做一次**只读**对账。
     * 只打印诊断告警；不调用任何修改计划状态的方法。异常一律吞掉，不影响 Agent 返回。
     */
    private void reconcileStepLedger(StepToolLedger ledger, Long userId, String conversationId) {
        try {
            if (ledger == null || userId == null || conversationId == null) return;
            List<PlanStep> steps = planService.stepsOf(userId, conversationId);
            for (TaskReconciler.Alert alert : TaskReconciler.reconcile(ledger, steps)) {
                System.out.println(alert.line());
            }
        } catch (Exception e) {
            System.err.println("[TaskReconcile] 对账失败（不影响对话）: " + e.getMessage());
        }
    }

    /** 结构化执行结论 → {@code success}。
     *
     * <p>只有"工具本体确实被调用并正常返回"才算成功；被拒绝、清单里没有、抛异常都不算。
     * 结论缺失时明确按未成功记录并打出原因 —— 这条路径无法安全判断是否执行过，
     * 因此**不猜成功**。
     */
    private static boolean successOf(ToolExecutionStatus.Kind kind) {
        if (kind == null) {
            System.out.println("[ToolCall] 缺少结构化执行结论，按未成功记录（不猜成功）");
            return false;
        }
        return ToolExecutionStatus.succeeded(kind);
    }

    /**
     * 工具结果入列表之后的旁路记录。不改消息，不读文件内容，解析失败记空路径。
     * 序号只活在当前请求线程里，请求之间不共用。
     */
    private static void observeToolResult(String conversationId, int turnIndex,
                                          AssistantMessage.ToolCall tc, String toolResult) {
        try {
            AtomicInteger seq = TOOL_RESULT_SEQ.get();
            if (seq == null) {
                seq = new AtomicInteger();
                TOOL_RESULT_SEQ.set(seq);
            }
            String toolName = tc == null ? null : tc.name();
            String args = tc == null ? null : tc.arguments();
            TurnState.ToolResultObservation obs = new TurnState.ToolResultObservation(
                    tc == null ? null : tc.id(),
                    toolName,
                    TurnState.targetPathOf(toolName, args),
                    TurnState.resultChars(toolResult),
                    TurnState.spilled(toolResult),
                    seq.incrementAndGet());
            System.out.println("[ToolObs] conversation=" + conversationId
                    + " turn=" + turnIndex
                    + " seq=" + obs.seq()
                    + " toolCallId=" + obs.toolCallId()
                    + " tool=" + obs.toolName()
                    + " path=" + obs.targetPath()
                    + " chars=" + obs.chars()
                    + " spilled=" + obs.spilled());
        } catch (RuntimeException e) {
            System.err.println("[ToolObs] 记录失败（不影响工具执行）: " + e.getMessage());
        }
    }

    /**
     * 这一次工具调用要不要把 filesModified 立起来（从而触发父循环自动 javac）。
     *
     * 父代理自己的写工具：永远立旗。
     * implement / GP 派发：默认也立旗（PARTIAL / FAILED / 解析失败 / 关开关都要兜底）；
     * 仅当 GP 打开且完工单 STATUS=SUCCEEDED 且未被降级时跳过。
     */
    private boolean shouldFlagFilesModified(AssistantMessage.ToolCall tc, String toolResult,
                                            String conversationId) {
        if (CodeVerifier.isWriteTool(tc.name())) return true;
        if (!subagentService.isImplementSpawn(tc, conversationId)) return false;
        return !subagentService.skipParentCompile(tc, toolResult);
    }

    /** 从 from 起连续的只读子代理。遇到写操作、其它工具或非只读派发就停下。 */
    private List<AssistantMessage.ToolCall> consecutiveReadOnlySpawns(
            List<AssistantMessage.ToolCall> calls, int from, String conversationId) {
        List<AssistantMessage.ToolCall> run = new ArrayList<>();
        if (calls == null) return run;
        for (int i = Math.max(0, from); i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            if (!subagentService.isReadOnlySpawn(call, conversationId)) break;
            run.add(call);
        }
        return run;
    }

    /**
     * 把本轮的 spawnSubagent 调用**一次性提交**（真并行），再按 toolCallId 收齐。
     *
     * <p>c0a8fc8 把这里改成"逐个派发"，于是同一轮里的两个子代理变成一前一后（日志里
     * done 与下一个 start 只差 22ms）。这里把批量通道接回来：两个房间同时开，墙钟重叠；
     * 非 spawn 工具不进来，仍按原顺序执行。</p>
     *
     * <p>返回的 map 交给 {@link #executeAndRecordTool} 的 {@code pre} 参数，
     * 它会直接用结论、不再派一次子代理。调用方必须等到"走到第一个 spawn"再调本方法，
     * 否则前面工具的审批阻塞 / 取消会被绕过。</p>
     */
    private Map<String, SubagentService.SpawnOutcome> runSpawnBatch(
            List<AssistantMessage.ToolCall> spawns, OpenAiChatModel model, String conversationId,
            SubagentService.EventSink sink, ToolCallback[] roundCallbacks) {
        TaskExecution task = TaskExecution.current();
        if (task == null || !task.acceptsTools() || task.cancelled()) return Map.of();
        if (!toolNamesOf(roundCallbacks).contains("spawnSubagent")) return Map.of();
        if (spawns == null || spawns.isEmpty()) return Map.of();
        return subagentService.runBatch(spawns, model, conversationId, sink);
    }

    /**
     * 记录强制 tool_choice 的实际效果。
     * 关键区分：请求**没报错**不等于强制生效 —— 模型完全可能无视 tool_choice 就是不调 createPlan，
     * 这种情况必须明确打日志（否则会误以为"强制成功了但没效果"）。
     */
    // ==================== 可观测性汇总（改动 5） ====================

    /** 估算 token 用的近似系数：中文大致 1.5 字符/token。**仅用于估算占比**，真实计费以服务商为准。 */
    private static final double CHARS_PER_TOKEN = 1.5;

    /** 累加消息列表里所有文本的长度（估算本轮 prompt 规模用） */
    private int promptChars(List<Message> msgs) {
        int n = 0;
        for (Message m : msgs) {
            if (m == null) continue;
            String t = m.getText();
            if (t != null) n += t.length();
        }
        return n;
    }

    /** 字符数 → 估算 token 数（四舍五入）；与 PlanSummaryFormatter.est 同一口径 */
    private static long estimateTokens(int chars) {
        return PlanSummaryFormatter.est(chars, CHARS_PER_TOKEN);
    }

    /**
     * 一次请求结束时的汇总日志（改动 5，INFO 级）。
     *
     * 目的：把"规划到底值不值"从感觉变成数据 ——
     *  ① 规划注入占整个 prompt 的比例（占比小 = slim 生效了；占比大 = 还得继续压）；
     *  ② 哪套开关配置能真正触发规划（createPlan 有没有被调用、在第几轮、nudge 命中什么信号）。
     * 注意：token 是**字符数 ÷ {@value #CHARS_PER_TOKEN} 的估算值**，不是精确计费，只用于横向对照。
     */
    private void logSessionSummary(int roundsUsed, int firstCreatePlanRound, PlanNudgeState nudges,
                                   int planInjectChars, int totalPromptChars) {
        // 文本拼装交给纯函数（可单测），这里只负责输出
        for (String line : PlanSummaryFormatter.lines(
                roundsUsed, firstCreatePlanRound,
                nudges.softCount, nudges.hardCount,
                nudges.lastSignals.isEmpty() ? "无" : nudges.lastSignals.toString(),
                nudges.forceFailed, planInjectChars, totalPromptChars,
                CHARS_PER_TOKEN,
                planProperties.getSteps().getMode(), planProperties.getInjection().isSlim(),
                planProperties.getInjection().getFullRefreshEvery(),
                planProperties.getNudge().getMode(), planProperties.getNudge().getHard().getStrategy())) {
            System.out.println(line);
        }
    }

    private static final String HARD_WINDOW_STOP =
            "必须保留的上下文超过模型窗口，本次没有发送模型请求，也没有丢弃内容。";

    /** 一次请求内记住上一轮实际发出的 Prompt，用来判断前缀是追加还是被改写。 */
    private static final class PromptCursor {
        private List<Message> previous = List.of();
        private String previousTools = "";

        private PromptView observe(ChatController controller, ContextSourceLedger ledger, List<Message> messages,
                                   ToolCallback[] tools, int round, boolean streaming, boolean delivery,
                                   Long userId, String conversationId) {
            PromptView view = controller.observeBeforeModel(ledger, messages, tools, round, streaming, delivery,
                    userId, conversationId, previous, previousTools);
            previous = view.messages();
            previousTools = PromptCanonical.toolsJson(tools);
            return view;
        }
    }

    private ContextSourceLedger newSourceLedger(String conversationId, List<MessageRecord> history, String currentUser) {
        ContextSourceLedger ledger = new ContextSourceLedger(conversationId);
        ledger.addHistory(history, currentUser);
        return ledger;
    }

    /**
     * M1：历史摘要的跨请求复用作用域。
     *
     * <p>只在默认工作集路径上给：{@code userId|conversationId} 保证不同会话不会命中同一段摘要，
     * 也让同一会话的后续请求能复用已覆盖范围（不再为同一段旧历史重复付费）。
     * 工作集关闭时返回 null，摘要退回「不复用、不缓存」的旧行为，不改变 total/history 旧口径。
     */
    private String summaryScope(Long userId, String conversationId) {
        if (!workingSetEnabled || conversationId == null || conversationId.isBlank()) return null;
        return (userId == null ? "anon" : userId.toString()) + "|" + conversationId;
    }

    /**
     * 模型调用前的统一投影入口。非流式、SSE、最终整理都走这里。
     * enabled 时返回投影后的新列表，压缩只作用在这份副本上；源账本和传入的消息列表不动。
     * 两个开关都关闭、或投影异常时，返回原来的消息。
     */
    private PromptView observeBeforeModel(ContextSourceLedger ledger, List<Message> messages, ToolCallback[] tools,
                                          int round, boolean streaming, boolean delivery, Long userId,
                                          String conversationId, List<Message> previousPrompt, String previousTools) {
        PromptView actual = PromptView.of(messages);
        if (ledger == null || (!workingSetShadowEnabled && !workingSetEnabled)) return actual;
        try {
            ledger.absorb(actual.messages(), round, planService.get(userId, conversationId));
            int window = activeWindow();
            int schema = schemaTokens(tools);
            ContextProjectionPolicy.Result projected = ContextProjectionPolicy.project(
                    ledger, actual.messages(), planService.get(userId, conversationId), round, schema,
                    workingSetSoftBudget, window, workingSetShadowEnabled, workingSetEnabled,
                    // 退休前的可读性/完整性探测走真实 FileTool：和模型真正恢复时同一段沙箱与哈希校验。
                    fileTool::snapshotStatus);
            boolean applied = workingSetEnabled && projected.error() == null;
            List<Message> outgoing = applied ? new ArrayList<>(projected.messages()) : new ArrayList<>(actual.messages());
            if (applied) compactCopy(outgoing, tools, round);
            String currentTools = com.example.jingjingai2.agent.turn.PromptCanonical.toolsJson(tools);
            ContextProjectionPolicy.PrefixStability prefix = ContextProjectionPolicy.prefix(
                    previousPrompt, outgoing, projected.decisions(), previousTools, currentTools);
            ledger.markSent(outgoing, round);
            ContextProjectionPolicy.Stats stats = projected.stats();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("conversationId", conversationId);
            record.put("round", round);
            record.put("streaming", streaming);
            record.put("delivery", delivery);
            record.put("currentStep", currentStepId(userId, conversationId));
            record.put("shadow", !workingSetEnabled && projected.error() == null);
            record.put("projectionApplied", applied);
            record.put("projectionError", projected.error());
            record.put("promptChanged", !sameMessages(actual.messages(), outgoing));
            record.put("totalPromptTokensEst", stats.totalPromptTokensEst());
            record.put("fixedTokensEst", stats.fixedTokensEst());
            record.put("toolSchemaTokensEst", stats.toolSchemaTokensEst());
            record.put("historyTokensEst", stats.historyTokensEst());
            record.put("activeTaskTokensEst", stats.activeTaskTokensEst());
            record.put("userMemoryTokensEst", stats.userMemoryTokensEst());
            record.put("workspaceMemoryTokensEst", stats.workspaceMemoryTokensEst());
            record.put("planTokensEst", stats.planTokensEst());
            record.put("toolResultTokensEst", stats.toolResultTokensEst());
            record.put("budgetPressureTokensEst", stats.budgetPressureTokensEst());
            record.put("recoverableToolTokensEst", stats.recoverableToolTokensEst());
            record.put("nonRecoverableToolTokensEst", stats.nonRecoverableToolTokensEst());
            record.put("shadowKeepTokensEst", stats.shadowKeepTokensEst());
            record.put("shadowRetireTokensEst", stats.shadowRetireTokensEst());
            record.put("shadowDiscardTokensEst", stats.shadowDiscardTokensEst());
            record.put("shadowProjectedTokensEst", stats.shadowProjectedTokensEst());
            record.put("candidateCount", stats.candidateCount());
            record.put("reasonCounts", stats.reasonCounts());
            record.put("actualPrefixHash", prefix.actualPrefixHash());
            record.put("shadowPrefixHash", prefix.shadowPrefixHash());
            record.put("actualFirstChange", prefix.actualFirstChange());
            record.put("shadowFirstChange", prefix.shadowFirstChange());
            record.put("actualTailAppendOnly", prefix.actualTailAppendOnly());
            record.put("shadowTailAppendOnly", prefix.shadowTailAppendOnly());
            record.put("actualRewroteEarlier", prefix.actualRewroteEarlier());
            record.put("shadowRewroteEarlier", prefix.shadowRewroteEarlier());
            record.put("toolSchemaChanged", prefix.toolSchemaChanged());
            observationLog().add(record);
            System.out.println("[ContextShadow] round=" + round
                    + " delivery=" + delivery
                    + " applied=" + applied
                    + " total=" + stats.totalPromptTokensEst()
                    + " keep=" + stats.shadowKeepTokensEst()
                    + " retire=" + stats.shadowRetireTokensEst()
                    + " discard=" + stats.shadowDiscardTokensEst()
                    + " toolResults=" + stats.toolResultTokensEst()
                    + " pressure=" + stats.budgetPressureTokensEst()
                    + " recoverableTools=" + stats.recoverableToolTokensEst()
                    + " promptChanged=" + record.get("promptChanged"));
            // 投影失败（配对被破坏、账本异常等）不能当成「没有投影 = 安全」：
            // 原始请求仍可能超过硬窗口。这里用同一窗口再兜一次，宁可停也不发。
            if (workingSetEnabled && projected.error() != null
                    && !ContextProjectionPolicy.HARD_WINDOW_EXCEEDED.equals(projected.error())
                    && ContextBudget.exceedsWindow(ContextBudget.requestTokens(actual.messages(), tools), window)) {
                System.err.println("[Context] 投影失败(" + projected.error() + ")且完整请求超过硬窗口，本轮不发送");
                return PromptView.of(List.of(new SystemMessage(HARD_WINDOW_STOP)));
            }
            if (ContextProjectionPolicy.HARD_WINDOW_EXCEEDED.equals(projected.error())) {
                System.err.println("[Context] 必须保留的内容超过硬窗口，本轮不发送");
                return PromptView.of(List.of(new SystemMessage(HARD_WINDOW_STOP)));
            }
            return PromptView.of(outgoing);
        } catch (RuntimeException e) {
            System.err.println("[ContextShadow] 观测失败，实际 Prompt 不变: " + e.getClass().getSimpleName());
        }
        return actual;
    }

    /**
     * 只压缩这份即将发出的副本。工作集已经选完，这里动不到源账本，也动不到循环里的消息列表。
     */
    private void compactCopy(List<Message> copy, ToolCallback[] tools, int round) {
        if (!totalScope()) return;
        int threshold = compactThresholdTotal <= 0 ? 32000 : compactThresholdTotal;
        int gap = compactMinGap <= 0 ? 3 : compactMinGap;
        double growth = compactGrowthRatio <= 0 ? 0.30 : compactGrowthRatio;
        int minBudget = compactMinMessageBudget <= 0 ? 1000 : compactMinMessageBudget;
        RoundCompactor.apply(copy, tools, round, threshold, gap, growth, minBudget,
                new RoundCompactor.Guard(),
                body -> new RoundCompactor.Summary("[历史摘要] 副本压缩未调用模型", 0, 0, true, false),
                "digest", Math.max(0, digestKeepToolGroups), tieredAcceptRatio);
    }

    private String currentStepId(Long userId, String conversationId) {
        try {
            for (PlanStep step : planService.stepsOf(userId, conversationId)) {
                if (step != null && step.open()) return step.getStepId();
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static int schemaTokens(ToolCallback[] tools) {
        return com.example.jingjingai2.context.TokenEstimator.estimate(
                com.example.jingjingai2.agent.turn.PromptCanonical.toolsJson(tools));
    }

    /** 测试直接 new 控制器时没有 Spring 注入，用这个打开影子观测。生产路径仍走配置。 */
    void enableWorkingSetShadowForTest(int softBudgetTokens) {
        workingSetShadowEnabled = true;
        workingSetEnabled = false;
        if (softBudgetTokens > 0) workingSetSoftBudget = softBudgetTokens;
    }

    /** 测试里打开真实投影。打开后实际发给模型的消息由工作集决定。 */
    void enableWorkingSetForTest(int softBudgetTokens) {
        workingSetEnabled = true;
        workingSetShadowEnabled = false;
        if (softBudgetTokens > 0) workingSetSoftBudget = softBudgetTokens;
    }

    /**
     * 测试用：只走「模型调用前」的真实投影入口，不执行完整请求。
     * 用于验证默认配置下工作集确实改变了即将发出的 Prompt，而不只是策略类单测。
     */
    PromptView projectBeforeModelForTest(ContextSourceLedger ledger, List<Message> messages,
                                         ToolCallback[] tools, int round, String conversationId) {
        return observeBeforeModel(ledger, messages, tools, round, false, false, 1L, conversationId,
                List.of(), "");
    }

    private ContextObservationLog observationLog() {
        String configured = workingSetObservationPath == null ? "" : workingSetObservationPath.trim();
        Path path = configured.isBlank() ? null : Path.of(configured);
        return new ContextObservationLog(path);
    }

    private static boolean sameMessages(List<Message> left, List<Message> right) {
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }

    /** 硬窗口放不下必须保留的内容。返回 true 表示本轮已经安全停止，没有发请求。 */
    private boolean stoppedForHardWindow(PromptView view, SseEmitter emitter) {
        boolean stopped = view.messages().size() == 1 && view.messages().get(0) instanceof SystemMessage
                && HARD_WINDOW_STOP.equals(view.messages().get(0).getText());
        if (stopped && emitter != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "error");
            event.put("message", HARD_WINDOW_STOP);
            sendJson(emitter, "error", event);
            emitter.complete();
        }
        return stopped;
    }

    /**
     * 摘要失败后的硬上限检查。65% 只触发压缩；这里比较完整请求估算和配置窗口的 100%。
     * history 口径看进循环前那次摘要；total 口径看本轮压缩的结果。
     * 返回 true 表示本轮不能发送。
     */
    private boolean rejectOverWindow(List<Message> messages, ToolCallback[] tools,
                                     ContextCompactor.SummaryUsage summary, SseEmitter emitter) {
        return rejectOverWindow(messages, tools, summary, emitter, null);
    }

    private boolean rejectOverWindow(List<Message> messages, ToolCallback[] tools,
                                     ContextCompactor.SummaryUsage summary, SseEmitter emitter,
                                     ContextCompactor.CompactStats totalRound) {
        int window = activeWindow();
        if (!WindowGuard.reject(messages, tools, summary, totalRound, totalScope(), window)) return false;
        String message = "历史摘要失败，且完整请求估算超过配置的上下文预算。本次保留原始历史，未发送模型请求。";
        if (emitter != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "error");
            event.put("message", message);
            sendJson(emitter, "error", event);
            emitter.complete();
        }
        return true;
    }

    /**
     * 本轮模型调用之前：历史太大就摘旧工具结果；累计输入到顶就让模型总结、不再给工具。
     * 只打日志和改 messages，不自己调用模型。
     */
    private void guardRound(List<Message> messages, ToolCallback[] round, long cumulative,
                            int roundNo, int maxIterations, boolean[] budgetHold) {
        String protection = null;
        int window = activeWindow();
        long history = ContextBudget.historyTokens(messages);
        long fixed = ContextBudget.fixedTokens(messages, round);
        if (!workingSetEnabled && ContextBudget.shouldCompact(history, fixed, window, compactWindowPercent, historyMaxTokens)) {
            ToolHistoryCompactor.Result compacted = ToolHistoryCompactor.compact(messages, historyMaxTokens);
            protection = compacted.complete() ? "压缩" : "压缩未完成";
            int percent = compactWindowPercent <= 0 ? 65 : compactWindowPercent;
            long line = window > 0 ? (long) window * percent / 100 : historyMaxTokens;
            System.out.println("[Context] 预计总输入 " + (history + fixed) + " > " + line
                    + "（历史 " + history + " + 固定开销 " + fixed + "），压缩了 " + compacted.replaced()
                    + " 条旧工具结果；完成=" + compacted.complete() + "；保留=" + compacted.blocked());
            history = ContextBudget.historyTokens(messages);
        }
        if (LoopGuard.overInputBudget(cumulative, taskInputTokenBudget)) {
            if (!budgetHold[0]) {
                messages.add(new UserMessage(LoopGuard.inputBudgetNote(cumulative, taskInputTokenBudget)));
                System.out.println("[Guard] 触发=累计输入预算 累计=" + cumulative
                        + " 上限=" + taskInputTokenBudget);
            }
            budgetHold[0] = true;
            protection = "累计输入预算";
        }
        System.out.println("[Round] 轮数=" + roundNo + "/" + maxIterations
                + " 估算输入token=" + (history + fixed)
                + " 累计输入token=" + cumulative
                + " 历史=" + history
                + " 固定开销=" + fixed
                + " 保护=" + (protection == null ? "无" : protection));
    }

    /** 第三次相同调用：不执行，把拒绝和提示塞回上下文，让模型换思路或收尾。 */
    private void pushRepeatHint(List<Message> messages, AssistantMessage am,
                                List<AssistantMessage.ToolCall> tcs) {
        if (am != null) messages.add(am);
        List<ToolResponseMessage.ToolResponse> rs = new ArrayList<>();
        if (tcs != null) {
            for (AssistantMessage.ToolCall tc : tcs) {
                if (tc == null) continue;
                rs.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), LoopGuard.REPEAT_REFUSAL));
            }
        }
        if (!rs.isEmpty()) messages.add(new ToolResponseMessage(rs));
        messages.add(new UserMessage(LoopGuard.REPEAT_HINT));
    }

    /**
     * 诊断用：打出这一次模型请求的真实输入 token，以及请求构成的字符数。
     * 只读观测，不改变发给模型的内容。字符数不是 token，分项只用于对照，不能相加成真实用量。
     */
    private void logRoundDiag(org.springframework.ai.chat.model.ChatResponse response,
                              List<Message> messages, ToolCallback[] round) {
        org.springframework.ai.chat.metadata.Usage u = null;
        if (response != null && response.getMetadata() != null) {
            u = response.getMetadata().getUsage();
        }
        Integer prompt = u == null ? null : u.getPromptTokens();
        Integer completion = u == null ? null : u.getCompletionTokens();
        long cached = UsageTracker.cachedTokensOf(u);
        boolean missing = prompt == null || (prompt == 0 && (completion == null || completion == 0));
        int sys = 0, user = 0, asst = 0, tool = 0, other = 0, search = 0, snapshot = 0;
        if (messages != null) {
            for (Message m : messages) {
                if (m == null) continue;
                String t = m.getText();
                int n = t == null ? 0 : t.length();
                if (m instanceof SystemMessage) sys += n;
                else if (m instanceof UserMessage) user += n;
                else if (m instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                    if (trm.getResponses() != null) {
                        for (org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                            if (r == null) continue;
                            int rn = r.responseData() == null ? 0 : r.responseData().length();
                            tool += rn;
                            if ("mcpToolSearch".equals(r.name())) search += rn;
                            if (rn >= 500 && McpNames.isMcp(r.name())) snapshot += rn;
                        }
                    }
                } else if (m instanceof AssistantMessage) asst += n;
                else other += n;
            }
        }
        int mcpTools = 0, nativeTools = 0, mcpChars = 0, nativeChars = 0, searchChars = 0;
        if (round != null) {
            for (ToolCallback cb : round) {
                if (cb == null || cb.getToolDefinition() == null) continue;
                org.springframework.ai.tool.definition.ToolDefinition d = cb.getToolDefinition();
                int c = (d.name() == null ? 0 : d.name().length())
                        + (d.description() == null ? 0 : d.description().length())
                        + (d.inputSchema() == null ? 0 : d.inputSchema().length());
                if ("mcpToolSearch".equals(d.name())) searchChars += c;
                else if (McpNames.isMcp(d.name())) { mcpTools++; mcpChars += c; }
                else { nativeTools++; nativeChars += c; }
            }
        }
        System.out.println("[RoundDiag] prompt=" + (missing ? "MISSING" : prompt)
                + " completion=" + (completion == null ? "MISSING" : completion)
                + " cached=" + cached
                + " | chars sys=" + sys + " user=" + user + " asst=" + asst
                + " tool=" + tool + " searchResult=" + search + " largeMcpResult=" + snapshot
                + " other=" + other
                + " | tools native=" + nativeTools + "(" + nativeChars + ")"
                + " mcp=" + mcpTools + "(" + mcpChars + ")"
                + " search=" + searchChars);
    }

    /** 真实用量日志（服务商返回的计费值，与上面按字符估算的 [PlanSummary] 互为对照） */
    private void logUsage(UsageTracker usage) {
        if (usage != null && usage.hasData()) {
            System.out.println(usage.line());
        } else {
            System.out.println("[Usage] 本次请求未拿到服务商用量字段（usage 为空），token 只能按字符估算");
        }
    }

    private void logForceOutcome(String channel, org.springframework.ai.chat.model.ChatResponse resp) {
        boolean called = resp != null && resp.getResult() != null
                && resp.getResult().getOutput() != null
                && resp.getResult().getOutput().getToolCalls() != null
                && resp.getResult().getOutput().getToolCalls().stream()
                        .anyMatch(tc -> "createPlan".equalsIgnoreCase(tc.name()));
        System.out.println(called
                ? "[PlanNudge] " + channel + "：强制 tool_choice 生效 —— 模型已调用 createPlan"
                : "[PlanNudge] " + channel + "：已强制 tool_choice=createPlan，但请求成功、模型仍未调用（强制未生效）");
    }

    /**
     * stripWriteTools 逃生舱（app.plan.nudge.hard.strategy=stripWriteTools 时启用）：
     * 下一轮临时把"能改变世界"的工具（写文件类 + executeCommand）摘掉，
     * 模型除了先 createPlan 之外没什么可做的。只对那一轮生效，不改任何全局状态。
     */
    private ToolCallback[] stripWriteTools(ToolCallback[] callbacks) {
        List<ToolCallback> kept = new ArrayList<>();
        for (ToolCallback cb : callbacks) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            String n = cb.getToolDefinition().name();
            if (PlanNudgePolicy.isStrippableTool(n)) continue;
            kept.add(cb);
        }
        return kept.toArray(new ToolCallback[0]);
    }

    /**
     * 评估漂移信号并决定"要不要催、怎么催"。
     *
     * 只在**当前会话还没有计划**时生效 —— 已经有计划还催就是纯打扰。
     * 提醒只写进本轮的消息列表（作用于下一轮请求），**不落长期历史**。
     *
     * @param roundIndex    当前轮号（0 基）
     * @param workspace     当前工作区（给"重复读取"信号的版本解析器用）
     * @param emitter       可空（非流式链路没有 emitter，只是不推前端提示）
     */
    private void applyPlanNudge(List<Message> msgs, PlanNudgeState st,
                                List<ChatResponse.ToolCallRecord> records,
                                Long userId, String conversationId, int roundIndex,
                                Path workspace, SseEmitter emitter) {
        PlanProperties.Nudge cfgP = planProperties.getNudge();
        if (!cfgP.isEnabled()) return;
        if (planService.hasPlan(userId, conversationId)) return;                 // 已有计划 → 不打扰
        if (PlanNudgePolicy.isCoolingDown(roundIndex, st.lastNudgeRound, cfgP.getCooldown())) return;

        List<PlanNudgePolicy.FileOp> ops = extractFileOps(records);
        int verifyFails = countVerifyFailures(records);
        int[] modifiedFiles = new int[1];
        PlanNudgePolicy.Signals sig = PlanNudgePolicy.detect(
                ops, verifyFails, roundIndex + 1, st.lastAssistantText,
                nudgeConfig(), versionResolver(workspace), modifiedFiles);

        PlanNudgePolicy.Level level = PlanNudgePolicy.decide(sig, st.softAlreadyFired, false);
        if (level == PlanNudgePolicy.Level.NONE) return;
        if (level == PlanNudgePolicy.Level.HARD && !cfgP.isHardAllowed()) {
            level = PlanNudgePolicy.Level.SOFT;   // mode=soft 时只提醒，不强制
        }

        st.lastNudgeRound = roundIndex;
        st.lastSignals = sig.names();
        String evidence = String.join("；", sig.descriptions(modifiedFiles[0], verifyFails, roundIndex + 1));
        System.out.println("[PlanNudge] 第 " + (roundIndex + 1) + " 轮命中信号 " + sig.names()
                + "（修改文件 " + modifiedFiles[0] + " 个 / 校验失败 " + verifyFails + " 次）→ " + level);

        // 提醒文案**引用具体命中的信号**，不说空话
        msgs.add(new SystemMessage("🧭 我观察到：" + evidence
                + "。这个任务已经明显超出「一轮就能说完」的规模，"
                + "请**立刻用 createPlan 把还没做的剩余步骤列成一份清单**，"
                + "然后用 updatePlanStep 逐步上报、按清单继续做。"));

        if (level == PlanNudgePolicy.Level.HARD) {
            st.hardCount++;
            st.forceRound[0] = roundIndex + 1;   // 只作用于**下一轮**；下下轮自动失效
            System.out.println("[PlanNudge] HARD：下一轮（第 " + (roundIndex + 2)
                    + " 轮）强制调用 createPlan，strategy=" + cfgP.getHard().getStrategy());
        } else {
            st.softCount++;
            st.softAlreadyFired = true;
            System.out.println("[PlanNudge] SOFT：已注入提醒（引用命中信号）");
        }

        if (emitter != null) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "text");
            ev.put("content", "\n\n🧭 检测到任务可能在偏离轨道（" + String.join("、", sig.names())
                    + "），已提醒模型补充执行计划。\n");
            sendJson(emitter, "text", ev);
        }
    }

    private boolean isPlanTool(String toolName) {
        return "createPlan".equalsIgnoreCase(toolName) || "updatePlanStep".equalsIgnoreCase(toolName);
    }

    /**
     * 推全量计划给前端（每次状态变化都推，前端面板实时打勾）。
     * 顺带带上「已用轮次 / 总预算」，这样用户等待时能看见"它跑到第几轮了"，而不是干等。
     */
    private void pushPlanEvent(SseEmitter emitter, Long userId, String conversationId,
                              int iteration, int budget) {
        List<PlanStep> steps = planService.stepsOf(userId, conversationId);
        if (steps.isEmpty()) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "plan");
        p.put("steps", steps);
        p.put("iteration", iteration);
        p.put("budget", budget);
        sendJson(emitter, "plan", p);
    }

    /**
     * 有步骤被标记 blocked（重拆过一层还是失败）时推一个"需要人工介入"事件。
     * 这是"只允许拆一层"的出口：机器不再自己套娃，把问题显式交给人。
     */
    private void pushBlockedEvents(SseEmitter emitter, Long userId, String conversationId, Set<String> blockedSeen) {
        for (PlanStep s : planService.stepsOf(userId, conversationId)) {
            if (PlanStep.BLOCKED.equals(s.getStatus()) && blockedSeen.add(s.getStepId())) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("type", "blocked");
                b.put("stepId", s.getStepId());
                b.put("description", s.getDescription());
                b.put("reason", s.getActualResult());
                b.put("message", "步骤 " + s.getStepId() + " 已自动重拆一次仍未成功，已停止自动重试，需要你人工介入。");
                sendJson(emitter, "blocked", b);
                System.err.println("[Plan] 推送 blocked 事件: " + s.getStepId() + " " + s.getDescription());
            }
        }
    }

    private ToolCallContext.Scope openPermissionScope(Long userId, String conversationId, boolean streaming,
                                                       SseEmitter emitter, List<Message> messages,
                                                       AtomicInteger approvalTurn) {
        // 挂起和结果必须分开去重：同一个 approvalId 先记挂起、再记结果，共用一个集合会把结果挡掉。
        // 子代理线程会并发进入这两个回调，所以用并发集合。
        java.util.Set<String> pendingRecorded = ConcurrentHashMap.newKeySet();
        java.util.Set<String> resultRecorded = ConcurrentHashMap.newKeySet();
        // 非流式没有 SSE 通道把审批卡送出去。events 保持 null，ASK 工具走原来的 NEED_STREAM，
        // 不进入没人能看见的审批等待。审批补记只存在于流式回调里。
        ToolCallContext.Events events = null;
        if (streaming && emitter != null) {
            events = new ToolCallContext.Events() {
                @Override public void approval(Map<String, Object> payload) {
                    sendJson(emitter, "approval", payload);
                    recordApprovalAudit(payload, approvalTurn, pendingRecorded, conversationId, userId, true);
                }
                @Override public void approvalResult(Map<String, Object> payload) {
                    sendJson(emitter, "approval_result", payload);
                    recordApprovalAudit(payload, approvalTurn, resultRecorded, conversationId, userId, false);
                }
                @Override public void spilled(Map<String, Object> payload) {
                    sendJson(emitter, "mcp_output_spilled", payload);
                }
            };
        }
        return ToolCallContext.open(new ToolCallContext(
                userId, conversationId, streaming, false, events,
                () -> mcpClientManager.isConversationTainted(conversationId)
                        || McpUntrustedGuard.isTainted(messages)));
    }

    /**
     * 审批补记。挂起和结果各记一行，turnIndex 用当前模型轮的 i，phase 用 APPROVAL_RESOLVED，
     * 这样 token 列和 prompt 快照都保持空。记不下来只打日志，不改变审批本身。
     *
     * @param pending true 记挂起，false 按 payload 的 status 记结果
     */
    private void recordApprovalAudit(Map<String, Object> payload, AtomicInteger approvalTurn,
                                     java.util.Set<String> seen, String conversationId, Long userId,
                                     boolean pending) {
        try {
            Object idObj = payload == null ? null : payload.get("approvalId");
            String approvalId = idObj == null ? null : String.valueOf(idObj);
            if (approvalId == null || approvalId.isBlank() || !seen.add(approvalId)) return;
            int turn = approvalTurn.get();
            if (turn < 0) {
                System.err.println("[AgentTurn] 审批补记时还没有轮次，跳过 approvalId=" + approvalId);
                return;
            }
            String endReason = pending
                    ? AgentTurnRecord.END_APPROVAL_PENDING
                    : approvalEndReason(payload.get("status"));
            if (endReason == null) return;
            agentTurnRecorder.recordTurn(new com.example.jingjingai2.agent.turn.TurnState(
                    conversationId, userId, turn,
                    com.example.jingjingai2.agent.turn.TurnState.PHASE_APPROVAL_RESOLVED,
                    null, null, null, null,
                    com.example.jingjingai2.agent.turn.TurnState.COMPACT_NONE,
                    null, null, null, null, null, java.util.List.of(), endReason));
        } catch (Exception e) {
            System.err.println("[AgentTurn] 审批补记失败（不影响审批）: " + e.getMessage());
        }
    }

    /** 审批结果状态到 end_reason。未知状态返回 null，不记。批准只表示用户同意，不表示工具已执行。 */
    static String approvalEndReason(Object status) {
        String s = status == null ? "" : String.valueOf(status);
        return switch (s) {
            case "APPROVED" -> AgentTurnRecord.END_APPROVED;
            case "REJECTED" -> AgentTurnRecord.END_USER_REJECTED;
            case "EXPIRED" -> AgentTurnRecord.END_APPROVAL_EXPIRED;
            case "CANCELLED" -> AgentTurnRecord.END_APPROVAL_CANCELLED;
            default -> null;
        };
    }

    private static void closePermissionScope(ToolCallContext.Scope[] holder) {
        if (holder != null && holder[0] != null) {
            holder[0].close();
            holder[0] = null;
        }
    }

    /** 没经过网关时沿用旧口径：不算高危、视为已执行。 */
    private static boolean[] auditFlags() {
        ToolCallContext.Audit audit = ToolCallContext.takeAudit();
        if (audit == null) return new boolean[]{false, true};
        return new boolean[]{audit.highRisk, audit.approved};
    }

    /**
     * 本轮正文的"边生成边推送"缓冲（SSE 逐段输出）。
     *
     * <p>为什么不能把每个分片直接发出去：模型偶尔把工具调用当普通文字吐出来
     * （{@code <tool_call>…</tool_call>}、{@code <function=…>}），这种文字一旦推出去，
     * 界面上就会出现一串内部标记；{@link TaskExecution#acceptableAnswer} 也据此判定"不可交付"。
     * 所以先攒一个保留窗口，确认整轮到目前为止都不是伪工具调用再推；一旦发现，这一轮整轮不推，
     * 交给收尾的整段发送（要么整段补发，要么被替换成兜底说明）。</p>
     *
     * <p>另一个作用：记账"已经推过哪些字"，收尾时只补发没推过的那一段，避免同一段正文出现两遍。</p>
     */
    private final class LiveText {
        /** 疑似标记最长压这么多字：超过就说明不是标记，立刻推出去（标记本身都不到 25 字）。 */
        private static final int MAX_HOLD = 64;
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder pushed = new StringBuilder();
        private boolean suppressed;

        /** 收到一个分片；{@code roundSoFar} 是本轮已累积的完整正文（识别伪工具调用用）。 */
        void accept(SseEmitter emitter, String chunk, CharSequence roundSoFar) {
            if (chunk == null || chunk.isEmpty() || suppressed) return;
            pending.append(chunk);
            if (TaskExecution.looksLikeFakeToolCall(roundSoFar)) {
                suppressed = true;      // 整轮不推：界面上不能出现内部标记
                pending.setLength(0);
                return;
            }
            // 标记一定以 '<' 或 '{' 开头：从最近的那个字符起压着，等它自己证明不是标记。
            // 其余部分可以立刻推 —— 这样正常正文几乎是边生成边到，只有可疑的尾巴会等。
            int marker = Math.max(pending.lastIndexOf("<"), pending.lastIndexOf("{"));
            int cut = marker >= 0 ? marker : pending.length();
            if (marker >= 0 && pending.length() - marker > MAX_HOLD) cut = pending.length();
            if (cut > 0) {
                String head = pending.substring(0, cut);
                pending.delete(0, cut);
                push(emitter, head);
            }
        }

        /** 本轮文字结束：把保留窗口里压着的尾巴推出去。 */
        void flush(SseEmitter emitter) {
            if (suppressed || pending.length() == 0) return;
            String rest = pending.toString();
            pending.setLength(0);
            push(emitter, rest);
        }

        /** 已经推给前端的正文。 */
        String pushed() { return pushed.toString(); }

        /** 新的一轮 / 收尾轮：重新记账。 */
        void reset() {
            pending.setLength(0);
            pushed.setLength(0);
            suppressed = false;
        }

        private void push(SseEmitter emitter, String text) {
            pushed.append(text);
            if (emitter == null) return;    // 非流式链路：只记账，不推送
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "text");
            e.put("content", text);
            sendJson(emitter, "text", e);
        }
    }

    /**
     * 最终正文里**还没推给前端**的那一段。
     *
     * <ul>
     *   <li>正常收尾：本轮正文已经逐段推过 → 空串，收尾不再重发整段；</li>
     *   <li>正文后面追加了说明（例如审批阻塞提示）→ 只补发追加的部分；</li>
     *   <li>正文被整段替换（用户停止 / 校验失败 / 兜底说明）→ 返回新正文，整段补发。</li>
     * </ul>
     */
    static String unsentTail(String finalText, String live) {
        if (finalText == null || finalText.isEmpty()) return "";
        if (live == null || live.isEmpty()) return finalText;
        if (finalText.startsWith(live)) return finalText.substring(live.length());
        return finalText;
    }

    /** 向 SSE 连接推送一个命名事件（JSON 载荷） */
    private boolean sendJson(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            // Write UTF-8 JSON bytes so the SSE string converter cannot replace non-ASCII text.
            byte[] json = objectMapper.writeValueAsBytes(payload);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(json, MediaType.APPLICATION_JSON));
            }
            return true;
        } catch (Exception failure) {
            if (emitter instanceof ChatStreamEmitter stream) stream.disconnected();
            return false;
        }
    }

    private static final class ChatStreamEmitter extends SseEmitter {
        private Runnable onDisconnect = () -> {};

        private ChatStreamEmitter() { super(0L); }

        private void bind(Runnable callback) {
            onDisconnect = callback;
            onTimeout(this::disconnected);
            onError(failure -> disconnected());
            onCompletion(this::disconnected);
        }

        private void disconnected() { onDisconnect.run(); }
    }

    // ==================== 子代理：冗余读提示 + 成本汇总 ====================

    /**
     * 子代理已经读过的文件、主 agent 又整份读了一遍 → 在工具结果**前面**加一行提醒。
     * 主 agent 一次 readFile 的观测（**只加一行、不拦截**）：
     *  - 整文件读 → {@code mainFullReadCount +1}；
     *  - 路径已在子代理台账里 → 再 {@code redundantReadCount +1} 并在结果前加提醒。
     * 带 offset/limit 的局部读两边都不计。
     *
     * 为什么是"加一行"而不是"拦下来"：这是本项目自己的设计，**不硬拦**。
     * 拦掉会破坏"工具读不到就卡死"的既有行为；加一行只是给主 agent 一次停手的机会，
     * 真要看内容它照样看得到。
     */
    private String withRedundantReadHint(String conversationId, String toolName, String arguments,
                                         String toolResult) {
        if (toolResult == null || !isReadFileTool(toolName)) return toolResult;
        try {
            // 条件注入阈值要用：跨请求累计已读正文（落库历史不含工具返回）
            subagentTelemetry.recordMainReadChars(conversationId, toolResult.length());
            Map<String, Object> args = objectMapper.readValue(arguments, new TypeReference<>() {});
            boolean hasRange = args.get("offset") != null || args.get("limit") != null;
            Object p = args.get("path");
            if (p == null) return toolResult;
            String hint = subagentTelemetry.hintForWholeFileRead(conversationId, String.valueOf(p), hasRange);
            return hint == null ? toolResult : hint + toolResult;
        } catch (Exception e) {
            return toolResult;   // 观测代码绝不允许影响主流程
        }
    }

    private boolean isReadFileTool(String name) {
        return "readFile".equalsIgnoreCase(name) || "read_file".equalsIgnoreCase(name);
    }

    /**
     * 请求结束时的子代理成本汇总行（{@code [SubagentCost]}）。
     * 与 {@code [Usage]} 并排看：主 agent 花了多少、子代理花了多少、其中重复读了几次。
     * 拿不到真实用量时主 agent 那一段留空，由 {@code [Usage]} 行自己解释原因。
     */
    private void logSubagentCost(String conversationId, UsageTracker usage) {
        Long prompt = (usage != null && usage.hasData()) ? usage.getPromptTokens() : null;
        Long completion = (usage != null && usage.hasData()) ? usage.getCompletionTokens() : null;
        System.out.println(subagentTelemetry.summaryLine(conversationId, prompt, completion, subagentProperties));
    }

    /**
     * 主 agent 每轮输入 token 的曲线 —— 成本核算要的"每轮一个数"。
     * 这是判断"**之后的轮数**"代价的直接证据：探索省下的上下文，会在后面每一轮里持续复利
     * （每一轮输入变小 → 每轮的 prompt 计费都变小），所以"派子代理值不值"不能只看子代理自己花了多少。
     */
    private void logPromptCurve(List<Long> curve) {
        if (curve == null || curve.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[PromptCurve] 主 agent 每轮输入 token = ");
        long peak = 0;
        for (int i = 0; i < curve.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(curve.get(i));
            peak = Math.max(peak, curve.get(i));
        }
        sb.append(" | 峰值=").append(peak).append(" | 轮数=").append(curve.size());
        System.out.println(sb.toString());
    }

    /**
     * 上下文预算占用环：分子是本轮主 Agent 单次模型调用的最大 promptTokens，
     * 分母是 Harness 压缩触发预算。没有有效用量时不放 usage 字段，保持 done 事件原样。
     * 记忆整理发生在本方法之前，且不进 promptCurve，所以不计入。
     */
    private void putContextUsage(Map<String, Object> done, List<Long> promptCurve) {
        int window = activeWindow();
        ContextUsage u = ContextUsage.of(promptCurve, window, compactWindowPercent);
        if (u == null) return;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", u.promptTokens);
        usage.put("contextBudget", u.contextBudget);
        done.put("usage", usage);
    }

    /**
     * 方案C：数据驱动分发（注册表 + 反射调用）。
     * 不再用 if/else 逐个判断工具名，而是拿着 LLM 返回的工具名，
     * 去 MethodToolCallbackProvider 反射生成的『注册表(toolCallbacks)』里按名查表，
     * 命中后直接 callback.call(arguments) 反射执行。
     * 这段通用逻辑一次写好，之后新增工具只需在 toolObjects(...) 注册一行，这里零改动。
     */
    /**
     * 按名字分发并执行工具。
     * 具体匹配逻辑已抽到 {@link ToolDispatcher}，便于单元测试与复用。
     */
    /**
     * 粗判一段文本是不是"完整的 JSON 对象"（工具参数）。
     * 用来发现"流式累积出来的参数被截断"的情况 —— 截断了就退回非流式重取，别拿半截参数去执行。
     */
    private boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (!t.startsWith("{")) return false;
        try {
            objectMapper.readTree(t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String callTool(ToolCallback[] callbacks, String toolName, String arguments) {
        // 控制台日志只留脱敏后的参数：这里最容易把 token / cookie / 命令原文写进终端。
        System.out.println("[ToolCall] name=" + toolName + " args=" + SensitiveRedactor.redactForLog(arguments));
        return ToolDispatcher.dispatch(callbacks, toolName, arguments);
    }

    /**
     * 【诊断】把本次**实际提供给模型**的工具清单打到日志。
     *
     * 为什么需要它：排查"模型为什么不调某个工具"时，第一步必须先分清两种情况 ——
     * （a）工具压根没传过去（注册/过滤问题）；还是（b）传过去了、模型看到了但选择不调（提示词问题）。
     * 原来的日志只打"这一轮调用了哪些工具"，看不出"提供了哪些工具"，于是只能靠猜。
     * 有了这行，看到列表里有 createPlan 就能确定是（b），提示词/描述才是要改的地方。
     */
    private void logOfferedTools(ToolCallback[] callbacks) {
        StringBuilder dump = new StringBuilder();
        for (ToolCallback cb : callbacks) {
            if (cb != null && cb.getToolDefinition() != null) {
                dump.append(cb.getToolDefinition().name()).append(' ');
            }
        }
        System.out.println("[Tools] 提供给模型 " + callbacks.length + " 个工具：" + dump.toString().trim());
        System.out.println("[Tools] createPlan 是否在列表中："
                + (ToolDispatcher.find(callbacks, "createPlan") != null));
        boolean spawnListed = ToolDispatcher.find(callbacks, "spawnSubagent") != null;
        // 一致性检查必须用「本轮是否注入」(effectiveEnabled)，不能用总开关 isEnabled()：
        // 条件注入打开时总开关仍为 true，但本轮可能 omitted/stripped —— 用总开关会假阳性 WARN
        // （2026-09 opt/S1 实测：enabled=true 与 omitted/stripped 同时出现）。
        boolean injected = effectiveEnabled();
        boolean promptHas = systemPrompt().contains("spawnSubagent");
        System.out.println("[SubagentGate] masterEnabled=" + subagentProperties.isEnabled()
                + " injected=" + injected
                + " spawnTool=" + (spawnListed ? "listed" : "omitted")
                + " promptRules=" + (promptHas ? "present" : "stripped")
                + " partialResumeFirst=" + subagentProperties.isPartialResumeFirst()
                + " conditionalInjection=" + subagentProperties.isConditionalInjection());
        if (injected != spawnListed || injected != promptHas) {
            System.out.println("[SubagentGate] WARN 本轮注入状态与下发不一致：injected=" + injected
                    + " spawnListed=" + spawnListed + " promptHas=" + promptHas);
        }
        boolean memOn = userMemoryProperties.isEnabled();
        boolean memTool = ToolDispatcher.find(callbacks, "saveUserMemory") != null;
        boolean memPrompt = systemPrompt().contains("saveUserMemory");
        System.out.println("[UserMemoryGate] enabled=" + memOn
                + " saveTool=" + (memTool ? "listed" : "omitted")
                + " promptRules=" + (memPrompt ? "present" : "stripped"));
        if (memOn != memTool || memOn != memPrompt) {
            System.out.println("[UserMemoryGate] WARN 开关与下发不一致：enabled=" + memOn
                    + " saveListed=" + memTool + " promptHas=" + memPrompt);
        }
        boolean projMemOn = memoryProperties.isEnabled();
        boolean projMemTool = ToolDispatcher.find(callbacks, "saveMemory") != null;
        boolean projMemPrompt = systemPrompt().contains("saveMemory");
        System.out.println("[MemoryGate] enabled=" + projMemOn
                + " saveTool=" + (projMemTool ? "listed" : "omitted")
                + " promptRules=" + (projMemPrompt ? "present" : "stripped"));
        if (projMemOn != projMemTool || projMemOn != projMemPrompt) {
            System.out.println("[MemoryGate] WARN 开关与下发不一致：enabled=" + projMemOn
                    + " saveListed=" + projMemTool + " promptHas=" + projMemPrompt);
        }
    }

    /** 把工作区 memory/*.md 追加到消息末尾。没有目录或关掉则不插消息。 */
    private void injectWorkspaceMemory(List<Message> msgs, Path workspace) {
        WorkspaceMemory.Snapshot snap = workspaceMemory.load(workspace);
        System.out.println("[Memory] enabled=" + snap.enabled
                + " injected=" + snap.injected
                + " reason=" + snap.reason
                + " files=" + snap.files
                + " chars=" + snap.chars);
        for (MemoryItemObservation o : snap.observations) {
            System.out.println("[MemoryItem] " + o.toLogLine());
        }
        if (!snap.injected || snap.text == null || snap.text.isBlank()) return;
        msgs.add(new SystemMessage(snap.text));
    }

    /** 本轮有实质进展时，短调用更新 MEMORY.md。失败不影响对用户的回答。 */
    private void maybeConsolidateMemory(OpenAiChatModel model, Path workspace,
                                        String userMessage, String assistantText,
                                        List<ChatResponse.ToolCallRecord> records,
                                        Long userId, String conversationId,
                                        UsageTracker usage) {
        MemoryConsolidator.Decision d = memoryConsolidator.decide(records);
        if (!d.run) {
            System.out.println("[Memory] consolidate=skipped reason=" + d.reason);
            return;
        }
        if (model == null || workspace == null) {
            System.out.println("[Memory] consolidate=skipped reason=no-model-or-workspace");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            String planSnap = formatPlanForMemory(userId, conversationId);
            String payload = memoryConsolidator.buildUserPayload(
                    workspace, userMessage, assistantText, records, planSnap);
            ToolCallingChatOptions noTools = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false).build();
            org.springframework.ai.chat.model.ChatResponse wr = com.example.jingjingai2.agent.TaskModelCalls.call(model, new Prompt(
                    List.of(new SystemMessage(MemoryConsolidator.SYSTEM_PROMPT),
                            new UserMessage(payload)),
                    noTools));
            if (usage != null) usage.accept(wr);
            String raw = "";
            if (wr != null && wr.getResult() != null && wr.getResult().getOutput() != null) {
                String t = wr.getResult().getOutput().getText();
                if (t != null) raw = t;
            }
            String body = memoryConsolidator.parseBody(raw);
            String save = memoryConsolidator.writeIndex(workspace, body);
            boolean wrote = save != null && save.startsWith("✅");
            System.out.println("[Memory] consolidate=ran reason=" + d.reason
                    + " wrote=" + wrote
                    + " chars=" + (body == null ? 0 : body.length())
                    + " ms=" + (System.currentTimeMillis() - t0));
        } catch (Exception e) {
            System.out.println("[Memory] consolidate=failed ms="
                    + (System.currentTimeMillis() - t0) + " err=" + e.getMessage());
        }
    }

    private String formatPlanForMemory(Long userId, String conversationId) {
        if (userId == null || conversationId == null) return "";
        List<PlanStep> steps = planService.stepsOf(userId, conversationId);
        if (steps == null || steps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PlanStep st : steps) {
            if (st == null) continue;
            sb.append(st.icon()).append(' ')
                    .append(st.getStepId() == null ? "?" : st.getStepId()).append(' ')
                    .append(st.getStatus() == null ? "" : st.getStatus()).append(' ')
                    .append(st.getDescription() == null ? "" : st.getDescription());
            if (st.getActualResult() != null && !st.getActualResult().isBlank()) {
                sb.append(" → ").append(MemoryConsolidator.clip(st.getActualResult(), 120));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String lastUserText(List<Message> msgs) {
        if (msgs == null) return "";
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m instanceof UserMessage) {
                String t = m.getText();
                return t == null ? "" : t;
            }
        }
        return "";
    }

    /**
     * 自动编译校验（自愈闭环的"校验"环节）。
     *
     * 把结果作为一条 {@code verifyCompile} 工具记录写进 toolCallRecords 与审计表，
     * 于是前端终端面板、DB 审计、接口响应三处都能看到"这次改完到底编没编过"。
     *
     * @param root 当前会话的工作区根目录（编译范围）
     */
    private CodeVerifier.Result runVerifyCompile(Path root, Long userId, String conversationId,
                                                 List<ChatResponse.ToolCallRecord> records, Integer turnIndex) {
        long start = System.currentTimeMillis();
        CodeVerifier.Result r = codeVerifier.verify(root);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[VerifyCompile] ran=" + r.ran() + " success=" + r.success()
                + " elapsed=" + elapsed + "ms");

        ChatResponse.ToolCallRecord rec = new ChatResponse.ToolCallRecord();
        rec.setToolName("verifyCompile");
        rec.setInput("{\"scope\":\"workspace\"}");
        rec.setResult(r.output());
        rec.setSuccess(r.success());
        rec.setElapsedMs(elapsed);
        records.add(rec);

        if (userId != null) {
            toolCallLogService.log(userId, conversationId, "verifyCompile", "{}",
                    r.output(), r.success(), elapsed, false, true, turnIndex);
        }
        return r;
    }

    /** 编译失败时回灌给模型的提示：明确要求"先定位、再精确修正"，并给出当前轮次 */
    private String verifyFeedback(String compileOutput, int round) {
        return "[自动编译校验] 你刚刚修改了工作区文件，系统已自动执行一次编译校验，结果是「失败」"
                + "（第 " + round + "/" + MAX_VERIFY_ROUNDS + " 轮重试）。\n"
                + "请根据下面的 javac 报错，用 grepFile 定位到具体文件与行号，再用 editFile 做精确修正"
                + "（不要整文件重写）。修正后系统会自动再校验一次。\n"
                + "如果你判断确实无法修复，请直接说明原因，不要反复做无意义的改动。\n\n"
                + "----- 编译输出 -----\n" + compileOutput;
    }

    /**
     * 记录模型的一轮（agent_turn 一行 + prompt_snapshot 按哈希去重）。
     * 调用点在"模型返回之后、工具执行之前"。写失败由 {@link AgentTurnRecorder} 吞掉，不影响对话。
     *
     * <p>压缩只发生在构造消息的那一次，所以只有第 0 轮可能是 SUMMARY，其后都是 NONE。
     * usage 取的是"本轮请求"的增量。本轮一个用量分片都没带回时写 null，不写 0
     * （前面几轮已经有用量时，不能把"本轮增量为 0"当成拿到了 0）。
     */
    private void recordTurn(String conversationId, Long userId, int turnIndex,
                            OpenAiChatModel activeModel, ToolCallback[] roundCallbacks,
                            List<Message> messages, String baseSystemPrompt,
                            ContextCompactor.CompactStats compactStats,
                            boolean perRound,
                            int responsesBefore, long promptBefore, long completionBefore,
                            UsageTracker usageNow, String endReason, RoundCompactor.Guard guard,
                            org.springframework.ai.chat.model.ChatResponse usageResponse) {
        try {
            ContextCompactor.CompactStats stats = compactStats == null
                    ? new ContextCompactor.CompactStats(false, 0, 0, 0) : compactStats;
            boolean compactedThisRound = perRound ? stats.compacted() : turnIndex == 0 && stats.compacted();
            boolean gotUsage = usageNow.getResponsesWithUsage() != responsesBefore
                    || usageNow.getPromptTokens() != promptBefore
                    || usageNow.getCompletionTokens() != completionBefore;
            Long inDelta = gotUsage ? usageDelta(usageNow.getPromptTokens(), promptBefore) : null;
            Long outDelta = gotUsage ? usageDelta(usageNow.getCompletionTokens(), completionBefore) : null;
            String toolsJson = PromptCanonical.toolsJson(roundCallbacks);
            // 使用 prepend 时实际插入的那条基础提示，不在记录阶段重新构造；避免 MCP 缓存变化导致漂移。
            String staticPrompt = baseSystemPrompt;
            String compactMode = TurnState.COMPACT_NONE;
            if (compactedThisRound) {
                compactMode = guard != null && "DIGEST".equals(guard.lastMode)
                        ? TurnState.COMPACT_DIGEST : TurnState.COMPACT_SUMMARY;
            }
            TurnState state = new TurnState(
                    conversationId, userId, turnIndex, TurnState.PHASE_MODEL_TURN,
                    modelNameOf(activeModel), staticPrompt,
                    TurnState.dynamicText(staticPrompt, messages), toolsJson,
                    compactMode,
                    compactedThisRound ? stats.tokensBeforeEst() : null,
                    compactedThisRound ? stats.tokensAfterEst() : null,
                    compactedThisRound ? stats.compactedCount() : 0,
                    inDelta == null ? null : Math.toIntExact(inDelta),
                    outDelta == null ? null : Math.toIntExact(outDelta),
                    messages, endReason);
            agentTurnRecorder.recordTurn(state);
            logEstimateVsUsage(conversationId, turnIndex, state, inDelta, outDelta, usageResponse);
        } catch (Exception e) {
            System.err.println("[AgentTurn] 组装轮次记录失败（不影响对话）: " + e.getMessage());
        }
    }

    private boolean totalScope() {
        return compactScope != null && "total".equalsIgnoreCase(compactScope);
    }

    private void accountSummary(UsageTracker requestUsage, Long userId, String conversationId, int turnIndex,
                                 OpenAiChatModel activeModel, ContextCompactor.SummaryUsage summary,
                                 ContextCompactor.CompactStats stats) {
        accountSummary(requestUsage, userId, conversationId, turnIndex, activeModel, summary, stats, null, "SUMMARY");
    }

    /**
     * 一次摘要模型调用只记一行 COMPACTION。真实用量另记进本请求的 UsageTracker，
     * 且发生在本轮模型增量快照之前，所以不会再进 MODEL_TURN。
     * 估算和失败不写入 UsageTracker，避免把估算值当成服务商账单。
     */
    private void accountSummary(UsageTracker requestUsage, Long userId, String conversationId, int turnIndex,
                                 OpenAiChatModel activeModel, ContextCompactor.SummaryUsage summary,
                                 ContextCompactor.CompactStats stats, String reason, String compactMode) {
        if (summary == null) return;
        int before = stats == null ? 0 : stats.tokensBeforeEst();
        int after = stats == null ? 0 : stats.tokensAfterEst();
        int count = stats == null ? 0 : stats.compactedCount();
        agentTurnRecorder.recordCompaction(conversationId, userId, turnIndex, modelNameOf(activeModel),
                before, after, count, summary, reason, compactMode);
        if (requestUsage != null && !summary.estimated() && (summary.inputTokens() > 0 || summary.outputTokens() > 0)) {
            requestUsage.acceptMeasured(summary.inputTokens(), summary.outputTokens(), summary.cachedPromptTokens());
        }
    }

    /**
     * 是否真的因为轮数上限退出。其余结束方式各有自己的原因，不能记成 MAX_ITERATIONS。
     * 非流式没有空响应这一路，调用时 emptyResponse 传 false。
     */
    static boolean iterationCapReached(boolean finishedNaturally, boolean cancelled, boolean spinning,
                                       boolean emptyResponse, boolean budgetStop, boolean wrapUp) {
        return !finishedNaturally && !cancelled && !spinning && !emptyResponse && !budgetStop && !wrapUp;
    }

    /**
     * 轮数用尽的补记。不走 {@link #recordTurn}：那条会按用量增量重算 token，
     * 而循环里那一轮已经记过 TOOL_CALLING，这里只补结束原因，token 列留空。
     * turnIndex 用 roundsUsed，即最后一轮的 i + 1，与循环里那一行（用 i）错开。
     */
    private void recordIterationCap(String conversationId, Long userId, int roundsUsed,
                                    OpenAiChatModel activeModel) {
        if (roundsUsed <= 0) return;
        try {
            agentTurnRecorder.recordTurn(new com.example.jingjingai2.agent.turn.TurnState(
                    conversationId, userId, roundsUsed, com.example.jingjingai2.agent.turn.TurnState.PHASE_MODEL_TURN,
                    modelNameOf(activeModel), null, null, null,
                    com.example.jingjingai2.agent.turn.TurnState.COMPACT_NONE,
                    null, null, null, null, null, java.util.List.of(),
                    AgentTurnRecord.END_MAX_ITERATIONS));
        } catch (Exception e) {
            System.err.println("[AgentTurn] 记录轮数用尽失败（不影响对话）: " + e.getMessage());
        }
    }

    /**
     * total 口径才在每轮模型调用前判断。history 口径返回 null，调用方继续用进循环前那一次的统计。
     * 返回值非 null 时，这一轮的 compact_mode 以它为准，不再固定成「只有第 0 轮可能是 SUMMARY」。
     */
    private ContextCompactor.CompactStats compactTotalIfEnabled(List<Message> messages, ToolCallback[] tools,
                                                                 int round, RoundCompactor.Guard guard,
                                                                 Long userId, String conversationId,
                                                                 OpenAiChatModel activeModel, UsageTracker requestUsage) {
        if (workingSetEnabled) return null;
        if (compactScope == null || !"total".equalsIgnoreCase(compactScope)) return null;
        int threshold = compactThresholdTotal <= 0 ? 32000 : compactThresholdTotal;
        int gap = compactMinGap <= 0 ? 3 : compactMinGap;
        double growth = compactGrowthRatio <= 0 ? 0.30 : compactGrowthRatio;
        int minBudget = compactMinMessageBudget <= 0 ? 1000 : compactMinMessageBudget;
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, tools, round, threshold, gap, growth, minBudget, guard,
                body -> {
                    ContextCompactor.SummaryUsage usage = contextCompactor.summarizeTracked(body, activeModel);
                    return new RoundCompactor.Summary(usage.text(), usage.inputTokens(), usage.outputTokens(),
                            usage.estimated(), usage.fallback());
                },
                compactStrategy,
                Math.max(0, digestKeepToolGroups),
                tieredAcceptRatio);
        System.out.println("[Compactor] scope=" + outcome.decision().scope()
                + " value=" + outcome.decision().value()
                + " threshold=" + outcome.decision().threshold()
                + " fixed=" + outcome.fixedTokens()
                + " messageBudget=" + outcome.messageBudget()
                + " trigger=" + outcome.decision().trigger()
                + (outcome.decision().reason() == null ? "" : " reason=" + outcome.decision().reason()));
        if (outcome.acted()) {
            ContextCompactor.SummaryUsage tracked = contextCompactor.takeSummaryUsage();
            RoundCompactor.Summary summary = outcome.summary();
            ContextCompactor.SummaryUsage usage = tracked != null ? tracked
                    : summary == null
                    ? new ContextCompactor.SummaryUsage("", 0, 0, false, false)
                    : new ContextCompactor.SummaryUsage(summary.text(), summary.inputTokens(),
                    summary.outputTokens(), summary.estimated(), summary.fallback());
            accountSummary(requestUsage, userId, conversationId, round, activeModel, usage,
                    new ContextCompactor.CompactStats(true, outcome.decision().value(), outcome.after(),
                            outcome.compactedCount()), outcome.decision().reason(), outcome.compactMode());
        } else {
            contextCompactor.takeSummaryUsage();
        }
        return new ContextCompactor.CompactStats(outcome.compacted(), outcome.decision().value(),
                outcome.after(), outcome.compactedCount(),
                outcome.decision().reason() == null
                        ? (outcome.compacted() ? "SUMMARY" : "NONE") : outcome.decision().reason());
    }

    /** 增量为负（用量被重置之类）时返回 null，不当成拿到了数。 */
    private static Long usageDelta(long after, long before) {
        long d = after - before;
        return d < 0 ? null : d;
    }

    /**
     * 本轮分类估算与真实用量的对照。估算是字符口径，真实值是服务商口径，两者不相等是正常的。
     * 真实值缺失时打印 null，不打印 0。cached 字段本轮没出现时同样打印 null。
     * 只记主请求这一轮，摘要调用走 COMPACTION 行，不进这里。
     */
    private void logEstimateVsUsage(String conversationId, int turnIndex, TurnState state,
                                    Long inputTokens, Long outputTokens,
                                    org.springframework.ai.chat.model.ChatResponse response) {
        TurnState.EstimateSplit est = TurnState.split(
                state.systemPrompt(), state.messages(), state.toolsCanonicalJson());
        Long cached = inputTokens == null ? null : TurnState.cachedTokensOfResponse(response);
        System.out.println("[ContextSplit] conversation=" + conversationId
                + " turn=" + turnIndex
                + " estSystem=" + est.system()
                + " estInjected=" + est.injected()
                + " estHistorySummary=" + est.historySummary()
                + " estUserMemory=" + est.userMemory()
                + " estWorkspaceMemory=" + est.workspaceMemory()
                + " estPlan=" + est.plan()
                + " estNudge=" + est.nudge()
                + " estUnknown=" + est.unknown()
                + " estHistory=" + est.history()
                + " estToolResults=" + est.toolResults()
                + " estTools=" + est.tools()
                + " estTotal=" + est.total()
                + " inputTokens=" + inputTokens
                + " outputTokens=" + outputTokens
                + " cachedTokens=" + cached);
    }

    private static String modelNameOf(OpenAiChatModel model) {
        try {
            if (model != null && model.getDefaultOptions() != null) {
                return model.getDefaultOptions().getModel();
            }
        } catch (Exception ignored) {
            // 模型名拿不到不影响记录
        }
        return null;
    }

    /** 把一次编译校验结果压成一句人话，用于追加到最终回答里 */
    private String describeVerify(CodeVerifier.Result r) {
        if (!r.ran()) {
            return "已跳过（" + r.output() + "）";
        }
        return r.success() ? "通过 ✅" : "未通过 ❌\n" + r.output();
    }

    private String listDirectory(String path) {
        try {
            String cmd = isWindows()
                    ? (path == null || path.isBlank() ? "dir" : "dir \"" + path + "\"")
                    : (path == null || path.isBlank() ? "ls -la" : "ls -la \"" + path + "\"");

            StringBuilder sb = new StringBuilder();
            Process p = Runtime.getRuntime().exec(isWindows()
                    ? new String[]{"cmd.exe", "/c", cmd}
                    : new String[]{"/bin/sh", "-c", cmd});

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return sb.toString().trim();
        } catch (Exception e) {
            return "列出目录失败: " + e.getMessage();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private ChatResponse parseAiResponse(String aiResponse,
                                          List<ChatResponse.ToolCallRecord> toolCallRecords) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return new ChatResponse("chat", "");
        }

        // 检查音乐数据块
        Matcher musicMatcher = MUSIC_BLOCK_PATTERN.matcher(aiResponse);
        if (musicMatcher.find()) {
            String jsonStr = musicMatcher.group(1);
            String messageText = aiResponse.replace(musicMatcher.group(0), "").trim();
            if (messageText.isEmpty()) messageText = "找到啦，看看有没有喜欢的~";
            try {
                List<Map<String, Object>> rawSongs =
                        objectMapper.readValue(jsonStr, new TypeReference<>() {});
                List<ChatResponse.SongItem> songs = new ArrayList<>();
                for (Map<String, Object> s : rawSongs) {
                    songs.add(new ChatResponse.SongItem(
                            String.valueOf(s.get("id")),
                            (String) s.get("name"),
                            (String) s.get("artist"),
                            s.get("pic") != null ? (String) s.get("pic") : ""
                    ));
                }
                ChatResponse resp = new ChatResponse("music_search", messageText, songs);
                resp.setToolCalls(toolCallRecords.isEmpty() ? null : toolCallRecords);
                return resp;
            } catch (Exception e) {
                return new ChatResponse("chat", aiResponse);
            }
        }

        ChatResponse resp = new ChatResponse("chat", aiResponse);
        resp.setToolCalls(toolCallRecords.isEmpty() ? null : toolCallRecords);
        return resp;
    }

    @GetMapping("/history")
    public List<MessageRecord> getHistory(
            @RequestParam String conversationId,
            HttpServletRequest request
    ) {
        Long userId = (Long) request.getAttribute("userId");
        return historyService.getHistory(userId, conversationId);
    }
}
