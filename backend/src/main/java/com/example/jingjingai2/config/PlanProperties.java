package com.example.jingjingai2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 规划机制的全部配置开关。
 *
 * 为什么集中放一处：这些开关的目的是"只改配置就能做 A/B 对照，不必改代码"，
 * 所以每一个都必须在 application.yml 里有默认值，且取值只影响行为、不影响正确性。
 *
 * 前缀统一为 {@code app.plan.*}（与项目里 app.sandbox / app.workspace / app.context 的风格一致）。
 */
@Component
@ConfigurationProperties(prefix = "app.plan")
public class PlanProperties {

    private Steps steps = new Steps();
    private Injection injection = new Injection();
    private Nudge nudge = new Nudge();

    public Steps getSteps() { return steps; }
    public void setSteps(Steps steps) { this.steps = steps; }

    public Injection getInjection() { return injection; }
    public void setInjection(Injection injection) { this.injection = injection; }

    public Nudge getNudge() { return nudge; }
    public void setNudge(Nudge nudge) { this.nudge = nudge; }

    /** createPlan 的入参形态。 */
    public static class Steps {

        /**
         * flat（默认）：steps 是**一个普通字符串**，"一行一步"，由后端解析。
         *   目的：绕开"数组套对象"这种嵌套 schema —— 实测模型对它的调用意愿明显偏低。
         * structured：保持原来的 {@code List<StepInput>} 嵌套数组形态（行为完全不变）。
         */
        private String mode = "flat";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        /** 是否使用 structured（嵌套数组）形态；仅此一处判断，避免散落的字符串比较。 */
        public boolean isStructured() {
            return "structured".equalsIgnoreCase(mode);
        }
    }

    /** 每轮注入计划内容的策略。 */
    public static class Injection {

        /**
         * true（默认）：**精简注入** —— 常态只注入一行摘要
         * （{@code [计划] 进度 3/7 | 当前：s4 xxx | 剩余预算 9 轮}），
         * 仅当步骤状态变化 / 距上次全量满 N 轮 / 刚压缩过上下文时，才注入完整清单。
         * false：回退到旧行为 —— 每轮都注入完整清单（用于 A/B 对照）。
         */
        private boolean slim = true;

        /** 全量清单的兜底刷新间隔（轮）。仅在 slim=true 时有意义。 */
        private int fullRefreshEvery = 6;

        public boolean isSlim() { return slim; }
        public void setSlim(boolean slim) { this.slim = slim; }

        public int getFullRefreshEvery() { return fullRefreshEvery; }
        public void setFullRefreshEvery(int fullRefreshEvery) { this.fullRefreshEvery = fullRefreshEvery; }
    }

    /** 漂移信号 + 分级提醒（"该催模型规划了"的判定）。 */
    public static class Nudge {

        /**
         * off  —— 完全关闭（对照组）；
         * soft（默认）—— 只提醒，不强制；
         * hard —— 信号 ≥2 条、或 SOFT 提醒过一次仍不建计划时，
         *          下一轮强制（默认走 stripWriteTools，见 Hard.strategy）。
         *
         * 默认从 hard 改成 soft 的原因：spring-ai-openai 1.0.0 从不序列化 tool_choice，
         * 强制那条路发不出去；soft 成本低、无副作用。hard 仍可作为可选项打开。
         */
        private String mode = "soft";

        /** 触发后多少轮内不再触发（避免连续唠叨） */
        private int cooldown = 3;
        /** 被修改的**不同**文件数达到多少算一条信号 */
        private int modifiedFilesThreshold = 3;
        /** 编译/校验失败累计多少次算一条信号 */
        private int verifyFailuresThreshold = 2;
        /** 累计轮数达到多少算一条信号（8 太敏感，简单但走得慢的任务也会误触发） */
        private int roundsThreshold = 12;
        /** "隐含规划"判定：文本长度下限（字） */
        private int enumerationMinLength = 120;
        /** "隐含规划"判定：步骤枚举标记数量下限 */
        private int enumerationMinMarkers = 3;

        private Hard hard = new Hard();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        /** 是否启用（off 之外都启用） */
        public boolean isEnabled() {
            return !"off".equalsIgnoreCase(mode);
        }

        /** 是否允许升级到 HARD（强制 tool_choice） */
        public boolean isHardAllowed() {
            return "hard".equalsIgnoreCase(mode);
        }

        public int getCooldown() { return cooldown; }
        public void setCooldown(int cooldown) { this.cooldown = cooldown; }
        public int getModifiedFilesThreshold() { return modifiedFilesThreshold; }
        public void setModifiedFilesThreshold(int v) { this.modifiedFilesThreshold = v; }
        public int getVerifyFailuresThreshold() { return verifyFailuresThreshold; }
        public void setVerifyFailuresThreshold(int v) { this.verifyFailuresThreshold = v; }
        public int getRoundsThreshold() { return roundsThreshold; }
        public void setRoundsThreshold(int v) { this.roundsThreshold = v; }
        public int getEnumerationMinLength() { return enumerationMinLength; }
        public void setEnumerationMinLength(int v) { this.enumerationMinLength = v; }
        public int getEnumerationMinMarkers() { return enumerationMinMarkers; }
        public void setEnumerationMinMarkers(int v) { this.enumerationMinMarkers = v; }

        public Hard getHard() { return hard; }
        public void setHard(Hard hard) { this.hard = hard; }

        /** HARD 的具体实现方式。 */
        public static class Hard {
            /**
             * stripWriteTools（默认）—— 下一轮临时把"写操作类工具"从可用清单里摘掉，
             *                          并由 ToolGuard 在执行层拒绝清单外调用；
             * toolChoice            —— 用 OpenAI 协议的 tool_choice 强制指定 createPlan。
             *                          spring-ai-openai 1.0.0 从不读取该字段，当前发不出去，
             *                          仅作为可选项保留，等升级 Spring AI 后再启用。
             */
            private String strategy = "stripWriteTools";

            /**
             * 强制那一轮是否顺带关闭"思考模式"（即发给模型 `reasoning_effort: none`）。
             *
             * 为什么默认开（实测结论，2026-09-19）：DeepSeek 的思考模式**直接拒绝** tool_choice
             * —— 返回 400「Thinking mode does not support this tool_choice」；
             * 同一个请求只要带上 `reasoning_effort: none`，就会被正常执行、模型照做调用 createPlan。
             * 也就是说：不关思考，DeepSeek 上的"强制"根本发不出去（只能靠异常回退）。
             *
             * 对其它 OpenAI 兼容端点的影响：小米 MiMo 接受这个字段但**仍然忽略 tool_choice**
             * （它本来就一直无视 tool_choice，所以行为不变）；个别端点若不认识该字段可能报错，
             * 那种情况会走既有的"强制请求异常 → 回退普通请求"分支，不会让对话挂掉。
             */
            private boolean disableThinking = true;

            public String getStrategy() { return strategy; }
            public void setStrategy(String strategy) { this.strategy = strategy; }

            public boolean isDisableThinking() { return disableThinking; }
            public void setDisableThinking(boolean disableThinking) { this.disableThinking = disableThinking; }

            public boolean isToolChoice() {
                return !"stripWriteTools".equalsIgnoreCase(strategy);
            }
        }
    }
}
