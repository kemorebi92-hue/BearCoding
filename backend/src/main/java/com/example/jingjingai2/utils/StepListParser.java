package com.example.jingjingai2.utils;

import com.example.jingjingai2.DTO.StepInput;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * flat 形态 createPlan 的入参解析器：把"一行一步"的纯文本解析成结构化的步骤对象。
 *
 * 为什么需要它：createPlan 原来的入参是 {@code List<StepInput>}（数组套对象），
 * 是全部 17 个工具里 schema 最复杂的一个；实测模型因此倾向"看到了但不调"。
 * flat 形态只要求它填一个普通字符串，调用门槛大幅降低 —— 代价是后端要把文本
 * 解析成结构化步骤，就是这个类存在的理由。
 *
 * 解析规则（健壮性优先，尽量容忍模型的花式输出）：
 * <ul>
 *   <li>按行切分，忽略空行 / 只有空白的行</li>
 *   <li>剥掉行首的序号或项目符号：{@code 1.} {@code 1、} {@code 1)} {@code (1)} {@code -} {@code *} {@code •} {@code ·}
 *       （支持多层，例如 {@code "- 1. xxx"}）</li>
 *   <li>去掉首尾空白</li>
 *   <li>每行可用 {@code ||} 分隔「做什么」和「预期产出」；预期产出用于自动验收核对，可省略</li>
 *   <li>最多 {@link #MAX_STEPS} 步，超出部分截断，并把 {@code truncated=true} 交给调用方提示模型</li>
 * </ul>
 *
 * 纯函数、无依赖，可直接单测（解析规则全靠测试钉死，不依赖模型行为去验证）。
 */
public final class StepListParser {

    /** 一次计划最多接受的步骤数（与 TaskPlanService 内部上限保持一致） */
    public static final int MAX_STEPS = 12;

    /**
     * 行首标记：
     *   - 项目符号：- * • ·（可重复，如 "---"）
     *   - 数字序号：1. / 1、 / 1) / 1）—— 后面**不能紧跟数字**，避免把 "1.5 倍" 的 "1." 误当序号剥掉
     *   - 括号序号：(1) / （1）
     */
    private static final Pattern LEADING_MARKER = Pattern.compile(
            "^\\s*(?:[-*•·]+\\s*|\\d{1,2}\\s*[.、)）](?!\\d)\\s*|\\(\\s*\\d{1,2}\\s*\\)\\s*)");

    private StepListParser() {}

    /** 解析结果：步骤列表 + 是否因为超出上限被截断。 */
    public static final class Result {
        public final List<StepInput> steps;
        public final boolean truncated;

        Result(List<StepInput> steps, boolean truncated) {
            this.steps = steps;
            this.truncated = truncated;
        }
    }

    /**
     * 把一行一步的文本解析成步骤列表。
     *
     * @param raw 模型给的原始文本（可为 null / 空 / 纯空白 → 返回空列表，不抛异常）
     */
    public static Result parse(String raw) {
        List<StepInput> steps = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return new Result(steps, false);
        }

        boolean truncated = false;
        for (String line : raw.split("\\r?\\n")) {
            String cleaned = clean(line);
            if (cleaned.isEmpty()) continue;              // 空行 / 纯空白 → 忽略
            if (steps.size() >= MAX_STEPS) {              // 超长 → 截断并标记
                truncated = true;
                break;
            }
            steps.add(toStep(cleaned));
        }
        return new Result(steps, truncated);
    }

    /**
     * 清理一行：剥掉行首序号/项目符号（可多层）和首尾空白。
     * 返回空串表示这一行应当被忽略。
     */
    static String clean(String line) {
        if (line == null) return "";
        String s = line.trim();
        String prev;
        do {                                              // 循环剥，处理 "- 1. xxx" 这种叠加前缀
            prev = s;
            s = LEADING_MARKER.matcher(s).replaceFirst("");
        } while (!s.equals(prev));
        return s.trim();
    }

    /** 把清理过的一行拆成「描述 || 预期产出」。 */
    private static StepInput toStep(String cleaned) {
        StepInput in = new StepInput();
        int bar = cleaned.indexOf("||");
        if (bar >= 0) {
            in.setDescription(cleaned.substring(0, bar).trim());
            in.setExpected(cleaned.substring(bar + 2).trim());
        } else {
            in.setDescription(cleaned);
            in.setExpected("");
        }
        return in;
    }
}
