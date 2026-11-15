package com.example.jingjingai2.agent.turn;

import com.example.jingjingai2.context.PromptView;
import com.example.jingjingai2.context.TokenEstimator;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.utils.PlanInjectionMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3B：分类估算和工具结果旁路记录。只读消息，不改内容。
 */
class ContextObservabilityTest {

    private static final String STATIC = "系统提示：你是助手";

    @Test
    @DisplayName("injected 六个子列之和等于原 injected 总估算")
    void injectedPartsSumToInjected() {
        List<Message> messages = List.of(
                new SystemMessage(STATIC),
                new SystemMessage("[历史摘要] 更早的对话"),
                new SystemMessage("【用户记忆】\n喜欢简短回答"),
                new SystemMessage("【长期记忆】以下来自工作区 memory/ 下的 markdown"),
                new PlanInjectionMessage("plan-1", "当前计划：第一步"),
                new SystemMessage("🧭 我观察到：修改了 3 个文件。请立刻用 createPlan。"),
                new SystemMessage("这段没有固定开头，不能归类"),
                new UserMessage("帮我读一下 README"),
                new AssistantMessage("好的"),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "readFile", "文件内容"))));

        TurnState.EstimateSplit split = TurnState.split(STATIC, messages, "[]");

        assertEquals(split.injected(), split.injectedParts());
        assertTrue(split.historySummary() > 0);
        assertTrue(split.userMemory() > 0);
        assertTrue(split.workspaceMemory() > 0);
        assertTrue(split.plan() > 0);
        assertTrue(split.nudge() > 0);
        assertTrue(split.unknown() > 0);
        assertEquals(TokenEstimator.estimate(STATIC), split.system());
    }

    @Test
    @DisplayName("静态系统提示只算一次，同文的第二条进 unknown，不进 system")
    void staticPromptBoundary() {
        List<Message> messages = List.of(
                new SystemMessage(STATIC),
                new SystemMessage(STATIC));
        TurnState.EstimateSplit split = TurnState.split(STATIC, messages, "");
        assertEquals(TokenEstimator.estimate(STATIC), split.system());
        assertEquals(TokenEstimator.estimate(STATIC), split.injected());
        assertEquals(split.injected(), split.unknown());
        assertEquals(0, split.injectedParts() - split.unknown());
    }

    @Test
    @DisplayName("PlanInjectionMessage 归计划，同文的普通 SystemMessage 不归计划")
    void planRecognizedByType() {
        String text = "当前计划：第一步";
        TurnState.EstimateSplit plan = TurnState.split(STATIC, List.of(
                new SystemMessage(STATIC), new PlanInjectionMessage("plan-1", text)), "");
        TurnState.EstimateSplit plain = TurnState.split(STATIC, List.of(
                new SystemMessage(STATIC), new SystemMessage(text)), "");
        assertEquals(TokenEstimator.estimate(text), plan.plan());
        assertEquals(0, plan.unknown());
        assertEquals(0, plain.plan());
        assertEquals(TokenEstimator.estimate(text), plain.unknown());
    }

    @Test
    @DisplayName("催办按固定开头归类：计划催办、预算将尽、用量提示、收尾、评测强制")
    void nudgesClassifiedByPrefix() {
        List<String> nudges = List.of(
                "🧭 我观察到：还在自己编译。",
                "⚠️ 轮数预算即将用尽（剩余 2 轮）：不要再发起新的大动作。",
                "【用量提示】本任务已读取较多内容。",
                "所有计划步骤都已完成。请直接输出一段给用户的总结。",
                "【评测强制】本轮已禁用 spawnSubagent。");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(STATIC));
        int expected = 0;
        for (String n : nudges) {
            messages.add(new SystemMessage(n));
            expected += TokenEstimator.estimate(n);
        }
        TurnState.EstimateSplit split = TurnState.split(STATIC, messages, "");
        assertEquals(expected, split.nudge());
        assertEquals(0, split.unknown());
        assertEquals(split.nudge(), split.injected());
    }

    @Test
    @DisplayName("认不出的 SystemMessage 进 unknown，不进任何已知子列")
    void unknownStaysUnknown() {
        String text = "【可恢复的工具结果】\n- readFile：完整内容已落盘";
        TurnState.EstimateSplit split = TurnState.split(STATIC, List.of(
                new SystemMessage(STATIC), new SystemMessage(text)), "");
        assertEquals(TokenEstimator.estimate(text), split.unknown());
        assertEquals(0, split.historySummary());
        assertEquals(0, split.userMemory());
        assertEquals(0, split.workspaceMemory());
        assertEquals(0, split.plan());
        assertEquals(0, split.nudge());
    }

    @Test
    @DisplayName("readFile 和 editFile 能解析 path；其它工具和坏 JSON 返回 null")
    void targetPathParsing() {
        assertEquals("src/A.java", TurnState.targetPathOf("readFile", "{\"path\":\"src/A.java\"}"));
        assertEquals("src/A.java", TurnState.targetPathOf(
                "editFile", "{\"path\":\"src/A.java\",\"oldStr\":\"a\",\"newStr\":\"b\"}"));
        assertEquals("a\\b.java", TurnState.targetPathOf("readFile", "{\"path\":\"a\\\\b.java\"}"));
        assertNull(TurnState.targetPathOf("grepFile", "{\"path\":\"src/A.java\"}"));
        assertNull(TurnState.targetPathOf("readFile", "not json"));
        assertNull(TurnState.targetPathOf("readFile", "{\"offset\":1}"));
        assertNull(TurnState.targetPathOf("readFile", null));
        assertNull(TurnState.targetPathOf(null, "{\"path\":\"src/A.java\"}"));
    }

    @Test
    @DisplayName("路径解析失败不抛，字符数按原文计，落盘只认签名有效的引用")
    void observationDoesNotThrowAndCountsChars() {
        String body = "文件内容共十个字";
        assertEquals(body.length(), TurnState.resultChars(body));
        assertEquals(0, TurnState.resultChars(null));
        assertFalse(TurnState.spilled(body));
        assertFalse(TurnState.spilled("[artifact-ref]path=x&chars=1&tool=readFile[/artifact-ref]"));

        String signed = body + "\n" + ToolArtifactRef.marker("D:/spill/a.txt", body.length(), "readFile");
        assertTrue(TurnState.spilled(signed));
        assertEquals(signed.length(), TurnState.resultChars(signed));

        TurnState.ToolResultObservation ok = new TurnState.ToolResultObservation(
                "call-1", "readFile", TurnState.targetPathOf("readFile", "{\"path\":\"src/A.java\"}"),
                TurnState.resultChars(signed), TurnState.spilled(signed), 1);
        assertEquals("src/A.java", ok.targetPath());
        assertTrue(ok.spilled());
        assertEquals(1, ok.seq());

        TurnState.ToolResultObservation bad = new TurnState.ToolResultObservation(
                null, "readFile", TurnState.targetPathOf("readFile", "{"),
                TurnState.resultChars("x"), false, 2);
        assertNull(bad.targetPath());
        assertEquals(1, bad.chars());
    }

    @Test
    @DisplayName("分类和旁路记录不改变 PromptView 的消息内容和顺序")
    void observationDoesNotChangePrompt() {
        List<Message> original = new ArrayList<>();
        original.add(new SystemMessage(STATIC));
        original.add(new SystemMessage("[历史摘要] 更早的对话"));
        original.add(new PlanInjectionMessage("plan-1", "当前计划"));
        original.add(new UserMessage("读文件"));
        ToolResponseMessage response = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("c1", "readFile", "正文")));
        original.add(response);

        List<String> before = texts(original);
        PromptView view = PromptView.of(original);
        TurnState.split(STATIC, view.messages(), "[]");
        TurnState.targetPathOf("readFile", "{\"path\":\"src/A.java\"}");
        TurnState.spilled("正文");

        assertEquals(before, texts(view.messages()));
        assertEquals(before, texts(original));
        for (int i = 0; i < original.size(); i++) {
            assertSame(original.get(i), view.messages().get(i));
        }
    }

    @Test
    @DisplayName("流式没有 usage 时 input/output 保持 null；缓存字段缺失时也是 null，真实 0 仍是 0")
    void missingUsageAndCacheStayNull() {
        TurnState missing = new TurnState("c", 1L, 0, TurnState.PHASE_MODEL_TURN, "m",
                STATIC, null, "[]", TurnState.COMPACT_NONE,
                null, null, 0, null, null, List.of(), AgentTurnRecord.END_NORMAL_STOP);
        AgentTurnRecord row = AgentTurnRecord.from(missing);
        assertNull(row.inputTokens());
        assertNull(row.outputTokens());

        assertNull(TurnState.cachedTokensOfResponse(null));
        org.springframework.ai.chat.model.ChatResponse noUsage =
                new org.springframework.ai.chat.model.ChatResponse(List.of());
        assertNull(TurnState.cachedTokensOfResponse(noUsage));

        org.springframework.ai.openai.api.OpenAiApi.Usage.PromptTokensDetails details =
                new org.springframework.ai.openai.api.OpenAiApi.Usage.PromptTokensDetails(0, 0);
        org.springframework.ai.openai.api.OpenAiApi.Usage nativeUsage =
                new org.springframework.ai.openai.api.OpenAiApi.Usage(0, 10, 10, details, null);
        org.springframework.ai.chat.metadata.Usage usage =
                new org.springframework.ai.chat.metadata.DefaultUsage(10, 0, 10, nativeUsage);
        org.springframework.ai.chat.model.ChatResponse realZero =
                new org.springframework.ai.chat.model.ChatResponse(List.of(),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(usage).build());
        assertEquals(0L, TurnState.cachedTokensOfResponse(realZero));
    }

    private static List<String> texts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage tr && tr.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
                    out.add(r.id() + ":" + r.name() + ":" + r.responseData());
                }
            } else {
                out.add(m.getText());
            }
        }
        return out;
    }
}
