package com.example.jingjingai2.agent.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收 1：给定 TurnState，生成的记录必填字段齐全；拿不到 usage 时是 null；
 * 同样的提示和工具表哈希相同，改一个字哈希不同；只改某个工具的描述，tools_sha256 也变化；
 * 四个估算列之和与总估算一致。
 */
class AgentTurnRecordTest {

    private static ToolCallback tool(String name, String description, String schema) {
        ToolDefinition def = new ToolDefinition() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public String inputSchema() { return schema; }
        };
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return ""; }
        };
    }

    private static TurnState state(String prompt, String toolsJson, Integer in, Integer out,
                                   List<Message> messages) {
        return new TurnState("conv-1", 7L, 0, TurnState.PHASE_MODEL_TURN, "deepseek-v4-flash",
                prompt, TurnState.dynamicText(prompt, messages), toolsJson, TurnState.COMPACT_NONE,
                null, null, 0, in, out, messages, AgentTurnRecord.END_TOOL_CALLING);
    }

    @Test
    @DisplayName("必填字段齐全：会话、用户、轮次、阶段、两个哈希、结束原因都有值")
    void requiredFieldsPresent() {
        AgentTurnRecord r = AgentTurnRecord.from(state(
                "你是助手", PromptCanonical.toolsJson(new ToolCallback[]{tool("readFile", "读文件", "{}")}),
                100, 20, List.of(new SystemMessage("你是助手"), new UserMessage("你好"))));

        assertEquals("conv-1", r.conversationId());
        assertEquals(7L, r.userId());
        assertEquals(0, r.turnIndex());
        assertEquals("MODEL_TURN", r.phase());
        assertEquals("deepseek-v4-flash", r.modelName());
        assertNotNull(r.systemPromptSha256());
        assertEquals(64, r.systemPromptSha256().length());
        assertNotNull(r.toolsSha256());
        assertEquals(64, r.toolsSha256().length());
        assertEquals("NONE", r.compactMode());
        assertEquals("TOOL_CALLING", r.endReason());
        assertEquals(100, r.inputTokens());
        assertEquals(20, r.outputTokens());
    }

    @Test
    @DisplayName("拿不到 usage 时 input/output 是 null，不是 0")
    void missingUsageStaysNull() {
        AgentTurnRecord r = AgentTurnRecord.from(state("p", "[]", null, null, List.of()));
        assertNull(r.inputTokens(), "拿不到用量应写 NULL");
        assertNull(r.outputTokens(), "拿不到用量应写 NULL");
    }

    @Test
    @DisplayName("同样的提示哈希相同，改一个字哈希不同")
    void promptHashSensitiveToOneChar() {
        String a = PromptCanonical.sha256("你是一个代码助手");
        String b = PromptCanonical.sha256("你是一个代码助手");
        String c = PromptCanonical.sha256("你是一个编码助手");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("只改某个工具的描述，tools_sha256 就变；工具顺序和 schema 键序不影响哈希")
    void toolsHashCoversDescriptionNotJustNames() {
        String schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
        String schemaReordered = "{\"properties\":{\"path\":{\"type\":\"string\"}},\"type\":\"object\"}";
        ToolCallback[] one = {tool("readFile", "读取文件内容", schema), tool("writeFile", "写文件", "{}")};
        ToolCallback[] reordered = {tool("writeFile", "写文件", "{}"), tool("readFile", "读取文件内容", schemaReordered)};
        ToolCallback[] descChanged = {tool("readFile", "读取文件的全部内容", schema), tool("writeFile", "写文件", "{}")};

        String h1 = PromptCanonical.sha256(PromptCanonical.toolsJson(one));
        String h2 = PromptCanonical.sha256(PromptCanonical.toolsJson(reordered));
        String h3 = PromptCanonical.sha256(PromptCanonical.toolsJson(descChanged));

        assertEquals(h1, h2, "工具顺序和 schema 键序不同但语义相同，哈希应相同");
        assertNotEquals(h1, h3, "只改描述，哈希必须变化");
        assertFalse(PromptCanonical.toolsJson(one).contains("读取文件的全部内容"));
    }

    @Test
    @DisplayName("五个估算列之和等于总估算；静态与动态分开")
    void estimateColumnsSumToTotal() {
        String staticPrompt = "系统提示：你是助手";
        List<Message> messages = List.of(
                new SystemMessage(staticPrompt),
                new SystemMessage("【用户记忆】\n喜欢简短回答"),
                new UserMessage("帮我读一下 README"),
                new AssistantMessage("好的，我来读"),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "readFile", "这是文件内容，共十二个字。"))));
        String toolsJson = PromptCanonical.toolsJson(new ToolCallback[]{tool("readFile", "读文件", "{}")});

        TurnState.EstimateSplit split = TurnState.split(staticPrompt, messages, toolsJson);
        AgentTurnRecord r = AgentTurnRecord.from(state(staticPrompt, toolsJson, 10, 5, messages));

        assertEquals(split.system(), r.estTokensSystem());
        assertEquals(split.injected(), r.estTokensInjected());
        assertEquals(split.tools(), r.estTokensTools());
        assertEquals(split.history(), r.estTokensHistory());
        assertEquals(split.toolResults(), r.estTokensToolResults());
        assertEquals(split.total(),
                r.estTokensSystem() + r.estTokensInjected() + r.estTokensTools()
                        + r.estTokensHistory() + r.estTokensToolResults(),
                "五个估算列之和应与总估算严格相等");
        assertTrue(r.estTokensSystem() > 0 && r.estTokensInjected() > 0 && r.estTokensHistory() > 0
                && r.estTokensToolResults() > 0 && r.estTokensTools() > 0);
        assertNotEquals(r.systemPromptSha256(), r.systemDynamicSha256());
    }

    @Test
    @DisplayName("同一段对话里注入变化：静态哈希不变，动态哈希变")
    void dynamicInjectionChangesOnlyDynamicHash() {
        String staticPrompt = "你是助手";
        String tools = PromptCanonical.toolsJson(new ToolCallback[]{tool("readFile", "读文件", "{}")});
        List<Message> first = List.of(new SystemMessage(staticPrompt),
                new SystemMessage("【用户记忆】\n名字是甲"), new UserMessage("hi"));
        List<Message> second = List.of(new SystemMessage(staticPrompt),
                new SystemMessage("【用户记忆】\n名字是乙"), new UserMessage("hi"));
        AgentTurnRecord a = AgentTurnRecord.from(state(staticPrompt, tools, 1, 1, first));
        AgentTurnRecord b = AgentTurnRecord.from(state(staticPrompt, tools, 1, 1, second));
        assertEquals(a.systemPromptSha256(), b.systemPromptSha256());
        assertNotEquals(a.systemDynamicSha256(), b.systemDynamicSha256());
    }

    @Test
    @DisplayName("审批补行：phase=APPROVAL_RESOLVED，所有 token 列都是 null")
    void approvalRowNullsAllTokenColumns() {
        TurnState s = new TurnState("conv-1", 7L, 3, TurnState.PHASE_APPROVAL_RESOLVED,
                null, null, null, null, TurnState.COMPACT_NONE,
                999, 999, 5, 100, 20, List.of(new UserMessage("不该被统计")),
                AgentTurnRecord.END_APPROVED_EXECUTED);
        AgentTurnRecord r = AgentTurnRecord.from(s);

        assertEquals("APPROVAL_RESOLVED", r.phase());
        assertEquals("APPROVED_EXECUTED", r.endReason());
        assertEquals(3, r.turnIndex());
        assertNull(r.inputTokens());
        assertNull(r.outputTokens());
        assertNull(r.tokensBeforeCompactEst());
        assertNull(r.tokensAfterCompactEst());
        assertNull(r.compactedMessageCount());
        assertNull(r.estTokensSystem());
        assertNull(r.estTokensInjected());
        assertNull(r.estTokensTools());
        assertNull(r.estTokensHistory());
        assertNull(r.estTokensToolResults());
    }

    @Test
    @DisplayName("压缩那一轮带上压缩前后估算和被压缩条数")
    void compactionStatsCarried() {
        TurnState s = new TurnState("conv-1", 7L, 0, TurnState.PHASE_MODEL_TURN, "m",
                "p", null, "[]", TurnState.COMPACT_SUMMARY,
                5000, 1800, 12, null, null, List.of(), AgentTurnRecord.END_NORMAL_STOP);
        AgentTurnRecord r = AgentTurnRecord.from(s);
        assertEquals("SUMMARY", r.compactMode());
        assertEquals(5000, r.tokensBeforeCompactEst());
        assertEquals(1800, r.tokensAfterCompactEst());
        assertEquals(12, r.compactedMessageCount());
    }
}
