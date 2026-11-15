package com.example.jingjingai2.permission;

import com.example.jingjingai2.mcp.McpNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存审批。重启即失效。同一条记录只能从 PENDING 迁出一次。
 *
 * <p>「本会话允许」也只在内存里：寿命跟随 {@link GrantKey} 三元组，
 * 由 {@link #forgetServer} / {@link #forgetTool} / {@link #forgetConversation}
 * 在服务、工具策略或会话被真正改掉时清掉，没有 TTL，也没有持久化。
 *
 * <p>{@link #decide} 只把审批状态迁出 PENDING，并在记录上记下「想要本会话允许」的意图。
 * 真正写入要等网关复查通过后调用 {@link #commitSessionGrant}。
 * 这样等待期间策略已经变成拒绝时，这次批准不会留下授权。
 */
@Component
public class ApprovalService {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED }

    public static final class Record {
        public final String id;
        public final Long userId;
        public final String conversationId;
        public final String toolName;
        public final String source;
        public final String arguments;
        public final String risk;
        public final boolean tainted;
        public final boolean canSessionGrant;
        /**
         * 弹卡那一次 explain 已经认定定义变更。
         * 复查时如果当时没有、现在有，这次批准作废；当时已经变更的，这次批准就是用户对新定义的确认。
         */
        volatile boolean definitionChangedAtCard;
        public final Instant createdAt;
        public final Instant expiresAt;
        final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
        final CompletableFuture<Status> future = new CompletableFuture<>();
        volatile boolean sessionGrant;
        /** 非空时本会话授权记在这个键上，而不是 {@link #toolName}。高危参数用。 */
        volatile String sessionGrantKey;

        Record(String id, Long userId, String conversationId, String toolName, String source,
               String arguments, String risk, boolean tainted, boolean canSessionGrant,
               Instant createdAt, Instant expiresAt) {
            this.id = id;
            this.userId = userId;
            this.conversationId = conversationId;
            this.toolName = toolName;
            this.source = source;
            this.arguments = arguments;
            this.risk = risk;
            this.tainted = tainted;
            this.canSessionGrant = canSessionGrant;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public Status status() { return status.get(); }
    }

    public static final class HttpResult {
        public final int http;
        public final String status;
        public final String message;

        HttpResult(int http, String status, String message) {
            this.http = http;
            this.status = status;
            this.message = message;
        }
    }

    /**
     * 会话授权的键：谁、哪条会话、哪个工具。
     *
     * <p>{@code toolName} 用的是对外名（MCP 是 {@code mcp__服务__工具}，内置工具是原名），
     * 不是服务端原始名。服务归属只能靠这个名字的服务段判断。
     * 高危参数的授权在工具名后加 {@link #ARG_GRANT_MARK}，不和普通调用混在一个键里。
     */
    public record GrantKey(Long userId, String conversationId, String toolName) {}

    /** 高危参数的本会话授权后缀。和普通调用分开，避免无害参数的允许盖过后来的 rm -rf。 */
    public static final String ARG_GRANT_MARK = "\u0000args";

    public static String argGrantName(String toolName) {
        return toolName + ARG_GRANT_MARK;
    }

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GrantKey, Boolean> sessionGrants = new ConcurrentHashMap<>();
    private final int timeoutSeconds;

    @Autowired
    public ApprovalService(PermissionProperties properties) {
        this(properties == null ? 120 : properties.getApprovalTimeoutSeconds());
    }

    public ApprovalService(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
    }

    public int timeoutSeconds() { return timeoutSeconds; }

    public Record create(Long userId, String conversationId, String toolName, String source,
                         String arguments, String risk, boolean tainted, boolean canSessionGrant) {
        Instant now = Instant.now();
        Record r = new Record(UUID.randomUUID().toString(), userId, conversationId, toolName, source,
                arguments, risk, tainted, canSessionGrant, now, now.plusSeconds(timeoutSeconds));
        evictFinishedIfCrowded();
        records.put(r.id, r);
        return r;
    }

    /** 只丢掉已经结束的审批。正在等的不淘汰。 */
    private void evictFinishedIfCrowded() {
        if (records.size() < 400) return;
        records.entrySet().removeIf(e -> e.getValue().status.get() != Status.PENDING);
    }

    public List<Record> pending() {
        List<Record> out = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.status.get() == Status.PENDING) out.add(r);
        }
        return out;
    }

    /** 阻塞到决定、超时或取消。超时自己把状态收成 EXPIRED。 */
    public Status await(Record record) {
        try {
            return record.future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (record.status.compareAndSet(Status.PENDING, Status.EXPIRED)) {
                record.future.complete(Status.EXPIRED);
                return Status.EXPIRED;
            }
            return record.future.getNow(record.status.get());
        } catch (Exception e) {
            if (record.status.compareAndSet(Status.PENDING, Status.CANCELLED)) {
                record.future.complete(Status.CANCELLED);
            }
            return record.future.getNow(Status.CANCELLED);
        }
    }

    public HttpResult decide(String id, Long userId, String decision) {
        Record r = records.get(id);
        if (r == null) return new HttpResult(404, null, "审批不存在或已失效");
        if (userId == null || r.userId == null || !r.userId.equals(userId)) {
            return new HttpResult(403, r.status.get().name(), "不能决定别人的审批");
        }
        Status next;
        boolean grant = false;
        if ("APPROVE".equals(decision)) {
            next = Status.APPROVED;
        } else if ("APPROVE_SESSION".equals(decision)) {
            next = Status.APPROVED;
            grant = r.canSessionGrant;
        } else if ("REJECT".equals(decision)) {
            next = Status.REJECTED;
        } else {
            return new HttpResult(400, r.status.get().name(), "decision 必须是 APPROVE、APPROVE_SESSION 或 REJECT");
        }
        if (!r.status.compareAndSet(Status.PENDING, next)) {
            return new HttpResult(409, r.status.get().name(), "审批已结束");
        }
        // 只记意图。写入推迟到复查通过，避免 DENY 之后这次批准还留下授权。
        r.sessionGrant = grant && next == Status.APPROVED;
        r.future.complete(next);
        return new HttpResult(200, next.name(), null);
    }

    /**
     * 复查确认「这次已批准的调用仍可执行，且现在仍允许本会话授权」之后才写入。
     * 重复调用只是再放一次同一个键。未批准、或 decide 没留下意图时不写。
     *
     * @return 这次是否写入
     */
    public boolean commitSessionGrant(Record record) {
        if (record == null || !record.sessionGrant) return false;
        if (record.status.get() != Status.APPROVED) return false;
        if (record.userId == null || record.conversationId == null || record.toolName == null) return false;
        String key = record.sessionGrantKey == null || record.sessionGrantKey.isBlank()
                ? record.toolName : record.sessionGrantKey;
        rememberGrant(record.userId, record.conversationId, key);
        return true;
    }

    public void cancelConversation(String conversationId) { cancelConversation(null, conversationId); }

    public void cancelConversation(Long userId, String conversationId) {
        if (conversationId == null) return;
        for (Record r : records.values()) {
            if (!conversationId.equals(r.conversationId)
                    || (userId != null && !userId.equals(r.userId))) continue;
            if (r.status.compareAndSet(Status.PENDING, Status.CANCELLED)) {
                r.future.complete(Status.CANCELLED);
            }
        }
    }

    public boolean sessionGranted(Long userId, String conversationId, String toolName) {
        if (userId == null || conversationId == null || toolName == null) return false;
        return sessionGrants.containsKey(new GrantKey(userId, conversationId, toolName));
    }

    /**
     * 服务真的被删掉 / 改配置之后，清掉<b>这个用户</b>在这个服务名下的会话授权。
     *
     * <p>必须带 userId：不同用户可以有同名服务，只按服务名清会把别人的授权一起清掉。
     * 只认对外名的服务段<b>精确相等</b>，不做前缀匹配。服务段自己被截断时无法还原原始
     * 服务名，这类授权不会被清（宁可不删别人的）。
     *
     * @return 清掉的条数；内置工具的授权不受影响
     */
    public int forgetServer(long userId, String serverName) {
        if (serverName == null || serverName.isBlank()) return 0;
        String segment = McpNames.sanitize(serverName);
        int removed = 0;
        for (GrantKey key : sessionGrants.keySet()) {
            if (key.userId() == null || key.userId() != userId) continue;
            if (!McpNames.isMcp(key.toolName())) continue;
            if (!segment.equals(McpNames.serverOf(key.toolName()))) continue;
            if (sessionGrants.remove(key) != null) removed++;
        }
        return removed;
    }

    /**
     * 某个工具的策略写成功之后，清掉<b>这个用户</b>这个工具的会话授权。
     * 其他用户、同一服务的其他工具、其他服务、内置工具都不动。
     *
     * <p>键是对外名。传入服务端原始名时按 {@link McpNames#exposed} 还原；
     * 传入的本来就是对外名则直接比。
     *
     * @return 清掉的条数
     */
    public int forgetTool(long userId, String serverName, String toolName) {
        if (serverName == null || serverName.isBlank() || toolName == null || toolName.isBlank()) return 0;
        String exposed = McpNames.isMcp(toolName) ? toolName : McpNames.exposed(serverName, toolName);
        String argKey = argGrantName(exposed);
        int removed = 0;
        for (GrantKey key : sessionGrants.keySet()) {
            if (key.userId() == null || key.userId() != userId) continue;
            if (!exposed.equals(key.toolName()) && !argKey.equals(key.toolName())) continue;
            if (sessionGrants.remove(key) != null) removed++;
        }
        return removed;
    }

    /**
     * 会话真的被删掉之后，清掉<b>这个用户在这条会话</b>里的会话授权。
     *
     * <p>带 userId：会话标识由客户端生成，不能仅凭一个字符串就清掉别人的授权。
     * 其他会话、其他用户的授权都不动。
     *
     * @return 清掉的条数
     */
    public int forgetConversation(Long userId, String conversationId) {
        if (userId == null || conversationId == null) return 0;
        int removed = 0;
        for (GrantKey key : sessionGrants.keySet()) {
            if (!userId.equals(key.userId()) || !conversationId.equals(key.conversationId())) continue;
            if (sessionGrants.remove(key) != null) removed++;
        }
        return removed;
    }

    private void rememberGrant(Long userId, String conversationId, String toolName) {
        sessionGrants.put(new GrantKey(userId, conversationId, toolName), Boolean.TRUE);
    }

    /** 测试用：清掉全部记录。 */
    public void clear() {
        List<Record> all = new ArrayList<>(records.values());
        for (Record r : all) {
            if (r.status.compareAndSet(Status.PENDING, Status.CANCELLED)) {
                r.future.complete(Status.CANCELLED);
            }
        }
        records.clear();
        sessionGrants.clear();
    }
}
