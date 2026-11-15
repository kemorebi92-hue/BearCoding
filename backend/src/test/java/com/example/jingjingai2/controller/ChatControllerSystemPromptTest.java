package com.example.jingjingai2.controller;

import com.example.jingjingai2.agent.SubagentProperties;
import com.example.jingjingai2.agent.turn.AgentTurnRecord;
import com.example.jingjingai2.agent.turn.AgentTurnRecorder;
import com.example.jingjingai2.agent.turn.PromptCanonical;
import com.example.jingjingai2.config.AgentPrompts;
import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.context.RoundCompactor;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import com.example.jingjingai2.mapper.PromptSnapshotMapper;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpProperties;
import com.example.jingjingai2.usermemory.UserMemoryInjector;
import com.example.jingjingai2.usermemory.UserMemoryProperties;
import com.example.jingjingai2.utils.UsageTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1/B2 回归：正式聊天只注入一条当前用户的基础系统提示；agent_turn/prompt_snapshot
 * 的静态提示也必须来自同一条提示，而不是诊断用的 userId=0 版本。
 *
 * <p>不启动 Spring，不连接 MCP，不访问数据库。用测试替身提供按 userId 不同的
 * serverLines，专门验证用户隔离和默认用户提示不会混入。
 */
class ChatControllerSystemPromptTest {

    private static final long USER = 42L;

