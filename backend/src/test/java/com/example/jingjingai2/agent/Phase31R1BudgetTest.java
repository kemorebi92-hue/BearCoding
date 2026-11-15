package com.example.jingjingai2.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Phase31R1BudgetTest {
    @Test void childPressureLeavesMainAndFinalCallsAvailable() throws Exception {
        TaskExecution task = new TaskExecution(8, 100_000, 100_000);
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        SubagentPool pool = new SubagentPool(1, null);
        try (TaskExecution.Scope ignored = TaskExecution.attach(task)) {
            var blocked = new java.util.concurrent.atomic.AtomicBoolean();
            var child = pool.trySubmit(new SubagentSpec("读取", SubagentSpec.Mode.EXPLORE, 8, "c"), () -> {
                // 共享额度后辅助调用不再有"一半"的硬闸门：先花掉 5 次，把余额压到 2
                for (int i = 0; i < 5; i++) TaskModelCalls.call(model, new Prompt("子代理读取"));
                try {
                    TaskModelCalls.call(model, new Prompt("辅助调用超出剩余额度"));
                } catch (TaskExecution.BudgetExceeded denied) {
                    blocked.set(true);
                }
                return null;
            });
            assertNotNull(child);
            child.get(5, TimeUnit.SECONDS);
            assertTrue(blocked.get(), "auxiliary calls must preserve the main execution allowance");
            for (int i = 0; i < 2; i++) TaskModelCalls.call(model, new Prompt("主任务继续"));
            task.beginFinal("交付");
            TaskModelCalls.call(model, new Prompt("最终正文"));
            assertEquals(8, task.calls());
            verify(model, times(8)).call(any(Prompt.class));
        } finally {
            pool.destroy();
        }
    }
}
