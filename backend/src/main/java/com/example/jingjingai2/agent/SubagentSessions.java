package com.example.jingjingai2.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理房间的**历史保留**（resume 的底座）。
 *
 * 为什么留着：Claude Code 的 {@code maxTurns} 到顶时返回"部分结果"并允许**恢复**那个子代理
 * （保留对话历史、复用 prompt 缓存），而不是让你从零重开一个。我们照这个来 ——
 * 轮数用尽时把 messages 留下，续跑时**前缀不变**，只在末尾追加一句
 * "你上次因轮数用尽而中断，请只补 unresolved 里的问题"。
 *
 * 代价要说清楚：留着历史 = 续跑那一轮的输入 token 明显更大（整个上次对话都在）。
 * 所以本类**只做保留**，值不值得续跑由主 agent 决定，实测数据（日志里的 input token）给人看。
 *
 * 上限：每个会话最多留 {@link #MAX_SESSIONS_PER_CONVERSATION} 个房间，超出按最久未用淘汰，
 * 避免长会话把内存吃光。释放点与 {@link SubagentMemory} 一致（新请求 / 结论出来）。
 */
@Component
public class SubagentSessions {

    /** 一次可续跑的房间 */
    public static final class Session {
        private final String resumeId;
        private final String conversationId;
        private final SubagentSpec.Mode mode;
        private final String goal;
        private final List<Message> history;
        private final ReadLedgerEntry.Ledger ledger = new ReadLedgerEntry.Ledger();
        private final AtomicInteger resumes = new AtomicInteger();
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile SubagentReport lastReport;

        Session(String resumeId, String conversationId, SubagentSpec.Mode mode, String goal,
                List<Message> history) {
            this.resumeId = resumeId;
            this.conversationId = conversationId;
            this.mode = mode;
            this.goal = goal;
            this.history = history;
        }

        public String resumeId() { return resumeId; }
        public String conversationId() { return conversationId; }
        public SubagentSpec.Mode mode() { return mode; }
        public String goal() { return goal; }
        public int resumes() { return resumes.get(); }
        public SubagentReport lastReport() { return lastReport; }

        /** 历史的**快照**（续跑时传给内核；内核会就地追加，所以给副本，避免两个线程同时改） */
        public synchronized List<Message> historySnapshot() {
            return new ArrayList<>(history);
        }

        /** 内核跑完把最新历史放回来（整体替换，保持"前缀不变、只加尾巴"的语义） */
        public synchronized void replaceHistory(List<Message> latest) {
            history.clear();
            if (latest != null) history.addAll(latest);
            lastTouched = System.currentTimeMillis();
        }

        public synchronized void addAll(ReadLedgerEntry.Ledger more) {
            if (more == null) return;
            for (ReadLedgerEntry e : more.entries()) ledger.add(e);
        }

        public ReadLedgerEntry.Ledger ledger() { return ledger; }

        public void markResumed() {
            resumes.incrementAndGet();
            lastTouched = System.currentTimeMillis();
        }

        public void lastReport(SubagentReport r) { this.lastReport = r; lastTouched = System.currentTimeMillis(); }

        /** 便条里"已读过"用 */
        public List<String> ledgerPaths() { return ledger.paths(); }
    }

    private static final int MAX_SESSIONS_PER_CONVERSATION = 8;
    private static final long SESSION_TTL_MS = 6L * 60 * 60 * 1000;

    private final Map<String, Map<String, Session>> byConversation = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    /** 开一个可续跑的房间，返回给主 agent 的凭据（短 id，模型抄得动） */
    public Session open(String conversationId, SubagentSpec spec, List<Message> history) {
        Session s = new Session(nextResumeId(), conversationId, spec.mode(), spec.goal(), history);
        Map<String, Session> m = byConversation.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>());
        m.put(s.resumeId(), s);
        evictIfNeeded(m);
        return s;
    }

    /** 按凭据找回（**必须**校验会话：别的会话的凭据不能续我们的房间） */
    public Session find(String conversationId, String resumeId) {
        if (conversationId == null || resumeId == null || resumeId.isBlank()) return null;
        Map<String, Session> m = byConversation.get(conversationId);
        if (m == null) return null;
        Session s = m.get(resumeId.trim());
        if (s != null) s.lastTouched = System.currentTimeMillis();
        return s;
    }

    /**
     * 只凭 resumeId 找回（id 进程内唯一）。父循环 {@code isImplementSpawn} 拿不到 conversationId
     * 时，仍要从原房间记录恢复 mode，不能把漏写 mode 的续跑当成 explore。
     */
    public Session findByResumeId(String resumeId) {
        if (resumeId == null || resumeId.isBlank()) return null;
        String id = resumeId.trim();
        for (Map<String, Session> m : byConversation.values()) {
            Session s = m.get(id);
            if (s != null) {
                s.lastTouched = System.currentTimeMillis();
                return s;
            }
        }
        return null;
    }

    /**
     * 同一个 goal 最近一个还活着的房间（合并路径：主 agent 没抄 resumeId 但目标一模一样时，
     * 与其白白丢历史，不如接上）。找不到返回 null → 调用方走降级方案。
     */
    public Session latestForGoal(String conversationId, SubagentSpec spec) {
        Map<String, Session> m = byConversation.get(conversationId);
        if (m == null) return null;
        Session best = null;
        for (Session s : m.values()) {
            if (!s.goal().equals(spec.goal())) continue;
            if (s.mode() != spec.mode()) continue;
            if (best == null || s.lastTouched > best.lastTouched) best = s;
        }
        return best;
    }

    public void close(String conversationId, String resumeId) {
        Map<String, Session> m = byConversation.get(conversationId);
        if (m != null) m.remove(resumeId);
    }

    /** 释放整个会话保留的房间（新用户请求） */
    public void releaseConversation(String conversationId) {
        if (conversationId == null) return;
        Map<String, Session> removed = byConversation.remove(conversationId);
        if (removed != null && !removed.isEmpty()) {
            SubagentLog.line("释放保留的房间历史 " + removed.size() + " 个");
        }
    }

    /**
     * 结论出来了 → 只释放**已经完整**的房间历史；PARTIAL 的留着（可能马上要被续跑）。
     * 与 {@link SubagentMemory#releaseCompleted} 同一套判据。
     */
    public void releaseCompleted(String conversationId) {
        if (conversationId == null) return;
        Map<String, Session> m = byConversation.get(conversationId);
        if (m == null) return;
        int removed = 0;
        for (Session s : new ArrayList<>(m.values())) {
            SubagentReport r = s.lastReport();
            if (r == null || r.status() == SubagentReport.Status.COMPLETE) {
                m.remove(s.resumeId());
                removed++;
            }
        }
        if (removed > 0) {
            SubagentLog.line("释放已完成的房间历史 " + removed + " 个，保留未完成 " + m.size() + " 个");
        }
    }

    public int sessionCount(String conversationId) {
        Map<String, Session> m = byConversation.get(conversationId);
        return m == null ? 0 : m.size();
    }

    /** 测试用：直接看某个凭据还在不在 */
    public boolean contains(String conversationId, String resumeId) {
        Map<String, Session> m = byConversation.get(conversationId);
        return m != null && m.containsKey(resumeId);
    }

    private void evictIfNeeded(Map<String, Session> m) {
        long now = System.currentTimeMillis();
        m.entrySet().removeIf(e -> !keepForResume(e.getValue())
                && now - e.getValue().lastTouched > SESSION_TTL_MS);
        while (m.size() > MAX_SESSIONS_PER_CONVERSATION) {
            Session oldest = null;
            for (Session s : m.values()) {
                if (keepForResume(s)) continue;
                if (oldest == null || s.lastTouched < oldest.lastTouched) oldest = s;
            }
            if (oldest == null) break;
            m.remove(oldest.resumeId());
            SubagentLog.line("保留房间超出上限，淘汰已结束的 " + oldest.resumeId());
        }
    }

    /** 还没跑完，或仍是 PARTIAL，都不能为了腾位置删掉。 */
    private static boolean keepForResume(Session s) {
        SubagentReport report = s.lastReport();
        return report == null || report.status() == SubagentReport.Status.PARTIAL;
    }

    /** 短凭据：前 3 个 hex 位是序号（并发下唯一），后 3 位取自 goal 的 hash（人眼可对照） */
    private String nextResumeId() {
        int n = seq.incrementAndGet();
        return "sa-" + Integer.toHexString(n);
    }

    /** 诊断：把会话里所有凭据列出来（保序） */
    public List<String> resumeIds(String conversationId) {
        Map<String, Session> m = byConversation.get(conversationId);
        if (m == null) return List.of();
        return new ArrayList<>(new LinkedHashMap<>(m).keySet());
    }
}
