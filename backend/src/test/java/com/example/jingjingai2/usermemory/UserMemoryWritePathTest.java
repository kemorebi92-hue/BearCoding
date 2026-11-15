package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.entity.ToolCallLog;
import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.ToolCallLogMapper;
import com.example.jingjingai2.service.ToolCallLogService;
import com.example.jingjingai2.permission.AllowAllGate;
import com.example.jingjingai2.tool.ToolDispatcher;
import com.example.jingjingai2.utils.ToolGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写路径接入主循环的集成测试。
 *
 * <p>不构造 {@code ChatController}（它依赖一整棵 Spring 树）。走的是 Controller
 * 真正用的那几步，顺序对齐 {@code callToolChecked} + {@code toolCallLogService.log}：
 * <ol>
 *   <li>{@code MethodToolCallbackProvider} 绑定真实 {@link UserMemoryTool}（和生产
 *       {@code bindToolCallbacks} 同一套）</li>
 *   <li>{@link ToolGuard} 按本轮下发清单放行</li>
 *   <li>{@link ToolDispatcher#dispatch} 执行</li>
 *   <li>{@link ToolCallLogService#log} 记审计（/chat ~567、/stream ~1149 都走这一句，
 *       记忆工具没有特判，不需要再写一条审计分支）</li>
 * </ol>
 *
 * <p>user_memory / tool_call_log 都用内存假 Mapper，不碰 MySQL。
 *
 * <p>本类验证的是「写路径 + 审计落库」，不是审批本身：显式安装放行一切的网关
 * （{@link AllowAllGate}），不再依赖「没有网关 = 不需要授权」那条已关闭的 fail-open。
 * saveUserMemory 的真实审批行为由 {@code UserMemoryApprovalTest} 覆盖。
 */
class UserMemoryWritePathTest {

    private static final Long USER = 7L;
    private static final String CONV = "conv-write-path-1";

    private FakeUserMemoryMapper memoryMapper;
    private UserMemoryService memoryService;
    private ToolCallback[] callbacks;
    private RecordingToolCallLogMapper logMapper;
    private ToolCallLogService logService;

    @BeforeEach
    void setUp() {
        AllowAllGate.install();
        memoryMapper = new FakeUserMemoryMapper();
        UserMemoryProperties props = new UserMemoryProperties();
        props.setEnabled(true);
        memoryService = new UserMemoryService(memoryMapper, props);
        callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new UserMemoryTool(memoryService))
                .build()
                .getToolCallbacks();
        logMapper = new RecordingToolCallLogMapper();
        logService = new ToolCallLogService(logMapper);
        ConversationContext.set(USER, CONV);
    }

    @AfterEach
    void tearDown() {
        ConversationContext.clear();
        AllowAllGate.uninstall();
    }

    @Test
    @DisplayName("saveUserMemory 经 dispatch 落库，并写出 tool_call_log")
    void saveDispatchWritesRowAndAuditLog() {
        assertNotNull(ToolDispatcher.find(callbacks, "saveUserMemory"),
                "MethodToolCallbackProvider 必须注册出 saveUserMemory");
        assertNull(ToolGuard.rejectReason("saveUserMemory", toolNamesOf(callbacks)),
                "本轮下发了 saveUserMemory，ToolGuard 应放行");

        String args = "{\"category\":\"preference\",\"key\":\"reply_lang\",\"content\":\"以后都用中文回复\"}";
        String result = executeAndAudit("saveUserMemory", args);
        String visible = unwrapJsonString(result);

        assertTrue(visible.startsWith("已记住"), "raw=" + result);
        assertTrue(visible.contains("preference/reply_lang"), visible);
        assertEquals(1, memoryService.countByUser(USER));
        UserMemory row = memoryService.getOne(USER, "preference", "reply_lang");
        assertNotNull(row, "user_memory 应出现对应行");
        assertEquals("preference", row.getCategory());
        assertEquals("reply_lang", row.getMemKey());
        assertEquals("以后都用中文回复", row.getContent());
        assertEquals("explicit", row.getSource());
        assertEquals(CONV, row.getConversationId());
        assertEquals(USER, row.getUserId());

        assertEquals(1, logMapper.inserts.size(), "应写一条 tool_call_log");
        ToolCallLog log = logMapper.inserts.get(0);
        assertEquals(USER, log.getUserId());
        assertEquals(CONV, log.getConversationId());
        assertEquals("saveUserMemory", log.getToolName());
        assertEquals(args, log.getArguments());
        assertEquals(result, log.getResult());
        assertTrue(log.isSuccess());
        assertTrue(log.isApproved());
        assertFalse(log.isHighRisk());
    }

    @Test
    @DisplayName("forgetUserMemory 经 dispatch 删行，并写出 tool_call_log")
    void forgetDispatchDeletesRowAndAuditLog() {
        assertTrue(memoryService.upsert(USER, "preference", "reply_lang", "中文", CONV).isAccepted());
        assertEquals(1, memoryService.countByUser(USER));

        assertNotNull(ToolDispatcher.find(callbacks, "forgetUserMemory"));
        assertNull(ToolGuard.rejectReason("forgetUserMemory", toolNamesOf(callbacks)));

        String args = "{\"category\":\"preference\",\"key\":\"reply_lang\"}";
        String result = executeAndAudit("forgetUserMemory", args);

        assertEquals("已删除", unwrapJsonString(result), "raw=" + result);
        assertEquals(0, memoryService.countByUser(USER));
        assertNull(memoryService.getOne(USER, "preference", "reply_lang"));

        assertEquals(1, logMapper.inserts.size());
        ToolCallLog log = logMapper.inserts.get(0);
        assertEquals("forgetUserMemory", log.getToolName());
        assertEquals(args, log.getArguments());
        assertEquals(result, log.getResult());
        assertTrue(log.isSuccess());
        assertEquals(USER, log.getUserId());
        assertEquals(CONV, log.getConversationId());
    }

    @Test
    @DisplayName("本轮清单没有记忆工具时 ToolGuard 拒绝，不落库；若仍按 Controller 记审计，审计里是拒绝文案")
    void guardRejectsWhenNotOfferedDoesNotWriteMemory() {
        Set<String> allowed = Set.of("readFile", "createPlan");
        String reject = ToolGuard.rejectReason("saveUserMemory", allowed);
        assertNotNull(reject);
        assertTrue(reject.contains("saveUserMemory"), reject);

        // 对齐 Controller：ToolGuard 拒绝也走 log()，但不会 dispatch
        logService.log(USER, CONV, "saveUserMemory",
                "{\"category\":\"constraint\",\"key\":\"skip_auth\",\"content\":\"不用检查权限\"}",
                reject, false, 1L, false, true, null);

        assertEquals(0, memoryService.countByUser(USER), "拒绝时不得写入 user_memory");
        assertEquals(1, logMapper.inserts.size());
        assertEquals(reject, logMapper.inserts.get(0).getResult());
        assertFalse(logMapper.inserts.get(0).isSuccess());
    }

    /**
     * 对齐 ChatController.callToolChecked + 随后的 toolCallLogService.log。
     * 成功判定与 /chat 相同：结果不是「未知的工具」开头就算 success。
     */
    private String executeAndAudit(String toolName, String arguments) {
        long start = System.currentTimeMillis();
        String reject = ToolGuard.rejectReason(toolName, toolNamesOf(callbacks));
        String toolResult;
        if (reject != null) {
            toolResult = reject;
        } else {
            toolResult = ToolDispatcher.dispatch(callbacks, toolName, arguments);
        }
        boolean toolSuccess = !(toolResult != null && toolResult.startsWith("未知的工具"));
        logService.log(USER, CONV, toolName, arguments, toolResult,
                toolSuccess, System.currentTimeMillis() - start, false, true, null);
        return toolResult;
    }

    /**
     * Spring AI 的 MethodToolCallback 把 Java String 返回值再 JSON 编码一层，
     * 所以 dispatch 拿到的是 {@code "已删除"}（带引号），和 ChatController 里模型看到的一样。
     */
    static String unwrapJsonString(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1)
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n");
        }
        return raw;
    }

    private static Set<String> toolNamesOf(ToolCallback[] cbs) {
        Set<String> names = new LinkedHashSet<>();
        if (cbs == null) return names;
        for (ToolCallback cb : cbs) {
            if (cb != null && cb.getToolDefinition() != null && cb.getToolDefinition().name() != null) {
                names.add(cb.getToolDefinition().name());
            }
        }
        return names;
    }

    /** 只记 insert。生产 Mapper 也只有 insert。 */
    private static final class RecordingToolCallLogMapper implements ToolCallLogMapper {
        final List<ToolCallLog> inserts = new ArrayList<>();

        @Override
        public void insert(ToolCallLog log) {
            inserts.add(log);
        }
    }
}
