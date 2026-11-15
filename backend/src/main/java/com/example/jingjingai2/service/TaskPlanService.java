package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.agent.TaskExecution;
import com.example.jingjingai2.agent.TaskModelCalls;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.permission.SensitiveRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计划的**状态管理**：创建 / 更新 / 核对 / 失败重拆 / 渲染注入文本 / 持久化。
 *
 * 设计取舍（面试可讲）：
 *  1. **状态外置**：计划存在会话里（DB），不在模型脑子里 —— 这是"每轮都能看到全局坐标"的前提；
 *  2. **不做递归任务树**：重拆只允许一层（resplitDepth 从 0 → 1），再失败就 blocked 交给人，
 *     避免"自己拆自己"无限套娃；
 *  3. **不做 DAG 调度**：步骤线性推进，只有"只读操作一轮内并发"这一条并发策略（写在提示词里）；
 *  4. **内存缓存 + DB 持久化**：同会话高频读写走缓存（ConcurrentHashMap），
 *     状态一变就落库（plan_json），所以刷新页面/换会话都能恢复。
 */
@Service
public class TaskPlanService {

    /** 重拆时最多允许拆出的子步骤数（防止模型把一步拆成十几步，预算被吃光） */
    private static final int MAX_SUB_STEPS = 4;
    private static final int MAX_STEPS = 12;

    private final ConversationMapper mapper;
    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 计划缓存。键必须是 userId + conversationId，不能只用 conversationId：
     * 库表唯一约束是 (user_id, conversation_id)，不同用户可以共用同一会话 id 字符串。
     */
    private final Map<String, TaskPlan> cache = new ConcurrentHashMap<>();

