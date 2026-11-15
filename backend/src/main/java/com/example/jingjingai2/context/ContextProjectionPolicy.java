package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从完整源账本选择本轮真正发给模型的消息。
 *
 * <p>shadow 模式只分类，返回原来的 messages。enabled 模式生成一份新的消息列表：
 * 旧助手回复换成一行带 locator 的说明；可以可靠恢复的旧工具交互**整组**把正文换成一行
 * 带签名引用的省略标记。两者都只减少发给模型的正文，源账本和数据库原文不动。
 * 选择发生在任何压缩之前，压缩器拿到的是这份副本，改不到规范来源。
 */
public final class ContextProjectionPolicy {

    public enum DecisionKind { KEEP, RETIRE, DISCARD }

    public record Decision(String id, DecisionKind kind, String reason, int tokensEst) {}

    public record Stats(
            int totalPromptTokensEst,
            int fixedTokensEst,
            int toolSchemaTokensEst,
            int historyTokensEst,
            int activeTaskTokensEst,
            int userMemoryTokensEst,
            int workspaceMemoryTokensEst,
            int planTokensEst,
            int toolResultTokensEst,
            /** 用来和软预算比较的正文估算：历史 + 工具结果。 */
            int budgetPressureTokensEst,
            int recoverableToolTokensEst,
            int nonRecoverableToolTokensEst,
            int shadowKeepTokensEst,
            int shadowRetireTokensEst,
            int shadowDiscardTokensEst,
            int shadowProjectedTokensEst,
            int candidateCount,
            Map<String, Integer> reasonCounts) {}

    public record PrefixStability(
            String actualPrefixHash,
            String shadowPrefixHash,
            int actualFirstChange,
            int shadowFirstChange,
            boolean actualTailAppendOnly,
            boolean shadowTailAppendOnly,
            boolean actualRewroteEarlier,
            boolean shadowRewroteEarlier,
            boolean toolSchemaChanged) {}

    public record Result(List<Message> messages, List<Decision> decisions, Stats stats, String error) {
        public boolean shadowUntouched() {
            return error == null;
        }
    }

    /** 只用于影子候选，不是已验证的生产阈值。 */
    static final int LARGE_RESULT_TOKENS = 1500;

    /** 最近完整交互轮次的保留数。轮次按模型调用计数，从 0 开始。 */
    static final int RECENT_ROUNDS = 3;

    /**
     * 工具结果自己的轮次保护。和 {@link #RECENT_ROUNDS} 分开：工具结果总在本轮 user 之后，
     * 按「最近用户回合」算的话永远落在保护区内，永远不可能退休。这里只看同一次请求内
     * 产生该结果的模型轮次（{@link ContextSourceLedger.Item#producedRound()}）。
     *
     * <p>轮次只是候选条件，不是唯一依据：还要同时满足预算压力、可恢复签名快照、
     * 快照仍可读且内容完整、计划步骤已完成等条件。
     */
    static final int TOOL_RESULT_RECENT_ROUNDS = 3;

    /**
     * 恢复结果的保护轮次。模型刚通过 readFile 读快照、或 searchContext read 取回旧内容，
     * 那份正文就是它这一步要用的证据；下一轮立刻把它换成省略标记等于把刚取回的东西又拿走。
     *
     * <p>比 {@link #TOOL_RESULT_RECENT_ROUNDS} 更长，因为恢复是模型**专门为当前步骤**发起的
     * 一次取回。到期后一切照旧：预算压力、可恢复性、轮次、计划步骤、快照完整性仍要重新全部满足。
     * 保护身份来自工具名 + 入参结构（签名引用 / operation=read），不看正文内容，也不跨请求。
     */
    static final int RECOVERY_PROTECT_ROUNDS = 6;

