package com.example.jingjingai2.utils;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * createPlan 的 **flat 形态**（默认）：入参就是一个普通字符串，"一行一步"。
 *
 * 为什么默认用它：原来 createPlan 收的是 {@code List<StepInput>}（数组套对象），
 * 是全部 17 个工具里 schema 最复杂的一个 —— 实测模型"看到了但不愿调"。
 * flat 形态只要求填一个字符串，调用门槛显著降低；结构化的工作交给后端
 * {@link StepListParser} 做（模型擅长写文本，不擅长填复杂 JSON schema）。
 *
 * 生效条件：app.plan.steps.mode = flat（或未配置 → matchIfMissing 生效）。
 */
@Component
@ConditionalOnProperty(name = "app.plan.steps.mode", havingValue = "flat", matchIfMissing = true)
public class PlanToolFlat implements PlanCreateTool {

    private final PlanTool planTool;

    public PlanToolFlat(PlanTool planTool) {
        this.planTool = planTool;
    }

    @Tool(description = PlanTool.CREATE_PLAN_DESCRIPTION
            + "\n【steps 参数怎么填】直接给**多行纯文本，一行一步**，每行写清这一步要做什么即可；"
            + "行首写不写序号都行（1. / - / • 会被自动去掉）。"
            + "如果某一步有明确的验收标准，可以用两个竖线追加预期产出，"
            + "例如「创建 Student.java 并编译通过 || out/Student.class 存在」。"
            + "示例：\n"
            + "  删除旧的 inventory 目录\n"
            + "  创建 Product.java 与 Inventory.java || 两个文件都存在\n"
            + "  用 javac 编译并运行 Demo || 编译无错且程序有输出\n"
            + "步骤数量控制在 3~8 个，每步都要是一次能做完的具体动作；最多 12 行，超出会被截断。")
    public String createPlan(
            @ToolParam(description = "步骤清单：多行文本，一行一步（可选：用 || 追加该步的预期产出）")
            String steps) {
        StepListParser.Result parsed = StepListParser.parse(steps);
        return planTool.createPlanFromSteps(parsed.steps, parsed.truncated);
    }
}
