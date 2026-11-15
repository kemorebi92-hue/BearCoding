package com.example.jingjingai2.mcp;

import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.tool.ToolExecutionStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按配置拉起 MCP Server 子进程，把远端工具收成 {@link ToolCallback} 数组。
 *
 * <p>关掉（默认）时 {@link #start()} 是空操作，{@link #callbacks()} 返回空数组。
 * 单个 server 启动失败只记日志、跳过，不让整个应用起不来。
 * Spring 启动走 {@link #boot()} 后台连接，避免 Playwright 冷启动堵住端口监听。
 */
@Component
public class McpClientManager {

    static final long RECONNECT_BACKOFF_MS = 2_000L;
    static final int RECONNECT_FAIL_LIMIT = 3;
    static final long RECONNECT_COOLDOWN_MS = 60_000L;

    /**
     * 会执行页面脚本或上传文件的高危工具名。
     * 只用于启动提示，<strong>不是安全控制</strong>：是否允许执行仍由统一权限网关 + URL 守卫决定。
     */
    private static final Set<String> RISKY_MCP_TOOLS = Set.of(
            "browser_evaluate", "browser_run_code", "browser_run_code_unsafe", "file_upload");

    /** 同一 server 的同一高危工具只提示一次，重连不重复刷屏。 */
    private final Set<String> warnedRiskyTools = ConcurrentHashMap.newKeySet();

    private final McpProperties props;
    private McpOutputStore outputStore;
    /** 运行时新增的服务配置。没注入时（单测直接 new）不读数据库。 */
    private com.example.jingjingai2.mapper.McpServerConfigMapper serverConfigMapper;
    private com.example.jingjingai2.service.McpCredentialService credentialService;
    private final List<McpSession> sessions = new CopyOnWriteArrayList<>();
    /**
     * 一个服务实例的身份：谁的服务 + 服务名。
     *
     * <p>不能只用服务名：不同用户允许有同名服务，运行时、工具回调、授权清理都必须能区分。
     * {@code ownerId == 0} 表示部署配置（YAML）或历史遗留的系统共享服务，不属于任何普通用户。
     */
    public record ServiceKey(long ownerId, String name) {}

    /** 已连上的服务，按 (ownerId, name) 索引。 */
    private final Map<ServiceKey, Slot> slots = new LinkedHashMap<>();
    /** 每个用户可见的工具清单 / 名字映射 / 提示词行，按 ownerId 缓存；槽位变化时整体失效。 */
    private final Map<Long, ToolCallback[]> callbacksByOwner = new HashMap<>();
    private final Map<Long, Map<String, String[]>> nameMapByOwner = new HashMap<>();
    private final Map<Long, List<String>> serverLinesByOwner = new HashMap<>();
    /** 保护 slots 这个 Map 本身的增删。连接和重连仍走每个 slot 自己的锁。 */
    private final Object slotsLock = new Object();
    private final ConcurrentHashMap<String, Set<String>> taintedBy = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-connect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger connectGenerations = new AtomicInteger();

    public McpClientManager(McpProperties props) {
        this.props = props;
    }

    /**
     * 超限落盘。没注入时（单测直接 new）行为与加这个功能之前完全一致：只做旧截断。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOutputStore(McpOutputStore outputStore) {
        this.outputStore = outputStore;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setServerConfigMapper(com.example.jingjingai2.mapper.McpServerConfigMapper serverConfigMapper) {
        this.serverConfigMapper = serverConfigMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCredentialService(com.example.jingjingai2.service.McpCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** Spring 启动：不阻塞 Tomcat。测试请调 {@link #start()} 同步连完。 */
    @PostConstruct
    public void boot() {
        if (props == null || !props.isEnabled()) {
            System.out.println("[MCP] 未启用（app.mcp.enabled=false）");
            return;
        }
        warnBootConfigIfAny();
        prepareSlots(props.getServers());
        // 数据库里的用户服务与 YAML 是否有内容无关：没有 YAML 也必须把它们拉起来。
        loadRuntimeConfigs();
        executor.submit(() -> {
            for (Slot slot : slots.values()) {
                connectSlot(slot, false);
            }
        });
    }

    /**
     * 测试与显式同步启动。关掉时仍是空操作。
     */
    public void start() {
        if (props == null || !props.isEnabled()) {
            System.out.println("[MCP] 未启用（app.mcp.enabled=false）");
            return;
        }
        warnBootConfigIfAny();
        prepareSlots(props.getServers());
        loadRuntimeConfigs();
        for (Slot slot : slots.values()) {
            connectSlot(slot, false);
        }
    }

    private void warnBootConfigIfAny() {
        Map<String, McpServerSpec> servers = props.getServers();
        if (servers == null || servers.isEmpty()) {
            System.out.println("[MCP] app.mcp.servers 为空，只加载用户自己的服务");
            return;
        }
        warnBootConfig();
    }

    void warnBootConfig() {
        Map<String, McpServerSpec> servers = props.getServers();
        if (servers == null) return;
        for (Map.Entry<String, McpServerSpec> e : servers.entrySet()) {
            String serverName = e.getKey();
            McpServerSpec spec = e.getValue();
            if (spec == null) continue;
            // enabled-tools 已废弃：只在启动时提示一次，不阻断启动，也不再用它判断是否漏配工具名。
            List<String> ignored = spec.getEnabledTools();
            if (ignored != null && !ignored.isEmpty()) {
                System.err.println("[MCP] server=" + serverName
                        + " 的 enabled-tools 已废弃并被忽略（" + ignored.size() + " 项："
                        + String.join(", ", ignored)
                        + "）。现在默认暴露该 server 发现的所有工具；如需排除请改用 disabled-tools");
            }
            if (spec.isUntrusted()) {
                List<String> disabled = spec.getDisabledTools();
                if (disabled == null || disabled.isEmpty()) {
                    System.err.println("[MCP] server=" + serverName
                            + " trust=untrusted 且未配置 disabled-tools，将暴露该 server 全部工具"
                            + "（能否执行仍由统一权限与 URL 守卫决定）");
                }
            }
            Map<String, String> env = spec.getEnv();
            if (env != null) {
                for (String key : env.keySet()) {
                    if (isSensitiveEnvKey(key)) {
                        System.err.println("[MCP] server=" + serverName
                                + " env 含敏感键名 " + key + "（值已脱敏，不会打印）");
                    }
                }
            }
        }
    }

    static boolean isSensitiveEnvKey(String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.toUpperCase(Locale.ROOT);
        return k.contains("KEY") || k.contains("TOKEN") || k.contains("SECRET") || k.contains("PASSWORD");
    }

    private synchronized void prepareSlots(Map<String, McpServerSpec> servers) {
        slots.clear();
        callbacksByOwner.clear();
        nameMapByOwner.clear();
        serverLinesByOwner.clear();
        if (servers == null) return;
        for (Map.Entry<String, McpServerSpec> e : servers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            Slot slot = new Slot(0L, e.getKey(), e.getValue());
            slot.state = "connecting";
            slots.put(slot.key(), slot);
        }
    }

    private void connectSlot(Slot slot, boolean backoff) {
        CompletableFuture<McpSession> existing;
        synchronized (slot.lock) {
            if (slot.session != null && slot.session.isAlive()) {
                slot.state = "connected";
                return;
            }
            if (slot.inflight != null && !slot.inflight.isDone()) {
                existing = slot.inflight;
            } else {
                long now = System.currentTimeMillis();
                if (slot.consecutiveFailures >= RECONNECT_FAIL_LIMIT && now < slot.cooldownUntilMs) {
                    slot.state = "failed";
                    return;
                }
                slot.connectEpoch++;
                slot.state = "connecting";
                System.out.println("[MCP] server=" + slot.name + " connecting");
                boolean doBackoff = backoff && slot.consecutiveFailures > 0;
                final int epoch = slot.connectEpoch;
                slot.inflight = CompletableFuture.supplyAsync(() -> {
                    if (doBackoff) {
                        try {
                            Thread.sleep(RECONNECT_BACKOFF_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                    return openAndInstall(slot, epoch);
                }, executor);
                existing = slot.inflight;
            }
        }
        try {
            existing.get(Math.max(slot.spec.initializeTimeoutMs(props) + 5_000L, 20_000L), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // openAndInstall 已经记了 lastError
        }
    }

    /**
     * epoch 是这次连接开始时槽位上的代际。断开、停用、删除、以及之后开始的新连接都会把它加一。
     * 对不上就说明这是一次迟到的旧连接：关掉刚拉起的会话，不能把槽位装回 connected。
     */
    private McpSession openAndInstall(Slot slot, int epoch) {
        connectGenerations.incrementAndGet();
        McpSession session = null;
        try {
            session = openSession(slot.ownerId, slot.name, slot.spec);
            synchronized (slot.lock) {
                boolean same = slots.get(slot.key()) == slot;
                if (epoch != slot.connectEpoch || !same) {
                    // 自己已经过期：inflight 这时可能属于更新的连接，不能清
                    session = discard(session);
                    return null;
                }
            }
            if (!installSession(slot, session, epoch)) {
                // 安装时才发现自己已经过期：不碰当前会话，也不碰 inflight，丢弃自己
                session = discard(session);
                return null;
            }
            synchronized (slot.lock) {
                boolean same = slots.get(slot.key()) == slot;
                if (epoch != slot.connectEpoch || !same || slot.session != session) {
                    if (slot.session == session) {
                        slot.session = null;
                        slot.state = "disconnected";
                        slot.offered = List.of();
                        slot.discovered = List.of();
                        slot.listedCount = 0;
                    }
                    // 同上：过期的自己不能清掉更新一代的 inflight
                    session = discard(session);
                    rebuildCallbacks();
                    return null;
                }
                slot.consecutiveFailures = 0;
                slot.lastError = null;
                slot.state = "connected";
                slot.inflight = null;
            }
            System.out.println("[MCP] server=" + slot.name + " connected");
            return session;
        } catch (Exception ex) {
            discard(session);
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            synchronized (slot.lock) {
                // 用户已经断开或另一次连接已经开始：这次的失败不能改它们的状态。
                if (epoch != slot.connectEpoch) {
                    return null;
                }
                slot.consecutiveFailures++;
                slot.lastError = msg;
                slot.session = null;
                slot.offered = List.of();
                // 已发现工具也是上一次连接的结果：不清的话，管理页会继续显示这些
                // 其实已经调不动的工具。
                slot.discovered = List.of();
                slot.listedCount = 0;
                if (slot.consecutiveFailures >= RECONNECT_FAIL_LIMIT) {
                    slot.state = "failed";
                    slot.cooldownUntilMs = System.currentTimeMillis() + RECONNECT_COOLDOWN_MS;
                    System.err.println("[MCP] server=" + slot.name + " failed（连续 "
                            + slot.consecutiveFailures + " 次），" + (RECONNECT_COOLDOWN_MS / 1000)
                            + "s 内不再重试: " + msg);
                } else {
                    slot.state = "failed";
                    System.err.println("[MCP] server=" + slot.name + " 启动失败，已跳过: " + msg);
                }
                slot.inflight = null;
            }
            rebuildCallbacks();
            return null;
        }
    }

    /** 关掉一次不该安装的会话。关闭抛出的异常不能再影响槽位状态。 */
    private McpSession discard(McpSession session) {
        if (session == null) return null;
        try {
            session.close();
        } catch (Exception ignored) {
        }
        sessions.remove(session);
        return null;
    }

    /**
     * 把会话装进槽位。{@code epoch} 是这次安装所属连接的代际。
     *
     * <p>{@code listTools()} 在锁外执行，可能耗时；这期间槽位可能已经被断开、重连换成了
     * 另一个会话。所以进入同步块后先比代际：过期就返回 false，不关闭、不覆盖当前会话，
     * 由调用方丢弃自己。返回 true 表示已安装。
     */
    private boolean installSession(Slot slot, McpSession session, int epoch) {
        // enabled-tools 已废弃：不再参与暴露判定（非空也只是打一次警告，见 warnBootConfig）。
        // 暴露 = 该 server 发现的所有工具 − disabled-tools。
        List<String> deny = slot.spec.getDisabledTools();
        boolean denyFilter = deny != null && !deny.isEmpty();
        Set<String> denySet = denyFilter ? new LinkedHashSet<>(deny) : Set.of();
        List<McpRemoteTool> listed = session.listTools();
        int maxChars = slot.spec.effectiveMaxResultChars(props);
        List<ToolCallback> cbs = new ArrayList<>();
        List<McpListedTool> discovered = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        if (listed != null) {
            for (McpRemoteTool tool : listed) {
                if (tool == null) continue;
                boolean exposed = isExposed(tool.originalName(), denyFilter, denySet);
                String exposedName = uniqueExposed(slot.name, tool.originalName(), used);
                used.add(exposedName);
                discovered.add(new McpListedTool(slot.name, tool.originalName(), exposedName,
                        tool.description(), tool.inputSchemaJson(), tool.annotations(), exposed));
                if (!exposed) continue;
                warnRiskyExposed(slot.name, tool.originalName());
                cbs.add(new McpToolCallback(session, exposedName, tool, maxChars, this, slot.spec, slot));
            }
        }
        synchronized (slot.lock) {
            if (epoch != slot.connectEpoch) return false;
            if (slot.session != null && slot.session != session) {
                try { slot.session.close(); } catch (Exception ignored) {}
                sessions.remove(slot.session);
            }
            slot.session = session;
            slot.listedCount = listed == null ? 0 : listed.size();
            slot.offered = List.copyOf(cbs);
            slot.discovered = List.copyOf(discovered);
            // 必须先标 connected，rebuild 才把本 slot 收进本轮清单。
            slot.state = "connected";
        }
        if (!sessions.contains(session)) sessions.add(session);
        rebuildCallbacks();
        return true;
    }

    /**
     * 槽位有任何变化后调用：丢掉所有用户的缓存，下一次按需重建。
     * 以前这里是全局一份清单，多用户下会把别人的工具也塞进当前用户的下发列表。
     */
    private synchronized void rebuildCallbacks() {
        callbacksByOwner.clear();
        nameMapByOwner.clear();
        serverLinesByOwner.clear();
        int total = 0;
        for (Slot slot : slots.values()) {
            if (!"connected".equals(slot.state) || slot.session == null || !slot.session.isAlive()) continue;
            List<ToolCallback> offered = slot.offered;
            if (offered != null) total += offered.size();
        }
        System.out.println("[MCP] 共暴露 " + total + " 个外部工具（所有用户合计，按请求分用户下发）");
    }

    /** 系统/部署级服务（ownerId=0）的工具。单测与部署视角用；生产按登录用户取。 */
    public ToolCallback[] callbacks() {
        return callbacks(0L);
    }

    /**
     * 某个用户本轮能看到的 MCP 工具：部署级/共享 + 自己的，按 (ownerId, name) 过滤。
     * 缓存按 userId 分开，避免同名服务的回调互相覆盖。
     */
    public synchronized ToolCallback[] callbacks(long userId) {
        ToolCallback[] cached = callbacksByOwner.get(userId);
        if (cached != null) return cached;
        List<ToolCallback> cbs = new ArrayList<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Slot slot : snapshotSlots()) {
            if (!visibleTo(slot, userId)) continue;
            if (!"connected".equals(slot.state) || slot.session == null || !slot.session.isAlive()) continue;
            List<ToolCallback> offered = slot.offered;
            if (offered == null) continue;
            for (ToolCallback cb : offered) {
                if (cb == null || cb.getToolDefinition() == null) continue;
                String exposed = cb.getToolDefinition().name();
                // 同一用户内重名只保留先到的一个（个人服务与共享服务重名在新增时就被拒绝）
                if (!used.add(exposed)) continue;
                String original = cb instanceof McpToolCallback m ? m.originalName() : McpNames.originalOf(exposed);
                names.put(exposed, new String[]{slot.name, original == null ? "" : original});
                cbs.add(cb);
            }
            String desc = slot.spec.getDescription();
            lines.add(desc == null || desc.isBlank() ? slot.name : slot.name + "（" + desc + "）");
        }
        ToolCallback[] arr = cbs.toArray(new ToolCallback[0]);
        callbacksByOwner.put(userId, arr);
        nameMapByOwner.put(userId, names);
        serverLinesByOwner.put(userId, List.copyOf(lines));
        return arr;
    }

    /**
     * 工具回调入口：URL 已在 callback 里查过。这里负责死会话单飞重连。
     *
     * <p>按<b>槽位对象本身</b>路由，不按服务名全局查：服务被删除后重建会产生新的 Slot，
     * 旧回调拿着的 Slot 已经不是当前生效的那个，直接失败，绝不会落到同名的新服务上。
     */
    public String invoke(Slot slot, String originalName, String argumentsJson, int maxResultChars) {
        if (slot == null) {
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
            return McpToolCallback.ERROR_PREFIX + "未知 MCP server";
        }
        if (slots.get(slot.key()) != slot) {
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
            return McpToolCallback.ERROR_PREFIX + "服务已删除或重建，请重试";
        }
        try {
            McpSession session = ensureSession(slot);
            if (session == null) {
                String err = slot.lastError == null ? "未连接" : slot.lastError;
                ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
                return McpToolCallback.ERROR_PREFIX + err;
            }
            // 必须在调用前记下会话是否活着。stdio 超时会自己把进程关掉，
            // 调用后再看 isAlive() 就分不清「调用前就死了」和「这次调用把它弄死的」。
            boolean aliveBefore = session.isAlive();
            McpCallResult r = session.callToolRaw(originalName, argumentsJson);
            if (shouldRetry(aliveBefore, r)) {
                markDead(slot, r == null ? "MCP 进程已退出" : r.text());
                session = ensureSession(slot);
                if (session == null) {
                    String err = slot.lastError == null ? "未连接" : slot.lastError;
                    ToolExecutionStatus.record(ToolExecutionStatus.Kind.FAILED);
                    return McpToolCallback.ERROR_PREFIX + err;
                }
                r = session.callToolRaw(originalName, argumentsJson);
            }
            if (r != null && r.delivery() != McpCallResult.Delivery.UNKNOWN) {
                String text = r.text() == null ? "" : r.text();
                text = spillOrTruncate(slot.name, originalName, text, maxResultChars);
                r = new McpCallResult(r.isError(), text, r.delivery());
            }
            return McpAccounting.finish(r);
        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) reason = e.getClass().getSimpleName();
            markDead(slot, reason);
            // 这里已经分不清请求有没有发出去，不能记成执行成功，也不能自动再发一次。
            ToolExecutionStatus.record(ToolExecutionStatus.Kind.UNKNOWN);
            return McpToolCallback.ERROR_PREFIX + reason;
        }
    }

    /** 系统/部署级服务的调用入口（单测与旧路径用）。 */
    public String invoke(String serverName, String originalName, String argumentsJson, int maxResultChars) {
        return invoke(slot(0L, serverName), originalName, argumentsJson, maxResultChars);
    }

    /** 超限落盘；没注入 store 时退回旧截断。 */
    private String spillOrTruncate(String server, String tool, String text, int inlineLimit) {
        if (outputStore == null) return McpContentMapper.truncate(text, inlineLimit);
        McpSpill spill = outputStore.handle(server, tool, text, inlineLimit);
        if (spill.spilled) notifySpilled(spill);
        return spill.text;
    }

    /** SSE：告诉前端这次超限落盘了。不带绝对路径。 */
    private void notifySpilled(McpSpill spill) {
        ToolCallContext ctx = ToolCallContext.current();
        if (ctx == null || ctx.events == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "mcp_output_spilled");
        payload.put("server", spill.server);
        payload.put("tool", spill.tool);
        payload.put("totalChars", spill.totalChars);
        payload.put("previewChars", spill.previewChars);
        payload.put("fileName", spill.fileName);
        ctx.events.spilled(payload);
    }

    private McpSession ensureSession(Slot slot) {
        CompletableFuture<McpSession> waitOn = null;
        synchronized (slot.lock) {
            // disconnected 只来自用户主动断开或停用（closeSlot）。意外断线是 failed，仍走下面的重连。
            // 显式 connectServer / addServer 不经过这里。
            if ("disconnected".equals(slot.state)) {
                return null;
            }
            if (slot.session != null && slot.session.isAlive() && "connected".equals(slot.state)) {
                return slot.session;
            }
            long now = System.currentTimeMillis();
            if (slot.consecutiveFailures >= RECONNECT_FAIL_LIMIT && now < slot.cooldownUntilMs) {
                slot.state = "failed";
                if (slot.lastError == null) slot.lastError = "连续失败冷却中";
                return null;
            }
            if (slot.inflight != null && !slot.inflight.isDone()) {
                waitOn = slot.inflight;
            }
        }
        if (waitOn != null) {
            try {
                return waitOn.get(Math.max(slot.spec.initializeTimeoutMs(props) + 5_000L, 20_000L),
                        TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return slot.session != null && slot.session.isAlive() ? slot.session : null;
            }
        }
        connectSlot(slot, true);
        synchronized (slot.lock) {
            return slot.session != null && slot.session.isAlive() ? slot.session : null;
        }
    }

    private void markDead(Slot slot, String reason) {
        McpSession old;
        synchronized (slot.lock) {
            old = slot.session;
            slot.session = null;
            slot.state = "failed";
            slot.lastError = reason;
            slot.offered = List.of();
            slot.discovered = List.of();
            slot.listedCount = 0;
        }
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
            sessions.remove(old);
        }
        rebuildCallbacks();
    }

    /**
     * 只有两种情况重发这次调用：调用前会话就已经死了，或者这次结果能证明请求一个字节都没出去。
     * 结果未知（超时、连接中断、写入抛异常）不重发，哪怕这次调用把会话自己关掉了。
     * 不看错误文本：工具返回的内容里出现「超时」不代表会话死了。
     */
    static boolean shouldRetry(boolean aliveBeforeCall, McpCallResult r) {
        if (!aliveBeforeCall) return true;
        return r != null && r.delivery() == McpCallResult.Delivery.NOT_SENT;
    }

    /** 测试可注入假 session，不必真拉进程。 */
    McpSession openSession(String serverName, McpServerSpec spec) {
        if (spec != null && spec.isStreamableHttp()) {
            return HttpMcpSession.start(serverName, spec, props);
        }
        return StdioMcpSession.start(serverName, spec, props);
    }

    /**
     * 带归属的创建入口：默认忽略 owner，转给按名字注入的那个方法。
     * 想区分「两个用户的同名服务」的测试覆盖这个方法。
     */
    McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
        if (spec != null && spec.isStreamableHttp()
                && com.example.jingjingai2.entity.McpServerConfig.AUTH_BEARER.equals(normalizedAuth(spec))) {
            if (ownerId <= 0 || spec.getServerConfigId() == null) {
                throw new McpException("Bearer 服务缺少可核对的用户和配置，拒绝连接");
            }
            if (credentialService == null) {
                throw new McpException("凭证服务不可用，拒绝连接");
            }
            String token = credentialService.load(ownerId, spec.getServerConfigId());
            return openBearerSession(serverName, spec, token);
        }
        return openSession(serverName, spec);
    }

    /**
     * 凭证已经核对并解密之后才到这里。测试可以覆盖这一步，改用本机假服务器，
     * 但不能跳过前面的归属检查。
     */
    McpSession openBearerSession(String serverName, McpServerSpec spec, String token) {
        return HttpMcpSession.start(serverName, spec, props, token);
    }

    private static String normalizedAuth(McpServerSpec spec) {
        String auth = spec.getAuthType();
        if (auth == null || auth.isBlank()) return com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE;
        return auth.trim();
    }

    /**
     * 高危工具按「实际发现且最终暴露」告警，同一 server + 工具只提示一次。
     * 只是提示，不改变授权规则。
     */
    void warnRiskyExposed(String serverName, String original) {
        if (original == null || !RISKY_MCP_TOOLS.contains(original)) return;
        if (!warnedRiskyTools.add(serverName + "\u0000" + original)) return;
        System.err.println("[MCP] server=" + serverName + " 暴露高危工具 '" + original
                + "'（可执行页面脚本或上传文件），调用仍需审批，请确认这是有意的");
    }

    /**
     * 只有 disabled-tools 会把工具从暴露集合里摘掉。
     * enabled-tools 已废弃，不再参与判断（字段保留只为兼容旧 YAML）。
     */
    static boolean isExposed(String original, boolean denyFilter, Set<String> deny) {
        return !(denyFilter && original != null && deny.contains(original));
    }

    private static String uniqueExposed(String server, String tool, Set<String> used) {
        String base = McpNames.exposed(server, tool);
        if (!used.contains(base)) return base;
        for (int i = 2; i < 100; i++) {
            String cand = base;
            String suffix = "_" + i;
            if (cand.length() + suffix.length() > McpNames.MAX_LEN) {
                cand = cand.substring(0, Math.max(1, McpNames.MAX_LEN - suffix.length()));
            }
            cand = cand + suffix;
            if (cand.length() > McpNames.MAX_LEN) cand = cand.substring(0, McpNames.MAX_LEN);
            if (!used.contains(cand) && McpNames.isLegal(cand)) return cand;
        }
        return base;
    }

    public boolean isEnabled() {
        return props != null && props.isEnabled();
    }

    public McpProperties properties() {
        return props;
    }

    /** 系统/部署级服务的名字映射（单测用）。 */
    public Map<String, String[]> nameMap() {
        return nameMap(0L);
    }

    /** 某个用户可见的 exposed name → (server, original tool)。 */
    public synchronized Map<String, String[]> nameMap(long userId) {
        callbacks(userId);   // 顺带把缓存建好
        Map<String, String[]> m = nameMapByOwner.get(userId);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    /** deferred 提示词用：已连上的 server，不列工具名。 */
    public List<String> serverLines() {
        return serverLines(0L);
    }

    public synchronized List<String> serverLines(long userId) {
        callbacks(userId);
        List<String> lines = serverLinesByOwner.get(userId);
        return lines == null ? List.of() : lines;
    }

    public boolean isUntrustedExposed(long userId, String exposed) {
        if (exposed == null || props == null) return false;
        String[] mapped = nameMap(userId).get(exposed);
        String server = mapped != null ? mapped[0] : McpNames.serverOf(exposed);
        if (server == null) return false;
        McpServerSpec spec = specOf(userId, server);
        return spec != null && spec.isUntrusted();
    }

    /** 先看这个用户自己的服务，再看部署/共享级。 */
    private McpServerSpec specOf(long userId, String server) {
        Slot own = slot(userId, server);
        if (own != null) return own.spec;
        Slot shared = slot(0L, server);
        if (shared != null) return shared.spec;
        Map<String, McpServerSpec> yaml = props == null ? null : props.getServers();
        return yaml == null ? null : yaml.get(server);
    }

    public void markTainted(String conversationId) {
        markTainted(conversationId, null);
    }

    /** server 非空时记下是哪个服务造成的污染。同服务后续调用不再额外强制询问。 */
    public void markTainted(String conversationId, String server) {
        if (conversationId == null || conversationId.isBlank()) return;
        Set<String> sources = taintedBy.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet());
        if (server != null && !server.isBlank()) sources.add(server);
    }

    public boolean isConversationTainted(String conversationId) {
        if (conversationId == null) return false;
        return taintedBy.containsKey(conversationId);
    }

    /** 空集 = 已污染但不知道来源（压缩后只剩标记）。这时无法证明是同一服务。 */
    public Set<String> taintSources(String conversationId) {
        if (conversationId == null) return Set.of();
        Set<String> sources = taintedBy.get(conversationId);
        return sources == null ? Set.of() : Set.copyOf(sources);
    }

    /** 单测用：按部署级服务的名字映射判断。 */
    public void noteToolResult(String conversationId, String exposed) {
        noteToolResult(conversationId, exposed, 0L);
    }

    public void noteToolResult(String conversationId, String exposed, long userId) {
        if (!isUntrustedExposed(userId, exposed)) return;
        String[] mapped = nameMap(userId).get(exposed);
        String server = mapped != null ? mapped[0] : McpNames.serverOf(exposed);
        markTainted(conversationId, server);
    }

    public boolean shouldStripDangerous(String conversationId, List<Message> messages) {
        if (!isEnabled()) return false;
        if (!"strip-dangerous".equals(props.effectiveUntrustedPolicy())) return false;
        return isConversationTainted(conversationId) || McpUntrustedGuard.isTainted(messages);
    }

    /**
     * 启动恢复：把数据库里启用的服务按各自的 owner 追加进来。
     * 停用的不建 slot；历史遗留（created_by 为空）按 owner=0 作为系统共享服务加载，只读。
     */
    private void loadRuntimeConfigs() {
        if (serverConfigMapper == null) return;
        java.util.List<com.example.jingjingai2.entity.McpServerConfig> rows;
        try {
            rows = serverConfigMapper.findAll();
        } catch (RuntimeException e) {
            System.err.println("[MCP] 读取运行时服务配置失败，已跳过: " + e.getClass().getSimpleName());
            return;
        }
        if (rows == null) return;
        for (com.example.jingjingai2.entity.McpServerConfig row : rows) {
            if (row == null || !row.isEnabled()) continue;
            long owner = ownerOf(row);
            if (slots.containsKey(new ServiceKey(owner, row.getName()))) {
                System.err.println("[MCP] 运行时服务 " + row.getName() + " 已存在，已跳过");
                continue;
            }
            if (owner != 0L && slots.containsKey(new ServiceKey(0L, row.getName()))) {
                // 个人服务不能和部署/共享服务重名，否则同一个用户会看到两个同名工具的清单
                System.err.println("[MCP] 用户 " + owner + " 的服务 " + row.getName()
                        + " 与部署配置同名，已跳过");
                continue;
            }
            String err = McpServerConfigValidator.check(row);
            if (err != null) {
                System.err.println("[MCP] 运行时服务 " + row.getName() + " 配置不合法，已跳过");
                continue;
            }
            String runtime = McpServerConfigValidator.checkRuntimeSupported(row);
            if (runtime != null) {
                System.err.println("[MCP] 运行时服务 " + row.getName() + " " + runtime + "，已跳过");
                continue;
            }
            appendSlot(owner, row.getName(), McpServerConfigValidator.toSpec(row), true);
        }
    }

    /** 配置行的归属：{@code created_by} 为空视为系统/历史共享（ownerId=0）。 */
    public static long ownerOf(com.example.jingjingai2.entity.McpServerConfig row) {
        if (row == null || row.getCreatedBy() == null) return 0L;
        return row.getCreatedBy();
    }

    /** 系统/部署级的添加（单测用）。 */
    public McpServerChange addServer(String name, McpServerSpec spec, boolean fromDatabase) {
        return addServer(0L, name, spec, fromDatabase);
    }

    /**
     * 追加一个服务并连接。{@code ownerId} 是归属用户，0 表示系统/部署级。
     * 同一个 owner 下服务名唯一；个人服务不能与部署/共享服务重名（否则同一用户会看到两个同名工具）。
     */
    public McpServerChange addServer(long ownerId, String name, McpServerSpec spec, boolean fromDatabase) {
        if (name == null || name.isBlank() || spec == null) return McpServerChange.rejected("配置不能为空");
        ServiceKey k = new ServiceKey(ownerId, name);
        synchronized (slotsLock) {
            if (slots.containsKey(k)) return McpServerChange.rejected("服务已存在");
            if (ownerId != 0L && slots.containsKey(new ServiceKey(0L, name))) {
                return McpServerChange.rejected("与部署配置里的服务重名，请换一个名字");
            }
            appendSlot(ownerId, name, spec, fromDatabase);
        }
        connectSlot(slots.get(k), false);
        return changeOf(ownerId, name, "已添加");
    }

    /** 系统/部署级服务的连接（单测用）。 */
    public McpServerChange connectServer(String name) {
        return connectServer(0L, name);
    }

    /** 对一个已有的服务重新连接。数据库里的配置优先于内存里的旧规格。会话还活着且配置没变时不拆进程。 */
    public McpServerChange connectServer(long ownerId, String name) {
        Slot slot = slot(ownerId, name);
        if (slot == null) return McpServerChange.rejected("服务不存在");
        McpServerSpec before;
        synchronized (slot.lock) {
            before = slot.spec;
            slot.consecutiveFailures = 0;
            slot.cooldownUntilMs = 0;
        }
        String reloadError = reloadDatabaseSpec(slot);
        if (reloadError != null) return McpServerChange.rejected(reloadError);
        boolean changed = before != slot.spec && (before == null || !before.sameLaunchConfig(slot.spec));
        McpSession alive;
        synchronized (slot.lock) {
            alive = slot.session;
        }
        if (changed && alive != null && alive.isAlive()) {
            closeSlot(slot, "配置已更新");
        }
        connectSlot(slot, false);
        return changeOf(ownerId, name, "已连接");
    }

    /**
     * 强制重建连接：先关掉现有会话，再连一次。
     *
     * <p>和 {@link #connectServer} 的区别：后者对存活的会话是空操作，所以换了凭证也不会生效；
     * 这里会先按数据库里的当前配置换掉内存规格，再 {@code closeSlot}（关会话、清工具、推进代际），然后连一次。
     * 旧会话在关闭后不会再暴露工具；迟到的旧连接会被代际检查丢弃。配置不合法时不关闭现有会话。
     */
    public McpServerChange reconnectServer(long ownerId, String name) {
        Slot slot = slot(ownerId, name);
        if (slot == null) return McpServerChange.rejected("服务不存在");
        // 重连必须用数据库里的当前配置。只复用内存里的 spec 时，管理页或接口刚写入的
        // env 不会进子进程，Playwright 会用空的 HOMEDRIVE 拼出 undefined\Program Files。
        String reloadError = reloadDatabaseSpec(slot);
        if (reloadError != null) return McpServerChange.rejected(reloadError);
        synchronized (slot.lock) {
            slot.consecutiveFailures = 0;
            slot.cooldownUntilMs = 0;
        }
        closeSlot(slot, "重新连接");
        connectSlot(slot, false);
        return changeOf(ownerId, name, "已重新连接");
    }

    /** 断开但保留 slot 和配置。工具立即从该用户的本轮清单和搜索目录里消失。
     *  停用走的也是这里。之后的工具调用，包括还在等的旧审批，不会自动重连；要恢复必须显式连接。 */
    public McpServerChange disconnectServer(long ownerId, String name) {
        Slot slot = slot(ownerId, name);
        if (slot == null) return McpServerChange.rejected("服务不存在");
        closeSlot(slot, "已断开");
        return changeOf(ownerId, name, "已断开");
    }

    /** 系统/部署级的断开（单测用）。 */
    public McpServerChange disconnectServer(String name) {
        return disconnectServer(0L, name);
    }

    /**
     * 删除一个运行时服务：关会话、移出清单、释放进程。
     * 只按 (ownerId, name) 精确定位，不会碰到其他用户的同名服务；部署服务拒绝删除。
     */
    public McpServerChange removeServer(long ownerId, String name) {
        ServiceKey k = new ServiceKey(ownerId, name);
        Slot slot;
        synchronized (slotsLock) {
            slot = slots.get(k);
            if (slot == null) return McpServerChange.rejected("服务不存在");
            if (slot.fromYaml) return McpServerChange.rejected("部署配置里的服务不能删除");
            slots.remove(k);
        }
        synchronized (slot.lock) {
            slot.connectEpoch++;
        }
        closeSlot(slot, "已删除");
        McpServerChange change = changeOf(ownerId, name, "已删除");
        change.removed = true;
        return change;
    }

    /** 系统/部署级的删除（单测用）。 */
    public McpServerChange removeServer(String name) {
        return removeServer(0L, name);
    }

    /** 重新发现工具。没连上时先连接。 */
    public McpServerChange refreshServer(long ownerId, String name) {
        Slot slot = slot(ownerId, name);
        if (slot == null) return McpServerChange.rejected("服务不存在");
        McpSession session;
        int epoch;
        synchronized (slot.lock) {
            session = slot.session;
            epoch = slot.connectEpoch;
        }
        if (session == null || !session.isAlive()) return connectServer(ownerId, name);
        try {
            installSession(slot, session, epoch);
        } catch (RuntimeException e) {
            synchronized (slot.lock) {
                slot.lastError = e.getMessage();
            }
        }
        return changeOf(ownerId, name, "已刷新");
    }

    /** 系统/部署级的刷新（单测用）。 */
    public McpServerChange refreshServer(String name) {
        return refreshServer(0L, name);
    }

    /**
     * 用新规格更新现有 Slot，并在<strong>同一个 Slot 对象</strong>上重连。
     *
     * <p>配置编辑走这里，而不是 {@link #replaceServer}：本轮已经发给模型的
     * {@link McpToolCallback} 按 Slot 身份路由，换掉 Slot 会让后续工具调用一律报
     * 「服务已删除或重建」。删除后再以同名新建仍走 remove/add，旧回调继续被拒。
     */
    public McpServerChange applySpecAndReconnect(long ownerId, String name, McpServerSpec spec) {
        if (spec == null) return McpServerChange.rejected("配置不能为空");
        Slot slot = slot(ownerId, name);
        if (slot == null) return McpServerChange.rejected("服务不存在");
        if (slot.fromYaml) return McpServerChange.rejected("部署配置里的服务不能修改");
        synchronized (slot.lock) {
            slot.spec = spec;
            slot.consecutiveFailures = 0;
            slot.cooldownUntilMs = 0;
        }
        closeSlot(slot, "配置已更新");
        connectSlot(slot, false);
        return changeOf(ownerId, name, "配置已更新");
    }

    /** 系统/部署级的同槽位更新（单测用）。 */
    public McpServerChange applySpecAndReconnect(String name, McpServerSpec spec) {
        return applySpecAndReconnect(0L, name, spec);
    }

    /**
     * 拆掉旧 Slot、按新规格新建并连接。会让已发出的旧 callback 永久失效。
     * 配置编辑请用 {@link #applySpecAndReconnect}；这里留给真·删除重建与兼容单测。
     */
    public McpServerChange replaceServer(long ownerId, String name, McpServerSpec spec) {
        if (spec == null) return McpServerChange.rejected("配置不能为空");
        ServiceKey k = new ServiceKey(ownerId, name);
        Slot old;
        synchronized (slotsLock) {
            old = slots.get(k);
            if (old == null) return McpServerChange.rejected("服务不存在");
            if (old.fromYaml) return McpServerChange.rejected("部署配置里的服务不能修改");
            slots.remove(k);
        }
        synchronized (old.lock) {
            old.connectEpoch++;
        }
        closeSlot(old, "配置已更新");
        return addServer(ownerId, name, spec, !old.fromYaml);
    }

    /** 系统/部署级的替换（单测用）。 */
    public McpServerChange replaceServer(String name, McpServerSpec spec) {
        return replaceServer(0L, name, spec);
    }

    /**
     * 只替换运行规格，<strong>不建立连接</strong>。用于「已停用服务改配置」：
     * 停用在当前实现里只是断开，slot 仍在；若留着旧 spec，下次「启用」会按旧配置拉起进程。
     *
     * <p>没有 slot（进程重启过、停用的服务不建 slot）时无需应用，数据库就是唯一来源，
     * 返回 true。slot 正在连接或已连接时不动它，返回 false 由调用方如实报告。
     *
     * @return true = 已应用或无需应用；false = 服务正在连接/运行，本次未应用
     */
    public boolean updateSpecOnly(long ownerId, String name, McpServerSpec spec) {
        if (spec == null) return false;
        Slot slot = slot(ownerId, name);
        if (slot == null) return true;
        synchronized (slot.lock) {
            if ("connected".equals(slot.state) || "connecting".equals(slot.state)) return false;
            slot.spec = spec;
        }
        return true;
    }

    /** 仅部署配置里的服务（ownerId=0 且来自 YAML）。个人服务永远不会是 YAML。 */
    public boolean isYamlServer(String name) {
        Slot slot = slot(0L, name);
        return slot != null && slot.fromYaml;
    }

    /**
     * 数据库里的服务在重连前换成当前行。没有 mapper（单测）、YAML 部署服务、或行已删除时保持原规格。
     * 行还在但不合法时不关现有会话，返回错误。
     */
    private String reloadDatabaseSpec(Slot slot) {
        if (slot == null || slot.fromYaml || serverConfigMapper == null) return null;
        com.example.jingjingai2.entity.McpServerConfig row =
                serverConfigMapper.findByOwnerAndName(slot.ownerId, slot.name);
        if (row == null) return null;
        String err = McpServerConfigValidator.check(row);
        if (err != null) return "配置不合法，未重新连接：" + err;
        String runtime = McpServerConfigValidator.checkRuntimeSupported(row);
        if (runtime != null) return "未重新连接：" + runtime;
        synchronized (slot.lock) {
            slot.spec = McpServerConfigValidator.toSpec(row);
        }
        return null;
    }

    private void appendSlot(long ownerId, String name, McpServerSpec spec, boolean fromDatabase) {
        Slot slot = new Slot(ownerId, name, spec);
        slot.fromYaml = !fromDatabase;
        slot.state = "connecting";
        synchronized (slotsLock) {
            slots.put(slot.key(), slot);
        }
    }

    private void closeSlot(Slot slot, String reason) {
        McpSession old;
        synchronized (slot.lock) {
            old = slot.session;
            slot.session = null;
            slot.connectEpoch++;
            slot.state = "disconnected";
            slot.lastError = reason;
            slot.offered = java.util.List.of();
            slot.discovered = java.util.List.of();
            slot.listedCount = 0;
            slot.inflight = null;
        }
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
            sessions.remove(old);
        }
        rebuildCallbacks();
    }

    private McpServerChange changeOf(long ownerId, String name, String action) {
        Slot slot = slot(ownerId, name);
        McpServerChange c = new McpServerChange();
        c.name = name;
        c.action = action;
        c.configSaved = true;
        if (slot == null) return c;
        c.connected = "connected".equals(slot.state) && slot.session != null && slot.session.isAlive();
        c.toolCount = slot.listedCount;
        c.lastError = slot.lastError;
        return c;
    }

    /** 一次服务操作的结果。三件事分开报，不合并成一个成功或失败。 */
    public static final class McpServerChange {
        public String name;
        public String action;
        /** 配置已写入（内存 slot 或数据库）。 */
        public boolean configSaved;
        /** 此刻进程已连接。 */
        public boolean connected;
        /** 最近一次发现到的工具数，含被 disabled-tools 排除的。 */
        public int toolCount;
        public String lastError;
        public boolean removed;

        static McpServerChange rejected(String reason) {
            McpServerChange c = new McpServerChange();
            c.action = "已拒绝";
            c.lastError = reason;
            return c;
        }

        public boolean rejected() { return "已拒绝".equals(action); }
    }

    /** 取某个归属名下的槽位。ownerId=0 是部署/系统级。 */
    private Slot slot(long ownerId, String name) {
        if (name == null || name.isBlank()) return null;
        synchronized (slotsLock) {
            return slots.get(new ServiceKey(ownerId, name));
        }
    }

    /** 遍历槽位前先快照：add/remove 会改 slots，直接遍历可能 ConcurrentModificationException。 */
    private List<Slot> snapshotSlots() {
        synchronized (slotsLock) {
            return new ArrayList<>(slots.values());
        }
    }

    /** 这个槽位对这个用户是否可见：部署/系统级对所有人可见，个人服务只对本人可见。 */
    public static boolean visibleTo(Slot slot, long userId) {
        return slot != null && (slot.ownerId == 0L || slot.ownerId == userId);
    }

    /**
     * 这个用户可见的服务名（自己的优先，其次部署/共享）。用于权限面板这类「按名字找服务」的场景。
     * 只做可见性判断，不代表有管理权。
     */
    public String findServer(long userId, String name) {
        if (slot(userId, name) != null) return name;
        return slot(0L, name) != null ? name : null;
    }

    /** 系统/部署级服务的查找（单测用）。 */
    public String findServer(String name) {
        return findServer(0L, name);
    }

    /** 这个用户名下的服务（不含共享/部署）。管理操作必须以它为准。 */
    public String findOwnServer(long userId, String name) {
        return slot(userId, name) != null ? name : null;
    }

    /**
     * 只在这一个 owner 的槽位里找名字。精确命中优先；否则仅当折叠后相同的槽位恰好一个时，
     * 返回槽位上的原样名字。两个以上就返回 null，调用方不能猜。
     * 不修改 {@link ServiceKey} 的相等规则，也不查看其他 owner。
     */
    public String storedSlotName(long ownerId, String name) {
        if (name == null || name.isBlank()) return null;
        if (slot(ownerId, name) != null) return name;
        String found = null;
        for (Slot candidate : snapshotSlots()) {
            if (candidate.ownerId != ownerId || candidate.name == null) continue;
            if (!namesMatch(candidate.name, name)) continue;
            if (found != null && !found.equals(candidate.name)) return null;
            found = candidate.name;
        }
        return found;
    }

    /** 同一个 owner 下有两个折叠后相同、且都不是精确命中的槽位。 */
    public boolean ambiguousSlotName(long ownerId, String name) {
        if (name == null || name.isBlank()) return false;
        if (slot(ownerId, name) != null) return false;
        String found = null;
        for (Slot candidate : snapshotSlots()) {
            if (candidate.ownerId != ownerId || candidate.name == null) continue;
            if (!namesMatch(candidate.name, name)) continue;
            if (found != null && !found.equals(candidate.name)) return true;
            found = candidate.name;
        }
        return false;
    }

    /**
     * 删除路径用来对齐隔离库上的 {@code utf8mb4_0900_ai_ci}：不区分大小写，
     * 并把 {@code café} 和 {@code cafe} 看成同一个名字。做法是 NFD 之后去掉
     * 非间距重音符号，再用 {@link Locale#ROOT} 折叠大小写。
     * 不改 {@link ServiceKey}。也不实现这套校对规则里的其他等价，例如 {@code ß} 和 {@code ss}。
     */
    public static boolean namesMatch(String a, String b) {
        if (a == null || b == null) return false;
        return foldServerName(a).equals(foldServerName(b));
    }

    private static String foldServerName(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        int i = 0;
        while (i < decomposed.length()) {
            int cp = decomposed.codePointAt(i);
            if (Character.getType(cp) != Character.NON_SPACING_MARK) {
                folded.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return folded.toString();
    }

    public List<McpListedTool> listedTools(long userId, String server) {
        Slot slot = slot(userId, server);
        if (slot == null) slot = slot(0L, server);
        if (slot == null || slot.discovered == null) return List.of();
        return List.copyOf(slot.discovered);
    }

    /** 系统/部署级服务的工具列表（单测用）。 */
    public List<McpListedTool> listedTools(String server) {
        return listedTools(0L, server);
    }

    public McpListedTool findListed(long userId, String server, String tool) {
        if (tool == null) return null;
        for (McpListedTool t : listedTools(userId, server)) {
            if (tool.equals(t.originalName) || tool.equals(t.exposedName)) return t;
        }
        return null;
    }

    /** 系统/部署级服务的工具查找（单测用）。 */
    public McpListedTool findListed(String server, String tool) {
        return findListed(0L, server, tool);
    }

    /** 某个用户可见的服务状态：自己的 + 部署/共享的。同名的个人服务优先。 */
    public List<McpServerStatus> statuses(long userId) {
        List<McpServerStatus> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Slot> snapshot = snapshotSlots();
        for (Slot slot : snapshot) {
            if (slot.ownerId != userId) continue;
            if (!seen.add(slot.name)) continue;
            out.add(statusOf(slot));
        }
        if (userId != 0L) {
            for (Slot slot : snapshot) {
                if (slot.ownerId != 0L) continue;
                if (!seen.add(slot.name)) continue;
                out.add(statusOf(slot));
            }
        }
        return out;
    }

    private static McpServerStatus statusOf(Slot slot) {
        int offered = slot.offered == null ? 0 : slot.offered.size();
        return new McpServerStatus(slot.name, slot.state, slot.lastError, slot.listedCount, offered);
    }

    /** 系统/部署级服务的状态（单测用）。 */
    public List<McpServerStatus> statuses() {
        return statuses(0L);
    }

    /** 测试：当前 session。 */
    McpSession sessionOf(String serverName) {
        Slot slot = slot(0L, serverName);
        return slot == null ? null : slot.session;
    }

    int connectGeneration() {
        return connectGenerations.get();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        for (McpSession s : sessions) {
            try { s.close(); } catch (Exception ignored) {}
        }
        sessions.clear();
        for (Slot slot : slots.values()) {
            synchronized (slot.lock) {
                slot.connectEpoch++;
                slot.session = null;
                slot.offered = List.of();
                slot.discovered = List.of();
                slot.state = "disconnected";
                slot.inflight = null;
            }
        }
        slots.clear();
        callbacksByOwner.clear();
        nameMapByOwner.clear();
        serverLinesByOwner.clear();
        taintedBy.clear();
    }

    static final class Slot {
        final long ownerId;
        final String name;
        /**
         * 运行规格。非 final：停用状态下改配置只换规格、不连接（{@link #updateSpecOnly}），
         * 否则下次「启用」会拿旧 spec 拉起进程。
         */
        volatile McpServerSpec spec;
        final Object lock = new Object();
        volatile String state = "disconnected";
        volatile String lastError;
        volatile McpSession session;
        volatile List<ToolCallback> offered = List.of();
        volatile List<McpListedTool> discovered = List.of();
        volatile int listedCount;
        int consecutiveFailures;
        long cooldownUntilMs;
        /**
         * 这次连接的身份。每次开始连接、主动断开、停用和删除都加一。
         * 完成时对不上的就是迟到的旧连接，不能安装。
         */
        int connectEpoch;
        CompletableFuture<McpSession> inflight;

        Slot(long ownerId, String name, McpServerSpec spec) {
            this.ownerId = ownerId;
            this.name = name;
            this.spec = spec;
        }

        ServiceKey key() {
            return new ServiceKey(ownerId, name);
        }

        /** true = 来自部署配置，拒绝通过管理接口修改或删除。 */
        boolean fromYaml = true;
    }
}