    @Test
    @DisplayName("正式聊天只注入一条当前用户的基础系统提示")
    void onlyOneBaseSystemPrompt() throws Exception {
        Fixture f = new Fixture();
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("hi")));

        String expected = f.prepend(messages, USER);

        assertEquals(1, systemTexts(messages).size(),
                "未启用评测/用户记忆时只能有一条基础 SystemMessage");
        assertEquals(expected, text(messages.get(0)));
        assertEquals("hi", text(messages.get(1)));
    }

    @Test
    @DisplayName("用户级 MCP 信息只出现一次，默认用户的完整提示不会混入")
    void userMcpInfoAppearsOnceWithoutDefaultPrompt() throws Exception {
        Fixture f = new Fixture();
        String defaultPrompt = f.systemPrompt(0L);
        assertTrue(defaultPrompt.contains("default-server（部署）"), defaultPrompt);

        List<Message> messages = new ArrayList<>(List.of(new UserMessage("hi")));
        String userPrompt = f.prepend(messages, USER);
        assertNotEquals(userPrompt, defaultPrompt, "前置：两个用户应看到不同的 MCP server 行");
        assertTrue(userPrompt.contains("user-42-server-A"), userPrompt);

        List<String> sys = systemTexts(messages);
        assertEquals(1, sys.size(), "不能同时注入当前用户提示和默认用户提示");
        assertEquals(userPrompt, sys.get(0));
        assertEquals(1, countOccurrences(sys.get(0), "user-42-server-A"),
                "用户级 server 行只能出现一次");
        assertFalse(sys.get(0).contains("default-server（部署）"), sys.get(0));
    }

    @Test
    @DisplayName("评测强制消息仍在基础提示之后，两个分支互斥")
    void evalForceMessagesKeepOrderAndExclusion() throws Exception {
        Fixture f = new Fixture();

        f.subagent.setEvalForceMode("force_dispatch");
        List<Message> dispatch = new ArrayList<>(List.of(new UserMessage("hi")));
        String base = f.prepend(dispatch, USER);
        assertEquals(3, dispatch.size());
        assertEquals(base, text(dispatch.get(0)));
        assertEquals(AgentPrompts.EVAL_FORCE_DISPATCH_MSG, text(dispatch.get(1)));
        assertEquals("hi", text(dispatch.get(2)));
        assertFalse(dispatch.stream().anyMatch(m -> AgentPrompts.EVAL_FORCE_NO_SPAWN_MSG.equals(text(m))));

        f.subagent.setEvalForceMode("force_no_spawn");
        List<Message> noSpawn = new ArrayList<>(List.of(new UserMessage("hi")));
        f.prepend(noSpawn, USER);
        assertEquals(3, noSpawn.size());
        assertEquals(base, text(noSpawn.get(0)));
        assertEquals(AgentPrompts.EVAL_FORCE_NO_SPAWN_MSG, text(noSpawn.get(1)));
        assertEquals("hi", text(noSpawn.get(2)));
        assertFalse(noSpawn.stream().anyMatch(m -> AgentPrompts.EVAL_FORCE_DISPATCH_MSG.equals(text(m))));

        f.subagent.setEvalForceMode("off");
        List<Message> off = new ArrayList<>(List.of(new UserMessage("hi")));
        f.prepend(off, USER);
        assertEquals(2, off.size());
        assertEquals(base, text(off.get(0)));
        assertEquals("hi", text(off.get(1)));
    }

    @Test
    @DisplayName("recordTurn/prompt_snapshot 使用当前用户基础提示，动态部分只含动态注入")
    void recordTurnUsesActualUserPromptForSnapshot() throws Exception {
        Fixture f = new Fixture();
        f.subagent.setEvalForceMode("off");
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("hi")));
        String base = f.prepend(messages, USER);
        messages.add(new SystemMessage("DYNAMIC-USER-MEMORY"));

        f.recordTurn(messages, USER, base);

        AgentTurnRecord row = f.turns.row;
        assertNotNull(row, "recordTurn 应写入一行 agent_turn");
        assertEquals(PromptCanonical.sha256(base), row.systemPromptSha256(),
                "静态提示哈希必须对应当前用户实际收到的基础提示");
        assertEquals(PromptCanonical.sha256("DYNAMIC-USER-MEMORY"), row.systemDynamicSha256(),
                "动态哈希只能包含动态注入，不能把用户级基础提示算进去");

        String snapshot = f.snapshots.byKind.get(AgentTurnRecorder.KIND_SYSTEM_PROMPT);
        assertNotNull(snapshot, "应写入 SYSTEM_PROMPT 快照");
        assertEquals(base, snapshot);
        assertTrue(snapshot.contains("user-42-server-A"), snapshot);
        assertFalse(snapshot.contains("default-server（部署）"), snapshot);
        assertEquals(row.systemPromptSha256(),
                f.snapshots.shaByKind.get(AgentTurnRecorder.KIND_SYSTEM_PROMPT),
                "快照 insertIgnore 的 sha256 必须与 agent_turn 的静态哈希一致");
        assertTrue(row.estTokensSystem() > 0);
        assertTrue(row.estTokensInjected() > 0);
    }

    @Test
    @DisplayName("MCP server 行在请求中途变化时，recordTurn 仍使用最初发送的基础提示")
    void recordTurnKeepsCapturedBaseWhenMcpChanges() throws Exception {
        Fixture f = new Fixture();
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("hi")));
        String baseA = f.prepend(messages, USER);
        assertTrue(baseA.contains("user-42-server-A"), baseA);

        // 模拟请求中途 MCP 生命周期变化：后续 systemPrompt(userId) 会看到 server-B。
        f.mcp.setUserServerLine("user-42-server-B");
        assertTrue(f.systemPrompt(USER).contains("user-42-server-B"),
                "前置：MCP 快照必须已切到 server-B，否则本用例对「重算提示」的实现会假通过");
        messages.add(new SystemMessage("DYNAMIC-USER-MEMORY"));

        f.recordTurn(messages, USER, baseA);

        AgentTurnRecord row = f.turns.row;
        assertNotNull(row, "recordTurn 应写入一行 agent_turn");
        assertEquals(PromptCanonical.sha256(baseA), row.systemPromptSha256(),
                "静态哈希必须对应最初发送的 server-A 基础提示");
        assertEquals(PromptCanonical.sha256("DYNAMIC-USER-MEMORY"), row.systemDynamicSha256(),
                "动态哈希不能包含重复的基础提示内容");

        String snapshot = f.snapshots.byKind.get(AgentTurnRecorder.KIND_SYSTEM_PROMPT);
        assertNotNull(snapshot, "应写入 SYSTEM_PROMPT 快照");
        assertEquals(baseA, snapshot);
        assertTrue(snapshot.contains("user-42-server-A"), snapshot);
        assertFalse(snapshot.contains("user-42-server-B"), snapshot);
        assertEquals(row.systemPromptSha256(),
                f.snapshots.shaByKind.get(AgentTurnRecorder.KIND_SYSTEM_PROMPT));
    }

    // ==================== 测试替身 ====================

    /** 按 userId 返回不同 serverLines；可切换用户行，模拟请求中途 MCP 生命周期变化。 */
    private static final class FakeMcpManager extends McpClientManager {
        private String userServerLine = "user-42-server-A";

        FakeMcpManager(McpProperties props) {
            super(props);
        }

        void setUserServerLine(String line) {
            this.userServerLine = line;
        }

        @Override
        public List<String> serverLines(long userId) {
            return userId == USER
                    ? List.of(userServerLine)
                    : List.of("default-server（部署）");
        }
    }

    private static final class CapturingTurnMapper implements AgentTurnMapper {
        AgentTurnRecord row;

        @Override
        public void insert(AgentTurnRecord record) {
            row = record;
        }

        @Override
        public Integer latestPendingTurnIndex(String conversationId) {
            return null;
        }
    }

    private static final class CapturingSnapshotMapper implements PromptSnapshotMapper {
        final Map<String, String> byKind = new LinkedHashMap<>();
        final Map<String, String> shaByKind = new LinkedHashMap<>();

        @Override
        public void insertIgnore(String sha256, String kind, String content) {
            byKind.putIfAbsent(kind, content);
            shaByKind.putIfAbsent(kind, sha256);
        }
    }

    private static final class Fixture {
        final SubagentProperties subagent = new SubagentProperties();
        final UserMemoryProperties userMemory = new UserMemoryProperties();
        final MemoryProperties memory = new MemoryProperties();
        final CapturingTurnMapper turns = new CapturingTurnMapper();
        final CapturingSnapshotMapper snapshots = new CapturingSnapshotMapper();
        final FakeMcpManager mcp;
        final ChatController controller;

        Fixture() throws Exception {
            McpProperties props = new McpProperties();
            props.setEnabled(true);
            props.setToolLoading("deferred");
            mcp = new FakeMcpManager(props);
            UserMemoryInjector injector = new UserMemoryInjector(null, userMemory);
            AgentTurnRecorder recorder = new AgentTurnRecorder(turns, snapshots);
            controller = newController(mcp, subagent, userMemory, injector, memory, recorder);
        }

        String systemPrompt(long userId) throws Exception {
            Method m = ChatController.class.getDeclaredMethod("systemPrompt", long.class);
            m.setAccessible(true);
            return (String) m.invoke(controller, userId);
        }

        String prepend(List<Message> messages, Long userId) throws Exception {
            Method m = ChatController.class.getDeclaredMethod(
                    "prependSystemAndUserMemory", List.class, Long.class);
            m.setAccessible(true);
            return (String) m.invoke(controller, messages, userId);
        }

        void recordTurn(List<Message> messages, Long userId, String baseSystemPrompt) throws Exception {
            Method m = ChatController.class.getDeclaredMethod("recordTurn",
                    String.class, Long.class, int.class, OpenAiChatModel.class, ToolCallback[].class,
                    List.class, String.class, ContextCompactor.CompactStats.class, boolean.class,
                    int.class, long.class, long.class, UsageTracker.class, String.class,
                    RoundCompactor.Guard.class,
                    org.springframework.ai.chat.model.ChatResponse.class);
            m.setAccessible(true);
            m.invoke(controller, "conv", userId, 0, null, new ToolCallback[0], messages,
                    baseSystemPrompt,
                    new ContextCompactor.CompactStats(false, 0, 0, 0), false,
                    0, 0L, 0L, new UsageTracker(), AgentTurnRecord.END_NORMAL_STOP, null, null);
        }
    }

    /** 只填本测试实际用到的依赖；其余构造参数保持 null。 */
    private static ChatController newController(McpClientManager mcp, SubagentProperties subagent,
                                                UserMemoryProperties userMemory,
                                                UserMemoryInjector injector,
                                                MemoryProperties memory,
                                                AgentTurnRecorder recorder) throws Exception {
        Constructor<?> ctor = ChatController.class.getConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == McpClientManager.class) args[i] = mcp;
            else if (types[i] == SubagentProperties.class) args[i] = subagent;
            else if (types[i] == UserMemoryProperties.class) args[i] = userMemory;
            else if (types[i] == UserMemoryInjector.class) args[i] = injector;
            else if (types[i] == MemoryProperties.class) args[i] = memory;
            else if (types[i] == AgentTurnRecorder.class) args[i] = recorder;
        }
        return (ChatController) ctor.newInstance(args);
    }

    private static List<String> systemTexts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof SystemMessage sm && sm.getText() != null) out.add(sm.getText());
        }
        return out;
    }

    private static String text(Message m) {
        return m == null ? null : m.getText();
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
