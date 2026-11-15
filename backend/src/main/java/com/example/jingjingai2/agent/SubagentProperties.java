package com.example.jingjingai2.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 子代理模块的全部开关（前缀 {@code app.subagent.*}，与 app.plan / app.sandbox 风格一致）。
 *
 * 为什么默认开：模型**不调**这个工具时，行为与加这个模块之前**完全一样**（零成本）。
 * 默认关反而容易出现"演示时忘了开、现场没效果"。
 *
 * 成本核算（价格表）放在这里，是**配置项**不是常量：换模型/换服务商时只改 yml，
 * 不改代码。默认值按本项目当前在用的模型量级填写，**请以实际服务商价格为准**。
 */
@Component
@ConfigurationProperties(prefix = "app.subagent")
public class SubagentProperties {

    /**
     * 总开关。关闭时<strong>工具不下发、提示词里也不提</strong> spawnSubagent
     * （模型连试都不会试）。不要改回"工具还在、闸门拒绝"——对照组会白烧一轮 REJECTED。
     */
    private boolean enabled = true;

    /**
     * 同时能跑几个子代理（真并行的上限）。**超出的直接拒绝**（返回错误 tool result），
     * 不再排队 —— 排队会让主 agent 以为"派出去了"其实还在等，成本与收益都算不清。
     */
    private int maxConcurrent = 4;

    /** 子代理默认轮数预算（**不计入**主计划的 maxIterations，否则 3 个 explore 会把主预算吃光） */
    private int maxRounds = 48;

    /** 轮数硬顶：模型就算自己要求更多，也不会超过它 */
    private int hardCap = 96;

    /** 同一 goal 在一次用户请求里：首次 + 这么多次重派（默认 2 → 总共 3 次），第 4 次直接拒 */
    private int maxRetriesPerGoal = 2;

    /** 同一 goal 最多允许**续跑**（resume）几次。0 = 关掉续跑（PARTIAL 模板会如实写"不可续跑"） */
    private int maxResumesPerGoal = 1;

    /** 续跑时追加的轮数预算（不含收尾轮；收尾轮另算 1 轮） */
    private int resumeRounds = 4;

    /**
     * PARTIAL 工具结果是否把续跑放第一位，并给出可复制的
     * {@code spawnSubagent(resumeTaskId=..., goal=...)}。
     * true = V1（默认）；false = 旧的三选一（评测 V0 对照）。
     */
    private boolean partialResumeFirst = true;

    // ==================== 条件注入（条件下发子代理工具+提示词规则） ====================

    /**
     * 子代理工具定义与派发规则是否按条件注入（{@code false} = {@code enabled=true} 时始终下发，
     * 与当前"只看总开关"的行为相同；不是"始终不下发"）。
     *
     * <p><strong>默认 false</strong>。开启后，只有满足以下任一条件才向主 agent 注入
     * {@code spawnSubagent} 工具与派发规则：
     * <ol>
     *   <li>用户请求命中宽泛探索特征（由 {@link #conditionalPromptPatterns} 配置的正则列表判定）；</li>
     *   <li>当前主 agent 上下文估算 token 超过 {@link #conditionalContextThreshold}。</li>
     * </ol>
     * 不满足时行为等同关组：工具不下发、提示词不发。
     *
     * <p>设计取舍：条件判断放在 {@code systemPrompt()} / {@code toolObjects()} 调用之前，
     * 在 {@link ChatController} 里根据 {@code currentContextEstimate} 动态计算。
     * 只在主 agent 端生效，不影响子代理自己用的房间（子代理房间永远用干净上下文）。
     */
    private boolean conditionalInjection = false;

    /**
     * 命中宽泛探索特征的 prompt 正则列表（JSON 数组字符串）。
     * 示例：{@code ["(?i).*所有.*有关","(?i).*梳理.*?（全部|整个|全库）","(?i).*找出.*?(所有|全部).*"]}
     * 与用户请求做 {@code Pattern#matcher(question).find()} 匹配。
     * 为空时不通过 prompt 触发条件注入（只靠上下文阈值）。
     */
    private String conditionalPromptPatterns = "";

    /**
     * 主 agent 上下文估算 token 阈值：当前上下文 ≥ 这个值时才允许子代理下发。
     * 单位：token。
     *
     * <p>保守取值依据：S1 关组主 prompt 中位 17213 tok，S2 关组 274279 tok；
     * 设为 15000 时 S1 不会被误触发，S2/S3 这类多文件任务会被触发。
     * 可按实际项目大小调大。
     */
    private int conditionalContextThreshold = 15000;

