package com.example.jingjingai2.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理的**短笔记 + 派出/续跑计数 + 宽泛目标闸记忆**（会话级，纯内存）。
 *
 * 生命周期（和 token 预算同一哲学：用的时候隔离，不够由主循环重试，结论出来立刻释放）：
 *  - 每次 {@code spawnSubagent} 落一条 {@link LastRunNote}（按规范化 goal 索引）；
 *  - 新用户消息进来 → 整会话释放（主 agent 已经翻篇了）；
 *  - 计划全部完成 / resetIfFinished（结论出来了）→ **只释放 COMPLETE 的便条**，
 *    PARTIAL 的留着 —— 它可能马上要被 {@code resume} 续跑（第二轮改造点）；
 *  - **不跨会话、不落库、不写 conversation_message** —— 第一期就是便条，不是长期记忆。
 *
 * 为什么按"规范化 goal"而不是原始字符串：模型每次措辞略有不同（大小写、空格、标点），
 * 不做归一化就会把"同一个目标"当成新目标，重派闸门形同虚设。
 *
 * 线程安全：并发子代理会同时读写真个 map，用 ConcurrentHashMap + AtomicInteger。
 */
@Component
public class SubagentMemory {

    /** conversationId → (goalKey → 上次笔记) */
    private final Map<String, Map<String, LastRunNote>> notes = new ConcurrentHashMap<>();
    /** conversationId → (goalKey → 本请求已派出次数) */
    private final Map<String, Map<String, AtomicInteger>> attempts = new ConcurrentHashMap<>();
    /** conversationId → (goalKey → 本请求已续跑次数) */
    private final Map<String, Map<String, AtomicInteger>> resumes = new ConcurrentHashMap<>();
    /** conversationId → 已经被"宽泛目标闸"提醒过的 vagueKey（同一目标只提醒一次，避免死循环） */
    private final Map<String, Set<String>> vagueNudged = new ConcurrentHashMap<>();

    /** 归一化：去首尾空白、压缩连续空白、统一小写、去掉常见尾部标点 */
    public static String normalize(String goal) {
        if (goal == null) return "";
        String s = goal.trim().replaceAll("\\s+", " ").toLowerCase();
        while (!s.isEmpty() && "。．.！!？?；;，,、：:".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 上次这一目标的笔记；没有则 null */
    public LastRunNote noteOf(String conversationId, String goalKey) {
        if (conversationId == null || goalKey == null) return null;
        Map<String, LastRunNote> m = notes.get(conversationId);
        return m == null ? null : m.get(goalKey);
    }

    /** 本请求内这一目标已经派出过几次（0 = 还没派过） */
    public int attemptsOf(String conversationId, String goalKey) {
        if (conversationId == null || goalKey == null) return 0;
        Map<String, AtomicInteger> m = attempts.get(conversationId);
        if (m == null) return 0;
        AtomicInteger c = m.get(goalKey);
        return c == null ? 0 : c.get();
    }

    /** 记一次派出，返回累计次数 */
    public int recordAttempt(String conversationId, String goalKey) {
        if (conversationId == null || goalKey == null) return 0;
        return attempts.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(goalKey, k -> new AtomicInteger())
                .incrementAndGet();
    }

    /** 本请求内这一目标已经续跑过几次 */
    public int resumesOf(String conversationId, String goalKey) {
        if (conversationId == null || goalKey == null) return 0;
        Map<String, AtomicInteger> m = resumes.get(conversationId);
        if (m == null) return 0;
        AtomicInteger c = m.get(goalKey);
        return c == null ? 0 : c.get();
    }

    /** 记一次续跑，返回累计次数 */
    public int recordResume(String conversationId, String goalKey) {
        if (conversationId == null || goalKey == null) return 0;
        return resumes.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(goalKey, k -> new AtomicInteger())
                .incrementAndGet();
    }

    /**
     * 宽泛目标闸用：这个宽泛目标是不是**第一次**被拦？
     * 第一次返回 true（调用方拦截并给出收窄提示），之后返回 false（放行 —— 否则主 agent
     * 换个措辞再试一次还是被拦，就变成死循环了）。
     */
    public boolean markVagueNudged(String conversationId, String vagueKey) {
        if (conversationId == null || vagueKey == null || vagueKey.isBlank()) return false;
        Set<String> set = vagueNudged.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet());
        return set.add(vagueKey);
    }

    /** 落一条笔记（覆盖同 key 的上一条） */
    public void remember(String conversationId, String goalKey, LastRunNote note) {
        if (conversationId == null || goalKey == null || note == null) return;
        notes.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>()).put(goalKey, note);
    }

