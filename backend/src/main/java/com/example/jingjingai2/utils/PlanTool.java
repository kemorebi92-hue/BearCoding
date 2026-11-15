package com.example.jingjingai2.utils;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.service.TaskPlanService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 计划工具（Plan-and-Execute 的"规划"与"上报进度"入口）。
 *
 * 为什么做成工具而不是写在循环里：
 *  这样"要不要规划"的决定权交给模型（简单任务不规划、复杂任务才规划），
 *  而且工具是走现有的 @Tool 注册表 + 反射分发——**新增工具不需要改任何分发代码**。
 *
 * 会话上下文（userId/conversationId）通过 ConversationContext(ThreadLocal) 传递，
 * 因为这些信息不该出现在模型能看到的工具参数里。
 *
 * 职责切分（改动 2 之后）：
 *  - 本类保留 {@code updatePlanStep} 工具 + {@link #createPlanFromSteps} 公共实现；
 *  - {@code createPlan} 这个**工具方法**本身因为要支持两种入参形态（flat / structured），
 *    拆到了 {@link PlanToolFlat} / {@link PlanToolStructured} 两个 @Component 里，
 *    由配置开关 app.plan.steps.mode 决定哪个生效（同一时刻只有一个）。
 *    所以 createPlan 的业务逻辑仍然只有这一份，两个形态只是"入口签名"不同。
 */
@Component
public class PlanTool {

    private final TaskPlanService planService;

    public PlanTool(TaskPlanService planService) {
        this.planService = planService;
    }

    /**
     * createPlan 工具的**公共描述**（用途 + 该用 / 不该用的情形）。
     *
     * 两种入参形态（flat / structured）共用这段文字，各自再追加"参数怎么填"的说明 ——
     * 这样改一次文案两处同时生效，不会出现两个形态描述不一致。
     * 必须是编译期常量，才能直接写进 @Tool(description = ...) 注解。
     */
    public static final String CREATE_PLAN_DESCRIPTION =
            "为一个多步骤任务建立执行计划，把要做的事拆成有序的步骤清单。"
            + "调用后系统会把清单注入你的上下文，你就能随时看到「做到第几步、还剩几步、还剩多少轮预算」。\n"
            + "涉及写入时，用反引号逐一列出完整目标路径，包括带空格或无扩展名的文件。\n"
            + "【应该使用的情形】（拿不准复不复杂时，倾向使用它）\n"
            + "  · 需要创建 2 个及以上文件，并编译 / 运行验证\n"
            + "  · 清掉旧代码或旧目录后，重新实现或重构\n"
            + "  · 需要修改 3 个及以上文件\n"
            + "  · 需要「先分析结构 → 再实现 → 再验证」这类多阶段推进的任务\n"
            + "  · 用户描述比较模糊，但需求本身包含**多个功能模块**（要拆出多个实体/多个文件的系统，"
            + "例如「做个学生成绩管理系统」「图书馆借还书小程序」）\n"
            + "【不应该使用的情形】\n"
            + "  · 只涉及**一个文件**的改动 —— 改个类名 / 文件名、加一个方法、修一处拼写、改几行代码，"
            + "即使需要编译或运行来确认结果，也**直接做完，不要建计划**\n"
            + "  · 纯问答、查一个信息、讲解一段代码\n"
            + "【重要】不要把「最后总结 / 汇报结果」单独列成一步 —— 全部步骤做完后直接给用户一段总结就是最终回答，"
            + "多列一步只会白白消耗一轮预算。";

    /**
     * createPlan 的**公共实现**：两种入参形态（flat 的字符串 / structured 的对象数组）
     * 最终都转成 {@code List<StepInput>} 调到这里，业务逻辑只有一份，不做任何复制。
     *
     * @param steps     步骤列表（description=做什么，expected=做完的预期状态）
     * @param truncated 入参是否因为超出步数上限被截断（flat 解析器告知），需要明确提醒模型
     */
    public String createPlanFromSteps(List<StepInput> steps, boolean truncated) {
        Long userId = ConversationContext.userId();
        String conversationId = ConversationContext.conversationId();
        TaskPlan plan = planService.create(userId, conversationId, steps);
        if (plan == null) {
            return "计划创建失败：没有有效的步骤（或当前不在会话上下文中）。";
        }
        StringBuilder sb = new StringBuilder("计划已创建（" + plan.getPlanId() + "），共 "
                + plan.getSteps().size() + " 步，本轮预算 " + plan.getMaxIterations() + " 轮：\n");
        for (PlanStep s : plan.getSteps()) {
            sb.append(s.icon()).append(' ').append(s.getStepId()).append(' ').append(s.getDescription())
              .append("（预期：").append(s.getExpected()).append("）\n");
        }
        if (truncated) {
            sb.append("⚠️ 你给的步骤超过了一次计划的上限，超出部分已被截断（只保留前 ")
              .append(plan.getSteps().size()).append(" 步）。请先按这份清单执行，剩下的等做完再补。\n");
        }
        sb.append("现在开始执行第 1 步：**先用一句话告诉用户你准备做什么，再调工具**；"
                + "每一步做完立刻调一次 updatePlanStep 上报（同一步只报一次，不用报 running）；"
                + "一步步把全部步骤做完后，用一段话向用户总结结果。中途不要停下来等用户确认。");
        return sb.toString();
    }

    @Tool(description = "上报计划中某一步的**最终**结果（同一步只需上报一次）：做完传 status=done 并给出 actualResult（实际产出摘要），"
            + "没做成传 status=failed 并给出 actualResult（失败现象摘要）。不需要先报 running，完成时直接上报即可。"
            + "系统会用你的 actualResult 和该步的 expected 做自动核对；核对不通过或你上报 failed 都会累加该步的失败次数，"
            + "失败 3 次会触发一次自动重拆（把这一步拆细）。所以请如实上报，不要为了好看谎报 done。"
            + "【效率提示】上报时把它和下一步的第一个工具调用放在同一轮里一起发出（同一轮可以发多个工具调用，系统会按顺序执行），"
            + "这样能省掉一半往返轮次。")
    public String updatePlanStep(
            @ToolParam(description = "步骤编号，例如 s1、s2（用计划清单里显示的 stepId）") String stepId,
            @ToolParam(description = "状态：running=开始做、done=已完成、failed=没做成") String status,
            @ToolParam(description = "实际产出或失败现象的简要摘要，例如「已生成 Student.java，javac 编译通过」或「javac 报 cannot find symbol，缺少 Student 类」")
            String actualResult) {
        Long userId = ConversationContext.userId();
        String conversationId = ConversationContext.conversationId();

        if ("running".equalsIgnoreCase(status)) {
            planService.markRunning(userId, conversationId, stepId);
            return "已把 " + stepId + " 标记为进行中。做完后记得上报 done/failed。";
        }
        TaskPlanService.UpdateOutcome out = planService.updateStep(userId, conversationId, stepId, status, actualResult);
        if (out.step == null) return out.message;

        StringBuilder sb = new StringBuilder(out.message);
        if (out.shouldResplit && out.subSteps != null && !out.subSteps.isEmpty()) {
            sb.append("\n新的子步骤已插入计划中，请从 ")
              .append(out.subSteps.get(0).getStepId()).append(" 开始继续执行：\n");
            for (PlanStep s : out.subSteps) {
                sb.append("☐ ").append(s.getStepId()).append(' ').append(s.getDescription())
                  .append("（预期：").append(s.getExpected()).append("）\n");
            }
        }
        if (out.blocked) {
            sb.append("\n这一步已被标记为 blocked，**不要再尝试重拆或重试它**，"
                    + "请在最终回答里明确告诉用户「哪一步卡住了、卡在哪、需要人工怎么处理」，然后继续做后面还能做的步骤。");
        }
        return sb.toString();
    }
}