    // ==================== 子代理独立模型 ====================

    /**
     * 子代理使用的模型 ID（与主 agent 相同时留空或 {@code null}）。
     *
     * <p>设置后会为子代理创建独立的 {@code ChatClient}/{@code ChatModel}，
     * 用该模型的 api-key/base-url/model-name（须同时在 {@code app.model-config} 中有对应条目）。
     * 典型用途：子代理跑大量读文件时，换成便宜/快的模型省成本；
     * 也可以用来跑另一个更强的模型提高准确率。
     *
     * <p><strong>注意</strong>：子代理房间上下文与主 agent 隔离，
     * 换模型后模型看不到主 agent 的历史。务必在 {@code spawnSubagent} 的 {@code goal}
     * 里把背景交代清楚。
     */
    private String modelId;

    /** 子代理模型专用的未命中输入价（元/每百万 token），默认为 0 表示沿用 {@link #priceInputPerMillion} */
    private double childPriceInputPerMillion = 0.0;
    /** 子代理模型专用的命中输入价 */
    private double childPriceCachedInputPerMillion = 0.0;
    /** 子代理模型专用的输出价 */
    private double childPriceOutputPerMillion = 0.0;

    /**
     * 派发决策 V2（默认 false = 现状）。
     * true 时：主循环对工具结果做 {@link #mainToolResultChars} 截断；
     * 系统提示派发段与 spawnSubagent 工具说明换成 V2 文案（见 AgentPrompts）。
     */
    private boolean dispatchV2 = false;

    /**
     * 主循环单条工具结果硬顶（字符）。仅 {@link #dispatchV2}=true 时生效。
     * 默认 12000 ≈ 混合中英文约 8000 token（CJK≈1 tok/字，ASCII≈4 字/tok）。
     * null/≤0 在 clip 层表示不截断；配置里请用正数。
     */
    private int mainToolResultChars = 12000;

    /**
     * V2 累计用量软提示（默认关）。仅 {@link #dispatchV2}=true 且本开关 true 时，
     * 主循环工具结果累计 token ≥ {@link #softHintThresholdTokens} 后注入一次建议派 explore 的提示。
     */
    private boolean dispatchV2SoftHint = false;

    /** 软提示阈值（工具结果累计估算 token），初始 15000。 */
    private int softHintThresholdTokens = 15000;

    /**
     * 开放式探索探测阈值：文件数超过此值 → 派 explore（默认 20）。
     * 仅写入 V2 提示词与探测工具的规模提示；默认不改变关开关行为。
     */
    private int probeFileThreshold = 20;

    /** 开放式探索探测阈值：总字节超过此值 → 派 explore（默认 102400 = 100KB）。 */
    private long probeBytesThreshold = 102400L;

    /**
     * 主循环 grep 命中行数超过此值时，结果附「命中较多」提示（默认 100）。
     */
    private int grepHitLineThreshold = 100;

    /** 主循环 grep 命中文件数超过此值时同样附提示（默认 30）。 */
    private int grepHitFileThreshold = 30;

    /**
     * 子代理房间内的 grep 命中行阈值（默认更高，减少子房间被提示打断）。
     */
    private int childGrepHitLineThreshold = 500;

    private int childGrepHitFileThreshold = 100;

    /**
     * 评测专用强制模式（默认 off）。仅评测脚本通过环境变量打开：
     * {@code off} | {@code force_dispatch} | {@code force_no_spawn}。
     * force_dispatch：追加系统消息，要求把探索整体交给一个 explore；
     * force_no_spawn：不下发 spawnSubagent 工具。
     */
    private String evalForceMode = "off";

    /** 子房间单条工具结果硬顶（主循环 V1 传 null；V2 走 {@link #mainToolResultChars}） */
    private int maxToolResultChars = 4000;

    /** 交回主 agent / 写进 LastRunNote 的摘要硬顶 */
    private int maxSummaryChars = 800;

    /** 结构化报告里 answer 的硬顶（汉字数）。多个子代理各回一大段会把主上下文重新撑爆 */
    private int maxAnswerChars = 600;

    // ==================== 档位：thoroughness → 轮数（**含**收尾轮） ====================

    /** quick：只看一小块、问一个具体问题 */
    private int quickRounds = 24;
    /** medium（默认）：跨几个文件的独立调研 */
    private int mediumRounds = 48;
    /** thorough：确实需要广撒网，但要自己承担成本 */
    private int thoroughRounds = 72;

