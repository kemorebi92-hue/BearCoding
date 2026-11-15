package com.example.jingjingai2.agent.turn;

import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import com.example.jingjingai2.mapper.PromptSnapshotMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把一轮记录落库。薄薄一层：记录内容全部由 {@link AgentTurnRecord#from} 决定，
 * 这里只负责写，并且<b>永不抛异常</b>——记录写失败只打日志，不能影响对话主流程。
 */
@Service
public class AgentTurnRecorder {

    public static final String KIND_SYSTEM_PROMPT = "SYSTEM_PROMPT";
    public static final String KIND_TOOLS = "TOOLS";

    private final AgentTurnMapper turnMapper;
    private final PromptSnapshotMapper snapshotMapper;

    public AgentTurnRecorder(AgentTurnMapper turnMapper, PromptSnapshotMapper snapshotMapper) {
        this.turnMapper = turnMapper;
        this.snapshotMapper = snapshotMapper;
    }

    /** 记录一轮。只把静态系统提示和工具表全文按哈希去重存进 prompt_snapshot，动态注入不存全文。 */
    public void recordTurn(TurnState state) {
        try {
            AgentTurnRecord record = AgentTurnRecord.from(state);
            boolean approvalRow = TurnState.PHASE_APPROVAL_RESOLVED.equals(state.phase());
            if (!approvalRow && state.systemPrompt() != null) {
                snapshotMapper.insertIgnore(record.systemPromptSha256(), KIND_SYSTEM_PROMPT, state.systemPrompt());
            }
            if (!approvalRow && state.toolsCanonicalJson() != null) {
                snapshotMapper.insertIgnore(record.toolsSha256(), KIND_TOOLS, state.toolsCanonicalJson());
            }
            turnMapper.insert(record);
        } catch (Exception e) {
            System.err.println("[AgentTurn] 写入轮次记录失败（不影响对话）: " + e.getMessage());
        }
    }

    /**
     * 摘要调用单独一行，phase=COMPACTION。不进工具循环的 totalTokens。
     * 选 agent_turn 而不是新表：用量列已经在，phase 没有枚举约束，导出按 phase 分开加总即可。
     */
    public void recordCompaction(String conversationId, Long userId, int turnIndex, String modelName,
                                 int before, int after, int compactedCount,
                                 ContextCompactor.SummaryUsage usage, String reason) {
        recordCompaction(conversationId, userId, turnIndex, modelName, before, after, compactedCount,
                usage, reason, null);
    }

    /**
     * @param compactMode {@code DIGEST} 时 tokens 记 0，compact_mode 记 DIGEST。null 时按摘要结果决定。
     */
    public void recordCompaction(String conversationId, Long userId, int turnIndex, String modelName,
                                 int before, int after, int compactedCount,
                                 ContextCompactor.SummaryUsage usage, String reason, String compactMode) {
        try {
            boolean fallback = usage != null && usage.fallback();
            boolean estimated = usage != null && usage.estimated();
            boolean skipped = "COMPACTION_SKIPPED_FIXED_OVERHEAD".equals(reason);
            boolean digest = "DIGEST".equals(compactMode);
            String end = compactionEnd(skipped, fallback, reason, estimated);
            int in = digest || skipped || usage == null ? 0 : usage.inputTokens();
            int out = digest || skipped || usage == null ? 0 : usage.outputTokens();
            boolean produced = usage != null && usage.text() != null && !usage.text().isBlank() && !skipped;
            String mode = digest
                    ? TurnState.COMPACT_DIGEST
                    : (produced ? TurnState.COMPACT_SUMMARY : TurnState.COMPACT_NONE);
            turnMapper.insert(new AgentTurnRecord(
                    conversationId, userId, turnIndex, TurnState.PHASE_COMPACTION, modelName,
                    null, null, null, mode,
                    before, after, compactedCount, in, out,
                    null, null, null, null, null, end));
        } catch (Exception e) {
            System.err.println("[AgentTurn] 写入摘要调用失败（不影响对话）: " + e.getMessage());
        }
    }

    /** 压缩行的 end_reason。摘要失败优先于「压完仍超阈值」。 */
    static String compactionEnd(boolean skipped, boolean fallback, String reason, boolean estimated) {
        if (skipped) return AgentTurnRecord.END_COMPACTION_SKIPPED_FIXED_OVERHEAD;
        if (fallback) return AgentTurnRecord.END_COMPACTION_FALLBACK;
        if ("COMPACTION_INEFFECTIVE".equals(reason)) return AgentTurnRecord.END_COMPACTION_INEFFECTIVE;
        if (estimated) return AgentTurnRecord.END_COMPACTION_ESTIMATED;
        return AgentTurnRecord.END_COMPACTION;
    }

    /**
     * 审批补行。轮次号从库里查该会话最近一条 APPROVAL_PENDING，不靠进程内记忆。
     * 查不到就不写，只打警告。
     */
    public void recordApprovalResolved(String conversationId, Long userId, String endReason) {
        try {
            Integer turn = turnMapper.latestPendingTurnIndex(conversationId);
            if (turn == null) {
                System.err.println("[AgentTurn] WARN 未找到 end_reason=APPROVAL_PENDING 的轮次，跳过审批补行 conversation="
                        + conversationId);
                return;
            }
            recordTurn(new TurnState(
                    conversationId, userId, turn, TurnState.PHASE_APPROVAL_RESOLVED,
                    null, null, null, null, TurnState.COMPACT_NONE,
                    null, null, null, null, null, List.of(), endReason));
        } catch (Exception e) {
            System.err.println("[AgentTurn] 组装审批补行失败（不影响对话）: " + e.getMessage());
        }
    }

    /** 批准后写 tool_call_log 要用的同一个轮次号。查不到返回 null。 */
    public Integer latestPendingTurnIndex(String conversationId) {
        try {
            return turnMapper.latestPendingTurnIndex(conversationId);
        } catch (Exception e) {
            System.err.println("[AgentTurn] 查询挂起轮次失败: " + e.getMessage());
            return null;
        }
    }
}
