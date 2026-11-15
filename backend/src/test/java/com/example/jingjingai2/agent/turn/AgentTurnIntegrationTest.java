package com.example.jingjingai2.agent.turn;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.mapper.AgentTurnMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收 2、3、4：用模型替身（不调用真实模型）跑对话，核对 agent_turn、prompt_snapshot、tool_call_log。
 *
 * <p>不连数据库。{@link AgentTurnRecorder} 只依赖两个 Mapper 接口，这里用内存实现替身；
 * tool_call_log 同样用内存列表。对话循环是把 ChatController 里「模型返回 → recordTurn →
 * 执行工具 → 写 tool_call_log」这段顺序按原样搬出来的最小版本，流式和非流式各走一遍。
 */
class AgentTurnIntegrationTest {

    // ==================== 内存版的两张表和工具日志 ====================

    static final class TurnTable implements AgentTurnMapper {
        final List<AgentTurnRecord> rows = new ArrayList<>();
        @Override public void insert(AgentTurnRecord r) { rows.add(r); }
        @Override public Integer latestPendingTurnIndex(String conversationId) {
            Integer found = null;
            for (AgentTurnRecord r : rows) {
                if (conversationId != null && conversationId.equals(r.conversationId())
                        && AgentTurnRecord.END_APPROVAL_PENDING.equals(r.endReason())) {
                    found = r.turnIndex();
                }
            }
            return found;
        }
    }

    static final class SnapshotTable {
        record Row(String sha256, String kind, String content) {}
        final List<Row> rows = new ArrayList<>();
        void insertIgnore(String sha256, String kind, String content) {
            if (rows.stream().noneMatch(r -> r.sha256.equals(sha256))) {
                rows.add(new Row(sha256, kind, content));
            }
        }
        Row find(String sha256) {
            return rows.stream().filter(r -> r.sha256.equals(sha256)).findFirst().orElse(null);
        }
    }

    static final class ToolLog {
        record Row(String conversationId, String toolName, int turnIndex) {}
        final List<Row> rows = new ArrayList<>();
    }

    /** 与 ChatController.recordTurn 同一组字段、同一调用顺序，只是把控制器依赖换成入参。 */
    static void recordTurn(AgentTurnRecorder recorder, String conv, Long user, int turn,
                           String model, ToolCallback[] tools, List<Message> messages,
                           ContextCompactor.CompactStats stats, boolean compacted,
                           Integer inputTokens, Integer outputTokens, String endReason) {
        boolean thisRound = compacted && turn == 0 && stats.compacted();
        String staticPrompt = null;
        if (messages != null) {
            for (Message m : messages) {
                if (m instanceof SystemMessage && m.getText() != null) {
                    staticPrompt = m.getText();
                    break;
                }
            }
        }
        String toolsJson = PromptCanonical.toolsJson(tools);
        TurnState state = new TurnState(conv, user, turn, TurnState.PHASE_MODEL_TURN, model,
                staticPrompt, TurnState.dynamicText(staticPrompt, messages), toolsJson,
                thisRound ? TurnState.COMPACT_SUMMARY : TurnState.COMPACT_NONE,
                thisRound ? stats.tokensBeforeEst() : null,
                thisRound ? stats.tokensAfterEst() : null,
                thisRound ? stats.compactedCount() : 0,
                inputTokens, outputTokens, messages, endReason);
        recorder.recordTurn(state);
    }

