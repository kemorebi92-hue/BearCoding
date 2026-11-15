package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「从 ToolCallingChatOptions 换成 OpenAiChatOptions」的**回归护栏**。
 *
 * 背景：改动 4 需要给请求带上 tool_choice 强制指定 createPlan，而本机 javap 实测
 * spring-ai 1.0.0 里只有 {@link OpenAiChatOptions} 有 toolChoice（接口
 * {@link ToolCallingChatOptions} 没有），所以请求选项的构造类必须换掉。
 * 换类是有风险的动作：一不小心就会丢掉原有的工具列表 / 自动执行开关等设置，
 * 而这种回归**不会编译报错**，只会让工具莫名消失。
 *
 * 所以这里把"换类前后必须逐项一致"写成断言：工具列表、工具名、自动执行开关、
 * 模型名、温度、topP、maxTokens 全部对齐；并确认**不强制时 toolChoice 为 null**，
 * 即默认行为与改动前完全相同。
 */
class PlanOptionsParityTest {

    /** 桩工具：只用来生成 ToolCallback，方法名与真实计划工具一致，方便断言 */
    static class StubTools {
        @Tool(description = "stub: createPlan")
        public String createPlan(@ToolParam(description = "steps") String steps) {
            return "ok";
        }

        @Tool(description = "stub: updatePlanStep")
        public String updatePlanStep(@ToolParam(description = "stepId") String stepId) {
            return "ok";
        }
    }

    private static ToolCallback[] stubCallbacks() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new StubTools())
                .build()
                .getToolCallbacks();
    }

    private static Set<String> names(List<ToolCallback> callbacks) {
        return callbacks.stream().map(cb -> cb.getToolDefinition().name()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("换类前后：工具列表、自动执行开关、模型名、温度等逐项一致（防静默回归）")
    void switchingToOpenAiOptionsKeepsEverySetting() {
        ToolCallback[] callbacks = stubCallbacks();

        ToolCallingChatOptions before = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        OpenAiChatOptions after = OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        assertEquals(names(before.getToolCallbacks()), names(after.getToolCallbacks()),
                "工具列表必须完全一致 —— 少一个工具就是功能回归");
        assertTrue(names(after.getToolCallbacks()).contains("createPlan"));
        assertTrue(names(after.getToolCallbacks()).contains("updatePlanStep"));

        assertEquals(before.getInternalToolExecutionEnabled(), after.getInternalToolExecutionEnabled(),
                "工具自动执行开关必须一致（本项目固定 false：由 harness 手动执行工具）");
        assertEquals(false, after.getInternalToolExecutionEnabled());

        assertEquals(before.getModel(), after.getModel(), "模型名一致（都未显式设置 → 都用 Bean 上的默认）");
        assertEquals(before.getTemperature(), after.getTemperature(), "温度一致");
        assertEquals(before.getTopP(), after.getTopP(), "topP 一致");
        assertEquals(before.getMaxTokens(), after.getMaxTokens(), "maxTokens 一致");
        assertEquals(before.getToolNames(), after.getToolNames(), "工具名集合一致");
    }

    @Test
    @DisplayName("默认（不强制）时 toolChoice 必须为 null —— 也就是换类本身不改变任何请求行为")
    void toolChoiceIsNullByDefault() {
        OpenAiChatOptions after = OpenAiChatOptions.builder()
                .toolCallbacks(stubCallbacks())
                .internalToolExecutionEnabled(false)
                .build();
        assertNull(after.getToolChoice(),
                "不做 HARD 强制时必须保持 null（等价于 auto），否则会变成每轮都逼着模型调工具");
    }

    @Test
    @DisplayName("HARD 强制时才带上 tool_choice，且指向 createPlan（一次性动作的载体）")
    void forcedOptionsCarryCreatePlan() {
        Object toolChoice = java.util.Map.of("type", "function", "function", java.util.Map.of("name", "createPlan"));

        OpenAiChatOptions forced = OpenAiChatOptions.builder()
                .toolCallbacks(stubCallbacks())
                .internalToolExecutionEnabled(false)
                .toolChoice(toolChoice)
                .build();

        assertEquals(toolChoice, forced.getToolChoice());
        // 强制时其余设置仍然保持原样（工具与开关不受影响）
        assertEquals(false, forced.getInternalToolExecutionEnabled());
        assertEquals(2, forced.getToolCallbacks().size());

        // ChatOptions 接口上取不到 toolChoice —— 这正是当初必须换到 OpenAiChatOptions 的原因
        ChatOptions asInterface = forced;
        assertNotNull(asInterface, "OpenAiChatOptions 实现 ChatOptions，仍然可以当接口用");
    }
}
