package com.example.jingjingai2.controller;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpServerConfigValidator;
import com.example.jingjingai2.mcp.McpServerSpec;
import com.example.jingjingai2.mcp.McpServerStatus;
import com.example.jingjingai2.permission.ApprovalService;
import com.example.jingjingai2.mcp.McpCredentialException;
import com.example.jingjingai2.service.McpCredentialService;
import com.example.jingjingai2.service.McpServerProvisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行时 MCP 服务管理。身份只认 JWT 放进 request 的 userId。
 *
 * <p>服务归属 = {@code mcp_server_config.created_by}：
 * <ul>
 *   <li>{@code created_by = 当前用户} —— 个人服务，可增删改启停；</li>
 *   <li>{@code created_by 为空或 0} —— 系统/历史共享服务，只读；</li>
 *   <li>YAML 里的部署服务 —— 只读，由部署方维护。</li>
 * </ul>
 * 所有写操作都用 {@code (created_by, name)} 精确定位，不会碰到别人的同名服务。
 * 这里不写 {@code mcp_user_policy}，新发现的工具保持默认询问。
 */
@RestController
@RequestMapping("/ai/mcp/servers")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class McpServerAdminController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpClientManager manager;
    private final McpServerConfigMapper configs;
    private final McpUserPolicyMapper policies;
    /** 只用来清内存里的「本会话允许」；持久化授权仍由 {@link #policies} 负责。 */
    private final ApprovalService approvals;
    /** 个人服务的「安全保存」业务能力（校验+归属+重名+强制禁用+落库，不启动）。 */
    private final McpServerProvisionService provision;
    private final McpCredentialService credentials;

    public McpServerAdminController(McpClientManager manager, McpServerConfigMapper configs,
                                    McpUserPolicyMapper policies, ApprovalService approvals,
                                    McpServerProvisionService provision,
                                    McpCredentialService credentials) {
        this.manager = manager;
        this.configs = configs;
        this.policies = policies;
        this.approvals = approvals;
        this.provision = provision;
        this.credentials = credentials;
    }

    /**
     * 当前用户能看到的服务：自己的个人服务 + 部署/系统共享服务。
     * 别人的个人服务既不出现在这里，也不可通过任何写接口操作。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();

        Map<String, McpServerConfig> own = new LinkedHashMap<>();
        Map<String, McpServerConfig> shared = new LinkedHashMap<>();
        for (McpServerConfig c : configs.findAll()) {
            if (c == null || c.getName() == null) continue;
            if (ownerOf(c) == userId) own.put(c.getName(), c);
            else if (ownerOf(c) == 0L) shared.putIfAbsent(c.getName(), c);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (McpServerStatus status : manager.statuses(userId)) {
            rows.add(statusView(userId, status, own.get(status.getName()), shared.get(status.getName())));
            seen.add(status.getName());
        }
        // 数据库才是「有哪些服务」的权威来源：停用的行重启后不建 slot，
        // 只遍历运行时状态会让它从界面上彻底消失，用户再也点不到「启用」或「删除」。
        for (McpServerConfig config : own.values()) {
            if (!seen.add(config.getName())) continue;
            rows.add(configOnlyView(userId, config));
        }
        for (McpServerConfig config : shared.values()) {
            if (!seen.add(config.getName())) continue;
            rows.add(configOnlyView(userId, config));
        }
        return ResponseEntity.ok(Map.of("servers", rows));
    }

    /** 只校验，不落库、不连接。 */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        if (userId(request) == null) return unauthorized();
        McpServerConfig config = fromBody(body, null);
        String err = McpServerConfigValidator.check(config);
        if (err != null) return bad(err);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        McpServerConfig config = fromBody(body, userId);
        // 阶段 1A：新增个人服务一律保存为禁用、不启动子进程。
        // 校验、归属、重名检查全部收口在 provision.saveDisabled 里，与未来 Agent 工具走同一份规则。
        McpServerProvisionService.SaveResult r = provision.saveDisabled(userId, config);
        if (!r.saved()) return bad(r.error());
        // 不调 manager.addServer：不建槽位、不连进程、工具清单里看不到。
        // 用户走 MCP 管理页「启用」才会触发 setEnabled → addServer → connectSlot。
        return ResponseEntity.ok(changeView(savedOnly(r.name(), "已保存（已禁用，未启动）")));
    }

    @PutMapping("/{name}")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@PathVariable String name,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        return updateAsUser(userId, name, body);
    }

    /** 改配置。HTTP 接口与 Agent 工具共用这一份，校验、凭证清理、授权清理都不分叉。 */
    @Transactional
    public ResponseEntity<Map<String, Object>> updateAsUser(Long userId, String name, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> denied = rejectNotOwned(userId, name);
        if (denied != null) return denied;
        McpServerConfig existing = configs.findByOwnerAndName(userId, name);
        if (existing == null) return bad("服务不存在");
        McpServerConfig config = fromBody(body, userId);
        config.setName(name);
        // fromBody 不带 id，但 toSpec 要用它填 serverConfigId 供凭证归属核对；
        // 不补的话，编辑过的 Bearer 服务会因为缺少配置主键而永远连不上。
        config.setId(existing.getId());
        // 部分更新：没出现的键沿用原值，出现的键（含空串、null、空数组、空对象）才覆盖。
        // 不要在这里调用 normalizeForCreate。那是新建时的默认值和清字段，用在更新上会改掉「缺键保持原值」。
        if (!body.containsKey("args")) config.setArgsJson(existing.getArgsJson());
        if (!body.containsKey("env")) config.setEnvJson(existing.getEnvJson());
        if (!body.containsKey("disabledTools")) config.setDisabledToolsJson(existing.getDisabledToolsJson());
        if (!body.containsKey("trust")) config.setTrust(existing.getTrust());
        if (!body.containsKey("connectTimeoutSeconds")) {
            config.setConnectTimeoutSeconds(existing.getConnectTimeoutSeconds());
        }
        if (!body.containsKey("maxResultChars")) config.setMaxResultChars(existing.getMaxResultChars());
        if (!body.containsKey("transport")) config.setTransport(existing.getTransport());
        if (!body.containsKey("baseUrl")) config.setBaseUrl(existing.getBaseUrl());
        if (!body.containsKey("endpointPath")) config.setEndpointPath(existing.getEndpointPath());
        if (!body.containsKey("authType")) config.setAuthType(existing.getAuthType());
        if (!body.containsKey("command")) config.setCommand(existing.getCommand());
        String err = McpServerConfigValidator.check(config);
        if (err != null) return bad(err);

        // 从 bearer 改回 none 时，旧凭证必须跟着清掉。配置更新和凭证删除在本方法的同一个事务里：
        // 凭证没清掉就抛出，配置更新回滚，不会留下「已经是 none、旧令牌还在」。
        // 校验失败发生在这两步之前，失败的编辑不会碰到凭证。
        boolean dropBearer = existing.isStreamableHttp()
                && McpServerConfig.AUTH_BEARER.equals(existing.normalizedAuthType())
                && McpServerConfig.AUTH_NONE.equals(config.normalizedAuthType());

        // 运行时不一定会立刻应用：停用的服务只落库；没有 slot 的服务（进程重启过、
        // 停用的行不建 slot）也没有可换的运行时。这两种都必须如实报告，
        // 不能再出现「库已改、却对用户说没保存」。
        boolean disabled = !existing.isEnabled();
        boolean hasSlot = manager.findOwnServer(userId, name) != null;
        McpServerSpec spec = McpServerConfigValidator.toSpec(config);

        int changed = configs.update(config);
        if (changed != 1) {
            throw new IllegalStateException("配置未更新，已取消本次修改");
        }
        if (dropBearer && existing.getId() != null) {
            credentials.delete(userId, existing.getId());
        }
        // 配置已经落库（不可逆），就把**这个用户**在这个服务名下的「本会话允许」清掉：
        // command/args/env/disabledTools/trust 任何一项变化都可能让「下次执行的是别的东西」。
        // 位置有意放在校验与 configs.update 之后：这两步抛异常都到不了这里，失败不会提前清。
        approvals.forgetServer(userId, name);

        if (disabled) {
            // 停用只是断开，slot 通常还在。换掉它的规格但不连接，否则下次「启用」会用旧配置。
            boolean applied = manager.updateSpecOnly(userId, name, spec);
            return ResponseEntity.ok(changeView(applied
                    ? savedOnly(name, "配置已保存（服务已停用，未连接）")
                    : savedButNotApplied(name, "服务正在连接或运行，本次未应用新配置")));
        }
        if (!hasSlot) {
            return ResponseEntity.ok(changeView(savedOnly(name, "配置已保存（服务当前未运行）")));
        }
        // 同一 Slot 上换 spec 并重连：本轮已发给模型的旧 callback 仍按 Slot 身份路由。
        // 若走 replaceServer 拆掉再建，旧 callback 会一律报「服务已删除或重建」。
        McpClientManager.McpServerChange change = manager.applySpecAndReconnect(userId, name, spec);
        if (change.rejected()) {
            return ResponseEntity.ok(changeView(savedButNotApplied(name, change.lastError)));
        }
        return ResponseEntity.ok(changeView(change));
    }

    @PostMapping("/{name}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable String name,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        return setEnabledAsUser(userId, name, Boolean.TRUE.equals(body.get("enabled")));
    }

    /** 启用/停用。HTTP 接口与 Agent 工具共用这一份。 */
    public ResponseEntity<Map<String, Object>> setEnabledAsUser(Long userId, String name, boolean enabled) {
        ResponseEntity<Map<String, Object>> denied = rejectNotOwned(userId, name);
        if (denied != null) return denied;
        McpServerConfig existing = configs.findByOwnerAndName(userId, name);
        if (existing == null) return bad("服务不存在");
        if (enabled) {
            // 启用会让配置生效，先重新校验，不合法的不连接
            String err = McpServerConfigValidator.check(existing);
            if (err != null) return bad(err);
            String runtime = McpServerConfigValidator.checkRuntimeSupported(existing);
            if (runtime != null) return bad(runtime);
        }
        configs.updateEnabled(userId, name, enabled);
        McpClientManager.McpServerChange change;
        if (enabled) {
            if (manager.findOwnServer(userId, name) == null) {
                change = manager.addServer(userId, name, McpServerConfigValidator.toSpec(existing), true);
            } else {
                change = manager.connectServer(userId, name);
            }
        } else {
            change = manager.findOwnServer(userId, name) == null
                    ? disconnected(name) : manager.disconnectServer(userId, name);
        }
        return ResponseEntity.ok(changeView(change));
    }

    @PostMapping("/{name}/connect")
    public ResponseEntity<Map<String, Object>> connect(@PathVariable String name, HttpServletRequest request) {
        return control(name, request, "connect");
    }

    @PostMapping("/{name}/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(@PathVariable String name, HttpServletRequest request) {
        return control(name, request, "disconnect");
    }

    @PostMapping("/{name}/reconnect")
    public ResponseEntity<Map<String, Object>> reconnect(@PathVariable String name, HttpServletRequest request) {
        // 重连要真的重建会话：connect 对存活会话是空操作，换了凭证也不会生效。
        return control(name, request, "reconnect");
    }

    @PostMapping("/{name}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String name, HttpServletRequest request) {
        return control(name, request, "refresh");
    }

    private ResponseEntity<Map<String, Object>> control(String name, HttpServletRequest request, String action) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        return controlAsUser(userId, name, action);
    }

    /** 连接控制。HTTP 接口与 Agent 工具共用这一份。 */
    public ResponseEntity<Map<String, Object>> controlAsUser(Long userId, String name, String action) {
        if (manager.findOwnServer(userId, name) == null) return bad("服务不存在");
        McpClientManager.McpServerChange change;
        switch (action == null ? "" : action) {
            case "connect" -> change = manager.connectServer(userId, name);
            case "disconnect" -> change = manager.disconnectServer(userId, name);
            case "reconnect" -> change = manager.reconnectServer(userId, name);
            case "refresh" -> change = manager.refreshServer(userId, name);
            default -> {
                return bad("action 只能是 connect、disconnect、reconnect、refresh");
            }
        }
        return ResponseEntity.ok(changeView(change));
    }

    /**
     * 删除个人服务：先摘运行时，再在同一个事务里删授权和配置。
     *
     * <p>为什么先摘运行时：万一数据库那两步失败并回滚，留下的是「配置还在、服务没在跑」，
     * 可以重连或重启恢复；反过来会留下「配置没了、进程还在跑、工具仍能被调用」的幽灵服务。
     *
     * <p>授权按 {@code (user_id, server_name)} 清理，这张表没有服务归属列。
     * 因此只要这个名字仍然对得上 YAML 部署服务或系统共享服务，就不能删授权，
     * 也不能清「本会话允许」——那两样同样作用在留下来的共享服务上。
     * 个人配置行和当前用户自己的槽位仍然删。别人的授权本来就不在
     * {@code user_id = 当前用户} 里。
     */
    @DeleteMapping("/{name}")
    @Transactional
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String name, HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        return deleteAsUser(userId, name);
    }

    /** 删除个人服务。HTTP 接口与 Agent 工具共用这一份。 */
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteAsUser(Long userId, String name) {
        // 部署服务看配置，不看槽位在不在。MCP 关掉或还没启动时槽位是空的，服务名仍在。
        boolean deployment = isDeploymentName(name);
        // 共享行用 SQL 的 name = ?，校对规则跟后面的授权删除一致。不读 env。
        McpServerConfig shared = configs.findSharedByName(name);
        McpServerConfig owned = configs.findByOwnerAndName(userId, name);
        // 同一个 owner 下有两个只差大小写的槽位时不猜，也不把授权当孤儿清掉。
        if (manager.ambiguousSlotName(userId, name) || manager.ambiguousSlotName(0L, name)) {
            return bad("服务不存在");
        }
        // 槽位键仍是大小写敏感的。这里只在同一个 owner 内接受唯一的大小写差异。
        String ownSlot = manager.storedSlotName(userId, name);
        boolean systemSlot = systemSlot(name, owned, shared);

        if (owned == null && ownSlot == null) {
            if (deployment) return bad("部署配置里的服务不能删除");
            if (shared != null || systemSlot) return bad("系统共享服务不能删除");
            // 配置、部署、共享槽位都没有，才补清这个用户自己的残留授权。
            int orphans = policies.deleteByUserAndServer(userId, name);
            if (orphans > 0) {
                McpClientManager.McpServerChange cleaned = new McpClientManager.McpServerChange();
                cleaned.name = name;
                cleaned.action = "服务不存在，已清理残留授权";
                cleaned.removed = true;
                approvals.forgetServer(userId, name);
                return ResponseEntity.ok(deleteView(cleaned, orphans));
            }
            return bad("服务不存在");
        }

        // 有库行就用库里的原样；只有槽位时用槽位上的原样，不用请求里的大小写。
        String canonical = owned != null && owned.getName() != null ? owned.getName()
                : (ownSlot != null ? ownSlot : name);
        boolean running = ownSlot != null;

        McpClientManager.McpServerChange change;
        if (running) {
            change = manager.removeServer(userId, ownSlot);
            // 运行时没摘掉就什么都不删：否则会留下进程在跑、配置却没了的幽灵服务
            if (change.rejected()) {
                return bad(change.lastError == null ? "运行时未能删除该服务" : change.lastError);
            }
        } else {
            change = disconnected(canonical);
        }

        // 同名共享/部署服务还在时，授权分不清是哪一个的，留着。
        boolean keepPolicies = deployment || shared != null || systemSlot;
        int cleared = 0;
        if (!keepPolicies) {
            cleared = policies.deleteByUserAndServer(userId, canonical);
        }
        if (owned != null && owned.getId() != null) {
            credentials.delete(userId, owned.getId());
        }
        if (owned != null) configs.delete(userId, canonical);
        if (!keepPolicies) {
            approvals.forgetServer(userId, canonical);
        }
        return ResponseEntity.ok(deleteView(change, cleared));
    }

    /** 只报告有没有凭证。不解密，响应里没有明文或密文。 */
    @GetMapping("/{name}/credential")
    public ResponseEntity<Map<String, Object>> credentialStatus(@PathVariable String name,
                                                                 HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        McpServerConfig owned = ownedHttp(userId, name);
        if (owned == null) return bad(ownedHttpError(userId, name));
        boolean configured = credentials.configured(userId, owned.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", configured);
        body.put("authType", owned.normalizedAuthType());
        return ResponseEntity.ok(body);
    }

    /** 保存或轮换。只读 token，不读请求体里的 userId。 */
    @PostMapping("/{name}/credential")
    public ResponseEntity<Map<String, Object>> saveCredential(@PathVariable String name,
                                                               @RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        McpServerConfig owned = ownedHttp(userId, name);
        if (owned == null) return bad(ownedHttpError(userId, name));
        Object raw = body == null ? null : body.get("token");
        String token = raw == null ? null : String.valueOf(raw);
        try {
            credentials.put(userId, owned.getId(), token);
        } catch (McpCredentialException e) {
            return bad(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("configured", true));
    }

    @DeleteMapping("/{name}/credential")
    public ResponseEntity<Map<String, Object>> deleteCredential(@PathVariable String name,
                                                                 HttpServletRequest request) {
        Long userId = userId(request);
        if (userId == null) return unauthorized();
        McpServerConfig owned = ownedHttp(userId, name);
        if (owned == null) return bad(ownedHttpError(userId, name));
        credentials.delete(userId, owned.getId());
        return ResponseEntity.ok(Map.of("configured", false));
    }

    /** 本人的 HTTP 服务。别人的、没有的，返回 null。stdio 也返回 null，错误文案另判。 */
    private McpServerConfig ownedHttp(Long userId, String name) {
        McpServerConfig owned = configs.findByOwnerAndName(userId, name);
        if (owned == null || owned.getId() == null || owned.getCreatedBy() == null
                || !owned.getCreatedBy().equals(userId) || !owned.isStreamableHttp()) {
            return null;
        }
        return owned;
    }

    private String ownedHttpError(Long userId, String name) {
        McpServerConfig owned = configs.findByOwnerAndName(userId, name);
        if (owned != null && owned.getCreatedBy() != null && owned.getCreatedBy().equals(userId)
                && !owned.isStreamableHttp()) {
            return "只有远程 HTTP 服务可以设置凭证";
        }
        return "服务不存在";
    }

    /**
     * YAML 里的部署服务。键来自 {@code app.mcp.servers}，与有没有建出槽位无关。
     *
     * <p>隔离库上的 {@code utf8mb4_0900_ai_ci} 不区分大小写，也不区分重音：
     * {@code café} 和 {@code cafe} 会命中同一行授权。YAML 键用同样的折叠去比。
     */
    private boolean isDeploymentName(String name) {
        if (name == null || manager.properties() == null || manager.properties().getServers() == null) {
            return false;
        }
        for (String key : manager.properties().getServers().keySet()) {
            if (sameServerName(key, name)) return true;
        }
        return false;
    }

    /** owner 0 的槽位还在。只在 owner 0 内比较，个人槽位不会被认成共享槽位。 */
    private boolean systemSlot(String name, McpServerConfig owned, McpServerConfig shared) {
        if (manager.storedSlotName(0L, name) != null) return true;
        if (owned != null && manager.storedSlotName(0L, owned.getName()) != null) return true;
        return shared != null && manager.storedSlotName(0L, shared.getName()) != null;
    }

    /** 与隔离库上的名字比较一致：大小写和重音都不区分。null 不是名字。 */
    static boolean sameServerName(String a, String b) {
        return McpClientManager.namesMatch(a, b);
    }

    /**
     * 个人服务的写操作前置校验：部署服务与系统共享服务一律拒绝，别人的服务按「不存在」处理
     * （不泄露它是否存在）。
     */
    private ResponseEntity<Map<String, Object>> rejectNotOwned(Long userId, String name) {
        if (manager.isYamlServer(name)) return bad("部署配置里的服务不能修改");
        if (configs.findByOwnerAndName(userId, name) == null) return bad("服务不存在");
        return null;
    }

    private Map<String, Object> statusView(Long userId, McpServerStatus status,
                                            McpServerConfig own, McpServerConfig shared) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", status.getName());
        row.put("state", status.getState());
        row.put("lastError", status.getLastError());
        row.put("toolCount", status.getToolCount());
        row.put("exposedCount", status.getOfferedCount());
        boolean yaml = manager.isYamlServer(status.getName());
        McpServerConfig config = own != null ? own : shared;
        row.put("source", yaml ? "yaml" : (config != null && ownerOf(config) == 0L ? "system" : "database"));
        row.put("editable", !yaml && config != null && ownerOf(config) == userId);
        if (config != null) putConfigFields(row, config);
        return row;
    }

    /**
     * 只有配置、没有运行时的那一行（停用的服务重启后就是这种）。
     * {@code state=stopped} 表示「进程没在跑」，与 {@code failed} 区分开；{@code enabled}
     * 单独给出，前端据此显示「已启用/已停用」并提供启用按钮。这里不触发任何连接。
     */
    private Map<String, Object> configOnlyView(Long userId, McpServerConfig config) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", config.getName());
        row.put("state", "stopped");
        row.put("lastError", null);
        row.put("toolCount", 0);
        row.put("exposedCount", 0);
        row.put("source", ownerOf(config) == 0L ? "system" : "database");
        row.put("editable", ownerOf(config) == userId);
        putConfigFields(row, config);
        return row;
    }

    /** 列表里与配置有关的字段。不含 env 的值（可能是密钥），只给键名。也不含任何 auth 密钥。 */
    private static void putConfigFields(Map<String, Object> row, McpServerConfig config) {
        row.put("command", config.getCommand());
        row.put("args", McpServerConfigValidator.readStringList(config.getArgsJson()));
        row.put("envKeys", new ArrayList<>(
                McpServerConfigValidator.readStringMap(config.getEnvJson()).keySet()));
        row.put("enabled", config.isEnabled());
        // 编辑表单要按真实配置回填；缺了这几项，前端只能用默认值，保存即覆盖。
        // 都不含密钥：disabledTools 是工具名，trust/超时/结果长度是策略参数。
        row.put("disabledTools",
                McpServerConfigValidator.readStringList(config.getDisabledToolsJson()));
        row.put("trust", config.getTrust());
        row.put("connectTimeoutSeconds",
                config.getConnectTimeoutSeconds() == null ? 0 : config.getConnectTimeoutSeconds());
        row.put("maxResultChars",
                config.getMaxResultChars() == null ? 0 : config.getMaxResultChars());
        row.put("transport", config.normalizedTransport());
        row.put("baseUrl", config.getBaseUrl());
        row.put("endpointPath", config.getEndpointPath());
        row.put("authType", config.normalizedAuthType());
    }

    private static Map<String, Object> changeView(McpClientManager.McpServerChange change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", change.name);
        row.put("action", change.action);
        row.put("configSaved", change.configSaved);
        row.put("connected", change.connected);
        row.put("toolCount", change.toolCount);
        row.put("lastError", change.lastError);
        return row;
    }

    /** 删除专用的响应体：在通用变更字段上补一条「清了几个授权」。 */
    private static Map<String, Object> deleteView(McpClientManager.McpServerChange change, int policiesCleared) {
        Map<String, Object> view = changeView(change);
        view.put("policiesCleared", policiesCleared);
        return view;
    }

    private static McpClientManager.McpServerChange disconnected(String name) {
        McpClientManager.McpServerChange c = new McpClientManager.McpServerChange();
        c.name = name;
        c.action = "已断开";
        c.configSaved = true;
        return c;
    }

    /** 配置已落库、但本次没有（也不需要）动运行时。 */
    private static McpClientManager.McpServerChange savedOnly(String name, String action) {
        McpClientManager.McpServerChange c = new McpClientManager.McpServerChange();
        c.name = name;
        c.action = action;
        c.configSaved = true;
        c.connected = false;
        return c;
    }

    /** 配置已落库、但运行时应用失败。configSaved=true 与 lastError 同时出现即为该语义。 */
    private static McpClientManager.McpServerChange savedButNotApplied(String name, String reason) {
        McpClientManager.McpServerChange c = savedOnly(name, "配置已保存，运行时未应用");
        c.lastError = reason;
        return c;
    }

    /** 归属：created_by 为空视为系统/历史共享（0）。 */
    private static long ownerOf(McpServerConfig config) {
        if (config == null || config.getCreatedBy() == null) return 0L;
        return config.getCreatedBy();
    }

    private static McpServerConfig fromBody(Map<String, Object> body, Long createdBy) {
        McpServerConfig c = new McpServerConfig();
        c.setName(text(body.get("name")));
        c.setCommand(text(body.get("command")));
        c.setArgsJson(jsonOf(body.get("args")));
        c.setEnvJson(jsonOf(body.get("env")));
        c.setDisabledToolsJson(jsonOf(body.get("disabledTools")));
        c.setTrust(text(body.get("trust")));
        c.setConnectTimeoutSeconds(intOf(body.get("connectTimeoutSeconds"), 0));
        c.setMaxResultChars(intOf(body.get("maxResultChars"), 0));
        c.setEnabled(!Boolean.FALSE.equals(body.get("enabled")));
        c.setCreatedBy(createdBy);
        // 传输相关：显式字段；不接受 authToken / headers / secret 等任意凭证键。
        c.setTransport(text(body.get("transport")));
        c.setBaseUrl(text(body.get("baseUrl")));
        c.setEndpointPath(text(body.get("endpointPath")));
        c.setAuthType(text(body.get("authType")));
        return c;
    }

    private static String jsonOf(Object v) {
        if (v == null) return null;
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long userId(HttpServletRequest request) {
        Object v = request.getAttribute("userId");
        return v instanceof Long ? (Long) v : null;
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static Integer intOf(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "未登录"));
    }
}
