package com.example.jingjingai2.agent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 子代理日志的**唯一出口**：统一前缀 + 毫秒级时间戳。
 *
 * 为什么要时间戳：§10 那条验收标准「多目录调研时至少两个 explore 在同一墙钟内重叠」，
 * 只能靠**日志时间戳**证明 —— 「两张卡片看起来都在转」不算证据。
 * 直接 {@code System.out.println} 打不出时间戳：这些行走 stdout，不过 logback 的格式。
 *
 * 格式（一眼能扫）：
 * <pre>
 * [Subagent 12:03:07.412] start mode=explore rounds&lt;=8 goal=对比两个包的结构
 * [Subagent 12:03:07.418] start mode=explore rounds&lt;=8 goal=查一下 xxx 的用法
 * [Subagent 12:03:19.902] done  mode=explore rounds=5/8 toolCalls=11 failureReason=SUCCESS
 * </pre>
 * 两条 start 的时间戳几乎相同、done 晚十几秒 → 重叠；若两条 start 相差一整个 done 的时长 → 串行了。
 */
final class SubagentLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private SubagentLog() {}

    static void line(String msg) {
        System.out.println("[Subagent " + LocalTime.now().format(TIME) + "] " + msg);
    }
}