    /**
     * 清掉这一目标的便条。触发点：**同一目标这次跑完整了**（COMPLETE）。
     *
     * 为什么必须有：第一次 PARTIAL 留了便条，重派之后这次完整了 —— 旧便条如果不删，
     * 下一次再派同一个目标时注入的就是一张**过期**的"上次没查清"，比不注入还糟。
     */
    public void forget(String conversationId, String goalKey) {
        if (conversationId == null || goalKey == null) return;
        Map<String, LastRunNote> m = notes.get(conversationId);
        if (m != null) m.remove(goalKey);
    }

    /**
     * 释放**整个会话**的笔记与计数。
     * 触发点：新用户消息（主 agent 翻篇了）。
     */
    public void releaseConversation(String conversationId) {
        if (conversationId == null) return;
        Map<String, LastRunNote> removed = notes.remove(conversationId);
        attempts.remove(conversationId);
        resumes.remove(conversationId);
        vagueNudged.remove(conversationId);
        if (removed != null && !removed.isEmpty()) {
            SubagentLog.line("释放会话便条 " + removed.size() + " 条（会话 " + shortId(conversationId) + "）");
        }
    }

    /**
     * 结论出来了 → 释放 **COMPLETE** 的便条；**PARTIAL 的留着**。
     *
     * 为什么留：PARTIAL 的便条刚被写进工具结果，主 agent 完全可能紧接着就 {@code resume} 它；
     * 现在放掉，续跑就只能降级成"新房间 + 注入摘要"。次数计数**不放** ——
     * 它是"同一目标最多派几次 / 续几次"的闸门，放掉等于把闸门拆了。
     */
    public void releaseCompleted(String conversationId) {
        if (conversationId == null) return;
        Map<String, LastRunNote> m = notes.get(conversationId);
        if (m == null) return;
        int removed = 0;
        for (Map.Entry<String, LastRunNote> e : m.entrySet()) {
            if (e.getValue() != null && e.getValue().completeness() == SubagentReport.Status.COMPLETE) {
                m.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            SubagentLog.line("释放已完成便条 " + removed + " 条，保留未完成 " + m.size()
                    + " 条（会话 " + shortId(conversationId) + "）");
        }
    }

    /** 测试/诊断：当前会话有几条笔记 */
    public int noteCount(String conversationId) {
        if (conversationId == null) return 0;
        Map<String, LastRunNote> m = notes.get(conversationId);
        return m == null ? 0 : m.size();
    }

    /** 测试/诊断：当前会话有几条**未完成**（PARTIAL）笔记 */
    public int partialNoteCount(String conversationId) {
        if (conversationId == null) return 0;
        Map<String, LastRunNote> m = notes.get(conversationId);
        if (m == null) return 0;
        int n = 0;
        for (LastRunNote note : m.values()) {
            if (note != null && note.completeness() != SubagentReport.Status.COMPLETE) n++;
        }
        return n;
    }

    /** 测试/诊断：已知的所有会话 key */
    public List<String> conversations() {
        return new ArrayList<>(notes.keySet());
    }

    private static String shortId(String id) {
        if (id == null) return "null";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