    /** 收尾轮之前剩几轮时开始软提醒（停止扩大搜索、准备写结论） */
    private int warnRoundsLeft = 3;

    // ==================== 价格表（每百万 token），**配置项，请以实际服务商为准** ====================

    /** 未命中缓存的输入价 */
    private double priceInputPerMillion = 2.0;
    /** 命中缓存的输入价 */
    private double priceCachedInputPerMillion = 0.5;
    /** 输出价 */
    private double priceOutputPerMillion = 8.0;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }

    public int getHardCap() { return hardCap; }
    public void setHardCap(int hardCap) { this.hardCap = hardCap; }

    public int getMaxRetriesPerGoal() { return maxRetriesPerGoal; }
    public void setMaxRetriesPerGoal(int maxRetriesPerGoal) { this.maxRetriesPerGoal = maxRetriesPerGoal; }

    public int getMaxResumesPerGoal() { return maxResumesPerGoal; }
    public void setMaxResumesPerGoal(int maxResumesPerGoal) { this.maxResumesPerGoal = maxResumesPerGoal; }

    public int getResumeRounds() { return resumeRounds; }
    public void setResumeRounds(int resumeRounds) { this.resumeRounds = resumeRounds; }

    public boolean isPartialResumeFirst() { return partialResumeFirst; }
    public void setPartialResumeFirst(boolean partialResumeFirst) { this.partialResumeFirst = partialResumeFirst; }

    public boolean isConditionalInjection() { return conditionalInjection; }
    public void setConditionalInjection(boolean conditionalInjection) { this.conditionalInjection = conditionalInjection; }

    public String getConditionalPromptPatterns() { return conditionalPromptPatterns; }
    public void setConditionalPromptPatterns(String conditionalPromptPatterns) { this.conditionalPromptPatterns = conditionalPromptPatterns; }

    public int getConditionalContextThreshold() { return conditionalContextThreshold; }
    public void setConditionalContextThreshold(int conditionalContextThreshold) { this.conditionalContextThreshold = conditionalContextThreshold; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public double getChildPriceInputPerMillion() { return childPriceInputPerMillion; }
    public void setChildPriceInputPerMillion(double v) { this.childPriceInputPerMillion = v; }
    public double getChildPriceCachedInputPerMillion() { return childPriceCachedInputPerMillion; }
    public void setChildPriceCachedInputPerMillion(double v) { this.childPriceCachedInputPerMillion = v; }
    public double getChildPriceOutputPerMillion() { return childPriceOutputPerMillion; }
    public void setChildPriceOutputPerMillion(double v) { this.childPriceOutputPerMillion = v; }

    public boolean isDispatchV2() { return dispatchV2; }
    public void setDispatchV2(boolean dispatchV2) { this.dispatchV2 = dispatchV2; }

    public int getMainToolResultChars() { return mainToolResultChars; }
    public void setMainToolResultChars(int mainToolResultChars) { this.mainToolResultChars = mainToolResultChars; }

    public boolean isDispatchV2SoftHint() { return dispatchV2SoftHint; }
    public void setDispatchV2SoftHint(boolean dispatchV2SoftHint) {
        this.dispatchV2SoftHint = dispatchV2SoftHint;
    }

    public int getSoftHintThresholdTokens() { return softHintThresholdTokens; }
    public void setSoftHintThresholdTokens(int softHintThresholdTokens) {
        this.softHintThresholdTokens = softHintThresholdTokens;
    }

    public int getProbeFileThreshold() { return probeFileThreshold; }
    public void setProbeFileThreshold(int probeFileThreshold) { this.probeFileThreshold = probeFileThreshold; }

    public long getProbeBytesThreshold() { return probeBytesThreshold; }
    public void setProbeBytesThreshold(long probeBytesThreshold) { this.probeBytesThreshold = probeBytesThreshold; }

    public int getGrepHitLineThreshold() { return grepHitLineThreshold; }
    public void setGrepHitLineThreshold(int grepHitLineThreshold) { this.grepHitLineThreshold = grepHitLineThreshold; }

    public int getGrepHitFileThreshold() { return grepHitFileThreshold; }
    public void setGrepHitFileThreshold(int grepHitFileThreshold) { this.grepHitFileThreshold = grepHitFileThreshold; }

    public int getChildGrepHitLineThreshold() { return childGrepHitLineThreshold; }
    public void setChildGrepHitLineThreshold(int childGrepHitLineThreshold) {
        this.childGrepHitLineThreshold = childGrepHitLineThreshold;
    }

    public int getChildGrepHitFileThreshold() { return childGrepHitFileThreshold; }
    public void setChildGrepHitFileThreshold(int childGrepHitFileThreshold) {
        this.childGrepHitFileThreshold = childGrepHitFileThreshold;
    }

    public String getEvalForceMode() { return evalForceMode == null ? "off" : evalForceMode; }
    public void setEvalForceMode(String evalForceMode) {
        this.evalForceMode = evalForceMode == null ? "off" : evalForceMode.trim();
    }

    public boolean isEvalForceDispatch() {
        return "force_dispatch".equalsIgnoreCase(getEvalForceMode());
    }

    public boolean isEvalForceNoSpawn() {
        return "force_no_spawn".equalsIgnoreCase(getEvalForceMode());
    }

    /** 当前线程生效的 grep 行阈值（子房间用 child）。 */
    public int effectiveGrepHitLineThreshold() {
        return SubagentContext.inRoom() ? childGrepHitLineThreshold : grepHitLineThreshold;
    }

    public int effectiveGrepHitFileThreshold() {
        return SubagentContext.inRoom() ? childGrepHitFileThreshold : grepHitFileThreshold;
    }

    /** 软提示实际是否开启：必须 V2 总开关也开。 */
    public boolean softHintActive() {
        return dispatchV2 && dispatchV2SoftHint;
    }

    public int getMaxToolResultChars() { return maxToolResultChars; }
    public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }

    public int getMaxSummaryChars() { return maxSummaryChars; }
    public void setMaxSummaryChars(int maxSummaryChars) { this.maxSummaryChars = maxSummaryChars; }

    public int getMaxAnswerChars() { return maxAnswerChars; }
    public void setMaxAnswerChars(int maxAnswerChars) { this.maxAnswerChars = maxAnswerChars; }

    public int getQuickRounds() { return quickRounds; }
    public void setQuickRounds(int quickRounds) { this.quickRounds = quickRounds; }

    public int getMediumRounds() { return mediumRounds; }
    public void setMediumRounds(int mediumRounds) { this.mediumRounds = mediumRounds; }

    public int getThoroughRounds() { return thoroughRounds; }
    public void setThoroughRounds(int thoroughRounds) { this.thoroughRounds = thoroughRounds; }

    public int getWarnRoundsLeft() { return warnRoundsLeft; }
    public void setWarnRoundsLeft(int warnRoundsLeft) { this.warnRoundsLeft = warnRoundsLeft; }

    public double getPriceInputPerMillion() { return priceInputPerMillion; }
    public void setPriceInputPerMillion(double v) { this.priceInputPerMillion = v; }

    public double getPriceCachedInputPerMillion() { return priceCachedInputPerMillion; }
    public void setPriceCachedInputPerMillion(double v) { this.priceCachedInputPerMillion = v; }

    public double getPriceOutputPerMillion() { return priceOutputPerMillion; }
    public void setPriceOutputPerMillion(double v) { this.priceOutputPerMillion = v; }

    /**
     * 实际生效的轮数：默认值 / 调用方要求值 取小，再用硬顶夹一次。
     * 第二参保留是为了不改已有调用点，不再切换另一套预算。
     */
    public int effectiveMaxRounds(int requested) {
        return effectiveMaxRounds(requested, false);
    }

    public int effectiveMaxRounds(int requested, boolean ignored) {
        int r = requested > 0 ? requested : maxRounds;
        return Math.max(2, Math.min(r, hardCap));
    }

    /** 档位 → 轮数（含收尾轮）。无法识别时按 medium。第二参不再切换预算。 */
    public int roundsForThoroughness(String raw) {
        return roundsForThoroughness(raw, false);
    }

    public int roundsForThoroughness(String raw, boolean ignored) {
        if (raw == null || raw.isBlank()) return mediumRounds;
        String s = raw.trim().toLowerCase();
        if (s.startsWith("quick") || s.contains("快") || s.contains("窄")) return quickRounds;
        if (s.startsWith("thorough") || s.contains("彻") || s.contains("全")) return thoroughRounds;
        return mediumRounds;
    }

    /** 同一 goal 允许派出的**总次数**（首次 + 重派） */
    public int totalAttemptsAllowed() {
        return 1 + Math.max(0, maxRetriesPerGoal);
    }

    /** 续跑是否开启（关掉时 PARTIAL 模板会如实写"此类型不可续跑"） */
    public boolean resumeEnabled() {
        return maxResumesPerGoal > 0;
    }
}
