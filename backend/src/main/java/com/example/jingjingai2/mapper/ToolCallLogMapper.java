package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.ToolCallLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具调用审计日志表 Mapper（MyBatis 注解式，替代原 ToolCallLogRepository）。
 */
@Mapper
public interface ToolCallLogMapper {

    @Insert("INSERT INTO tool_call_log(user_id, conversation_id, tool_name, arguments, result, " +
            "success, elapsed_ms, high_risk, approved, turn_index) " +
            "VALUES(#{userId}, #{conversationId}, #{toolName}, #{arguments}, #{result}, " +
            "#{success}, #{elapsedMs}, #{highRisk}, #{approved}, #{turnIndex})")
    void insert(ToolCallLog log);
}
