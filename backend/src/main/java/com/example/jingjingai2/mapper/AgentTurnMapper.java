package com.example.jingjingai2.mapper;

import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code agent_turn} 表：一行 = 模型的一轮。 */
@Mapper
public interface AgentTurnMapper {

    @Insert("INSERT INTO agent_turn(conversation_id, user_id, turn_index, phase, model_name, " +
            "system_prompt_sha256, system_dynamic_sha256, tools_sha256, compact_mode, " +
            "tokens_before_compact_est, tokens_after_compact_est, compacted_message_count, " +
            "input_tokens, output_tokens, " +
            "est_tokens_system, est_tokens_injected, est_tokens_tools, est_tokens_history, est_tokens_tool_results, " +
            "end_reason) VALUES(" +
            "#{conversationId}, #{userId}, #{turnIndex}, #{phase}, #{modelName}, " +
            "#{systemPromptSha256}, #{systemDynamicSha256}, #{toolsSha256}, #{compactMode}, " +
            "#{tokensBeforeCompactEst}, #{tokensAfterCompactEst}, #{compactedMessageCount}, " +
            "#{inputTokens}, #{outputTokens}, " +
            "#{estTokensSystem}, #{estTokensInjected}, #{estTokensTools}, #{estTokensHistory}, #{estTokensToolResults}, " +
            "#{endReason})")
    void insert(AgentTurnRecord record);

    /** 该会话最近一条挂起审批的轮次号。没有则返回 null。 */
    @Select("SELECT turn_index FROM agent_turn " +
            "WHERE conversation_id = #{conversationId} AND end_reason = 'APPROVAL_PENDING' " +
            "ORDER BY id DESC LIMIT 1")
    Integer latestPendingTurnIndex(@Param("conversationId") String conversationId);
}
