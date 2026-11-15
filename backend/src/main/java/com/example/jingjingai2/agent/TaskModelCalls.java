package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.ContextBudget;
import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.context.PromptView;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/** Single admission point for main, summary, resplit, memory and child model requests. */
public final class TaskModelCalls {
    private static final ThreadLocal<Boolean> AUXILIARY = new ThreadLocal<>();

    private TaskModelCalls() {}

    static TaskExecution.Scope auxiliaryScope() {
        Boolean previous = AUXILIARY.get();
        AUXILIARY.set(true);
        return () -> {
            if (previous == null) AUXILIARY.remove(); else AUXILIARY.set(previous);
        };
    }

    public static ChatResponse auxiliaryText(ChatModel model, String text) {
        ToolCallingChatOptions noTools = ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();
        try (TaskExecution.Scope ignored = auxiliaryScope()) {
            return call(model, new Prompt(text, noTools));
        }
    }

    private static long admit(Prompt prompt, TaskExecution task, boolean auxiliary) {
        if (!MessageProtocolValidator.check(prompt.getInstructions()).ok())
            throw new IllegalStateException("内部工具消息不完整，未发送模型请求");
        ToolCallback[] tools = prompt.getOptions() instanceof ToolCallingChatOptions o && o.getToolCallbacks() != null
                ? o.getToolCallbacks().toArray(new ToolCallback[0]) : new ToolCallback[0];
        if (task != null && task.finalizing() && tools.length != 0)
            throw new IllegalStateException("最终交付阶段不允许工具");
        long estimated = ContextBudget.requestTokens(prompt.getInstructions(), tools);
        if (task != null) task.acquire(estimated, auxiliary);
        return estimated;
    }
    public static ChatResponse call(ChatModel model, Prompt prompt) {
        // The retry wrapper charges each delegate attempt, not the enclosing loop twice.
        if (model instanceof RetryingChatModel) return model.call(prompt);
        TaskExecution task = TaskExecution.current();
        boolean auxiliary = Boolean.TRUE.equals(AUXILIARY.get());
        long estimated = admit(prompt, task, auxiliary);
        ChatResponse response = model.call(new Prompt(PromptView.of(prompt.getInstructions()).messages(), prompt.getOptions()));
        if (task != null && response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null
                && response.getMetadata().getUsage().getPromptTokens() != null)
            task.accountExtra(estimated, response.getMetadata().getUsage().getPromptTokens(), auxiliary);
        return response;
    }
    public static Flux<ChatResponse> stream(ChatModel model, Prompt prompt) {
        TaskExecution task = TaskExecution.current();
        boolean auxiliary = Boolean.TRUE.equals(AUXILIARY.get());
        // Charge on each subscription, including resubscription, with the captured task identity.
        return Flux.defer(() -> {
            long estimated = admit(prompt, task, auxiliary);
            java.util.concurrent.atomic.AtomicLong accounted = new java.util.concurrent.atomic.AtomicLong(estimated);
            return model.stream(new Prompt(PromptView.of(prompt.getInstructions()).messages(), prompt.getOptions()))
                    .doOnNext(r -> {
                        if (task != null && r.getMetadata() != null && r.getMetadata().getUsage() != null
                                && r.getMetadata().getUsage().getPromptTokens() != null) {
                            long actual = r.getMetadata().getUsage().getPromptTokens();
                            long old = accounted.getAndAccumulate(actual, Math::max);
                            task.accountExtra(old, actual, auxiliary);
                        }
                    });
        }).transform(stream -> task == null ? stream : stream.takeUntilOther(
                Flux.interval(java.time.Duration.ofMillis(50)).filter(tick -> task.cancelled()).next()));
    }
}