    /**
     * 退休的旧助手消息在模型视图里留下的一行说明。
     *
     * <p>这里给的是 <strong>可直接读取的 locator</strong>（{@code message|<id>}），不是让人去搜的关键词。
     * 早先的措辞是“用 searchContext 搜索 message &lt;id&gt;”，但 id 并不出现在消息正文里，
     * 按关键词搜永远搜不到；降序结果又有条数上限，旧消息会被永久遮蔽。
     * 改成 read + locator 之后，模型拿到的就是一条可直接命中的引用。
     */
    static final String RETIRED_NOTE_PREFIX =
            "[旧回复已移出上下文，需要原文时用 searchContext operation=read locator=message|";

    /**
     * 必须保留的内容仍然放不进硬窗口。调用方据此停止本轮，不发送、不静默丢内容。
     */
    public static final String HARD_WINDOW_EXCEEDED = "HARD_WINDOW_EXCEEDED";

    private ContextProjectionPolicy() {}

    public static Result project(ContextSourceLedger ledger, List<Message> actual, TaskPlan plan,
                                 int round, int toolSchemaTokensEst, int softBudgetTokens, int hardWindowTokens,
                                 boolean shadow, boolean enabled) {
        return project(ledger, actual, plan, round, toolSchemaTokensEst, softBudgetTokens, hardWindowTokens,
                shadow, enabled, ToolSnapshotProbe.SANDBOX_FREE);
    }

    /**
     * 带快照探测的投影入口。生产必须走这个重载，并传入当前请求的沙箱探测
     * （{@code FileTool#snapshotStatus}），否则「可恢复」只证明了文件存在，没证明读得到。
     */
    public static Result project(ContextSourceLedger ledger, List<Message> actual, TaskPlan plan,
                                 int round, int toolSchemaTokensEst, int softBudgetTokens, int hardWindowTokens,
                                 boolean shadow, boolean enabled, ToolSnapshotProbe probe) {
        List<Message> original = actual == null ? List.of() : List.copyOf(actual);
        ToolSnapshotProbe snapshotProbe = probe == null ? ToolSnapshotProbe.SANDBOX_FREE : probe;
        if (!shadow && !enabled) {
            return new Result(original, List.of(), empty(toolSchemaTokensEst), null);
        }
        if (ledger == null) {
            return new Result(original, List.of(), empty(toolSchemaTokensEst), "LEDGER_MISSING");
        }
        try {
            List<Decision> decisions = classify(ledger, original, plan, round, softBudgetTokens, hardWindowTokens,
                    snapshotProbe);
            Stats stats = stats(ledger, original, decisions, toolSchemaTokensEst);
            if (!enabled) {
                return new Result(original, decisions, stats, null);
            }
            List<Message> projected = apply(ledger, original, decisions);
            if (!MessageProtocolValidator.check(projected).ok()) {
                return new Result(original, decisions, stats, "PROJECTION_BROKE_PROTOCOL");
            }
            if (hardWindowTokens > 0
                    && projectedTokens(ledger, projected, decisions, toolSchemaTokensEst) > hardWindowTokens) {
                return new Result(original, decisions, stats, HARD_WINDOW_EXCEEDED);
            }
            // 引用真的发出去了，才需要保护快照：容量清理不能在这之后把刚退休的那份删掉。
            retainRetiredSnapshots(ledger, decisions);
            return new Result(projected, decisions, stats, null);
        } catch (RuntimeException e) {
            return new Result(original, List.of(), empty(toolSchemaTokensEst), e.getClass().getSimpleName());
        }
    }

    public static PrefixStability prefix(List<Message> previous, List<Message> actual,
                                         List<Decision> decisions, String previousTools, String currentTools) {
        List<String> before = canonical(previous);
        List<String> now = canonical(actual);
        List<String> projected = shadowCanonical(now, decisions);
        int actualChange = firstChange(before, now);
        int shadowChange = firstChange(before, projected);
        boolean toolsChanged = !text(previousTools).equals(text(currentTools));
        return new PrefixStability(hash(now), hash(projected),
                actualChange, shadowChange,
                tailOnly(before, now), tailOnly(before, projected),
                actualChange >= 0 && actualChange < before.size(),
                shadowChange >= 0 && shadowChange < before.size(),
                toolsChanged);
    }

