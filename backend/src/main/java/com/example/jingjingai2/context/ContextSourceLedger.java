package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次请求里出现过的完整信息。它不是模型 Prompt，压缩器不能改这里的正文。
 *
 * <p>请求内新建，不是 Spring 单例。同一条信息重复登记时保留第一次的完整正文。
 */
public final class ContextSourceLedger {

    public enum Kind {
        HISTORY, CURRENT_USER, ASSISTANT, TOOL_CALL, TOOL_RESULT,
        SYSTEM, USER_MEMORY, WORKSPACE_MEMORY, PLAN, DELIVERY, OTHER
    }

    public enum Recoverability {
        /** 数据库消息、签名快照，或工作区里可以再读的资源。 */
        RECOVERABLE,
        /** 本次请求里的唯一正文，库和快照都没有。 */
        NOT_RECOVERABLE,
        /** 本来就没有一份可恢复的原文，不假装有。 */
        NO_SOURCE
    }

    /**
     * @param id                稳定身份，不使用对象地址或列表下标
     * @param fullText          登记时的完整正文。后续生产消息被改写不影响它
     * @param sentToModel       进入过某次实际 Prompt。只表示发送过，不表示模型记住了
     * @param lastSentRound     最近一次进入 Prompt 的轮次；没发送过为 -1
     * @param recovery          这条结果是「模型刚刚主动取回旧内容」的恢复调用产物
     *                          （readFile 带签名引用、searchContext operation=read）。
     *                          由工具名和入参推导，不看正文关键词。
     */
    public record Item(
            String id,
            Kind kind,
            String role,
            Long databaseId,
            String conversationId,
            Date timestamp,
            boolean currentRequest,
            String fullText,
            int tokensEst,
            String toolCallId,
            String toolName,
            String resourcePath,
            String version,
            String planStepId,
            String status,
            Integer producedRound,
            boolean sentToModel,
            int lastSentRound,
            boolean inCurrentPrompt,
            Recoverability recoverability,
            String snapshotPath,
            String contentHash,
            boolean sourceMissing,
            boolean recovery) {}

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final String conversationId;
    /**
     * 已被历史摘要覆盖的数据库消息 id（Phase M1）。
     *
     * <p>这些原文仍然完整地登记在账本里（它们是「存在过的信息」，不是「本次要发送的内容」），
     * 但投影配对时必须跳过：否则摘要和同一段原文可能被同时当成两份独立信息，
     * 或者在正文重复时把一条最近消息错配到一条已经被摘要替代的旧消息上。
     */
    private final java.util.Set<Long> summarizedIds = new java.util.LinkedHashSet<>();

    public ContextSourceLedger(String conversationId) {
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return conversationId;
    }

    /**
     * 登记一段已被历史摘要覆盖的原文 id。只影响「配对」：账本里的正文不删、不改，
     * 数据库原文也不动；调用方据此可以证明摘要与原文没有被同时发送。
     */
    public void markSummarized(java.util.Collection<Long> messageIds) {
        if (messageIds == null) return;
        for (Long id : messageIds) {
            if (id != null) summarizedIds.add(id);
        }
    }

    /** 这条数据库消息是否已被本次请求的历史摘要覆盖。 */
    public boolean summarized(Long databaseId) {
        return databaseId != null && summarizedIds.contains(databaseId);
    }

    public int summarizedCount() {
        return summarizedIds.size();
    }

    /** 入库历史。本轮用户消息也留在历史里：它是否属于当前请求，由 absorb 按实际 Prompt 判断。 */
    public void addHistory(List<MessageRecord> records, String currentUserText) {
        if (records == null) return;
        for (MessageRecord record : records) addHistoryRow(record);
    }

    private void addHistoryRow(MessageRecord record) {
        if (record == null) return;
        boolean missing = record.getId() == null;
        String id = missing
                ? "message:missing:" + items.size()
                : "message:" + record.getId();
        put(new Item(id, Kind.HISTORY, record.getRole(), record.getId(), conversationId, record.getTimestamp(),
                false, text(record.getContent()), TokenEstimator.estimate(record.getContent()),
                null, null, null, null, null, null, null, false, -1, false,
                missing ? Recoverability.NO_SOURCE : Recoverability.RECOVERABLE,
                null, null, missing, false));
    }