    private static String systemText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m instanceof SystemMessage && m.getText() != null) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(m.getText());
            }
        }
        return sb.toString();
    }

    // ==================== 模型替身：按脚本返回，可带 usage，支持流式 ====================

    static final class ScriptedModel implements ChatModel {
        private final List<ChatResponse> script = new ArrayList<>();
        private int cursor = 0;
        void add(ChatResponse r) { script.add(r); }
        @Override public ChatResponse call(Prompt prompt) {
            return script.get(Math.min(cursor++, script.size() - 1));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            ChatResponse whole = call(prompt);
            // 模拟流式：拆成两个分片，usage 只在最后一片给出（很多兼容接口的真实行为）
            AssistantMessage am = whole.getResult().getOutput();
            ChatResponse first = new ChatResponse(List.of(new Generation(
                    new AssistantMessage(am.getText() == null ? "" : am.getText(), Map.of(), List.of()))));
            return Flux.just(first, whole);
        }
    }

    private static ChatResponse reply(String text, Integer promptTokens, Integer completionTokens) {
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(usage(promptTokens, completionTokens)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), meta);
    }

    private static ChatResponse toolCall(String name, String args, Integer promptTokens, Integer completionTokens) {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("c1", "function", name, args);
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(usage(promptTokens, completionTokens)).build();
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage("", Map.of(), List.of(tc), List.of()))), meta);
    }

    /** promptTokens 为 null 时返回一个全空的 usage，模拟"这一片没带用量"。 */
    private static Usage usage(Integer promptTokens, Integer completionTokens) {
        return new Usage() {
            @Override public Integer getPromptTokens() { return promptTokens; }
            @Override public Integer getCompletionTokens() { return completionTokens; }
            @Override public Object getNativeUsage() { return null; }
        };
    }

    private static ToolCallback tool(String name, String description) {
        ToolDefinition def = new ToolDefinition() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
        };
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return "工具[" + name + "]执行结果"; }
        };
    }

    private static AgentTurnRecorder recorder(TurnTable turns, SnapshotTable snaps) {
        return new AgentTurnRecorder(turns, snaps::insertIgnore);
    }

    /** 跑一轮循环：模型返回 → 记录 → 有工具就执行并写 tool_call_log → 把结果追加进消息。 */
    private static void runLoop(ScriptedModel model, boolean streaming, AgentTurnRecorder recorder,
                                ToolLog toolLog, String conv, List<Message> messages,
                                ToolCallback[] tools, ContextCompactor.CompactStats stats, boolean compacted) {
        for (int turn = 0; turn < 4; turn++) {
            ChatResponse resp = streaming ? model.stream(new Prompt(messages)).blockLast()
                    : model.call(new Prompt(messages));
            AssistantMessage am = resp.getResult().getOutput();
            List<AssistantMessage.ToolCall> tcs = am.getToolCalls() == null ? List.of() : am.getToolCalls();
            Usage u = resp.getMetadata().getUsage();
            Integer in = (u == null || u.getPromptTokens() == null) ? null : u.getPromptTokens();
            Integer out = (u == null || u.getCompletionTokens() == null) ? null : u.getCompletionTokens();
            // 全零用量按"没拿到"处理，与 UsageTracker 的口径一致
            if (Integer.valueOf(0).equals(in) && Integer.valueOf(0).equals(out)) { in = null; out = null; }
            String end = tcs.isEmpty() ? AgentTurnRecord.END_NORMAL_STOP : AgentTurnRecord.END_TOOL_CALLING;
            recordTurn(recorder, conv, 7L, turn, "stub-model", tools, messages, stats, compacted, in, out, end);
            if (tcs.isEmpty()) break;
            messages.add(am);
            for (AssistantMessage.ToolCall tc : tcs) {
                String result = tools[0].call(tc.arguments());
                toolLog.rows.add(new ToolLog.Row(conv, tc.name(), turn));
                messages.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result))));
            }
        }
    }

    @Test
    @DisplayName("调工具的对话：一行 agent_turn，哈希能查回全文，工具名对得上 tool_call_log")
    void toolCallingConversation() {
        TurnTable turns = new TurnTable();
        SnapshotTable snaps = new SnapshotTable();
        ToolLog toolLog = new ToolLog();
        ToolCallback[] tools = {tool("readFile", "读取工作区内的文件")};

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是代码助手，需要时调用工具。"));
        messages.add(new UserMessage("读一下 README"));

        ScriptedModel model = new ScriptedModel();
        model.add(toolCall("readFile", "{\"path\":\"README.md\"}", 300, 15));
        model.add(reply("README 的内容是项目说明。", 420, 30));

        runLoop(model, false, recorder(turns, snaps), toolLog, "conv-tools", messages, tools,
                new ContextCompactor.CompactStats(false, 0, 0, 0), false);

        assertEquals(2, turns.rows.size(), "一轮工具 + 一轮最终回答 = 两行");
        AgentTurnRecord first = turns.rows.get(0);
        assertEquals("TOOL_CALLING", first.endReason());
        assertEquals(0, first.turnIndex());
        assertEquals(300, first.inputTokens());
        assertEquals(15, first.outputTokens());
        assertEquals("NONE", first.compactMode());

        SnapshotTable.Row prompt = snaps.find(first.systemPromptSha256());
        assertNotNull(prompt, "system_prompt_sha256 应能在 prompt_snapshot 查到");
        assertEquals("SYSTEM_PROMPT", prompt.kind);
        assertEquals("你是代码助手，需要时调用工具。", prompt.content);

        SnapshotTable.Row toolsRow = snaps.find(first.toolsSha256());
        assertNotNull(toolsRow);
        assertEquals("TOOLS", toolsRow.kind);
        assertTrue(toolsRow.content.contains("读取工作区内的文件"), "工具表全文含描述，不只是名字");

        // 同一 turn_index 对上 tool_call_log
        List<ToolLog.Row> matched = toolLog.rows.stream()
                .filter(r -> r.conversationId.equals(first.conversationId()) && r.turnIndex == first.turnIndex())
                .toList();
        assertEquals(List.of("readFile"), matched.stream().map(r -> r.toolName).toList());

        AgentTurnRecord second = turns.rows.get(1);
        assertEquals("NORMAL_STOP", second.endReason());
        assertEquals(first.systemPromptSha256(), second.systemPromptSha256(), "同一提示只存一次");
        assertEquals(1, snaps.rows.stream().filter(r -> r.kind.equals("SYSTEM_PROMPT")).count());
        dump("conv-tools", turns, snaps, toolLog);
    }

    @Test
    @DisplayName("流式路径同样落记录；usage 只在最后一片时取得到，没有 usage 时为 null")
    void streamingPathRecordsAndNullUsage() {
        TurnTable turns = new TurnTable();
        SnapshotTable snaps = new SnapshotTable();
        ToolCallback[] tools = {tool("readFile", "读文件")};

        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("系统"), new UserMessage("你好")));
        ScriptedModel model = new ScriptedModel();
        model.add(reply("你好，有什么可以帮你。", null, null));   // 流式分片不带 usage

        runLoop(model, true, recorder(turns, snaps), new ToolLog(), "conv-stream", messages, tools,
                new ContextCompactor.CompactStats(false, 0, 0, 0), false);

        assertEquals(1, turns.rows.size());
        assertNull(turns.rows.get(0).inputTokens(), "流式没带回 usage 时应为 NULL");
        assertNull(turns.rows.get(0).outputTokens());
        assertNotNull(snaps.find(turns.rows.get(0).systemPromptSha256()));
    }

    @Test
    @DisplayName("预算调小触发压缩：记录里有压缩前后 token 和被压缩条数")
    void compactionRecorded() {
        // 预算极小，历史必然超预算 → 走摘要分支。summarize 用的模型替身不联网。
        org.springframework.ai.openai.OpenAiChatModel unused = null;
        ContextCompactor compactor = new ContextCompactor(unused, 30);
        List<MessageRecord> history = new ArrayList<>();
        String longText = "这是一段足够长的历史，用来把估算 token 撑过摘要截断的上限。".repeat(30);
        for (int i = 0; i < 6; i++) {
            history.add(new MessageRecord("user", "第" + i + "轮问题：" + longText));
            history.add(new MessageRecord("assistant", "第" + i + "轮回答：" + longText));
        }
        List<Message> built = compactor.buildMessages(history, "现在这个新问题是什么情况");
        assertFalse(compactor.lastStats().compacted(), "没有摘要模型时不能把失败记成压缩成功");

        TurnTable turns = new TurnTable();
        SnapshotTable snaps = new SnapshotTable();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new SystemMessage("[历史摘要] 更早的对话已压缩"));
        messages.addAll(built);
        ScriptedModel model = new ScriptedModel();
        model.add(reply("基于摘要的回答", 200, 40));
        ContextCompactor.CompactStats stats = new ContextCompactor.CompactStats(true, 1000, 200, 4);
        runLoop(model, false, recorder(turns, snaps), new ToolLog(), "conv-compact", messages,
                new ToolCallback[]{tool("readFile", "读文件")}, stats, true);

        AgentTurnRecord r = turns.rows.get(0);
        assertEquals("SUMMARY", r.compactMode());
        assertEquals(stats.tokensBeforeEst(), r.tokensBeforeCompactEst());
        assertEquals(stats.tokensAfterEst(), r.tokensAfterCompactEst());
        assertEquals(stats.compactedCount(), r.compactedMessageCount());
        assertNotNull(r.systemDynamicSha256(), "历史摘要应进动态哈希");
        SnapshotTable.Row snap = snaps.find(r.systemPromptSha256());
        assertEquals("系统提示", snap.content);
        assertFalse(snap.content.contains("[历史摘要]"), "静态快照不含历史摘要");
        assertFalse(snaps.rows.stream().anyMatch(row -> row.content.contains("[历史摘要]")),
                "prompt_snapshot 不存动态注入全文");
        dump("conv-compact", turns, snaps, new ToolLog());
    }

    @Test
    @DisplayName("审批挂起 → 批准、审批挂起 → 拒绝：各补一行且 token 列全为 null")
    void approvalResolvedRows() {
        TurnTable turns = new TurnTable();
        SnapshotTable snaps = new SnapshotTable();
        AgentTurnRecorder recorder = recorder(turns, snaps);

        // 挂起那一轮
        recordTurn(recorder, "conv-approve", 7L, 2, "stub-model",
                new ToolCallback[]{tool("executeCommand", "执行命令")},
                new ArrayList<>(List.of(new SystemMessage("系统"), new UserMessage("删掉 a.txt"))),
                new ContextCompactor.CompactStats(false, 0, 0, 0), false,
                150, 10, AgentTurnRecord.END_APPROVAL_PENDING);
        // 用户批准：补行，token 列全部 null
        recorder.recordTurn(new TurnState("conv-approve", 7L, 2, TurnState.PHASE_APPROVAL_RESOLVED,
                null, null, null, null, TurnState.COMPACT_NONE, null, null, null, null, null,
                List.of(), AgentTurnRecord.END_APPROVED_EXECUTED));

        recordTurn(recorder, "conv-reject", 7L, 1, "stub-model",
                new ToolCallback[]{tool("executeCommand", "执行命令")},
                new ArrayList<>(List.of(new SystemMessage("系统"), new UserMessage("删掉 b.txt"))),
                new ContextCompactor.CompactStats(false, 0, 0, 0), false,
                150, 10, AgentTurnRecord.END_APPROVAL_PENDING);
        recorder.recordTurn(new TurnState("conv-reject", 7L, 1, TurnState.PHASE_APPROVAL_RESOLVED,
                null, null, null, null, TurnState.COMPACT_NONE, null, null, null, null, null,
                List.of(), AgentTurnRecord.END_USER_REJECTED));

        AgentTurnRecord approved = turns.rows.stream()
                .filter(r -> "conv-approve".equals(r.conversationId())
                        && "APPROVAL_RESOLVED".equals(r.phase())).findFirst().orElseThrow();
        assertEquals("APPROVED_EXECUTED", approved.endReason());
        assertEquals(2, approved.turnIndex(), "补行沿用挂起那一轮的 turn_index");
        assertNull(approved.inputTokens());
        assertNull(approved.estTokensSystem());

        AgentTurnRecord rejected = turns.rows.stream()
                .filter(r -> "conv-reject".equals(r.conversationId())
                        && "APPROVAL_RESOLVED".equals(r.phase())).findFirst().orElseThrow();
        assertEquals("USER_REJECTED", rejected.endReason());
        assertNull(rejected.outputTokens());
        assertNull(rejected.estTokensHistory());
        dump("conv-approve", turns, snaps, new ToolLog());
        dump("conv-reject", turns, snaps, new ToolLog());
    }

    @Test
    @DisplayName("新的 recorder 实例（模拟重启）仍能按库里的 APPROVAL_PENDING 补上轮次；查不到不写行")
    void approvalTurnSurvivesNewRecorder() {
        TurnTable turns = new TurnTable();
        SnapshotTable snaps = new SnapshotTable();
        AgentTurnRecorder first = recorder(turns, snaps);
        recordTurn(first, "conv-restart", 7L, 4, "stub-model",
                new ToolCallback[]{tool("executeCommand", "执行命令")},
                new ArrayList<>(List.of(new SystemMessage("系统"), new UserMessage("删掉 c.txt"))),
                new ContextCompactor.CompactStats(false, 0, 0, 0), false,
                10, 2, AgentTurnRecord.END_APPROVAL_PENDING);

        AgentTurnRecorder restarted = recorder(turns, snaps);
        restarted.recordApprovalResolved("conv-restart", 7L, AgentTurnRecord.END_APPROVED_EXECUTED);
        AgentTurnRecord row = turns.rows.get(turns.rows.size() - 1);
        assertEquals(4, row.turnIndex());
        assertEquals("APPROVAL_RESOLVED", row.phase());
        assertEquals("APPROVED_EXECUTED", row.endReason());
        assertNull(row.inputTokens());

        int size = turns.rows.size();
        restarted.recordApprovalResolved("no-such-conv", 7L, AgentTurnRecord.END_USER_REJECTED);
        assertEquals(size, turns.rows.size(), "查不到挂起轮次时不应写入");
    }

    @Test
    @DisplayName("记录写入失败只打日志，不抛出，对话继续")
    void writeFailureDoesNotBreakConversation() {
        AgentTurnRecorder recorder = new AgentTurnRecorder(new AgentTurnMapper() {
            @Override public void insert(AgentTurnRecord r) { throw new RuntimeException("数据库挂了"); }
            @Override public Integer latestPendingTurnIndex(String conversationId) { return null; }
        }, (sha, kind, content) -> { throw new RuntimeException("数据库挂了"); });
        assertDoesNotThrow(() -> recorder.recordTurn(new TurnState(
                "conv", 7L, 0, TurnState.PHASE_MODEL_TURN, "m", "p", null, "[]",
                TurnState.COMPACT_NONE, null, null, 0, null, null, List.of(),
                AgentTurnRecord.END_NORMAL_STOP)));
    }

    /** 把内存表按即将对真实库执行的 SQL 打出来，供验收对照。不连数据库。 */
    private static void dump(String conv, TurnTable turns, SnapshotTable snaps, ToolLog toolLog) {
        System.out.println("SELECT * FROM agent_turn WHERE conversation_id='" + conv + "';");
        for (AgentTurnRecord r : turns.rows) {
            if (!conv.equals(r.conversationId())) continue;
            System.out.println("  turn_index=" + r.turnIndex()
                    + " phase=" + r.phase()
                    + " model_name=" + r.modelName()
                    + " system_prompt_sha256=" + r.systemPromptSha256()
                    + " tools_sha256=" + r.toolsSha256()
                    + " compact_mode=" + r.compactMode()
                    + " tokens_before_compact_est=" + r.tokensBeforeCompactEst()
                    + " tokens_after_compact_est=" + r.tokensAfterCompactEst()
                    + " compacted_message_count=" + r.compactedMessageCount()
                    + " input_tokens=" + r.inputTokens()
                    + " output_tokens=" + r.outputTokens()
                    + " est_system=" + r.estTokensSystem()
                    + " est_tools=" + r.estTokensTools()
                    + " est_history=" + r.estTokensHistory()
                    + " est_tool_results=" + r.estTokensToolResults()
                    + " end_reason=" + r.endReason());
        }
        System.out.println("SELECT kind, content FROM prompt_snapshot WHERE sha256 IN ("
                + "SELECT system_prompt_sha256 FROM agent_turn WHERE conversation_id='" + conv + "');");
        for (AgentTurnRecord r : turns.rows) {
            if (!conv.equals(r.conversationId()) || r.systemPromptSha256() == null) continue;
            SnapshotTable.Row row = snaps.find(r.systemPromptSha256());
            if (row == null) continue;
            String content = row.content.length() > 80 ? row.content.substring(0, 80) + "..." : row.content;
            System.out.println("  kind=" + row.kind + " sha256=" + row.sha256 + " content=" + content);
            break;
        }
        System.out.println("SELECT tool_name FROM tool_call_log WHERE conversation_id='" + conv + "';");
        for (ToolLog.Row row : toolLog.rows) {
            if (conv.equals(row.conversationId)) {
                System.out.println("  tool_name=" + row.toolName + " turn_index=" + row.turnIndex);
            }
        }
    }
}