    private static List<Decision> classify(ContextSourceLedger ledger, List<Message> actual, TaskPlan plan,
                                           int round, int softBudget, int hardWindow, ToolSnapshotProbe probe) {
        List<ContextSourceLedger.Item> visible = ledger == null ? List.of() : ledger.match(actual);
        Map<String, ContextSourceLedger.Item> latestResource = new LinkedHashMap<>();
        for (ContextSourceLedger.Item item : visible) {
            if (item.resourcePath() != null && item.version() != null) {
                latestResource.put(item.resourcePath(), item);
            }
        }
        int historyTotal = 0;
        int toolTotal = 0;
        for (ContextSourceLedger.Item item : visible) {
            if (isHistory(item)) historyTotal += item.tokensEst();
            else if (item.kind() == ContextSourceLedger.Kind.TOOL_RESULT) toolTotal += item.tokensEst();
        }
        // 预算压力看「历史 + 工具结果」。只看历史会让一个几乎全是工具结果的请求永远不退休：
        // 工具正文漏算，软预算形同虚设。历史/旧助手消息自己的退休规则仍只看历史（见 decide）。
        boolean overBudget = softBudget > 0 && historyTotal + toolTotal > softBudget;
        java.util.Set<String> retireGroups = overBudget
                ? retireableToolGroups(actual, visible, plan, round, probe) : java.util.Set.of();
        java.util.Set<String> recent = recentItems(visible);
        int historyRunning = 0;
        List<Decision> out = new ArrayList<>();
        for (ContextSourceLedger.Item item : visible) {
            if (isHistory(item)) historyRunning += item.tokensEst();
            int olderThanBudget = isHistory(item) ? historyTotal - historyRunning : 0;
            out.add(decide(item, plan, latestResource, olderThanBudget, softBudget, hardWindow, recent,
                    retireGroups, overBudget, round, probe));
        }
        return out;
    }

    /** 最近 {@link #RECENT_ROUNDS} 个用户交互及其后的全部消息。按位置算，不看有没有发送过。 */
    private static java.util.Set<String> recentItems(List<ContextSourceLedger.Item> visible) {
        List<String> userTurns = new ArrayList<>();
        for (ContextSourceLedger.Item item : visible) {
            if ("user".equals(item.role()) && isHistory(item)) userTurns.add(item.id());
        }
        java.util.Set<String> recent = new java.util.LinkedHashSet<>();
        if (userTurns.isEmpty()) return recent;
        String boundary = userTurns.get(Math.max(0, userTurns.size() - RECENT_ROUNDS));
        boolean after = false;
        for (ContextSourceLedger.Item item : visible) {
            if (item.id().equals(boundary)) after = true;
            if (after) recent.add(item.id());
        }
        return recent;
    }

