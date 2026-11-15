package com.example.jingjingai2.utils;

import com.example.jingjingai2.agent.SubagentContext;
import com.example.jingjingai2.agent.SubagentService;
import com.example.jingjingai2.agent.SubagentSpec;
import com.example.jingjingai2.context.ConversationContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 「派出子代理」工具 —— 主 agent 唯一能碰子代理的入口。
 *
 * 为什么是**工具**而不是 harness 自动拆计划：谁最清楚"这一步该不该并行去查"？
 * 是主 agent（它刚看完计划、刚读完报错）。harness 按 PlanStep 自动扇出会把"哪几步独立"这件事
 * 猜错，而且一旦猜错用户看到的是"莫名其妙多出几个子代理"。所以第一期只给工具，不做自动扇出。
 *
 * 真正的调度（开关、宽泛目标闸、同目标重派/续跑上限、并发、写互斥、干净房间）全在
 * {@link SubagentService}。这里只做三件事：把参数收下来、把当前会话与模型取出来传下去、
 * 让**工具描述本身**承担"什么时候不该派"的说服工作（提示词比校验更省一轮）。
 *
 * 第二轮加的字段（question / scope / doneWhen / thoroughness / resumeTaskId）不是装饰：
 *  - {@code scope} + {@code question} 是**宽泛目标闸**放行的依据（没 scope 的"找出所有…"会被拦一次）；
 *  - {@code thoroughness} 决定预算轮数（quick/medium/thorough → 24/48/72，**含**最后的收尾轮）；
 *  - {@code resumeTaskId} 是**续跑**凭据：上一次 PARTIAL 结果里会带一个 {@code sa-xxx}，
 *    带上它就能接着那个房间的历史往下跑（前缀不变、能命中缓存），而不是从头再查一遍。
 */
@Component
public class SubagentTool {

    private final SubagentService subagentService;

    public SubagentTool(SubagentService subagentService) {
        this.subagentService = subagentService;
    }

    @Tool(description = "【派出子代理】开一个**上下文干净、工具更少**的独立助手干**一件明确的事**，"
            + "跑完只回一段短摘要（结论、已读清单、未覆盖、下一步），不返回过程原文。"
            + "【该派】① 不知道相关代码在哪、预计要翻 ≥3 个文件，而你只要结论 → 派 explore（可同一轮并行多个）；"
            + "② 一个问题牵出 ≥2 个互不相干的方向；③ 开放式梳理/盘点/对比。"
            + "先 globFile/grepFile 探范围，候选 ≥3 个文件就派，不要先自己读一遍再决定；"
            + "拿不准时优先派 explore：只读、可并行，比在主对话里读一堆文件更省。"
            + "【不该派（重要）】① 已知具体文件/类/函数且 1–2 次调用能定位 → 自己读；"
            + "单个文件的小改（改类名/加方法/修拼写）→ 自己改；"
            + "② 要看中间过程才能定下一步的活；③ 只为\"保险\"（读完还要自己再核 = 白花两份钱）；"
            + "④ 目标含糊到写不出 scope/question（先把范围收窄再派）。"
            + "【mode】explore=只读调研（**默认推荐**，可并行）；implement=改文件/执行命令（同时刻**只允许一个**）。"
            + "【委托必须自包含】子代理看不到我们的对话，只看到你写的 goal/question/scope/doneWhen —— "
            + "写清\"看哪个目录、回答什么、什么算查清\"。"
            + "【预算】它每轮收到\"已用 k/N 轮\"，最后一轮不给工具、必须出结论；轮数用尽返回 PARTIAL"
            + "（部分结论 + 已读清单 + 未覆盖）。PARTIAL 时**优先**把结果里那行 "
            + "spawnSubagent(resumeTaskId=..., goal=...) **原样再调一次**（只补未覆盖的，同目标最多续 1 次），"
            + "**不要**整份重读它已读过的文件。"
            + "【边界】子代理**没有**规划权限、也**不能再派子代理**。")
    public String spawnSubagent(
            @ToolParam(description = "要子代理完成的那**一件事**，写具体：看哪里、回答什么问题。"
                    + "例如「通读 src/main/java/com/example/jingjingai2/plan 包，回答：计划步骤的状态流转有哪几种、分别由谁写入」")
            String goal,
            @ToolParam(description = "explore=只读调研（默认，可并行）；implement=动手改文件（同一时刻只允许一个）",
                    required = false) String mode,
            @ToolParam(description = "要它回答的**那一个**具体问题（一句话）。填了它，同一个 goal 下措辞不同也能被当成同一次调研，"
                    + "并且宽泛目标闸不会拦你。例：「失败几次会重拆计划步骤、谁把步骤标成 failed」",
                    required = false) String question,
            @ToolParam(description = "搜索范围：目录或 glob（如 src/main/java/com/example/jingjingai2/plan、**/*Plan*.java）。"
                    + "**填了它，宽泛的 goal 才不会被拦**；也直接决定子代理翻多少文件。",
                    required = false) String scope,
            @ToolParam(description = "什么算查清（给子代理一个可自检的终点，能显著减少它无谓地多翻文件）。"
                    + "例：「说清状态流转的每个写入点在哪个文件的哪一行」", required = false) String doneWhen,
            @ToolParam(description = "quick=24 轮 / medium=48 轮（默认）/ thorough=72 轮（轮数**含**最后的收尾轮）。"
                    + "范围窄、问题明确就 quick；不确定才 medium/thorough —— 轮数越多越贵。",
                    required = false) String thoroughness,
            @ToolParam(description = "【续跑】上一次 spawnSubagent 返回 PARTIAL 时给出的 sa-xxx 凭据。"
                    + "用法：把结果里那行 spawnSubagent(resumeTaskId=\"sa-xxx\", goal=\"原来的目标\") **原样再调一次**。"
                    + "带上它就接着那个子代理**已有的对话历史**继续跑（只补它列出的未覆盖问题），"
                    + "比重新派一个便宜得多。**goal 要保持不变**；同一目标最多续跑 1 次。",
                    required = false) String resumeTaskId) {

        ChatModel model = SubagentContext.model();
        if (model == null) {
            // 理论上不会发生：SubagentTool 只在 /ai/chat、/ai/stream 的主循环里被派发。
            return "工具 spawnSubagent 只能在对话主循环里使用（当前没有可用的模型上下文），已拒绝执行。";
        }
        String conversationId = ConversationContext.conversationId();
        if (conversationId == null) {
            return "工具 spawnSubagent 需要会话上下文（conversationId 为空），已拒绝执行。";
        }
        return subagentService.spawnOnce(new SubagentSpec(goal, question, scope, doneWhen,
                SubagentSpec.Mode.parse(mode), 0, conversationId, thoroughness, resumeTaskId), model);
    }
}
