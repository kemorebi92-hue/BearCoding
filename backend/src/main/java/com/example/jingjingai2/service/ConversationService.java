package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.db.DbDialect;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.mcp.McpOutputStore;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 会话（Conversation）服务：负责"会话列表"这一层的东西。
 *
 * 与 {@link ConversationHistoryService} 的分工：
 *  - HistoryService：管消息正文的读写（多轮上下文用）
 *  - 本类：管会话元数据（有哪些会话、叫什么名字、什么时候活跃、删会话要连消息一起删）
 *
 * 设计要点：
 *  1. 会话记录**惰性创建**：用户点"新对话"时前端只生成一个 UUID，不发请求；
 *     等第一条消息真正发出来时才由 {@link #touch} 落库并生成标题 —— 避免列表里出现一堆空会话。
 *  2. 标题**自动生成**：取首条用户消息的前 24 个字（见 {@link #deriveTitle}），零成本、够用。
 *  3. 删除会话用事务包住"删消息 + 删会话"，避免只删一半留下脏数据。
 */
@Service
public class ConversationService {

    /** 会话标题最大长度（超出截断加省略号） */
    static final int MAX_TITLE_LEN = 24;
    /** 会话列表最多返回多少条 */
    private static final int MAX_LIST_SIZE = 200;
    private static final String DEFAULT_TITLE = "新对话";
    /** 缺省权限模式。旧数据补列、新建会话、读到空值时都是它。 */
    public static final String MODE_ASK = "ASK";
    public static final String MODE_WORKSPACE_EDIT = "WORKSPACE_EDIT";
    public static final String MODE_FULL_ACCESS = "FULL_ACCESS";
    private static final Set<String> PERMISSION_MODES =
            Set.of(MODE_ASK, MODE_WORKSPACE_EDIT, MODE_FULL_ACCESS);

    private final ConversationMapper mapper;
    /** MCP 超限结果的落盘目录。没注入时不清理（单测直接 new）。 */
    private McpOutputStore mcpOutputStore;
    private final WorkspaceService workspaceService;

    public ConversationService(ConversationMapper mapper, WorkspaceService workspaceService) {
        this.mapper = mapper;
        this.workspaceService = workspaceService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMcpOutputStore(McpOutputStore mcpOutputStore) {
        this.mcpOutputStore = mcpOutputStore;
    }

    /**
     * 会话打卡：每条用户消息落库时调用。
     * 会话不存在 → 用首条消息生成标题建一条（可选挂到 workspaceId）；已存在 → 消息数 +1、刷新活跃时间，不改归属。
     */
    public void touch(Long userId, String conversationId, String firstUserMessage) {
        touch(userId, conversationId, firstUserMessage, null);
    }

    public void touch(Long userId, String conversationId, String firstUserMessage, Long workspaceId) {
        touch(userId, conversationId, firstUserMessage, workspaceId, MODE_ASK);
    }

    /**
     * 会话打卡，并在**首次 INSERT** 时写入前端选定的权限模式。
     * 已有会话的 ON DUPLICATE 分支不更新 permission_mode：改模式只走 PUT permission-mode，
     * 避免每条消息都把模式按请求体重置一遍。非法或缺省的 mode 按 ASK。
     */
    public void touch(Long userId, String conversationId, String firstUserMessage,
                      Long workspaceId, String permissionMode) {
        if (userId == null || conversationId == null || conversationId.isBlank()) return;
        Long owned = ownedWorkspaceId(userId, workspaceId);
        String mode = isPermissionMode(permissionMode) ? permissionMode : MODE_ASK;
        try {
            mapper.touch(userId, conversationId, deriveTitle(firstUserMessage), owned, mode);
        } catch (Exception e) {
            // 会话元数据只是"列表展示"用的，出问题不应该影响正常对话
            System.err.println("[Conversation] 会话打卡失败（不影响对话）: " + e.getMessage());
        }
    }

    /** 会话列表（按最近活跃倒序）；补上默认工作区显示名 */
    public List<ConversationSummary> list(Long userId) {
        if (userId == null) return Collections.emptyList();
        List<ConversationSummary> all = mapper.listByUser(userId, MAX_LIST_SIZE);
        if (all == null) return Collections.emptyList();
        for (ConversationSummary c : all) {
            WorkspaceService.fillDefaultName(c);
            fillPermissionMode(c);
        }
        return all;
    }

    public ConversationSummary get(Long userId, String conversationId) {
        if (userId == null || conversationId == null) return null;
        ConversationSummary c = mapper.selectOne(userId, conversationId);
        WorkspaceService.fillDefaultName(c);
        fillPermissionMode(c);
        return c;
    }

    /**
     * 当前会话的权限模式。会话不属于该用户、或不存在时返回 null，调用方据此拒绝。
     * 读到空值（旧数据补列前的瞬时窗口）按 ASK 处理。
     */
    public String permissionMode(Long userId, String conversationId) {
        ConversationSummary c = get(userId, conversationId);
        return c == null ? null : c.getPermissionMode();
    }

    /**
     * 只更新当前用户当前会话的权限模式。
     *
     * @return true 表示改到了；false 表示模式非法，或会话不属于该用户 / 不存在
     */
    public boolean updatePermissionMode(Long userId, String conversationId, String mode) {
        if (userId == null || conversationId == null || conversationId.isBlank()) return false;
        if (mode == null || !PERMISSION_MODES.contains(mode)) return false;
        return mapper.updatePermissionMode(userId, conversationId, mode) > 0;
    }

    /** 三个允许值之一。非法（含 null、空串、大小写不符）返回 false，不静默归一。 */
    public static boolean isPermissionMode(String mode) {
        return mode != null && PERMISSION_MODES.contains(mode);
    }

    private static void fillPermissionMode(ConversationSummary c) {
        if (c == null) return;
        if (c.getPermissionMode() == null || c.getPermissionMode().isBlank()) {
            c.setPermissionMode(MODE_ASK);
        }
    }

    /** 校验 workspaceId 属于当前用户；非法或不存在则当默认工作区（null），不把别人的 id 写进去。 */
    public Long ownedWorkspaceId(Long userId, Long workspaceId) {
        if (userId == null || workspaceId == null) return null;
        try {
            workspaceService.requireOwnedWorkspace(userId, workspaceId);
            return workspaceId;
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 重命名：返回是否真的改到了（false = 这个会话不属于该用户或不存在） */
    public boolean rename(Long userId, String conversationId, String newTitle) {
        if (userId == null || conversationId == null || newTitle == null || newTitle.isBlank()) return false;
        String title = newTitle.trim();
        if (title.length() > MAX_TITLE_LEN * 2) title = title.substring(0, MAX_TITLE_LEN * 2);
        return mapper.updateTitle(userId, conversationId, title) > 0;
    }

    /** 删除会话：消息 + 会话元数据一起删（事务保证要么都删、要么都不删） */
    @Transactional
    public boolean delete(Long userId, String conversationId) {
        if (userId == null || conversationId == null) return false;
        mapper.deleteMessages(userId, conversationId);
        boolean deleted = mapper.deleteConversation(userId, conversationId) > 0;
        // 归属由 mapper 的 userId 条件保证：别人的会话删不掉，也就不会删到别人的落盘目录
        if (deleted && mcpOutputStore != null) {
            try {
                mcpOutputStore.deleteConversation(conversationId);
            } catch (Exception e) {
                System.err.println("[MCP] 删除落盘目录失败（不影响会话删除）: " + e.getMessage());
            }
        }
        return deleted;
    }

    /** 会话里的历史消息（时间正序） */
    public List<MessageRecord> history(Long userId, String conversationId) {
        if (userId == null || conversationId == null) return Collections.emptyList();
        List<MessageRecord> all = mapper.getHistory(userId, conversationId, 200);
        if (all == null || all.isEmpty()) return Collections.emptyList();
        Collections.reverse(all);
        return all;
    }

    /**
     * 由首条用户消息生成会话标题。
     * 规则：压掉换行/连续空格 → 去掉 markdown 前缀符号 → 截断。
     * 抽成 static 纯函数，便于单测（不依赖 Spring 容器）。
     */
    static String deriveTitle(String message) {
        if (message == null) return DEFAULT_TITLE;
        String t = message.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[#>\\-*\\s]+", "").trim();
        if (t.isEmpty()) return DEFAULT_TITLE;
        return t.length() <= MAX_TITLE_LEN ? t : t.substring(0, MAX_TITLE_LEN) + "…";
    }

    /**
     * 启动后的迁移与补齐（幂等，失败也不影响启动）：
     *  ① 给已存在的旧 conversation 表补 plan_json 列
     *     （MySQL 8 没有 `ADD COLUMN IF NOT EXISTS`，所以先查 information_schema 再决定）；
     *  ② 把"只有消息、没有会话记录"的旧会话补进 conversation 表。
     * 放在 ApplicationReadyEvent 而不是 schema.sql 里，就是为了"万一失败也不阻塞启动"。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateAndBackfill() {
        // MySQL 专有的「补列 / 补索引」只对历史库有意义；SQLite fresh 库的结构由 schema-sqlite.sql 建好。
        // 这里显式跳过，而不是让 MySQL DDL 抛异常再被 catch 吞掉——后者会把「没执行」伪装成「迁移成功」。
        if (DbDialect.isSqlite()) {
            System.out.println("[Conversation] SQLite：跳过 MySQL 专有补列（结构由 schema-sqlite.sql 保证）");
        } else {
            migrateLegacyColumns();
        }
        // 业务回填与库类型无关：只要存在「有消息、没有会话记录」的数据就要补，两种库都跑。
        backfillMissingConversations();
        try {
            int n = workspaceService.migrateBindings();
            if (n > 0) System.out.println("[Workspace] 已把 " + n + " 条会话挂到工作区（来自旧 .bindings）");
        } catch (Exception e) {
            System.err.println("[Workspace] 绑定迁移跳过: " + e.getMessage());
        }
    }

    /** MySQL 历史库的结构升级：补 plan_json / workspace_id / permission_mode 列与索引。 */
    private void migrateLegacyColumns() {
        try {
            if (mapper.countPlanColumn() == 0) {
                mapper.addPlanColumn();
                System.out.println("[Conversation] 已为 conversation 表补充 plan_json 列（任务计划持久化用）");
            }
        } catch (Exception e) {
            System.err.println("[Conversation] plan_json 列迁移跳过: " + e.getMessage());
        }
        try {
            if (mapper.countWorkspaceIdColumn() == 0) {
                mapper.addWorkspaceIdColumn();
                System.out.println("[Conversation] 已为 conversation 表补充 workspace_id 列");
            }
            if (mapper.countWorkspaceIdIndex() == 0) {
                mapper.addWorkspaceIdIndex();
            }
        } catch (Exception e) {
            System.err.println("[Conversation] workspace_id 列迁移跳过: " + e.getMessage());
        }
        try {
            if (mapper.countPermissionModeColumn() == 0) {
                mapper.addPermissionModeColumn();
                System.out.println("[Conversation] 已为 conversation 表补充 permission_mode 列（默认 ASK）");
            }
        } catch (Exception e) {
            System.err.println("[Conversation] permission_mode 列迁移跳过: " + e.getMessage());
        }
    }

    /** 把「只有消息、没有会话记录」的旧会话补进 conversation 表。两种数据库都要执行。 */
    private void backfillMissingConversations() {
        try {
            int n = mapper.backfillFromMessages();
            if (n > 0) System.out.println("[Conversation] 已补齐历史会话记录 " + n + " 条");
        } catch (Exception e) {
            System.err.println("[Conversation] 历史会话补齐跳过: " + e.getMessage());
        }
    }
}
