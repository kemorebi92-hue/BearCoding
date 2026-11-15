package com.example.jingjingai2.agent;

import com.example.jingjingai2.context.MessageProtocolValidator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 「一轮」的最小内核：**模型调用 → 白名单执行 → （可选）截断 → 追加 ToolResponseMessage → 取消点**。
 *
 * 为什么抽它：这一轮骨架在三处都要用 —— 非流式主循环、SSE 主循环、子代理房间。
 * 不抽的话子代理就是**第三份副本**（{@code ToolGuard} 已经为同样的原因抽成纯函数了），
 * 三份会各自漂移：改一处取消语义、忘了另一处，就会出现"点了停止子代理还在跑"这类 bug。
 *
 * **刻意不抽的东西**（抽了内核就会变成第二个 ChatController）：
 *  - SSE 推送、高危命令审批（这两个是**协议层**：一个面向连接，一个面向"返回给用户一个待确认响应"）
 *  - 计划注入 / wrap-up / nudge / HARD / CodeVerifier 编译回环
 *
 * 与子代理的分工：内核只管"一轮怎么跑"，**结束条件**（轮数用尽 / 同一工具连失败 3 次）放在
 * {@link SubagentRunner} 里，因为那是子代理特有的语义，主循环并不需要。
 *
 * 线程模型：无状态静态方法，可被并发调用（每个请求/每个子代理各持自己的 messages 列表）。
 */
public final class ToolLoopStep {

    /** 一轮跑完之后的走向 */
    public enum Outcome {
        /** 模型没有再调工具 → 它自己收尾了（子代理里 = SUCCESS） */
        NATURAL_STOP,
        /** 本轮的工具都执行完了 → 调用方决定要不要进下一轮 */
        CONTINUE,
        /** 取消点命中（用户点了"停止"） */
        CANCELLED,
        /** 执行器要求中止整趟（例如高危命令需要人工审批，不能继续往下跑） */
        ABORTED
    }

    /** 取消检查（协作式取消：循环自己在安全点轮询，而不是被强杀线程） */
    public interface CancelCheck {
        boolean isCancelled();
    }

    /**
     * 单次工具的执行器。**审批 / 审计 / 记账 / SSE 都留在调用方这里**，
     * 内核只负责"什么时候调、调完怎么追加进 messages"。
     */
    public interface ToolExecutor {
        ToolExec execute(AssistantMessage.ToolCall call);
    }

    /** 一次工具执行的结果：正常结果，或"中止整趟"的信号 */
    public static final class ToolExec {
        public final String result;
        public final boolean abort;
        /** abort=true 时的原因（例如被拦截的那条命令），供调用方拼用户可见的提示 */
        public final String abortReason;

        private ToolExec(String result, boolean abort, String abortReason) {
            this.result = result;
            this.abort = abort;
            this.abortReason = abortReason;
        }

        public static ToolExec ok(String result) { return new ToolExec(result, false, null); }

        public static ToolExec abort(String reason) { return new ToolExec(null, true, reason); }
    }

    /** 一轮的结果 */
    public static final class Result {
        public final Outcome outcome;
        /** 本轮模型产出的助手消息（可能为 null：空响应） */
        public final AssistantMessage assistant;
        /** 本轮实际执行了几个工具 */
        public final int toolsExecuted;
        /** outcome=ABORTED 时带上原因 */
        public final String abortReason;
        /**
         * 本轮模型的**原始响应**。调用方要拿它读 usage（真实计费 token）。
         * 这是纯观测出口：内核自己不解释它、也不因为它是 null 就改变走向。
         */
        public final ChatResponse response;

        Result(Outcome outcome, AssistantMessage assistant, int toolsExecuted, String abortReason,
               ChatResponse response) {
            this.outcome = outcome;
            this.assistant = assistant;
            this.toolsExecuted = toolsExecuted;
            this.abortReason = abortReason;
            this.response = response;
        }
    }

    private ToolLoopStep() {}

    /**
     * 跑一轮。
     *
     * @param messages            本轮的消息列表；助手消息与工具结果会**就地追加**进去
     * @param prompt              发出去的提示（调用方自己控制 options：全量工具 / 被摘过的子集 / 强制 tool_choice）
     * @param model               本次生效的模型（主会话选定的那个直接传下来）
     * @param cancel              取消检查；null = 不检查
     * @param executor            单次工具的执行器
     * @param maxToolResultChars  工具结果硬顶；null = 这一层不裁剪（主循环第一期传 null）
     */
    public static Result run(List<Message> messages,
                             Prompt prompt,
                             ChatModel model,
                             CancelCheck cancel,
                             ToolExecutor executor,
                             Integer maxToolResultChars) {
        MessageProtocolValidator.Result protocol = MessageProtocolValidator.check(messages);
        if (!protocol.ok()) {
            // 不合法就不发、不执行、不补结果。摘要只有类型、id 和位置，没有正文。
            return new Result(Outcome.ABORTED, null, 0, "协议不完整: " + protocol.summary(), null);
        }
        ChatResponse response = com.example.jingjingai2.agent.TaskModelCalls.call(model, prompt);
        AssistantMessage assistant = (response == null || response.getResult() == null)
                ? null : response.getResult().getOutput();
        List<AssistantMessage.ToolCall> calls = (assistant == null || assistant.getToolCalls() == null)
                ? List.of() : assistant.getToolCalls();

        // 没有工具调用 → 模型自己收尾。注意：**这一轮不追加助手消息**，
        // 由调用方决定怎么用它（主循环要拿它做编译校验/最终回答，子代理要拿它当摘要）。
        if (calls.isEmpty()) {
            return new Result(Outcome.NATURAL_STOP, assistant, 0, null, response);
        }

        // 要和 OpenAI 协议对齐：带 tool_calls 的助手消息必须先进 messages，工具结果才接得上
        messages.add(assistant);

        int executed = 0;
        for (AssistantMessage.ToolCall call : calls) {
            if (cancel != null && cancel.isCancelled()) {
                return new Result(Outcome.CANCELLED, assistant, executed, null, response);
            }
            ToolExec exec = (executor == null) ? ToolExec.ok("(没有配置工具执行器)") : executor.execute(call);
            if (exec == null) exec = ToolExec.ok("");
            if (exec.abort) {
                return new Result(Outcome.ABORTED, assistant, executed, exec.abortReason, response);
            }
            String clipped = ToolResultClipper.clip(exec.result, maxToolResultChars);
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), clipped))));
            executed++;
        }
        return new Result(Outcome.CONTINUE, assistant, executed, null, response);
    }
}
