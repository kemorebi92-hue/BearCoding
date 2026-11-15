package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpServerConfigValidator;
import org.springframework.stereotype.Service;

/**
 * 个人 MCP 服务的「安全保存」业务能力：校验、归属、重名检查、强制禁用后落库，
 * <strong>不</strong>启动运行时连接，<strong>不</strong>拉子进程。
 *
 * <p>给 {@link com.example.jingjingai2.controller.McpServerAdminController} 的新增入口
 * 和下一阶段的 Agent 工具共用，保证两条入口走同一份校验与重名规则——
 * 不能只把 {@code mapper.insert(...)} 包一层就交给 Agent，否则会漏掉重名检查、
 * 也不能让两条入口各自抄一份重名逻辑，否则规则会漂移。
 *
 * <p>归属（{@code created_by}）由调用方传入可信 userId，service 强制覆盖 config 上
 * 的同名字段——请求体或模型都不能决定记到谁头上。{@code enabled} 强制写 false：
 * 阶段 1A 的新增服务一律保存为禁用，用户走 MCP 管理页「启用」才会触发
 * {@code McpClientManager.addServer → connectSlot}。
 *
 * <p>本类不调用 {@link McpClientManager#addServer}、{@code connectSlot} 或任何会建进程的方法。
 * 对 manager 只做只读的 {@link McpClientManager#findServer(long, String)} 查 ownerId=0 槽位，
 * 用于和已运行的部署/共享服务比名（与旧 Controller.add 的两层重名检查一致）。
 */
@Service
public class McpServerProvisionService {

    private final McpServerConfigMapper configs;
    private final McpClientManager manager;

    public McpServerProvisionService(McpServerConfigMapper configs, McpClientManager manager) {
        this.configs = configs;
        this.manager = manager;
    }

    /**
     * 把一份个人 MCP 服务配置以禁用状态落库。流程：
     * <ol>
     *   <li>强制 {@code createdBy = trustedOwnerId}、{@code enabled = false}（不采信 config 上的同名字段）；</li>
     *   <li>{@link McpServerConfigValidator#check} 全字段校验；</li>
     *   <li>同一用户个人服务重名 → 拒；</li>
     *   <li>ownerId=0 槽位（部署/YAML/共享）或 DB 共享行同名 → 拒；</li>
     *   <li>{@link McpServerConfigMapper#insert} 落库。</li>
     * </ol>
     * 全程不碰 {@code addServer} / {@code connectSlot} / 子进程。
     *
     * @param trustedOwnerId 调用方已验证身份的 userId（Controller 走 JWT，
     *                       未来 Agent 工具走 {@code ConversationContext.userId()}）
     * @param config         已从请求体解析的配置；其 {@code createdBy}/{@code enabled}
     *                       会被本方法覆盖
     * @return {@link SaveResult}，{@code saved=true} 时 {@code name} 为落库后的服务名
     */
    public SaveResult saveDisabled(long trustedOwnerId, McpServerConfig config) {
        if (config == null) return SaveResult.failed(null, "配置不能为空");
        // 归属与启用状态由业务层决定：请求体里的 createdBy / enabled 字段一律不采信。
        config.setCreatedBy(trustedOwnerId);
        config.setEnabled(false);
        // 新建专用。部分更新不能走这里，否则没传的字段会被补默认值或清空。
        McpServerConfigValidator.normalizeForCreate(config);

        String err = McpServerConfigValidator.check(config);
        if (err != null) return SaveResult.failed(config.getName(), err);

        if (configs.findByOwnerAndName(trustedOwnerId, config.getName()) != null) {
            return SaveResult.failed(config.getName(), "服务已存在");
        }
        if (hasSharedOrDeploymentName(config.getName())) {
            return SaveResult.failed(config.getName(), "与系统或部署服务重名，请换一个名字");
        }
        configs.insert(config);
        return SaveResult.ok(config.getName());
    }

    /**
     * 与旧 {@code Controller.add} 一致的两层重名检查：
     * <ul>
     *   <li>{@link McpClientManager#findServer(long, String)} 查 ownerId=0 槽位
     *       ——已运行的部署/YAML/共享服务（启动时 {@code prepareSlots} 给所有 YAML 键建槽）；</li>
     *   <li>{@link McpServerConfigMapper#findAll()} 里 created_by=0 的行——历史共享配置，
     *       可能没建槽但库里有。</li>
     * </ul>
     * 用 {@code name.equals} 精确比名，与旧实现一致（不折叠大小写/重音，折叠判定在
     * {@code delete} 里走 {@code namesMatch}，不在新增路径）。
     */
    private boolean hasSharedOrDeploymentName(String name) {
        if (name == null) return false;
        if (manager.findServer(0L, name) != null) return true;
        for (McpServerConfig c : configs.findAll()) {
            if (c != null && name.equals(c.getName()) && McpClientManager.ownerOf(c) == 0L) return true;
        }
        return false;
    }

    /**
     * 保存结果。{@code saved=false} 时 {@code error} 给出拒绝原因（用于回 400），
     * {@code name} 尽量回传（校验失败前可能为空）。不可变 record，调用方不能改。
     */
    public record SaveResult(boolean saved, String name, String error) {
        public static SaveResult ok(String name) { return new SaveResult(true, name, null); }
        public static SaveResult failed(String name, String error) {
            return new SaveResult(false, name, error);
        }
    }
}