    /** 缓存键：用户与会话缺一不可。 */
    static String cacheKey(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) return null;
        return userId + "\0" + conversationId;
    }

    public TaskPlanService(ConversationMapper mapper, OpenAiChatModel chatModel) {
        this.mapper = mapper;
        this.chatModel = chatModel;
    }

    // ==================== ① 创建 ====================

    /** 规划阶段：把 LLM 给的步骤清单变成一份带状态的计划 */
    public synchronized TaskPlan create(Long userId, String conversationId, List<StepInput> inputs) {
        if (userId == null || conversationId == null) return null;
        if (inputs == null || inputs.isEmpty()) return null;

        List<PlanStep> steps = new ArrayList<>();
        int n = 0;
        for (StepInput in : inputs) {
            if (in == null || in.getDescription() == null || in.getDescription().isBlank()) continue;
            if (++n > MAX_STEPS) break;
            // 步骤描述/预期会写进 plan_json、经 SSE 推给前端、并注入后续提示词。
            // 它们是模型生成的自由文本，可能把上文的 token / 密码复述进来，所以入口处就脱敏。
            steps.add(new PlanStep("s" + n, SensitiveRedactor.redactText(in.getDescription().trim()),
                    in.getExpected() == null ? "" : SensitiveRedactor.redactText(in.getExpected().trim())));
        }
        if (steps.isEmpty()) return null;

        TaskExecution execution = TaskExecution.current();
        if (execution != null && (execution.finalizing() || execution.approvalBlocked() || execution.cancelled()))
            throw new IllegalStateException("当前阶段不允许修改计划");
        TaskPlan plan = new TaskPlan("p-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setSteps(steps);
        // 动态预算：max(12, 步数 × 4) —— 长任务不再被固定 12 轮硬切断
        plan.setMaxIterations(TaskPlan.budgetFor(steps.size()));
        String key = cacheKey(userId, conversationId);
        if (key != null) cache.put(key, plan);
        persist(userId, conversationId, plan);
        System.out.println("[Plan] 创建计划 " + plan.getPlanId() + "，共 " + steps.size()
                + " 步，预算 " + plan.getMaxIterations() + " 轮");
        return plan;
    }

    // ==================== ② 更新单步（含核对与失败计数） ====================

    /**
     * 单步处理后的走向决策（抽成纯函数，便于用单测把规则钉死，不依赖模型行为去验证）。
     * 规则：
     *  通过 → DONE
     *  失败但没到阈值 → FAILED（等下次重试）
     *  失败到阈值 且 没拆过（depth=0）→ RESPLIT（允许拆一次）
     *  失败到阈值 且 已拆过（depth>=1）→ BLOCK（不再自己套娃，交给人）
     */
    public enum Decision { DONE, FAILED, RESPLIT, BLOCK }

    static Decision decide(boolean passed, int failCount, int resplitDepth) {
        if (passed) return Decision.DONE;
        if (failCount < PlanStep.RESPLIT_THRESHOLD) return Decision.FAILED;
        return resplitDepth == 0 ? Decision.RESPLIT : Decision.BLOCK;
    }

    /** updatePlanStep 的处理结果（含是否需要通知前端"人工介入"） */
    public static final class UpdateOutcome {
        public PlanStep step;
        public String finalStatus;
        public String message;        // 给模型看的一句话反馈
        public boolean shouldResplit; // 触发重拆
        public boolean blocked;       // 禁止再拆，需要人工介入
        public List<PlanStep> subSteps; // 重拆产生的子步骤
    }

    public synchronized UpdateOutcome updateStep(Long userId, String conversationId,
                                    String stepId, String reportedStatus, String actualResult) {
        UpdateOutcome out = new UpdateOutcome();
        TaskPlan plan = get(userId, conversationId);
        if (plan == null) {
            out.message = "当前会话没有计划，先调用 createPlan 建立计划。";
            return out;
        }
        PlanStep step = plan.findStep(stepId);
        if (step == null) {
            out.message = "找不到步骤 " + stepId + "，请用计划清单里的 stepId。";
            return out;
        }
        out.step = step;
        out.finalStatus = step.getStatus();
        TaskExecution task = TaskExecution.current();
        String status = reportedStatus == null ? "" : reportedStatus.trim().toLowerCase(java.util.Locale.ROOT);
        // 上报正文是模型自由文本，常常把命令、token、密码原样复述一遍。
        // 判定用原文（rawActual），落库/推前端只用脱敏结果，两边不混。
        String rawActual = actualResult == null ? "" : actualResult;
        // 重复上报判定只需要「是不是同一次上报」这个事实。用不可逆指纹代替原文：
        // 既不会把凭证写进 plan_json，也不会因为脱敏把两次不同的上报判成同一次。
        String reportKey = SensitiveRedactor.fingerprint(status + "\n" + rawActual);
        if (task != null && (task.finalizing() || task.approvalBlocked() || task.cancelled())) {
            out.message = "未更新计划：当前处于最终交付或审批阻塞状态；未执行的操作不能上报完成，也不累计失败。";
            return out;
        }
        if (!status.equals("done") && !status.equals("failed")) {
            out.message = "未更新计划：非法状态，只接受 done/failed；running 使用开始步骤入口。";
            return out;
        }
        boolean repeated = reportKey.equals(step.getLastReportKey());
        if (repeated && !step.isResplitDeferred()) {
            out.message = "重复上报已忽略，计划与失败次数不变。";
            return out;
        }
        if (PlanStep.DONE.equals(step.getStatus()) || PlanStep.BLOCKED.equals(step.getStatus())) {
            out.message = "未更新计划：已结束步骤不能被重复上报改写。";
            return out;
        }
        boolean requiresEvidence = TaskExecution.requiresEffectEvidence(step, actualResult);
        Map<String, String> files = requiresEvidence && status.equals("done") && task != null
                ? task.verifiedFiles(step) : Map.of();
        // F2：编译 / 执行命令 / 删除这类步骤没有文件指纹，用本次请求真实执行过的命令或删除记录作证据。
        Map<String, String> commandEvidence = requiresEvidence && status.equals("done") && task != null
                ? task.verifiedCommands(step) : Map.of();
        boolean verified = !files.isEmpty() || !commandEvidence.isEmpty();
        if (status.equals("done") && requiresEvidence && !verified) {
            out.message = "未更新计划：模型报告不等于执行证据；没有可明确归属此步骤的成功副作用，不能标记 DONE。";
            return out;
        }
        Map<String, String> allEvidence = new java.util.LinkedHashMap<>(files);
        allEvidence.putAll(commandEvidence);
        step.setLastReportKey(reportKey);
        step.setResplitDeferred(false);
        step.setResultVerified(verified);
        step.setVerifiedFiles(allEvidence);
        // 对外只留脱敏后的摘要。actualResult 会进 plan_json，也会经 SSE / HTTP 送到前端；
        // 原始正文只在本次判定的内存里用（下面的 PlanVerifier 与 requiresEffectEvidence）。
        step.setActualResult(SensitiveRedactor.redactText(rawActual));

        boolean llmSaysFailed = status.equals("failed");
        PlanVerifier.Result check = PlanVerifier.verify(step.getExpected(), rawActual);

        if (llmSaysFailed || !check.passed()) {
            step.setResultVerified(false);
            step.setStatus(PlanStep.FAILED);
            if (!repeated) step.setFailCount(step.getFailCount() + 1);
            out.finalStatus = PlanStep.FAILED;
            out.message = "步骤 " + stepId + " 判定为失败（" + (llmSaysFailed ? "模型上报失败" : check.reason())
                    + "），累计失败 " + step.getFailCount() + " 次。";

            // 失败达到阈值 → 触发一次重拆；已经拆过一层 → blocked（人工介入）
            Decision decision = decide(false, step.getFailCount(), step.getResplitDepth());
            if (decision == Decision.RESPLIT) {
                out.shouldResplit = true;
                try {
                    out.subSteps = resplit(userId, conversationId, plan, step);
                } catch (TaskExecution.BudgetExceeded | TaskExecution.Cancelled deferred) {
                    step.setResplitDeferred(true);
                    out.shouldResplit = false;
                    out.message += " 重拆已暂缓，后续请求可继续尝试。";
                    plan.bumpVersion();
                    persist(userId, conversationId, plan);
                    return out;
                }
                if (out.subSteps != null && !out.subSteps.isEmpty()) {
                    out.message += " 已把这一步拆成 " + out.subSteps.size() + " 个更细的子步骤，插入到原位置继续执行："
                            + String.join(" → ", out.subSteps.stream().map(PlanStep::getStepId).toList());
                } else {
                    // 拆不出来（模型没给出可解析的结果）→ 也要明确暴露，不要假装成功
                    blockStep(step);
                    out.blocked = true;
                    out.shouldResplit = false;
                    out.message += " 尝试重拆但没有得到可用的子步骤，已标记为 blocked。";
                }
            } else if (decision == Decision.BLOCK) {
                blockStep(step);
                out.blocked = true;
                out.message += " 该步骤已经重拆过一层（resplitDepth=" + step.getResplitDepth()
                        + "），不再自动重拆，已标记 blocked，需要人工介入。";
            }
        } else {
            step.setStatus(PlanStep.DONE);
            out.finalStatus = PlanStep.DONE;
            if (task != null && plan.allDone()) task.planCompleted(plan);
            out.message = "步骤 " + stepId + (verified ? " 已验证执行（" : " 已记录模型上报，尚非系统验证的交付（") + check.reason() + "）。";
        }

        plan.bumpVersion();   // 步骤状态变了 → 清单内容变了，版本号 +1
        persist(userId, conversationId, plan);
        return out;
    }

    /** 把某一步推进为 running（模型开始做这一步时可调用；不强求） */
    public synchronized void markRunning(Long userId, String conversationId, String stepId) {
        TaskPlan plan = get(userId, conversationId);
        if (plan == null) return;
        PlanStep step = plan.findStep(stepId);
        TaskExecution task = TaskExecution.current();
        if (task != null && (task.finalizing() || task.approvalBlocked() || task.cancelled())) return;
        if (step != null && (PlanStep.PENDING.equals(step.getStatus()) || PlanStep.FAILED.equals(step.getStatus()))) {
            step.setStatus(PlanStep.RUNNING);
            plan.bumpVersion();   // 图标从 ☐ 变成 ▶，清单内容变了
            persist(userId, conversationId, plan);
        }
    }

    private void blockStep(PlanStep step) {
        step.setStatus(PlanStep.BLOCKED);
        System.err.println("[Plan] 步骤 " + step.getStepId() + " 已 blocked（需人工介入）: " + step.getDescription());
    }

    // ==================== ③ 失败重拆（只允许一层） ====================

    /**
     * 调 LLM 把失败的这一步拆成更细的子步骤，插到原步骤后面。
     * 新子步骤：parentStepId = 原步骤，resplitDepth = 原步骤的深度 + 1（即 1）。
     * 子步骤产生的额外步骤会**同步抬高预算**（每多一步多 4 轮），否则拆完立刻又撞上限。
     */
    private List<PlanStep> resplit(Long userId, String conversationId, TaskPlan plan, PlanStep failed) {
        String prompt = "有一个执行步骤反复失败，请把它拆成 2~" + MAX_SUB_STEPS + " 个更小、更具体的子步骤。\n"
                + "原步骤：" + failed.getDescription() + "\n"
                + "预期产出：" + (failed.getExpected() == null || failed.getExpected().isBlank() ? "（未填）" : failed.getExpected()) + "\n"
                + "失败信息：" + (failed.getActualResult() == null ? "" : failed.getActualResult()) + "\n\n"
                + "要求：每个子步骤必须是一个可立即执行的具体动作（小到一次工具调用能做完）。\n"
                + "输出格式：每行一个子步骤，用 `描述 || 预期产出` 两个竖线分隔，不要编号、不要多余解释。";
        List<PlanStep> created = new ArrayList<>();
        try {
            String text = TaskModelCalls.auxiliaryText(chatModel, prompt).getResult().getOutput().getText();
            if (text == null) return created;
            int idx = plan.indexOf(failed.getStepId());
            int seq = 0;
            for (String line : text.split("\\r?\\n")) {
                String l = line.trim();
                if (l.isEmpty() || created.size() >= MAX_SUB_STEPS) continue;
                String[] parts = l.split("\\|\\|");
                String desc = parts[0].replaceAll("^[\\-*\\d.、)\\s]+", "").trim();
                String expected = parts.length > 1 ? parts[1].trim() : "";
                if (desc.length() < 2) continue;
                PlanStep sub = new PlanStep(failed.getStepId() + "-" + (++seq), desc, expected);
                sub.setParentStepId(failed.getStepId());
                sub.setResplitDepth(failed.getResplitDepth() + 1);   // 只允许 1 层：0 → 1
                plan.getSteps().add(Math.min(idx + seq, plan.getSteps().size()), sub);
                created.add(sub);
            }
            if (!created.isEmpty()) {
                // 步骤变多了，预算同步抬高（否则拆完自己就把预算吃掉了，等于没拆）
                plan.setMaxIterations(TaskPlan.budgetFor(plan.getSteps().size()));
                plan.bumpVersion();   // 清单里多了几个子步骤 → 内容变了
                System.out.println("[Plan] 重拆 " + failed.getStepId() + " → "
                        + created.stream().map(PlanStep::getStepId).toList() + "，预算调整为 " + plan.getMaxIterations());
            }
        } catch (TaskExecution.BudgetExceeded | TaskExecution.Cancelled deferred) {
            throw deferred;
        } catch (Exception e) {
            System.err.println("[Plan] 重拆失败: " + e.getMessage());
        }
        return created;
    }

    // ==================== ④ 读取 / 渲染 / 预算 ====================

    /** 取当前用户当前会话的计划（缓存 → DB → null）。缓存命中也必须对上 userId。 */
    public TaskPlan get(Long userId, String conversationId) {
        String key = cacheKey(userId, conversationId);
        if (key == null) return null;
        TaskPlan cached = cache.get(key);
        if (cached != null) return cached;
        String json = mapper.selectPlanJson(userId, conversationId);
        if (json == null || json.isBlank()) return null;
        try {
            TaskPlan plan = objectMapper.readValue(json, TaskPlan.class);
            cache.put(key, plan);
            return plan;
        } catch (Exception e) {
            System.err.println("[Plan] 反序列化失败: " + e.getMessage());
            return null;
        }
    }

    /** 当前会话是否存在计划（区分"有计划续预算"与"无计划兜底续预算"两条路） */
    public boolean hasPlan(Long userId, String conversationId) {
        return get(userId, conversationId) != null;
    }

    /**
     * 取当前计划的版本号（步骤状态每变一次 +1）；没有计划返回 -1。
     * slim 注入策略用它判断"要不要重新注入整张清单"。
     */
    public int versionOf(Long userId, String conversationId) {
        TaskPlan plan = get(userId, conversationId);
        return plan == null ? -1 : plan.getVersion();
    }

    /** 计划是否已全部完成（用于 harness 层的"强制收尾"判定） */
    public boolean allStepsDone(Long userId, String conversationId) {
        TaskPlan plan = get(userId, conversationId);
        return plan != null && plan.allDone();
    }

    /** 动态预算：有计划就按计划走，没有就沿用默认轮数 */
    public int budget(Long userId, String conversationId, int fallback) {
        TaskPlan plan = get(userId, conversationId);
        return plan == null ? fallback : Math.max(fallback, plan.getMaxIterations());
    }

    /**
     * 渲染成注入提示词的清单（每轮刷新）：
     *   【当前任务计划】进度 2/5　预算 剩余 9 / 共 20 轮
     *   ☑ s1 创建目录结构
     *   ▶ s2 编写 Student 类（预期：文件存在且能编译）
     *   ☐ s3 ...
     */
    public String render(Long userId, String conversationId, int usedIterations) {
        TaskPlan plan = get(userId, conversationId);
        if (plan == null) return null;
        plan.setCurrentIteration(usedIterations);

        int done = 0;
        for (PlanStep s : plan.getSteps()) if (PlanStep.DONE.equals(s.getStatus())) done++;
        StringBuilder sb = new StringBuilder();
        sb.append("【当前任务计划】进度 ").append(done).append("/").append(plan.getSteps().size());
        com.example.jingjingai2.agent.TaskExecution task =
                com.example.jingjingai2.agent.TaskExecution.current();
        if (task != null) {
            sb.append("　本次请求已用模型调用 ").append(task.calls())
              .append(" / 上限 ").append(task.limit())
              .append("，执行阶段还可 ").append(task.remainingExecutionCalls()).append(" 次\n");
        } else {
            sb.append('\n');
        }
        for (PlanStep s : plan.getSteps()) {
            sb.append(s.icon()).append(' ').append(s.getStepId()).append(' ').append(s.getDescription());
            if (PlanStep.FAILED.equals(s.getStatus()) && s.getFailCount() > 0) {
                sb.append("（已失败 ").append(s.getFailCount()).append(" 次）");
                if (s.isResplitDeferred()) sb.append("（重拆因本次预算暂缓，可继续）");
            }
            if (PlanStep.BLOCKED.equals(s.getStatus())) sb.append("（已阻塞，需人工介入，跳过它）");
            sb.append('\n');
        }
        // 说明（改动 3）：原来这里每轮都重复一段 ①②③④「执行要求」固定文案。它跟计划内容无关，
        // 却每轮重发一次 —— 这是规划最大的 token 开销来源（5 步计划跑 20 轮 ≈ 6k token 纯重复）。
        // 现已移到系统提示词（AgentPrompts 的「计划的执行规范」一条），会话内恒定不变，
        // 也顺带满足"系统提示词不变 → 利于服务商 prompt cache"的要求。
        // 这里只保留真正随状态变化的内容（进度/清单/预算提醒）。

        if (task != null && task.remainingExecutionCalls() <= 3) {
            sb.append("\n⚠️ 本次请求的执行调用还剩 ").append(task.remainingExecutionCalls())
              .append(" 次：请优先做完剩余步骤，并留出最后一次给用户的总结。总额度不会自动增加。");
        }
        return sb.toString();
    }

    /**
     * slim 注入用的**一行摘要**（多数轮次注入它，替代整张清单）。
     *
     * 为什么要有它：完整清单每轮重发一次很贵，而清单内容只在"步骤状态变化"时才变 ——
     * 状态没变时，模型只需要知道"我在第几步、当前该做哪个、还剩多少预算"，一行就够。
     * 至于"什么时候必须给完整清单"，由
     * {@link com.example.jingjingai2.utils.PlanInjectionPlanner} 统一裁决
     * （版本变化 / 距上次全量满 N 轮 / 刚压缩过上下文）。
     *
     * 形如：{@code [计划] 进度 3/7 | 当前：s4 创建 Product.java | 剩余预算 9 轮}
     */
    public String renderSlim(Long userId, String conversationId, int usedIterations) {
        TaskPlan plan = get(userId, conversationId);
        if (plan == null) return null;
        plan.setCurrentIteration(usedIterations);

        int done = 0;
        PlanStep current = null;
        for (PlanStep s : plan.getSteps()) {
            if (PlanStep.DONE.equals(s.getStatus())) { done++; continue; }
            if (current == null) current = s;   // 第一个还没做完的步骤 = 当前正在推进的那步
        }
        int remain = Math.max(0, plan.getMaxIterations() - usedIterations);

        StringBuilder sb = new StringBuilder();
        sb.append("[计划] 进度 ").append(done).append('/').append(plan.getSteps().size());
        sb.append(" | 当前：").append(current == null
                ? "全部步骤已完成，请用一段话向用户总结收尾"
                : current.getStepId() + " " + current.getDescription());
        sb.append(" | 剩余预算 ").append(remain).append(" 轮");
        // 预算将尽仍然要提醒收敛（这个和"要不要给全量清单"无关，属于关键决策信息）
        if (remain <= 3 && current != null) {
            sb.append("\n⚠️ 预算只剩 ").append(remain).append(" 轮：优先把剩余步骤一次做完，"
                    + "并留出最后一轮直接输出给用户的总结。");
        }
        return sb.toString();
    }

    /**
     * 新任务开始前的"计划重置"判定。
     *
     * 为什么必须有它（真实 bug）：计划是**会话级**状态。上个任务的计划全部 done 之后，
     * 用户在同一会话里说"重新做一个别的"时，harness 会立刻看到"计划全完成"→ 直接进强制收尾轮，
     * 新任务根本没被规划。（实测踩过：第 1 轮就打印"计划全部完成 → 进入收尾轮"。）
     *
     * 规则：**只有"还有未完成步骤"的计划才保留**（那是"继续做"的场景）；
     * 已经没有任何未完成步骤（全 done / 全 blocked）的计划属于上个任务，清掉。
     *
     * @return 是否清空了计划
     */
    public synchronized boolean resetIfFinished(Long userId, String conversationId) {
        TaskPlan plan = get(userId, conversationId);
        if (plan == null) return false;
        if (!plan.isDeliveryReturned() || !plan.allDone()) return false;   // 还有没做完的 → 保留，这是"继续做"
        String key = cacheKey(userId, conversationId);
        if (key != null) cache.remove(key);
        persist(userId, conversationId, null);
        System.out.println("[Plan] 上一个计划已无未完成步骤 → 已清空，本次将按新任务重新规划");
        return true;
    }

    /** Recheck supported writes after mutations and before offering delivery for the current plan. */
    public synchronized void revalidateFiles(Long userId, String conversationId, String tool, String args) {
        TaskPlan plan = get(userId, conversationId);
        if (plan == null) return;
        TaskExecution task = TaskExecution.current();
        boolean changed = false;
        for (PlanStep step : plan.getSteps()) {
            if (!PlanStep.DONE.equals(step.getStatus())) continue;
            if (!step.isResultVerified() && !TaskExecution.requiresEffectEvidence(step, step.getActualResult())) continue;
            if (step.isResultVerified() && TaskExecution.verifiedFilesStillCurrent(step)
                    && (task == null || !task.invalidatedFiles(step, tool, args))) continue;
            step.setResultVerified(false);
            step.setVerifiedFiles(Map.of());
            step.setLastReportKey(null);
            step.setStatus(PlanStep.PENDING);
            changed = true;
        }
        if (changed) {
            plan.setDeliveryReturned(false);
            plan.setDeliveryReceipt(null);
            plan.bumpVersion();
            persist(userId, conversationId, plan);
        }
    }

    public synchronized void noteDeliveryReturned(Long userId, String conversationId) {
        noteDeliveryReturned(userId, conversationId, get(userId, conversationId));
    }

    /** Synchronous replies have already been written to recoverable conversation history. */
    public synchronized void noteDeliveryReturned(Long userId, String conversationId, TaskPlan delivered) {
        if (!deliverable(userId, conversationId, delivered)) return;
        delivered.setDeliveryReturned(true);
        if (!persist(userId, conversationId, delivered)) delivered.setDeliveryReturned(false);
    }

    /** The client acknowledges the terminal event after applying the body. */
    public synchronized String offerDeliveryReceipt(Long userId, String conversationId, TaskPlan delivered) {
        if (!deliverable(userId, conversationId, delivered)) return null;
        String receipt = UUID.randomUUID().toString();
        delivered.setDeliveryReceipt(receipt);
        if (persist(userId, conversationId, delivered)) return receipt;
        delivered.setDeliveryReceipt(null);
        return null;
    }

    public synchronized boolean acknowledgeDelivery(Long userId, String conversationId, String receipt) {
        TaskPlan plan = get(userId, conversationId);
        if (receipt == null || !deliverable(userId, conversationId, plan)
                || !receipt.equals(plan.getDeliveryReceipt())) return false;
        if (plan.isDeliveryReturned()) return true;
        noteDeliveryReturned(userId, conversationId, plan);
        return plan.isDeliveryReturned();
    }

    private boolean deliverable(Long userId, String conversationId, TaskPlan plan) {
        return plan != null && plan == get(userId, conversationId) && plan.allDone()
                && plan.getSteps().stream().allMatch(step ->
                    !TaskExecution.requiresEffectEvidence(step, step.getActualResult())
                            || step.isResultVerified() && TaskExecution.verifiedFilesStillCurrent(step));
    }

    /** 清空当前用户当前会话的计划缓存（删除会话/新任务覆盖时用）。不碰其他用户。 */
    public void clear(Long userId, String conversationId) {
        String key = cacheKey(userId, conversationId);
        if (key != null) cache.remove(key);
    }

    private boolean persist(Long userId, String conversationId, TaskPlan plan) {
        try {
            return mapper.updatePlanJson(userId, conversationId,
                    plan == null ? null : objectMapper.writeValueAsString(plan)) > 0;
        } catch (Exception e) {
            System.err.println("[Plan] 持久化失败（不影响本轮执行）: " + e.getMessage());
            return false;
        }
    }

    /** 供前端/测试用：计划的只读快照 */
    public List<PlanStep> stepsOf(Long userId, String conversationId) {
        TaskPlan plan = get(userId, conversationId);
        return plan == null ? Collections.emptyList() : plan.getSteps();
    }
}
