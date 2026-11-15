package com.example.jingjingai2.mapper;

import com.example.jingjingai2.DTO.ConversationSummary;
import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.mapper.sql.ConversationSqlProvider;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;

import java.util.List;

/**
 * 对话历史表 Mapper（MyBatis 注解式，替代原 ConversationJdbcRepository）。
 *
 * 这里管两张表：
 *  - conversation_message：消息正文（一对多，消息量大）
 *  - conversation：会话元数据（一个会话一行，用于会话列表）
 */
@Mapper
public interface ConversationMapper {

    @Insert("INSERT INTO conversation_message(user_id, conversation_id, role, content) " +
            "VALUES(#{userId}, #{conversationId}, #{role}, #{content})")
    void insert(@Param("userId") Long userId,
                @Param("conversationId") String conversationId,
                @Param("role") String role,
                @Param("content") String content);

    /**
     * 取最近 limit 条（倒序），由调用方再逆序还原时间顺序。
     * id 是稳定身份：正文相同的两条消息仍然不同。
     * created_at 必须别名成 timestamp，否则前端拿到的时间是空的。
     */
    @Select("SELECT id, role, content, created_at AS timestamp FROM conversation_message " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId} " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<MessageRecord> getHistory(@Param("userId") Long userId,
                                  @Param("conversationId") String conversationId,
                                  @Param("limit") int limit);

    /**
     * 当前用户当前会话内按关键词找消息，返回 id、角色、时间和短摘要。
     * 摘要取关键词首次命中处的前 {@code before} 个字符起、共 {@code window} 个字符。
     * 起点用 CASE，不用多参数 MAX：MySQL 的 MAX 是聚合函数，SQLite 的 MAX 是标量比较，
     * 两者不能混用。INSTR 和 SUBSTR 在两种库里都从 1 开始。
     */
    @Select("SELECT id, role, created_at AS timestamp, " +
            "SUBSTR(content, CASE WHEN INSTR(content, #{needle}) - #{before} > 1 " +
            "THEN INSTR(content, #{needle}) - #{before} ELSE 1 END, #{window}) AS snippet " +
            "FROM conversation_message " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId} " +
            "AND content LIKE #{pattern} ESCAPE '\\' " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<MessageHit> searchMessages(@Param("userId") Long userId,
                                    @Param("conversationId") String conversationId,
                                    @Param("pattern") String pattern,
                                    @Param("needle") String needle,
                                    @Param("before") int before,
                                    @Param("window") int window,
                                    @Param("limit") int limit);

    /**
     * 与 {@link #searchMessages} 相同的匹配与摘要，但支持翻页：{@code offset} 表示跳过多少条命中。
     *
     * <p>旧方法原样保留，既有调用方不受影响。升序/降序写成两条独立 SQL，
     * 不用 {@code ${}} 拼接 ORDER BY，避免 SQL 注入。
     */
    @Select("SELECT id, role, created_at AS timestamp, " +
            "SUBSTR(content, CASE WHEN INSTR(content, #{needle}) - #{before} > 1 " +
            "THEN INSTR(content, #{needle}) - #{before} ELSE 1 END, #{window}) AS snippet " +
            "FROM conversation_message " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId} " +
            "AND content LIKE #{pattern} ESCAPE '\\' " +
            "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MessageHit> searchMessagesDescPage(@Param("userId") Long userId,
                                            @Param("conversationId") String conversationId,
                                            @Param("pattern") String pattern,
                                            @Param("needle") String needle,
                                            @Param("before") int before,
                                            @Param("window") int window,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    /** 升序翻页：从最早的一条命中开始。用于找回被降序首页挤掉的旧消息。 */
    @Select("SELECT id, role, created_at AS timestamp, " +
            "SUBSTR(content, CASE WHEN INSTR(content, #{needle}) - #{before} > 1 " +
            "THEN INSTR(content, #{needle}) - #{before} ELSE 1 END, #{window}) AS snippet " +
            "FROM conversation_message " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId} " +
            "AND content LIKE #{pattern} ESCAPE '\\' " +
            "ORDER BY id ASC LIMIT #{limit} OFFSET #{offset}")
    List<MessageHit> searchMessagesAscPage(@Param("userId") Long userId,
                                           @Param("conversationId") String conversationId,
                                           @Param("pattern") String pattern,
                                           @Param("needle") String needle,
                                           @Param("before") int before,
                                           @Param("window") int window,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    /**
     * 多个关键词一次匹配后统一排序、去重、分页。
     *
     * <p>按关键词分别 LIMIT 再在 Java 里合并，全局顺序和各关键词自己的前 N 条不是一回事，
     * 翻页就会重复或漏掉。这里把 OR、ORDER BY、LIMIT、OFFSET 放进同一条 SQL。
     * 用户输入只绑定到 {@code #{}}，排序方向不走 {@code ${}}。
     * 单关键词的 {@link #searchMessages} 原样保留。
     */
    @SelectProvider(type = ConversationSqlProvider.class, method = "searchMessagesAnyDesc")
    List<MessageHit> searchMessagesAnyDesc(@Param("userId") Long userId,
                                            @Param("conversationId") String conversationId,
                                            @Param("patterns") List<String> patterns,
                                            @Param("needles") List<String> needles,
                                            @Param("before") int before,
                                            @Param("window") int window,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    /** 与 {@link #searchMessagesAnyDesc} 相同，但从最早的命中开始。 */
    @SelectProvider(type = ConversationSqlProvider.class, method = "searchMessagesAnyAsc")
    List<MessageHit> searchMessagesAnyAsc(@Param("userId") Long userId,
                                           @Param("conversationId") String conversationId,
                                           @Param("patterns") List<String> patterns,
                                           @Param("needles") List<String> needles,
                                           @Param("before") int before,
                                           @Param("window") int window,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    /**
     * 按消息 id 读正文。必须同时带 userId 和 conversationId：
     * 只凭模型给的 id 不能读到别人的、别的会话的消息。
     */
    @Select("SELECT id, role, content, created_at AS timestamp FROM conversation_message " +
            "WHERE id = #{id} AND user_id = #{userId} AND conversation_id = #{conversationId}")
    MessageRecord selectMessage(@Param("userId") Long userId,
                                 @Param("conversationId") String conversationId,
                                 @Param("id") long id);

    // ===================== 会话元数据（conversation 表） =====================

    /**
     * 会话"打卡"：不存在就按首条消息生成标题插入（可选带 workspace_id）；
     * 已存在则消息数 +1、刷新活跃时间，**不覆盖 title 和 workspace_id**。
     * 用 ON DUPLICATE KEY UPDATE 一条 SQL 搞定插入或更新，并发下也不会主键冲突。
     * workspaceId 为 null 时 INSERT 的 workspace_id 就是 NULL（默认工作区）。
     */
    @InsertProvider(type = ConversationSqlProvider.class, method = "touch")
    int touch(@Param("userId") Long userId,
              @Param("conversationId") String conversationId,
              @Param("title") String title,
              @Param("workspaceId") Long workspaceId,
              @Param("permissionMode") String permissionMode);

    @Select("SELECT c.conversation_id, c.title, c.message_count, c.created_at, c.updated_at, " +
            "       c.workspace_id, w.name AS workspace_name, c.permission_mode " +
            "FROM conversation c LEFT JOIN workspace w ON w.id = c.workspace_id " +
            "WHERE c.user_id = #{userId} AND c.conversation_id = #{conversationId}")
    ConversationSummary selectOne(@Param("userId") Long userId,
                                  @Param("conversationId") String conversationId);

    @Select("SELECT c.conversation_id, c.title, c.message_count, c.created_at, c.updated_at, " +
            "       c.workspace_id, w.name AS workspace_name, c.permission_mode " +
            "FROM conversation c LEFT JOIN workspace w ON w.id = c.workspace_id " +
            "WHERE c.user_id = #{userId} ORDER BY c.updated_at DESC LIMIT #{limit}")
    List<ConversationSummary> listByUser(@Param("userId") Long userId,
                                         @Param("limit") int limit);

    @Select("SELECT c.conversation_id, c.title, c.message_count, c.created_at, c.updated_at, " +
            "       c.workspace_id, w.name AS workspace_name, c.permission_mode " +
            "FROM conversation c LEFT JOIN workspace w ON w.id = c.workspace_id " +
            "WHERE c.user_id = #{userId} AND c.workspace_id = #{workspaceId} " +
            "ORDER BY c.updated_at DESC")
    List<ConversationSummary> listByWorkspace(@Param("userId") Long userId,
                                              @Param("workspaceId") Long workspaceId);

    @Select("SELECT c.conversation_id, c.title, c.message_count, c.created_at, c.updated_at, " +
            "       c.workspace_id, w.name AS workspace_name, c.permission_mode " +
            "FROM conversation c LEFT JOIN workspace w ON w.id = c.workspace_id " +
            "WHERE c.user_id = #{userId} AND c.workspace_id IS NULL " +
            "ORDER BY c.updated_at DESC")
    List<ConversationSummary> listUnbound(@Param("userId") Long userId);

    @Update("UPDATE conversation SET workspace_id = #{workspaceId}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int updateWorkspaceId(@Param("userId") Long userId,
                          @Param("conversationId") String conversationId,
                          @Param("workspaceId") Long workspaceId);

    @Update("UPDATE conversation SET workspace_id = NULL, updated_at = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId} AND workspace_id = #{workspaceId}")
    int clearWorkspaceId(@Param("userId") Long userId, @Param("workspaceId") Long workspaceId);

    @Update("UPDATE conversation SET title = #{title}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int updateTitle(@Param("userId") Long userId,
                    @Param("conversationId") String conversationId,
                    @Param("title") String title);

    /**
     * 只改当前用户当前会话的权限模式，不动其他列和其他会话。
     * 调用方必须先校验 mode 是 ASK / WORKSPACE_EDIT / FULL_ACCESS 之一。
     */
    @UpdateProvider(type = ConversationSqlProvider.class, method = "updatePermissionMode")
    int updatePermissionMode(@Param("userId") Long userId,
                             @Param("conversationId") String conversationId,
                             @Param("mode") String mode);

    @Delete("DELETE FROM conversation WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int deleteConversation(@Param("userId") Long userId,
                           @Param("conversationId") String conversationId);

    @Delete("DELETE FROM conversation_message WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int deleteMessages(@Param("userId") Long userId,
                       @Param("conversationId") String conversationId);

    /**
     * 一次性把"只有消息、没有会话记录"的历史数据补进 conversation 表。
     * 场景：会话表是后加的，数据库里已经躺着不少旧的 conversation_message，
     * 不补的话用户在会话列表里就看不到它们（数据在，但打不开）。
     * 标题取该会话第一条用户消息的前 24 个字。
     */
    @InsertProvider(type = ConversationSqlProvider.class, method = "backfill")
    int backfillFromMessages();

    // ===================== 任务计划（Plan-and-Execute 的会话级状态） =====================

    @UpdateProvider(type = ConversationSqlProvider.class, method = "updatePlanJson")
    int updatePlanJson(@Param("userId") Long userId,
                       @Param("conversationId") String conversationId,
                       @Param("planJson") String planJson);

    @Select("SELECT plan_json FROM conversation " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    String selectPlanJson(@Param("userId") Long userId,
                          @Param("conversationId") String conversationId);

    /**
     * 幂等迁移用：查 plan_json 列是否存在。
     * MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，所以先查 information_schema 再决定要不要加，
     * 这样 schema.sql 反复执行（mode=always）也不会报错。
     */
    @SelectProvider(type = ConversationSqlProvider.class, method = "countPlanColumn")
    int countPlanColumn();

    @Update("ALTER TABLE conversation ADD COLUMN plan_json MEDIUMTEXT NULL")
    void addPlanColumn();

    @SelectProvider(type = ConversationSqlProvider.class, method = "countWorkspaceIdColumn")
    int countWorkspaceIdColumn();

    @Update("ALTER TABLE conversation ADD COLUMN workspace_id BIGINT NULL")
    void addWorkspaceIdColumn();

    @SelectProvider(type = ConversationSqlProvider.class, method = "countWorkspaceIdIndex")
    int countWorkspaceIdIndex();

    @Update("ALTER TABLE conversation ADD INDEX idx_conv_workspace (workspace_id)")
    void addWorkspaceIdIndex();

    @SelectProvider(type = ConversationSqlProvider.class, method = "countPermissionModeColumn")
    int countPermissionModeColumn();

    @Update("ALTER TABLE conversation ADD COLUMN permission_mode VARCHAR(16) NOT NULL DEFAULT 'ASK'")
    void addPermissionModeColumn();
}
