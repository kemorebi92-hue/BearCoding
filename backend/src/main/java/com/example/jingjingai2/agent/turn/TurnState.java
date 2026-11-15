package com.example.jingjingai2.agent.turn;

import com.example.jingjingai2.context.TokenEstimator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一轮模型调用的全部输入事实，纯数据，不含任何持久化。
 *
 * <p>{@link AgentTurnRecord#from} 只读这些字段就能生成一行记录，所以两个循环
 * （流式 / 非流式）和审批补行都只需要把状态填进来，不需要各自拼记录。
 *
 * @param conversationId            会话 id
 * @param userId                    用户 id
 * @param turnIndex                 本会话内的轮次序号（从 0 起；审批补行沿用挂起那一轮的序号）
 * @param phase                     {@code MODEL_TURN} 或 {@code APPROVAL_RESOLVED}
 * @param modelName                 本轮实际调用的模型名，拿不到为 null
 * @param systemPrompt              静态基础系统提示全文（不含历史摘要、计划、记忆等动态注入）
 * @param dynamicSystemText         动态注入拼起来的全文，只用于哈希，不入库；没有则为 null
 * @param toolsCanonicalJson        工具表的规范化 JSON（见 {@link PromptCanonical}），哈希前的原文
 * @param compactMode               {@code NONE} / {@code SUMMARY} / {@code DIGEST}
 * @param tokensBeforeCompactEst    压缩前的估算 token；没压缩时与压缩后相同
 * @param tokensAfterCompactEst     压缩后的估算 token
 * @param compactedMessageCount     被压进摘要的历史消息条数；没压缩为 0
 * @param inputTokens               服务商返回的输入 token；拿不到为 null（不要用 0 顶替）
 * @param outputTokens              服务商返回的输出 token；拿不到为 null
 * @param messages                  本轮发给模型的消息，用于估算列
 * @param endReason                 见 {@link AgentTurnRecord} 的 end_reason 枚举
 */
public record TurnState(
        String conversationId,
        Long userId,
        int turnIndex,
        String phase,
        String modelName,
        String systemPrompt,
        String dynamicSystemText,
        String toolsCanonicalJson,
        String compactMode,
        Integer tokensBeforeCompactEst,
        Integer tokensAfterCompactEst,
        Integer compactedMessageCount,
        Integer inputTokens,
        Integer outputTokens,
        List<Message> messages,
        String endReason) {

    public static final String PHASE_MODEL_TURN = "MODEL_TURN";
    public static final String PHASE_APPROVAL_RESOLVED = "APPROVAL_RESOLVED";
    /** 摘要那一次模型调用，不算进工具循环的 totalTokens。 */
    public static final String PHASE_COMPACTION = "COMPACTION";

    public static final String COMPACT_NONE = "NONE";
    public static final String COMPACT_SUMMARY = "SUMMARY";
    public static final String COMPACT_DIGEST = "DIGEST";

    /**
     * 五个估算列，同一个 {@link TokenEstimator}，按条相加，所以之和等于总估算：
     * <ul>
     *   <li>system：与 {@code staticPrompt} 文本相同的那一条 {@link SystemMessage}（静态基础提示）；</li>
     *   <li>injected：其余 {@link SystemMessage}（历史摘要、计划、用户记忆、工作区记忆、催办，以及认不出的）；</li>
     *   <li>history：{@link UserMessage} + {@link AssistantMessage} 正文，不含工具结果；</li>
     *   <li>toolResults：{@link ToolResponseMessage}；</li>
     *   <li>tools：工具表规范化 JSON。</li>
     * </ul>
     *
     * <p>injected 的六个子列只是观测拆分，算法仍是 {@link TokenEstimator}。
     * 六个子列之和等于 {@code injected}。认不出类别的进 {@code unknown}，不猜测。
     * 这些都是字符估算，不是服务商返回的真实 token。
     */
    public record EstimateSplit(int system, int injected, int tools, int history, int toolResults,
                                int historySummary, int userMemory, int workspaceMemory,
                                int plan, int nudge, int unknown) {
        public int total() { return system + injected + tools + history + toolResults; }

        /** 六个子列之和。与 {@link #injected} 不相等就是分类漏计或重复计。 */
        public int injectedParts() {
            return historySummary + userMemory + workspaceMemory + plan + nudge + unknown;
        }
    }

    /** 除静态基础提示之外的系统消息，按出现顺序用空行拼起来。没有则返回 null。 */
    public static String dynamicText(String staticPrompt, List<Message> messages) {
        if (messages == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean staticSeen = false;
        for (Message m : messages) {
            if (!(m instanceof SystemMessage)) continue;
            String t = textOf(m);
            if (!staticSeen && staticPrompt != null && staticPrompt.equals(t)) {
                staticSeen = true;
                continue;
            }
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public static EstimateSplit split(String staticPrompt, List<Message> messages, String toolsCanonicalJson) {
        int system = 0, injected = 0, history = 0, toolResults = 0;
        int historySummary = 0, userMemory = 0, workspaceMemory = 0, plan = 0, nudge = 0, unknown = 0;
        boolean staticSeen = false;
        if (messages != null) {
            for (Message m : messages) {
                if (m instanceof SystemMessage) {
                    String t = textOf(m);
                    if (!staticSeen && staticPrompt != null && staticPrompt.equals(t)) {
                        system += TokenEstimator.estimate(t);
                        staticSeen = true;
                    } else {
                        int est = TokenEstimator.estimate(t);
                        injected += est;
                        switch (injectedKind(m, t)) {
                            case HISTORY_SUMMARY -> historySummary += est;
                            case USER_MEMORY -> userMemory += est;
                            case WORKSPACE_MEMORY -> workspaceMemory += est;
                            case PLAN -> plan += est;
                            case NUDGE -> nudge += est;
                            case UNKNOWN -> unknown += est;
                        }
                    }
                } else if (m instanceof UserMessage || m instanceof AssistantMessage) {
                    history += TokenEstimator.estimate(textOf(m));
                } else if (m instanceof ToolResponseMessage) {
                    toolResults += TokenEstimator.estimate(textOf(m));
                }
            }
        }
        return new EstimateSplit(system, injected, TokenEstimator.estimate(toolsCanonicalJson),
                history, toolResults, historySummary, userMemory, workspaceMemory, plan, nudge, unknown);
    }

    /** injected 子列。只用于观测，不决定消息是否发送。 */
    public enum InjectedKind {
        HISTORY_SUMMARY, USER_MEMORY, WORKSPACE_MEMORY, PLAN, NUDGE, UNKNOWN
    }

    /**
     * 认不出就返回 {@link InjectedKind#UNKNOWN}，不按内容猜测。
     * 计划看类型（正文没有固定前缀）；其余看注入处写死的开头。
     */
    public static InjectedKind injectedKind(Message message, String text) {
        if (message instanceof com.example.jingjingai2.utils.PlanInjectionMessage) return InjectedKind.PLAN;
        String t = text == null ? "" : text;
        if (t.startsWith("[历史摘要]")) return InjectedKind.HISTORY_SUMMARY;
        if (t.startsWith("【用户记忆】")) return InjectedKind.USER_MEMORY;
        if (t.startsWith("【长期记忆】")) return InjectedKind.WORKSPACE_MEMORY;
        if (t.startsWith("🧭 我观察到：")
                || t.startsWith("⚠️ 轮数预算即将用尽")
                || t.startsWith("【用量提示】")
                || t.startsWith("所有计划步骤都已完成。")
                || t.startsWith("【评测强制】")) {
            return InjectedKind.NUDGE;
        }
        return InjectedKind.UNKNOWN;
    }

    /**
     * 本请求内一条工具结果的旁路记录。不进发给模型的消息。
     *
     * @param toolCallId 拿不到为 null
     * @param toolName   工具名
     * @param targetPath 仅 readFile / editFile 能解析出的路径；解析失败为 null
     * @param chars      结果字符数（UTF-16），不是 token
     * @param spilled    结果正文里有签名有效的落盘引用
     * @param seq        本请求内的执行序号，从 1 起
     */
    public record ToolResultObservation(String toolCallId, String toolName, String targetPath,
                                        int chars, boolean spilled, int seq) {}

    private static final Pattern FILE_PATH_ARG = Pattern.compile("\"path\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * 从工具入参里取目标路径。只认 readFile 和 editFile，且只认 JSON 里的 {@code path}。
     * 解析失败返回 null，不抛。
     */
    public static String targetPathOf(String toolName, String arguments) {
        if (!"readFile".equals(toolName) && !"editFile".equals(toolName)) return null;
        if (arguments == null || arguments.isBlank()) return null;
        try {
            Matcher m = FILE_PATH_ARG.matcher(arguments);
            if (!m.find()) return null;
            String raw = m.group(1);
            if (raw == null || raw.isBlank()) return null;
            return raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 结果字符数。null 当 0。 */
    public static int resultChars(String toolResult) {
        return toolResult == null ? 0 : toolResult.length();
    }

    /** 正文里有签名有效的落盘引用才算落盘。无签名或签名无效不算。 */
    public static boolean spilled(String toolResult) {
        return com.example.jingjingai2.tool.ToolArtifactRef.parse(toolResult) != null;
    }

    /**
     * 这一轮响应里的 cached_tokens。字段缺失、响应为空、或用量为空时返回 null。
     * 字段存在且值为 0 时返回 0，那是真实的未命中。
     */
    public static Long cachedTokensOfResponse(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getMetadata() == null) return null;
        org.springframework.ai.chat.metadata.Usage u = response.getMetadata().getUsage();
        if (u == null) return null;
        try {
            Object nativeUsage = u.getNativeUsage();
            if (nativeUsage instanceof org.springframework.ai.openai.api.OpenAiApi.Usage ou
                    && ou.promptTokensDetails() != null
                    && ou.promptTokensDetails().cachedTokens() != null) {
                return ou.promptTokensDetails().cachedTokens().longValue();
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String textOf(Message m) {
        if (m instanceof ToolResponseMessage tr && tr.getResponses() != null) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
                if (r != null && r.responseData() != null) sb.append(r.responseData());
            }
            return sb.toString();
        }
        String t = m.getText();
        return t == null ? "" : t;
    }
}
