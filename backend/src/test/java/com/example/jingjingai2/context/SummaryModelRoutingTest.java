package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 回归：条件摘要必须使用「本次请求选中的模型」，不能用注入的单例系统模型。
 *
 * <p>背景（2026-11-14 真实模型验收）：用户只在界面配置了 DB 模型、没有系统级 api-key 时，
 * {@code AiConfig} 把系统 ChatModel 钉在 {@code http://127.0.0.1}，摘要请求必然失败，
 * 于是条件摘要永远不生效。这里用两个假模型把「谁被调用」钉死。
 *
 * <p>全部离线：不连数据库、不调真实模型、不产生费用。
 */
class SummaryModelRoutingTest {

    private static final String CURRENT = "当前问题：把 CHANGE.txt 改成 LATEST";

    @Test
    @DisplayName("摘要调用走本次请求的模型，注入的系统模型一次都不被调用")
    void summaryUsesRequestModelNotInjectedSystemModel() {
        HistorySummaryM1Test.ScriptedModel system = new HistorySummaryM1Test.ScriptedModel();
        HistorySummaryM1Test.ScriptedModel request = new HistorySummaryM1Test.ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(system.model, 2_000);

        List<MessageRecord> rows = HistorySummaryM1Test.history(6, 300, CURRENT);
        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-routing", request.model);

        assertEquals(1, request.calls.get(), "摘要必须调用本次请求选中的模型");
        assertEquals(0, system.calls.get(), "系统兜底模型不得被用来做摘要");
        assertTrue(messages.get(0) instanceof SystemMessage, "摘要以系统消息放在最前面");
        assertTrue(compactor.lastHistorySummary().applied(), "摘要应被应用");
        assertEquals(1, compactor.lastHistorySummary().calls());
    }

    @Test
    @DisplayName("没有传入模型时退回注入模型（旧调用方行为不变）")
    void nullModelFallsBackToInjectedModel() {
        HistorySummaryM1Test.ScriptedModel system = new HistorySummaryM1Test.ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(system.model, 2_000);

        List<Message> messages = compactor.assemble(HistorySummaryM1Test.history(6, 300, CURRENT),
                CURRENT, "1|conv-null", null);

        assertEquals(1, system.calls.get(), "null 时退回注入模型");
        assertTrue(messages.get(0) instanceof SystemMessage);
    }

    @Test
    @DisplayName("请求模型失败只降级一次：不回退到另一个模型，也不重试")
    void failingRequestModelDoesNotFallBackToAnotherModel() {
        HistorySummaryM1Test.ScriptedModel system = new HistorySummaryM1Test.ScriptedModel();
        HistorySummaryM1Test.ScriptedModel request = new HistorySummaryM1Test.ScriptedModel();
        request.fail = true;
        ContextCompactor compactor = new ContextCompactor(system.model, 2_000);

        List<MessageRecord> rows = HistorySummaryM1Test.history(6, 300, CURRENT);
        List<Message> messages = compactor.assemble(rows, CURRENT, "1|conv-fail", request.model);

        assertEquals(1, request.calls.get(), "失败后不得重试第二次");
        assertEquals(0, system.calls.get(), "不得回退到别的模型再打一轮");
        assertEquals("SUMMARY_UNAVAILABLE", compactor.lastHistorySummary().reason());
        assertTrue(messages.stream().noneMatch(SystemMessage.class::isInstance), "失败时保留原始历史");
        assertEquals(rows.size(), messages.size(), "原文一条不少（末条 user 与当前消息合并）");
    }

    @Test
    @DisplayName("非工作集路径 buildMessages 同样使用请求模型")
    void buildMessagesAlsoUsesRequestModel() {
        HistorySummaryM1Test.ScriptedModel system = new HistorySummaryM1Test.ScriptedModel();
        HistorySummaryM1Test.ScriptedModel request = new HistorySummaryM1Test.ScriptedModel();
        ContextCompactor compactor = new ContextCompactor(system.model, 500);

        List<MessageRecord> rows = HistorySummaryM1Test.history(6, 300, CURRENT);
        List<Message> messages = compactor.buildMessages(rows, CURRENT, request.model);

        assertEquals(1, request.calls.get(), "buildMessages 也必须用请求模型");
        assertEquals(0, system.calls.get());
        assertTrue(messages.get(0) instanceof SystemMessage, "压缩后以历史摘要开头");
    }
}