    /**
     * 可以整组退休的旧工具交互。工具调用和它的结果必须一起走，缺一个就整组留下。
     * 普通文件读取、目录、搜索和没有可靠快照的正文不在这里：路径或哈希不能证明能读回原文。
     */
    private static java.util.Set<String> retireableToolGroups(List<Message> actual,
                                                               List<ContextSourceLedger.Item> visible,
                                                               TaskPlan plan, int round,
                                                               ToolSnapshotProbe probe) {
        Map<String, ContextSourceLedger.Item> byCall = new LinkedHashMap<>();
        for (ContextSourceLedger.Item item : visible) {
            if (item.kind() == ContextSourceLedger.Kind.TOOL_RESULT && item.toolCallId() != null) {
                byCall.put(item.toolCallId(), item);
            }
        }
        java.util.Set<String> groups = new java.util.LinkedHashSet<>();
        if (actual == null) return groups;
        for (Message message : actual) {
            if (!(message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant)
                    || assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
                continue;
            }
            boolean whole = true;
            for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                ContextSourceLedger.Item result = call == null ? null : byCall.get(call.id());
                if (result == null || toolResultRetireBlockedBy(result, plan, round, true, probe) != null) {
                    whole = false;
                    break;
                }
            }
            if (whole) {
                for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    groups.add(call.id());
                }
            }
        }
        return groups;
    }

    private static boolean restorable(ContextSourceLedger.Item item) {
        return item.recoverability() == ContextSourceLedger.Recoverability.RECOVERABLE
                && !item.sourceMissing()
                && (item.databaseId() != null || (item.snapshotPath() != null && item.contentHash() != null));
    }

    /**
     * 工具结果退休前的安全闸门。
     *
     * @return null 表示可以退休；否则是保留原因（写进决策，方便日志解释为什么没退）
     *
     * <p>M3 起「可恢复」不再只是「标记指向的文件存在」：必须在退休这一刻证明
     * <strong>内容哈希一致</strong>，并且（生产路径）<strong>当前请求的沙箱允许读回</strong>。
     * 校验失败一律保留原始正文，不做「先退休、读不到再说」。
     *
     * <p>轮次只是候选条件之一。没有有效签名快照、快照读不回来、计划步骤还没完成、
     * 或者当前根本没有预算压力，都不退休。
     */
    private static String toolResultRetireBlockedBy(ContextSourceLedger.Item item, TaskPlan plan,
                                                    int round, boolean budgetPressure,
                                                    ToolSnapshotProbe probe) {
        if (!budgetPressure) return "TOOL_RESULT_NO_BUDGET_PRESSURE";
        if (!item.currentRequest()) return "TOOL_RESULT_NOT_CURRENT_REQUEST";
        if (item.toolCallId() == null || item.toolCallId().isBlank()) return "TOOL_RESULT_NO_CALL_ID";
        if (item.recoverability() != ContextSourceLedger.Recoverability.RECOVERABLE || item.sourceMissing()) {
            return "TOOL_RESULT_NOT_RECOVERABLE";
        }
        if (item.snapshotPath() == null || item.snapshotPath().isBlank()) return "TOOL_RESULT_NO_SNAPSHOT";
        if (item.contentHash() == null || item.contentHash().isBlank()) return "TOOL_RESULT_NO_CONTENT_HASH";
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(item.fullText());
        if (ref == null || !ref.hasContentHash()) return "TOOL_RESULT_UNVERIFIED_SNAPSHOT";
        // 退休时校验：签名 → 路径合法性 → 沙箱授权 → 存在性 → 内容哈希。
        ToolSnapshotProbe snapshotProbe = probe == null ? ToolSnapshotProbe.SANDBOX_FREE : probe;
        String snapshotProblem = snapshotReason(snapshotProbe.check(ref));
        if (snapshotProblem != null) return snapshotProblem;
        if (round < 0 || item.producedRound() == null) return "TOOL_RESULT_ROUND_UNKNOWN";
        if (round - item.producedRound() < TOOL_RESULT_RECENT_ROUNDS) return "TOOL_RESULT_RECENT_ROUND";
        if (item.planStepId() != null && !completedStep(plan, item.planStepId())) return "TOOL_RESULT_ACTIVE_STEP";
        return null;
    }

    /** 快照校验状态 → 保留原因。OK 返回 null。 */
    private static String snapshotReason(com.example.jingjingai2.tool.ToolSnapshotIntegrity.Status status) {
        return switch (status) {
            case OK -> null;
            case NO_HASH -> "TOOL_RESULT_UNVERIFIED_SNAPSHOT";
            case PATH_UNSAFE -> "TOOL_RESULT_SNAPSHOT_PATH_UNSAFE";
            case NOT_AUTHORIZED -> "TOOL_RESULT_SNAPSHOT_UNAUTHORIZED";
            case MISSING -> "TOOL_RESULT_SNAPSHOT_MISSING";
            case UNREADABLE -> "TOOL_RESULT_SNAPSHOT_UNREADABLE";
            case HASH_MISMATCH -> "TOOL_RESULT_SNAPSHOT_HASH_MISMATCH";
        };
    }

    /**
     * 已经以引用形式发出去的快照，在进程内打一个有限租约：容量/TTL 清理不能在租约内删它。
     *
     * <p>只保护真正发出去的那些（决策为 RETIRE 且投影成功）。这不是跨请求的持久保留，
     * 重启即丢；租约到期后仍按原 TTL/容量策略清理。
     */
    private static void retainRetiredSnapshots(ContextSourceLedger ledger, List<Decision> decisions) {
        if (ledger == null || decisions == null) return;
        for (Decision decision : decisions) {
            if (decision.kind() != DecisionKind.RETIRE || !decision.id().startsWith("tool:")) continue;
            ContextSourceLedger.Item item = ledger.get(decision.id());
            if (item == null || item.snapshotPath() == null || item.snapshotPath().isBlank()) continue;
            try {
                com.example.jingjingai2.tool.ToolOutputStore.retain(Path.of(item.snapshotPath()));
            } catch (RuntimeException ignored) {
                // 路径异常不影响本轮投影；清理侧仍会按 TTL 处理
            }
        }
    }

    /**
     * 按分类生成新的消息列表。原列表的元素一个不动。
     * 退休的旧助手消息换成一行带消息 id 的说明。
     * 退休的旧工具组：**保留** assistant 的 tool_call 和对应的 tool_result，只把结果正文换成
     * 一行带签名引用的省略标记 —— 这样配对不坏，模型手里仍然有一条能 readFile 读回原文的引用。
     * 整组直接删掉会同时丢掉引用，模型就再也不知道快照在哪。
     * 账本对不上的消息保留：拿不准时不丢。
     */
    private static List<Message> apply(ContextSourceLedger ledger, List<Message> actual, List<Decision> decisions) {
        Map<String, Decision> byId = new LinkedHashMap<>();
        for (Decision decision : decisions) byId.put(decision.id(), decision);
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        List<Message> out = new ArrayList<>();
        for (Message message : actual) {
            if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool
                    && tool.getResponses() != null) {
                List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> kept = new ArrayList<>();
                for (org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    ContextSourceLedger.Item item = response == null || response.id() == null
                            ? null : ledger.get("tool:" + response.id());
                    if (item != null) used.add(item.id());
                    if (item == null || response == null) {
                        if (response != null) kept.add(response);
                        continue;
                    }
                    DecisionKind kind = decisionOf(byId, item).kind();
                    if (kind == DecisionKind.KEEP) kept.add(response);
                    else if (kind == DecisionKind.RETIRE) kept.add(retiredToolResponse(response, item));
                }
                if (!kept.isEmpty()) out.add(new org.springframework.ai.chat.messages.ToolResponseMessage(kept));
                continue;
            }
            if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant
                    && assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                // 只有结果被整条丢掉（DISCARD）时才丢调用；退休只换正文，调用必须留下维持配对。
                boolean allDropped = true;
                for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    ContextSourceLedger.Item result = call == null || call.id() == null
                            ? null : ledger.get("tool:" + call.id());
                    Decision decision = result == null ? null : byId.get(result.id());
                    if (decision == null || decision.kind() != DecisionKind.DISCARD) {
                        allDropped = false;
                        break;
                    }
                }
                if (!allDropped) out.add(message);
                continue;
            }
            ContextSourceLedger.Item item = findUnused(ledger, message, used);
            if (item != null) used.add(item.id());
            Decision decision = item == null ? null : byId.get(item.id());
            if (decision != null && decision.kind() == DecisionKind.RETIRE
                    && "assistant".equals(item.role()) && item.databaseId() != null) {
                out.add(new org.springframework.ai.chat.messages.AssistantMessage(
                        RETIRED_NOTE_PREFIX + item.databaseId() + "]"));
            } else if (decision == null || decision.kind() == DecisionKind.KEEP) {
                out.add(message);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 把一条已退休的工具结果正文换成一行省略标记，id 和 name 保持不变。
     *
     * <p>标记里带上原来的签名引用，模型据此仍能 readFile 读到快照；引用不可用时
     * {@link ToolArtifactRef#recoverySuffix} 会直接说明读不回来，不会给出一条不存在的精确读取。
     * 前缀沿用 {@link ToolHistoryCompactor#MARKER_PREFIX}，后续压缩器按
     * {@code isOmissionMarker} 原样保留，不再二次改写。
     */
    private static org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse retiredToolResponse(
            org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse response,
            ContextSourceLedger.Item item) {
        String body = item.fullText() == null ? "" : item.fullText();
        if (ToolArtifactRef.isOmissionMarker(body)) return response;
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(body);
        int original = ref != null && ref.chars() >= 0 ? ref.chars() : body.length();
        String note = ToolHistoryCompactor.MARKER_PREFIX
                + (response.name() == null ? "工具结果" : response.name())
                + "，原 " + original + " 字符。"
                + ToolArtifactRef.recoverySuffix(ref, body.length()) + "]";
        return new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                response.id(), response.name(), note);
    }

    private static Decision decisionOf(Map<String, Decision> byId, ContextSourceLedger.Item item) {
        Decision decision = byId.get(item.id());
        return decision == null ? new Decision(item.id(), DecisionKind.KEEP, "UNMATCHED", item.tokensEst()) : decision;
    }

    private static ContextSourceLedger.Item findUnused(ContextSourceLedger ledger, Message message,
                                                        java.util.Set<String> used) {
        for (ContextSourceLedger.Item item : ledger.match(List.of(message))) {
            if (!used.contains(item.id())) return item;
        }
        return null;
    }

    /** 投影后仍在视图里的正文，加上工具定义。硬窗口比较用这个数。 */
    private static int projectedTokens(ContextSourceLedger ledger, List<Message> projected,
                                       List<Decision> decisions, int toolSchemaTokensEst) {
        int tokens = toolSchemaTokensEst;
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (Message message : projected) {
            if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool
                    && tool.getResponses() != null) {
                for (org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    ContextSourceLedger.Item item = response == null || response.id() == null
                            ? null : ledger.get("tool:" + response.id());
                    if (item != null && used.add(item.id())) {
                        tokens += shrunk(item, response) ? TokenEstimator.estimate(response.responseData())
                                : item.tokensEst();
                    } else if (item == null && response != null) tokens += TokenEstimator.estimate(response.responseData());
                }
            } else {
                ContextSourceLedger.Item item = findUnused(ledger, message, used);
                if (item != null && used.add(item.id())) tokens += item.tokensEst();
                else tokens += TokenEstimator.estimate(message.getText());
            }
        }
        return tokens;
    }

    /** 这条结果已经由本策略换成省略标记（账本原文不是标记），按实际发出的标记计费。 */
    private static boolean shrunk(ContextSourceLedger.Item item, 
                                  org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse response) {
        String data = response == null ? null : response.responseData();
        return data != null && ToolArtifactRef.isOmissionMarker(data)
                && !ToolArtifactRef.isOmissionMarker(item.fullText());
    }

    private static boolean isHistory(ContextSourceLedger.Item item) {
        return item.kind() == ContextSourceLedger.Kind.HISTORY || item.kind() == ContextSourceLedger.Kind.ASSISTANT;
    }

    private static Decision decide(ContextSourceLedger.Item item, TaskPlan plan,
                                   Map<String, ContextSourceLedger.Item> latestResource,
                                   int olderHistoryTokens, int softBudget, int hardWindow,
                                   java.util.Set<String> recent, java.util.Set<String> retireGroups,
                                   boolean budgetPressure, int round, ToolSnapshotProbe probe) {
        if (protectedItem(item, plan)) {
            return new Decision(item.id(), DecisionKind.KEEP, "PROTECTED", item.tokensEst());
        }
        if ("user".equals(item.role())) {
            return new Decision(item.id(), DecisionKind.KEEP, "USER_MESSAGE", item.tokensEst());
        }
        // 工具结果用独立的「本次请求内模型轮次」判断，不能套用按用户回合算的 recent：
        // 工具结果总在本轮 user 之后，按 recent 算永远不会成为候选。
        if (item.kind() == ContextSourceLedger.Kind.TOOL_RESULT) {
            // 刚恢复的内容先受保护：模型专门取回的证据不能下一轮就被换掉。
            if (item.recovery() && recoveryProtected(item, round)) {
                return new Decision(item.id(), DecisionKind.KEEP, "TOOL_RESULT_RECOVERY_PROTECTED",
                        item.tokensEst());
            }
            String blocked = toolResultRetireBlockedBy(item, plan, round, budgetPressure, probe);
            if (blocked == null && retireGroups.contains(item.toolCallId())) {
                return new Decision(item.id(), DecisionKind.RETIRE, "RECOVERABLE_OLD_TOOL_GROUP", item.tokensEst());
            }
            // 同一条 assistant 里只要有一个结果不能安全退休，整组留下（配对和证据都不拆）。
            return new Decision(item.id(), DecisionKind.KEEP,
                    blocked == null ? "TOOL_RESULT_GROUP_INCOMPLETE" : blocked, item.tokensEst());
        }
        if (recent.contains(item.id())) {
            return new Decision(item.id(), DecisionKind.KEEP, "RECENT_ROUND", item.tokensEst());
        }
        ContextSourceLedger.Item newer = item.resourcePath() == null ? null : latestResource.get(item.resourcePath());
        if (newer != null && !newer.id().equals(item.id())
                && !text(newer.version()).equals(text(item.version())) && restorable(item)) {
            return new Decision(item.id(), DecisionKind.RETIRE, "SUPERSEDED_VERSION", item.tokensEst());
        }
        if ("assistant".equals(item.role())
                && (item.kind() == ContextSourceLedger.Kind.HISTORY || item.kind() == ContextSourceLedger.Kind.ASSISTANT)
                && softBudget > 0 && olderHistoryTokens > softBudget
                && restorable(item) && item.databaseId() != null) {
            return new Decision(item.id(), DecisionKind.RETIRE, "OLD_ASSISTANT_OVER_SOFT_BUDGET", item.tokensEst());
        }
        if (duplicate(item)) {
            return new Decision(item.id(), DecisionKind.DISCARD, "EXACT_DUPLICATE", item.tokensEst());
        }
        return new Decision(item.id(), DecisionKind.KEEP, "NO_RETIRE_RULE", item.tokensEst());
    }

    /**
     * 恢复结果是否还在保护期。轮次未知时按「无法证明刚恢复」处理，交给常规闸门判断，
     * 不凭猜测延长保护。
     */
    private static boolean recoveryProtected(ContextSourceLedger.Item item, int round) {
        if (round < 0 || item.producedRound() == null) return false;
        return round - item.producedRound() < RECOVERY_PROTECT_ROUNDS;
    }

    private static boolean protectedItem(ContextSourceLedger.Item item, TaskPlan plan) {
        return switch (item.kind()) {
            case SYSTEM, USER_MEMORY, WORKSPACE_MEMORY, PLAN, DELIVERY, CURRENT_USER -> true;
            default -> false;
        } || item.id().startsWith("system:")
                || (plan != null && item.kind() == ContextSourceLedger.Kind.TOOL_RESULT
                && item.planStepId() != null && !completedStep(plan, item.planStepId()));
    }

    private static boolean completedStep(TaskPlan plan, String stepId) {
        if (plan == null || plan.getSteps() == null || stepId == null) return false;
        for (PlanStep step : plan.getSteps()) {
            if (step != null && stepId.equals(step.getStepId())) return PlanStep.DONE.equals(step.getStatus());
        }
        return false;
    }

    private static boolean duplicate(ContextSourceLedger.Item item) {
        return item.kind() == ContextSourceLedger.Kind.OTHER && item.fullText().isBlank();
    }

    private static Stats stats(ContextSourceLedger ledger, List<Message> actual, List<Decision> decisions,
                               int toolSchemaTokensEst) {
        int fixed = 0, history = 0, active = 0, userMemory = 0, workspace = 0, plan = 0;
        int tools = 0, recoverable = 0, nonRecoverable = 0;
        if (ledger != null) {
            for (ContextSourceLedger.Item item : ledger.match(actual)) {
                switch (item.kind()) {
                    case SYSTEM, DELIVERY -> fixed += item.tokensEst();
                    case HISTORY, ASSISTANT -> history += item.tokensEst();
                    case CURRENT_USER, TOOL_CALL -> active += item.tokensEst();
                    case USER_MEMORY -> userMemory += item.tokensEst();
                    case WORKSPACE_MEMORY -> workspace += item.tokensEst();
                    case PLAN -> plan += item.tokensEst();
                    case TOOL_RESULT -> {
                        tools += item.tokensEst();
                        if (item.recoverability() == ContextSourceLedger.Recoverability.RECOVERABLE) recoverable += item.tokensEst();
                        else nonRecoverable += item.tokensEst();
                    }
                    default -> active += item.tokensEst();
                }
            }
        }
        int keep = 0, retire = 0, discard = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        for (Decision decision : decisions) {
            reasons.merge(decision.kind() + ":" + decision.reason(), 1, Integer::sum);
            switch (decision.kind()) {
                case KEEP -> keep += decision.tokensEst();
                case RETIRE -> retire += decision.tokensEst();
                case DISCARD -> discard += decision.tokensEst();
            }
        }
        int total = fixed + toolSchemaTokensEst + history + active + userMemory + workspace + plan + tools;
        // 和软预算比较的正文口径。工具结果必须计入，否则大工具输出再多也不会触发退休。
        int pressure = history + tools;
        return new Stats(total, fixed, toolSchemaTokensEst, history, active, userMemory, workspace, plan,
                tools, pressure, recoverable, nonRecoverable, keep, retire, discard,
                keep + fixed + toolSchemaTokensEst, decisions.size(), Map.copyOf(reasons));
    }

    private static Stats empty(int toolSchemaTokensEst) {
        return new Stats(toolSchemaTokensEst, 0, toolSchemaTokensEst, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                toolSchemaTokensEst, 0, Map.of());
    }

    private static List<String> canonical(List<Message> messages) {
        List<String> out = new ArrayList<>();
        if (messages == null) return out;
        for (Message message : messages) out.add(message == null ? "" : message.getClass().getSimpleName() + ":" + textOf(message));
        return out;
    }

    private static List<String> shadowCanonical(List<String> actual, List<Decision> decisions) {
        List<String> out = new ArrayList<>();
        int index = 0;
        for (String line : actual) {
            Decision decision = index < decisions.size() ? decisions.get(index) : null;
            index++;
            if (decision != null && decision.kind() != DecisionKind.KEEP) {
                out.add("shadow:" + decision.kind() + ":" + decision.id());
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static int firstChange(List<String> before, List<String> now) {
        int shared = Math.min(before.size(), now.size());
        for (int i = 0; i < shared; i++) if (!before.get(i).equals(now.get(i))) return i;
        return before.size() == now.size() ? -1 : shared;
    }

    private static boolean tailOnly(List<String> before, List<String> now) {
        if (now.size() < before.size()) return false;
        for (int i = 0; i < before.size(); i++) if (!before.get(i).equals(now.get(i))) return false;
        return true;
    }

    private static String textOf(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    static String hash(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) digest.update(line.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }

    /** 工具定义只参与是否变化的比较，影子模式不替换它们。 */
    public static boolean sameTools(ToolCallback[] left, ToolCallback[] right) {
        return text(com.example.jingjingai2.agent.turn.PromptCanonical.toolsJson(left))
                .equals(text(com.example.jingjingai2.agent.turn.PromptCanonical.toolsJson(right)));
    }
}
