package com.example.jingjingai2.agent;

import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.permission.ToolCallContext.ApprovalLink;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * 子代理的**调度入口**：闸门检查 → 进房间（或续跑） → 记笔记 → 交回一段给模型看的中文。
 *
 * 谁调它：
 *  - {@link com.example.jingjingai2.utils.SubagentTool}（模型直接调工具那条路，单个派出）
 *  - {@code ChatController}（按原工具顺序通过 {@link #runBatch} 派发）
 *
 * 为什么把"闸门"放在这里而不是池里：池只管并发，**业务闸门**（功能开关、宽泛目标闸、同 goal 重派/续跑上限）
 * 属于调度语义。放一处，两条调用路径共用同一套规则，不会出现"从工具走有上限、从批量走没上限"。
 *
 * 第二轮改造后，闸门按顺序是：
 * <pre>
 *   ① 功能开关 → ② 目标非空 → ③（带 resumeTaskId 的走续跑路径）
 *   → ④ 宽泛目标闸（第一次拦、第二次放行）→ ⑤ 同 goal 重派上限 → ⑥ implement 写互斥 → ⑦ 并发满
 * </pre>
 * ④⑤⑥⑦ 全部返回**错误 tool result**（主 agent 读得到、能自己决策），而不是静默排队或抛异常。
 */
@Service
public class SubagentService {

    /** 事件出口：非流式链路传 null（没有连接可推），SSE 链路传 emitter 的包装 */
    public interface EventSink {
        void emit(String event, Map<String, Object> payload);
    }

    /**
     * 一次派发的结果。**文本**是回灌给模型的工具结果，**ok** 是给审计和前端卡片用的。
     * 这里的 ok 判据是**完整性**（COMPLETE / PARTIAL 都算拿到了东西），不是"停因是不是 SUCCESS" ——
     * 轮数用尽的 PARTIAL 也应该在前端显示成黄色警告，而不是红色失败。
     */
    public record SpawnOutcome(String text, boolean ok, boolean skipParentCompile) {
        public SpawnOutcome(String text, boolean ok) {
            this(text, ok, false);
        }
    }

    /** 一次派发失败时（批量通道漏掉、被摘掉）兜底用的文本 */
    public static final String NO_RESULT_TEXT = "子代理调用未返回结果（本轮已拒绝或异常）。请自己做这一步。";

    private final SubagentProperties props;
    private final SubagentRunner runner;
    private final SubagentPool pool;
    private final SubagentMemory memory;
    private final SubagentSessions sessions;
    private final SubagentTelemetry telemetry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubagentService(SubagentProperties props, SubagentRunner runner,
                           SubagentPool pool, SubagentMemory memory,
                           SubagentSessions sessions, SubagentTelemetry telemetry) {
        this.props = props;
        this.runner = runner;
        this.pool = pool;
        this.memory = memory;
        this.sessions = sessions;
        this.telemetry = telemetry;
    }

    /** 工具名判定（兼容下划线与大小写） */
    public static boolean isSpawnTool(String name) {
        if (name == null) return false;
        return name.replace("_", "").equalsIgnoreCase("spawnsubagent");
    }

    /**
     * 这次派发是不是 implement（会写文件的那一种）。
     * 主循环靠它决定"要不要给这一轮打上 filesModified"—— implement 子代理改过的文件
     * 同样应该走主循环的编译校验闭环，否则"子代理改坏了"没人发现。
     */
    public boolean isImplementSpawn(AssistantMessage.ToolCall call) {
        return isImplementSpawn(call, null);
    }

    public boolean isImplementSpawn(AssistantMessage.ToolCall call, String conversationId) {
        if (call == null || !isSpawnTool(call.name())) return false;
        return !specOf(call, conversationId).mode().isReadOnly();
    }

    /** 写子代理是否正在持锁（父代理写守卫用） */
    public boolean isImplementRunning() {
        return pool.isImplementRunning();
    }

    /**
     * 父循环不因子代理自称成功就跳过 javac。
     * 完工单自动放行已经去掉，PARTIAL / 完成都交回主代理决定要不要再校验。
     */
    public boolean skipParentCompile(AssistantMessage.ToolCall call, String toolResult) {
        return false;
    }

    /** 新用户请求开始：上一个请求留下的便条/房间历史/观测数据都没用了，立刻释放 */
    public void beginUserRequest(String conversationId) {
        memory.releaseConversation(conversationId);
        sessions.releaseConversation(conversationId);
        telemetry.beginUserRequest(conversationId);
    }

    /**
     * 结论出来了（计划全部完成 / resetIfFinished）→ 释放该会话**已完成**的便条与房间历史。
     * **PARTIAL 的留着**：主 agent 可能马上要续跑它。
     * 观测数据（成本）**不清** —— 请求结束时还要打汇总行。
     */
    public void releaseOnConclusion(String conversationId) {
        memory.releaseCompleted(conversationId);
        sessions.releaseCompleted(conversationId);
    }

    /** 兼容旧签名：只给 goal + mode */
    public String spawnOnce(String goal, String mode, ChatModel model, String conversationId) {
        return spawnOnce(new SubagentSpec(goal, SubagentSpec.Mode.parse(mode), 0, conversationId), model);
    }

    /**
     * 单个派出（{@link com.example.jingjingai2.utils.SubagentTool} 直达路径）。
     * 返回的是**给模型看的一段中文**（子代理摘要 + 停因 + 读台账 + 下一步）。
     */
    public String spawnOnce(SubagentSpec spec, ChatModel model) {
        return execute(spec, model).toModelText(props.getMaxSummaryChars(), props.isPartialResumeFirst());
    }

    /**
     * 这次 spawn 是不是**只读**子代理（explore 类）。
     *
     * <p>只有只读房间允许并行开：写子代理共用一把 implement 锁，同时开第二个会被
     * {@link SubagentPool#tryAcquireImplement()} 当场拒掉 —— 串行时代它排在后面等锁，
     * 行为会从"排队"变成"被拒"。所以批量通道只收只读派发，写派发仍走主循环逐个执行。</p>
     *
     * <p>解析不出 mode 时按只读处理（{@link SubagentSpec.Mode#parse} 的空值语义就是 EXPLORE），
     * 与"不填 mode 默认 explore"的既有约定一致。</p>
     */
    public boolean isReadOnlySpawn(AssistantMessage.ToolCall call, String conversationId) {
        if (call == null || !isSpawnTool(call.name())) return false;
        return specOf(call, conversationId).mode().isReadOnly();
    }

    /**
     * 一轮里的**多个** spawn：全部先提交（真正的并行），再按 toolCallId 收齐。
     * 这是相对"顺序 callToolChecked"的关键差异 —— 墙钟上真的重叠。
     *
     * <p><b>调用方负责只传只读派发</b>（见 {@link #isReadOnlySpawn}）：写子代理共用 implement 锁，
     * 混进来只会立刻被拒。</p>
     *
     * @return toolCallId → 派发结果（文本 + 是否拿到了可用结论）
     */
    public Map<String, SpawnOutcome> runBatch(List<AssistantMessage.ToolCall> calls,
                                              ChatModel model,
                                              String conversationId,
                                              EventSink sink) {
        Map<String, SpawnOutcome> out = new LinkedHashMap<>();
        if (calls == null || calls.isEmpty()) return out;

        List<String> ids = new ArrayList<>();
        List<Job> jobs = new ArrayList<>();
        List<Future<SubagentResult>> futures = new ArrayList<>();

        for (AssistantMessage.ToolCall call : calls) {
            if (!isSpawnTool(call.name())) continue;
            Job job = prepare(specOf(call, conversationId));
            if (job.rejected != null) {
                // 闸门直接拒：根本不进房间，但前端仍要给一张"被拒绝"的卡片
                SubagentLog.line("拒绝派出 mode=" + job.spec.mode().name().toLowerCase()
                        + " 原因=" + job.rejected.failureReason() + " goal=" + shortGoal(job.spec.goal()));
                emitStart(sink, job.spec);
                emitDone(sink, job.spec, job.rejected);
                out.put(call.id(), outcomeOf(job.rejected));
                continue;
            }
            // start 卡**在提交之前**推：这样"进行中"的卡片会立刻出现在前端，
            // 而不是等子代理跑完才和 done 卡一起冒出来（整批 await 之前，读起来像卡住了）。
            // 提交失败（并发已满）也走过这一行，所以 start/done **永远配对**。
            emitStart(sink, job.spec);
            Future<SubagentResult> f = submit(job, model);
            if (f == null) {
                SubagentResult rejected = SubagentResult.rejected(job.spec.goal(), job.spec.mode(),
                        "同时运行的子代理已达上限 " + pool.maxConcurrent()
                                + " 个。请稍后再派，或把目标收窄后自己做这一步。");
                SubagentLog.line("拒绝派出（并发已满 " + pool.maxConcurrent() + "）goal="
                        + shortGoal(job.spec.goal()));
                emitDone(sink, job.spec, rejected);
                out.put(call.id(), outcomeOf(rejected));
                continue;
            }
            ids.add(call.id());
            jobs.add(job);
            futures.add(f);
        }

        for (int i = 0; i < futures.size(); i++) {
            Job job = jobs.get(i);
            SubagentResult result = settle(job, model, await(futures.get(i), job.spec));
            emitDone(sink, job.spec, result);
            out.put(ids.get(i), outcomeOf(result));
        }
        return out;
    }

    // ==================== 内部：一次派发的完整生命周期 ====================

    /** 一次派发的全部上下文（房间怎么开、开了之后往哪记账） */
    private static final class Job {
        final SubagentSpec spec;
        /** 续跑：上一个房间的历史（null = 全新房间） */
        final List<Message> history;
        final List<String> unresolved;
        final List<ReadLedgerEntry> previousLedger;
        /** 续跑：接的是哪个房间（null = 新房间） */
        final SubagentSessions.Session session;
        /** 同一 goal 的上次便条（新房间/降级续跑时注入） */
        final LastRunNote previous;
        /** 续跑方式的说明（正常接历史 / 降级为新房间 + 注入摘要） */
        final String resumeNote;
        final boolean implement;
        /** 只有新开房间的成功提交才计入重派次数。锁拒绝、并发满和内部重派都不计。 */
        boolean chargeAttempt;
        /** 房间把手（工作线程里填，主线程 future.get() 之后读） */
        volatile SubagentRunner.Room room;
        SubagentResult rejected;

        Job(SubagentSpec spec, List<Message> history, List<String> unresolved,
            List<ReadLedgerEntry> previousLedger, SubagentSessions.Session session,
            LastRunNote previous, String resumeNote, boolean implement) {
            this.spec = spec;
            this.history = history;
            this.unresolved = unresolved;
            this.previousLedger = previousLedger;
            this.session = session;
            this.previous = previous;
            this.resumeNote = resumeNote;
            this.implement = implement;
        }

        static Job rejected(SubagentSpec spec, SubagentResult result) {
            Job j = new Job(spec, null, null, null, null, null, "", false);
            j.rejected = result;
            return j;
        }
    }

    /** 单个派出（工具直达路径）：一条 Job 走完全程 */
    private SubagentResult execute(SubagentSpec spec, ChatModel model) {
        Job job = prepare(spec);
        if (job.rejected != null) {
            SubagentLog.line("拒绝派出 原因=" + job.rejected.failureReason() + " goal=" + shortGoal(spec.goal()));
            return job.rejected;
        }
        Future<SubagentResult> f = submit(job, model);
        if (f == null) {
            return SubagentResult.rejected(spec.goal(), spec.mode(),
                    "同时运行的子代理已达上限 " + pool.maxConcurrent() + " 个。请稍后再派，或自己做这一步。");
        }
        return settle(job, model, await(f, spec));
    }

    /**
     * 房间首轮就完全失败（toolCalls=0 且 completeness=NONE，区别于正常 PARTIAL）时，
     * 自动再开一次房间跑同一任务。不占同 goal 重派上限，也不把第一次空失败记进 telemetry
     * （避免看起来像「子代理已经失败、该父代理接手」）。
     * 第二次仍空失败，才把结果交回现有父代理接管兜底。
     */
    private SubagentResult settle(Job job, ChatModel model, SubagentResult result) {
        Job effective = job;
        if (isVirginCrash(job, result)) {
            SubagentLog.line("autoRedispatch=true reason=first-round-empty-crash"
                    + " toolCalls=0 completeness=NONE goal=" + shortGoal(job.spec.goal()));
            Job retry = redispatchJob(job.spec);
            if (retry.rejected == null) {
                Future<SubagentResult> f2 = submit(retry, model);
                if (f2 != null) {
                    result = await(f2, retry.spec);
                    effective = retry;
                }
            }
        }
        return finish(effective, result);
    }

    /** 全新房间、首轮零工具且没有任何可用结论。续跑 / 闸门拒绝 / 取消不走这条。 */
    private static boolean isVirginCrash(Job job, SubagentResult r) {
        if (job == null || job.spec == null || r == null) return false;
        if (!job.spec.resumeTaskId().isBlank()) return false;
        if (job.history != null || job.session != null) return false;
        if (r.cancelled()) return false;
        if (r.failureReason() == SubagentResult.FailureReason.REJECTED
                || r.failureReason() == SubagentResult.FailureReason.RETRY_LIMIT) {
            return false;
        }
        return r.toolCalls() == 0 && r.completeness() == SubagentReport.Status.NONE;
    }

    /** 同一任务再开一间房：不计 attempts，start 日志带 autoRedispatch。 */
    private Job redispatchJob(SubagentSpec spec) {
        LastRunNote previous = memory.noteOf(spec.conversationId(), spec.goalKey());
        boolean implement = !spec.mode().isReadOnly();
        if (implement && !pool.tryAcquireImplement()) {
            return Job.rejected(spec, SubagentResult.rejected(spec.goal(), spec.mode(),
                    "已经有一个 implement 子代理在跑，写操作不允许并行。请等它结束后再派，或自己做这一步。"));
        }
        SubagentLog.line("start mode=" + spec.mode().name().toLowerCase()
                + " rounds<=" + props.effectiveMaxRounds(spec.maxRounds())
                + " thoroughness=" + (spec.thoroughness().isBlank() ? "default" : spec.thoroughness())
                + " scope=" + (spec.hasScope() ? shortGoal(spec.scope()) : "(未填)")
                + " autoRedispatch=true"
                + " goal=" + shortGoal(spec.goal()));
        return new Job(spec, null, null, null, null, previous, "", implement);
    }

    /**
     * 闸门 + 开房间的准备工作。返回的 Job 上若 {@code rejected != null}，表示**没进房间**。
     */
    private Job prepare(SubagentSpec spec) {
        String conversationId = spec.conversationId();
        String gate = gateReason(spec);
        if (gate != null) return Job.rejected(spec, gateResult(spec, gate));

        // ===== 续跑路径 =====
        if (!spec.resumeTaskId().isBlank()) {
            Job resumed = resumeJob(spec);
            if (resumed != null) return resumed;
            SubagentResult invalid = SubagentResult.rejected(spec.goal(), spec.mode(),
                    "续跑凭据 " + spec.resumeTaskId() + " 已失效：不属于本会话，或房间已过期。"
                            + "不会降级成新的 explore 房间。");
            SubagentLog.line("续跑凭据失效 " + spec.resumeTaskId() + " goal=" + shortGoal(spec.goal()));
            return Job.rejected(spec, invalid);
        }

        LastRunNote previous = memory.noteOf(conversationId, spec.goalKey());
        return newJob(spec, previous, "");
    }

    /** 新开一个房间（含 implement 写互斥闸 + 派出计数） */
    private Job newJob(SubagentSpec spec, LastRunNote previous, String resumeNote) {
        String conversationId = spec.conversationId();
        boolean implement = !spec.mode().isReadOnly();
        if (implement && !pool.tryAcquireImplement()) {
            SubagentResult r = SubagentResult.rejected(spec.goal(), spec.mode(),
                    "已经有一个 implement 子代理在跑，写操作不允许并行。请等它结束后再派，或自己做这一步。");
            return Job.rejected(spec, r);
        }
        SubagentLog.line("start mode=" + spec.mode().name().toLowerCase()
                + " rounds<=" + props.effectiveMaxRounds(spec.maxRounds())
                + " thoroughness=" + (spec.thoroughness().isBlank() ? "default" : spec.thoroughness())
                + " scope=" + (spec.hasScope() ? shortGoal(spec.scope()) : "(未填)")
                + " goal=" + shortGoal(spec.goal()));
        Job job = new Job(spec, null, null, null, null, previous, resumeNote, implement);
        job.chargeAttempt = true;
        return job;
    }

    /**
     * 续跑：找到房间 → 校验续跑次数 → 用保留的历史开跑。
     *
     * @return 可跑的 Job；凭据找不到时返回 null（调用方降级为新房间 + 注入上次便条）
     */
    private Job resumeJob(SubagentSpec spec) {
        String conversationId = spec.conversationId();
        SubagentSessions.Session session = sessions.find(conversationId, spec.resumeTaskId());
        if (session == null) return null;

        int used = memory.resumesOf(conversationId, spec.goalKey());
        if (used >= props.getMaxResumesPerGoal()) {
            SubagentResult r = SubagentResult.rejected(spec.goal(), session.mode(),
                    "同一目标最多续跑 " + props.getMaxResumesPerGoal() + " 次，已经用完。"
                            + "请自己做这一步，或把目标再收窄一点重新派。");
            return Job.rejected(spec, r);
        }
        // 续跑白名单跟原房间走，不跟调用方填的 mode 走：PARTIAL 提示里的可复制调用不带 mode，
        // Mode.parse(空) 会落成 EXPLORE，写工具会被拒，父代理却可能把 COMPLETE 当成交接。
        if (spec.mode() != session.mode()) {
            SubagentLog.line("续跑沿用原 mode=" + session.mode().name().toLowerCase()
                    + "（调用方给的是 " + spec.mode().name().toLowerCase() + "）");
        }
        boolean implement = !session.mode().isReadOnly();
        if (implement && !pool.tryAcquireImplement()) {
            return Job.rejected(spec, SubagentResult.rejected(spec.goal(), session.mode(),
                    "已经有一个 implement 子代理在跑，写操作不允许并行。"));
        }
        memory.recordResume(conversationId, spec.goalKey());

        // 续跑预算：resumeRounds + 1（收尾轮另算一轮）
        int rounds = props.getResumeRounds() + 1;
        SubagentSpec resumeSpec = new SubagentSpec(spec.goal(), spec.question(), spec.scope(),
                spec.doneWhen(), session.mode(), rounds, conversationId,
                spec.thoroughness(), spec.resumeTaskId());
        List<String> unresolved = session.lastReport() == null
                ? List.of() : session.lastReport().unresolved();
        List<Message> history = session.historySnapshot();
        SubagentLog.line("resume " + spec.resumeTaskId() + " mode=" + session.mode().name().toLowerCase()
                + " rounds<=" + rounds + " 缺口=" + unresolved.size()
                + " 历史消息=" + history.size() + " goal=" + shortGoal(spec.goal()));
        return new Job(resumeSpec, history, unresolved, session.ledger().entries(), session,
                null, "接了上次的房间历史（前缀不变）；只补未覆盖的问题", implement);
    }

    /** 真正提交到池里。返回 null = 并发已满（调用方翻译成错误 tool result） */
    private Future<SubagentResult> submit(Job job, ChatModel model) {
        final SubagentSpec spec = job.spec;
        final SubagentRunner.Room room = job.history == null
                ? SubagentRunner.Room.fresh()
                : SubagentRunner.Room.resume(job.history, job.unresolved, job.previousLedger,
                        job.session == null ? spec.mode() : job.session.mode());
        final ApprovalLink approvalLink = ApprovalLink.capture(shortGoal(spec.goal()));
        try {
            Future<SubagentResult> submitted = pool.trySubmit(spec, () -> {
                try (ToolCallContext.Scope ignored = ApprovalLink.attach(approvalLink)) {
                    return runner.run(spec, model, job.previous, room).withResumeNote(job.resumeNote);
                } catch (TaskExecution.BudgetExceeded refused) {
                    // 主任务已进入收尾、或剩余调用不够 → 重开房间只会撞同一堵墙，直接如实报"被拒"，
                    // 不要落到 crashed/TOOL_FAILURES 上：那条路径会被当成"首轮空失败"再重派一次。
                    System.err.println("[Subagent] 房间被预算闸门拒绝: " + refused.getMessage());
                    return SubagentResult.rejected(spec.goal(), spec.mode(),
                            "本次请求剩余调用不足（子代理与主任务共用同一份额度），"
                                    + "不再新开房间。请主 agent 直接自己做这一步。");
                } catch (Exception e) {
                    System.err.println("[Subagent] 房间内部异常: " + e);
                    return SubagentResult.crashed(spec.goal(), spec.mode(),
                            String.valueOf(e.getMessage()), room.roundsUsed, 0);
                } finally {
                    if (job.implement) pool.releaseImplement();
                    if (job.session != null) {
                        // 续跑：把新历史交回会话保管（失败路径也别丢，还能接着查）
                        job.session.replaceHistory(SubagentRunner.historyForResume(room.messages));
                        job.session.addAll(room.ledger);
                        job.session.markResumed();
                    } else {
                        job.room = room;
                    }
                }
            });
            // 许可满：callable 根本不跑，finally 不会释放写锁 —— 必须在这里还
            if (submitted == null && job.implement) pool.releaseImplement();
            if (submitted != null && job.chargeAttempt) {
                memory.recordAttempt(spec.conversationId(), spec.goalKey());
            }
            return submitted;
        } catch (Exception e) {
            if (job.implement) pool.releaseImplement();
            return null;
        }
    }

    /** 跑完之后：留房间（发凭据）、记笔记、记成本、打日志 */
    private SubagentResult finish(Job job, SubagentResult result) {
        String conversationId = job.spec.conversationId();

        // ① 可续跑的房间：历史留着，把凭据写进工具结果（主 agent 抄回来就能续跑）
        if (job.session != null) {
            job.session.lastReport(result.report());
            if (result.completeness() == SubagentReport.Status.COMPLETE) {
                sessions.close(conversationId, job.session.resumeId());
            } else {
                result = result.withResumeId(job.session.resumeId());
            }
        } else if (job.room != null
                && result.completeness() == SubagentReport.Status.PARTIAL
                && props.resumeEnabled()
                && !result.cancelled()) {
            SubagentSessions.Session s = sessions.open(conversationId, job.spec,
                    SubagentRunner.historyForResume(job.room.messages));
            s.addAll(job.room.ledger);
            s.lastReport(result.report());
            result = result.withResumeId(s.resumeId());
        }

        // ② 便条：重派（新房间）与降级续跑都靠它。
        //    **完整的就不留**（留着只会在下次注入里当噪音），而且要把上次的 PARTIAL 便条**清掉**
        //    —— 不清的话，同一个目标下次再派时注入的是一张过期的"上次没查清"。
        if (result.failureReason() != SubagentResult.FailureReason.RETRY_LIMIT
                && result.failureReason() != SubagentResult.FailureReason.REJECTED
                && result.completeness() != SubagentReport.Status.COMPLETE) {
            memory.remember(conversationId, job.spec.goalKey(),
                    LastRunNote.of(result, props.getMaxSummaryChars()));
        } else if (result.completeness() == SubagentReport.Status.COMPLETE) {
            memory.forget(conversationId, job.spec.goalKey());
        }

        // ③ 成本 + "读过哪些文件"（主 agent 的冗余读提示靠后者）
        telemetry.recordChildRun(conversationId, eventId(job.spec), result, result.usage());
        if (result.handoff() != null && result.handoff().demoted()) {
            telemetry.noteHandoffDemotion(conversationId);
        }

        logDone(job.spec, result);
        return result;
    }

    private SubagentResult await(Future<SubagentResult> future, SubagentSpec spec) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            TaskExecution task = TaskExecution.current();
            if (task != null) task.disconnect();
            Thread.currentThread().interrupt();
            return SubagentResult.cancelled(spec.goal(), spec.mode(), 0, 0, 0, java.util.Set.of());
        } catch (Exception e) {
            return SubagentResult.crashed(spec.goal(), spec.mode(), String.valueOf(e.getMessage()), 0, 0);
        }
    }

    private SpawnOutcome outcomeOf(SubagentResult r) {
        return new SpawnOutcome(r.toModelText(props.getMaxSummaryChars(), props.isPartialResumeFirst()),
                r.completeness() != SubagentReport.Status.NONE, false);
    }

    // ==================== 闸门 ====================

    /** 进房间**之前**的闸门：功能开关 + 目标非空 + 宽泛目标闸 + 同 goal 重派上限 */
    private String gateReason(SubagentSpec spec) {
        if (!props.isEnabled()) return "子代理功能未启用（app.subagent.enabled=false）";
        if (spec.goal().isBlank() && spec.question().isBlank()) return "目标为空";
        // 主任务剩余调用不足（或已进入收尾）→ 别再开房间。子代理与主任务共用同一份额度，
        // 开房只会立刻被预算闸门拒掉，还会被当成"首轮空失败"白重派一次。
        TaskExecution budget = TaskExecution.current();
        if (budget != null && budget.auxiliaryShareExhausted())
            return "本次请求剩余调用不足（子代理与主任务共用同一份额度）。"
                    + "不要再派子代理：请主 agent 直接自己做这一步，或让用户在新的一条消息里再试。";
        // 续跑的目标本来就是上次那个（闸门在上一趟已经过了一遍），不再重复拦
        if (!spec.resumeTaskId().isBlank()) return null;

        // 宽泛目标闸：**同一个宽泛目标只拦第一次**。第二次放行 —— 否则主 agent 换个措辞还是被拦，变成死循环。
        String vagueSource = spec.goal().isBlank() ? spec.question() : spec.goal();
        if (SubagentSpec.looksBroad(vagueSource) && !spec.hasScope()) {
            if (memory.markVagueNudged(spec.conversationId(), spec.vagueKey())) {
                return "目标太宽泛（\"" + shortGoal(vagueSource) + "\"）且没有指定 scope";
            }
        }
        int used = memory.attemptsOf(spec.conversationId(), spec.goalKey());
        int allowed = props.totalAttemptsAllowed();
        if (used >= allowed) {
            return "同一目标本轮已派出 " + used + " 次（上限 " + allowed + " 次）";
        }
        return null;
    }

    private SubagentResult gateResult(SubagentSpec spec, String gate) {
        if (gate.startsWith("同一目标本轮已派出")) {
            return SubagentResult.retryLimit(spec.goal(), spec.mode(),
                    memory.attemptsOf(spec.conversationId(), spec.goalKey()), props.totalAttemptsAllowed());
        }
        if (gate.startsWith("目标太宽泛")) {
            return SubagentResult.rejected(spec.goal(), spec.mode(),
                    gate + "。**先把目标收窄再派**：补上 scope（例如 scope=\"src/main/java/com/example/jingjingai2/plan\"），"
                            + "并用 question 写清要回答的那一个具体问题。"
                            + "如果你确认这一轮就是要全面扫一遍，那就**再调一次同样的调用**"
                            + "（同一个宽泛目标只会被拦这一次）。");
        }
        return SubagentResult.rejected(spec.goal(), spec.mode(), gate + "。请自己做这一步。");
    }

    // ==================== 日志与事件 ====================

    private void logDone(SubagentSpec spec, SubagentResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("done mode=").append(spec.mode().name().toLowerCase())
          .append(" completeness=").append(result.completeness())
          .append(" rounds=").append(result.roundsUsed()).append('/').append(result.maxRounds())
          .append(" toolCalls=").append(result.toolCalls())
          .append(" filesRead=").append(result.filesRead().size())
          .append(" wrapUp=").append(result.wrapUp())
          .append(" resumed=").append(result.resumed())
          .append(" fixedOverheadTokens=").append(result.fixedOverheadTokens())
          .append(" failureReason=").append(result.failureReason());
        if (result.resumeId() != null && !result.resumeId().isBlank()) {
            sb.append(" resumeTaskId=").append(result.resumeId());
        }
        if (result.usage() != null && result.usage().hasData()) {
            sb.append(" usage=").append(result.usage())
              .append(" cost=").append(result.usage().cost(props));
        }
        sb.append(" goal=").append(shortGoal(spec.goal()));
        SubagentLog.line(sb.toString());
        SubagentLog.line(HandoffResult.formatExecuted(result.executedCommands()));
    }

    private void emitStart(EventSink sink, SubagentSpec spec) {
        if (sink == null) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "subagent_start");
        p.put("id", eventId(spec));
        p.put("mode", spec.mode().name().toLowerCase());
        p.put("goal", spec.goal());
        p.put("question", spec.question());
        p.put("scope", spec.scope());
        p.put("resumed", !spec.resumeTaskId().isBlank());
        if (!spec.resumeTaskId().isBlank()) p.put("resumeTaskId", spec.resumeTaskId());
        sink.emit("subagent_start", p);
    }

    /** 前端靠这几个字段决定"黄（PARTIAL）还是红（NONE）"，以及展开时显示什么 */
    private void emitDone(EventSink sink, SubagentSpec spec, SubagentResult result) {
        if (sink == null) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "subagent_done");
        p.put("id", eventId(spec));
        p.put("mode", spec.mode().name().toLowerCase());
        p.put("goal", spec.goal());
        p.put("ok", result.ok());
        p.put("completeness", result.completeness().name());
        p.put("failureReason", result.failureReason().name());
        p.put("rounds", result.roundsUsed());
        p.put("maxRounds", result.maxRounds());
        p.put("toolCalls", result.toolCalls());
        p.put("filesRead", result.filesRead());
        p.put("summary", result.answer());
        p.put("answer", result.answer());
        p.put("unresolved", result.report() == null ? List.of() : result.report().unresolved());
        p.put("wrapUp", result.wrapUp());
        p.put("resumed", result.resumed());
        p.put("resumeTaskId", result.resumeId());
        p.put("fixedOverheadTokens", result.fixedOverheadTokens());
        p.put("resumeNote", result.resumeNote());
        List<String> ledger = new ArrayList<>();
        if (result.report() != null) {
            for (ReadLedgerEntry e : result.report().readLedger()) ledger.add(e.line());
        }
        p.put("readLedger", ledger);
        List<Map<String, Object>> cmds = new ArrayList<>();
        for (HandoffResult.ExecutedCommand c : result.executedCommands()) {
            if (c == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("command", c.command());
            row.put("exitCode", c.exitCode());
            cmds.add(row);
        }
        p.put("executedCommands", cmds);
        sink.emit("subagent_done", p);
    }

    private final java.util.concurrent.atomic.AtomicInteger eventSeq = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Map<SubagentSpec, String> eventIds = new java.util.concurrent.ConcurrentHashMap<>();

    /** 同一次派发的 start/done 共用一个 id；相同 goal 的下一次派发不会串到上一张卡。 */
    private String eventId(SubagentSpec spec) {
        return eventIds.computeIfAbsent(spec, s -> "d" + Integer.toHexString(eventSeq.incrementAndGet()));
    }

    // ==================== 参数解析 ====================

    private SubagentSpec specOf(AssistantMessage.ToolCall call, String conversationId) {
        Map<String, Object> args = parseArgs(call.arguments());
        String goal = str(args.get("goal"));
        if (goal == null || goal.isBlank()) goal = str(args.get("task"));
        if (goal == null || goal.isBlank()) goal = str(args.get("objective"));
        String thoroughness = str(args.get("thoroughness"));
        Integer maxRounds = asInt(args.get("maxRounds"));
        String resumeId = str(args.get("resumeTaskId"));
        SubagentSpec.Mode mode = SubagentSpec.Mode.parse(str(args.get("mode")));
        if (resumeId != null && !resumeId.isBlank()) {
            SubagentSessions.Session session = sessions.find(conversationId, resumeId);
            if (session != null) mode = session.mode();
        }
        int rounds = (thoroughness != null && !thoroughness.isBlank())
                ? props.roundsForThoroughness(thoroughness)
                : (maxRounds == null ? 0 : maxRounds);
        return new SubagentSpec(goal == null ? "" : goal,
                str(args.get("question")), str(args.get("scope")), str(args.get("doneWhen")),
                mode,
                rounds, conversationId, thoroughness, resumeId);
    }

    private Map<String, Object> parseArgs(String arguments) {
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return null;
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private static String shortGoal(String goal) {
        if (goal == null) return "";
        String s = goal.replaceAll("\\s+", " ").trim();
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
