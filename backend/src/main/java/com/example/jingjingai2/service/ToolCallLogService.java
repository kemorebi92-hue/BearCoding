package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.ToolCallLog;
import com.example.jingjingai2.mapper.ToolCallLogMapper;
import org.springframework.stereotype.Service;

/**
 * 工具调用审计日志服务（替代原 ToolCallLogRepository）。
 * 设计要点：
 *  - 审计是旁路逻辑，绝不能因为它的失败拖垮主对话流程，所以 log() 内部吞掉异常；
 *  - 入参与结果做长度截断，避免大文件读写把表撑爆。
 */
@Service
public class ToolCallLogService {

    private final ToolCallLogMapper mapper;

    public ToolCallLogService(ToolCallLogMapper mapper) {
        this.mapper = mapper;
    }

    private static final int MAX_ARG_CHARS = 2000;
    private static final int MAX_RESULT_CHARS = 4000;

    /**
     * 记录一次工具调用。此方法永不抛异常。
     */
    public void log(long userId, String conversationId, String toolName,
                    String arguments, String result, boolean success,
                    long elapsedMs, boolean highRisk, boolean approved, Integer turnIndex) {
        try {
            mapper.insert(new ToolCallLog(userId, conversationId, toolName,
                    truncate(arguments, MAX_ARG_CHARS), truncate(result, MAX_RESULT_CHARS),
                    success, elapsedMs, highRisk, approved, turnIndex));
        } catch (Exception e) {
            System.err.println("[Audit] 写入工具调用日志失败: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[已截断]";
    }
}