    /** 当前生产消息里账本还没有的内容。工具结果正文在这里冻结。 */
    public void absorb(List<Message> messages, int round, TaskPlan plan) {
        if (messages == null) return;
        Map<String, AssistantMessage.ToolCall> calls = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    if (call != null && call.id() != null && !call.id().isBlank()) calls.putIfAbsent(call.id(), call);
                }
            }
        }
        int occurrence = 0;
        for (Message message : messages) {
            occurrence++;
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    absorbTool(response, calls.get(response == null ? null : response.id()), round, plan);
                }
            } else {
                absorbMessage(message, occurrence);
            }
        }
    }

    private void absorbMessage(Message message, int occurrence) {
        if (message == null) return;
        String text = textOf(message);
        Kind kind = kindOf(message, text);
        String id = idOf(message, kind, text, occurrence);
        Recoverability recoverability = kind == Kind.OTHER && text.isBlank()
                ? Recoverability.NO_SOURCE : Recoverability.NOT_RECOVERABLE;
        put(new Item(id, kind, roleOf(message), null, conversationId, null, true, text,
                TokenEstimator.estimate(text), null, null, null, null, null, null, null,
                false, -1, false, recoverability, null, null, recoverability == Recoverability.NO_SOURCE,
                false));
    }

    private void absorbTool(ToolResponseMessage.ToolResponse response, AssistantMessage.ToolCall call,
                            int round, TaskPlan plan) {
        if (response == null || response.id() == null || response.id().isBlank()) {
            put(new Item("tool:missing:" + items.size(), Kind.TOOL_RESULT, "tool", null, conversationId, null,
                    true, "", 0, null, response == null ? null : response.name(), null, null, null,
                    "MISSING_ID", round, false, -1, false, Recoverability.NO_SOURCE, null, null, true, false));
            return;
        }
        String body = response.responseData() == null ? "" : response.responseData();
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(body);
        boolean signed = ref != null && ref.hasContentHash();
        String path = resourcePath(call, ref);
        String version = signed ? ref.sha256().toLowerCase() : digest(body);
        String step = matchingStep(plan, path);
        put(new Item("tool:" + response.id(), Kind.TOOL_RESULT, "tool", null, conversationId, null, true, body,
                TokenEstimator.estimate(body), response.id(),
                response.name() == null && call != null ? call.name() : response.name(),
                path, version, step, signed ? "SNAPSHOT" : "INLINE", round, false, -1, false,
                signed ? Recoverability.RECOVERABLE : Recoverability.NOT_RECOVERABLE,
                signed ? ref.path() : null, signed ? ref.sha256().toLowerCase() : null, false,
                isRecoveryCall(call)));
    }

    /**
     * 这条结果是不是「模型刚刚主动取回旧内容」的恢复调用产物。
     *
     * <p>只看工具名和入参里的结构化字段，不看正文关键词：
     * <ul>
     *   <li>{@code readFile} 的 path 参数是一条验签通过的 {@code [artifact-ref]} 引用；</li>
     *   <li>{@code searchContext} 的 operation 参数是 {@code read}（locator 就是身份）。</li>
     * </ul>
     * 恢复结果会在当前请求内受到有限轮次保护，见 {@code ContextProjectionPolicy}。
     */
    private static boolean isRecoveryCall(AssistantMessage.ToolCall call) {
        if (call == null || call.name() == null) return false;
        String arguments = call.arguments();
        if (arguments == null || arguments.isBlank()) return false;
        if ("readFile".equals(call.name())) {
            String arg = com.example.jingjingai2.agent.turn.TurnState.targetPathOf("readFile", arguments);
            return arg != null && arg.contains(ToolArtifactRef.OPEN) && ToolArtifactRef.parse(arg) != null;
        }
        if (ContextAssetTool.NAME.equals(call.name())) {
            java.util.regex.Matcher m = OPERATION_ARG.matcher(arguments);
            return m.find() && "read".equalsIgnoreCase(m.group(1).trim());
        }
        return false;
    }

    /** searchContext 的 operation 参数。只认 JSON 里的字符串值。 */
    private static final java.util.regex.Pattern OPERATION_ARG =
            java.util.regex.Pattern.compile("\"operation\"\\s*:\\s*\"([^\"]*)\"");

    /** 实际 Prompt 已交给模型。只记录发送，不表示模型理解了正文。 */
    public void markSent(List<Message> prompt, int round) {
        if (prompt == null) return;
        List<Item> visible = match(prompt);
        for (Item item : visible) {
            items.put(item.id(), copy(item, true, round, true));
        }
        for (Item item : List.copyOf(items.values())) {
            if (!item.inCurrentPrompt()) continue;
            boolean stillThere = visible.stream().anyMatch(found -> found.id().equals(item.id()));
            if (!stillThere) items.put(item.id(), copy(item, item.sentToModel(), item.lastSentRound(), false));
        }
    }

    public List<Item> items() {
        return List.copyOf(items.values());
    }

    public Item get(String id) {
        return items.get(id);
    }

    /**
     * 按当前 Prompt 的内容和顺序找出账本项。每条账本项最多对应一次。
     * 找不到的不临时补造：调用方由此知道投影覆盖不到什么。
     */
    public List<Item> match(List<Message> prompt) {
        List<Item> out = new ArrayList<>();
        if (prompt == null) return out;
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (Message message : prompt) {
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    if (response == null || response.id() == null) continue;
                    Item item = items.get("tool:" + response.id());
                    if (item != null && used.add(item.id())) out.add(item);
                }
            } else {
                Item item = findUnused(message, used);
                if (item != null && used.add(item.id())) out.add(item);
            }
        }
        return out;
    }

    private Item findUnused(Message message, java.util.Set<String> used) {
        String text = textOf(message);
        for (Item item : items.values()) {
            if (used.contains(item.id()) || !same(item.fullText(), text)) continue;
            // 已被历史摘要替代的原文不再参与配对：摘要与同一段原文不能同时被当成两份信息。
            if (summarized(item.databaseId())) continue;
            if (message instanceof UserMessage
                    && (item.kind() == Kind.HISTORY || item.kind() == Kind.CURRENT_USER) && "user".equals(item.role())) {
                return item;
            }
            if (message instanceof AssistantMessage assistant
                    && (assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty())
                    && (item.kind() == Kind.HISTORY || item.kind() == Kind.ASSISTANT)
                    && "assistant".equals(item.role())) {
                return item;
            }
            if (message instanceof SystemMessage && item.kind() == kindOf(message, text)) return item;
        }
        return null;
    }

    private void put(Item item) {
        Item existing = items.get(item.id());
        if (existing == null) {
            items.put(item.id(), item);
            return;
        }
        items.put(item.id(), copy(existing, existing.sentToModel(), existing.lastSentRound(), existing.inCurrentPrompt()));
    }

    private static Item copy(Item item, boolean sent, int lastRound, boolean current) {
        return new Item(item.id(), item.kind(), item.role(), item.databaseId(), item.conversationId(),
                item.timestamp(), item.currentRequest(), item.fullText(), item.tokensEst(), item.toolCallId(),
                item.toolName(), item.resourcePath(), item.version(), item.planStepId(), item.status(),
                item.producedRound(), sent, sent ? Math.max(item.lastSentRound(), lastRound) : -1, current,
                item.recoverability(), item.snapshotPath(), item.contentHash(), item.sourceMissing(),
                item.recovery());
    }

    private static Kind kindOf(Message message, String text) {
        if (message instanceof com.example.jingjingai2.utils.PlanInjectionMessage) return Kind.PLAN;
        if (message instanceof SystemMessage) {
            if (text.startsWith("【用户记忆】")) return Kind.USER_MEMORY;
            if (text.startsWith("【长期记忆】")) return Kind.WORKSPACE_MEMORY;
            if (text.startsWith("执行阶段已经结束")) return Kind.DELIVERY;
            return Kind.SYSTEM;
        }
        if (message instanceof UserMessage) return Kind.CURRENT_USER;
        if (message instanceof AssistantMessage assistant
                && assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) return Kind.TOOL_CALL;
        if (message instanceof AssistantMessage) return Kind.ASSISTANT;
        return Kind.OTHER;
    }

    private static String idOf(Message message, Kind kind, String text, int occurrence) {
        return switch (kind) {
            case USER_MEMORY -> "system:user-memory";
            case WORKSPACE_MEMORY -> "system:workspace-memory";
            case PLAN -> "system:plan";
            case DELIVERY -> "system:delivery";
            case SYSTEM -> text.startsWith("[历史摘要]") || text.startsWith("[压缩索引]")
                    ? "system:history-summary" : "system:base";
            case CURRENT_USER -> "request:current-user:" + digest(text);
            case ASSISTANT -> "request:assistant:" + digest(text);
            case TOOL_CALL -> "request:tool-call:" + digest(text);
            default -> "request:other:" + occurrence + ":" + digest(text);
        };
    }

    /**
     * 这条结果对应的资源路径。结果正文自带签名引用时用引用里的路径；否则从入参里的 path 取。
     *
     * <p>恢复调用（readFile 传引用）的入参本身就是一条引用，直接拿整串当路径会让路径变成
     * 一段标记文本，计划步骤匹配和版本比较都会失效。这里把它解析成真实快照路径。
     */
    private static String resourcePath(AssistantMessage.ToolCall call, ToolArtifactRef.Ref ref) {
        if (ref != null && ref.path() != null && !ref.path().isBlank()) return ref.path();
        if (call == null) return null;
        String target = com.example.jingjingai2.agent.turn.TurnState.targetPathOf(call.name(), call.arguments());
        if (target != null && target.contains(ToolArtifactRef.OPEN)) {
            ToolArtifactRef.Ref argRef = ToolArtifactRef.parse(target);
            if (argRef != null && argRef.path() != null && !argRef.path().isBlank()) return argRef.path();
        }
        return target;
    }

    private static String matchingStep(TaskPlan plan, String path) {
        if (plan == null || plan.getSteps() == null || path == null || path.isBlank()) return null;
        String leaf = path.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        for (PlanStep step : plan.getSteps()) {
            if (step == null) continue;
            String description = step.getDescription() == null ? "" : step.getDescription();
            if (description.contains(leaf)) return step.getStepId();
        }
        return null;
    }

    private static String roleOf(Message message) {
        if (message instanceof SystemMessage) return "system";
        if (message instanceof UserMessage) return "user";
        if (message instanceof AssistantMessage) return "assistant";
        return "other";
    }

    private static String textOf(Message message) {
        if (message instanceof ToolResponseMessage) return "";
        String text = message.getText();
        return text == null ? "" : text;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean same(String left, String right) {
        return text(left).equals(text(right));
    }

    static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            return "digest-error";
        }
    }
}
