package com.example.jingjingai2.DTO;

/**
 * 计划中的一步（Plan-and-Execute 的 Executor 照着它干活）。
 *
 * 为什么计划要落成"带状态的对象"而不是一段文字：
 *  - 状态是**进度**的载体：每轮把它注入提示词，模型才知道"我在第几步、还剩几步"，
 *    这正是纯 ReAct 缺的那块"全局坐标系"（没有它就会撞轮数上限、重复劳动、目标漂移）；
 *  - 结构化之后前端才能画成可勾选的清单，让你一眼看出它卡在哪一步。
 *
 * 字段设计与 Plan-and-Execute 规格一致：
 *  - expected：规划时写的"验收标准"（做完应该达到什么状态），供自动核对用
 *  - actualResult：执行完实际产出的摘要，和 expected 做轻量核对
 *  - failCount：**每步各自计数**（不是全局），达到 3 才触发重拆
 *  - resplitDepth：0 = 原始步骤；1 = 已重拆过一层；>=1 时禁止再拆（只允许拆一层）
 */
public class PlanStep {

    /** 状态取值（用字符串而不是枚举：要随 plan_json 一起持久化、也要对模型可读） */
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE    = "done";
    public static final String FAILED  = "failed";
    public static final String BLOCKED = "blocked";

    /** 一次重拆的触发阈值（某步连续失败这么多次就拆） */
    public static final int RESPLIT_THRESHOLD = 3;

    private String stepId;
    private String parentStepId;    // null = 原始步骤；非 null = 由哪个步骤重拆而来
    private String description;     // 这一步要做什么
    private String expected;        // 验收描述（规划时写入）
    private String actualResult;    // 执行后的实际产出摘要
    private String status = PENDING;
    private int failCount;
    private boolean resultVerified;
    private java.util.Map<String, String> verifiedFiles = java.util.Map.of();
    private boolean resplitDeferred;
    public java.util.Map<String, String> getVerifiedFiles() { return verifiedFiles; }
    public void setVerifiedFiles(java.util.Map<String, String> value) { verifiedFiles = value; }
    public boolean isResplitDeferred() { return resplitDeferred; }
    public void setResplitDeferred(boolean value) { resplitDeferred = value; }
    private String lastReportKey;
    public boolean isResultVerified() { return resultVerified; }
    public void setResultVerified(boolean value) { resultVerified = value; }
    public String getLastReportKey() { return lastReportKey; }
    public void setLastReportKey(String value) { lastReportKey = value; }
    private int resplitDepth;       // 0 = 原始；1 = 已重拆一层（>=1 禁止再拆）

    public PlanStep() {}

    public PlanStep(String stepId, String description, String expected) {
        this.stepId = stepId;
        this.description = description;
        this.expected = expected;
    }

    /** 前端与提示词共用的状态图标 */
    public String icon() {
        switch (status == null ? PENDING : status) {
            case DONE: return "☑";
            case RUNNING: return "▶";
            case FAILED: return "✗";
            case BLOCKED: return "⛔";
            default: return "☐";
        }
    }

    /** 是否是"还需要继续做"的步骤（未完成且未被阻塞） */
    public boolean open() {
        return PENDING.equals(status) || RUNNING.equals(status) || FAILED.equals(status);
    }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public String getParentStepId() { return parentStepId; }
    public void setParentStepId(String parentStepId) { this.parentStepId = parentStepId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }

    public String getActualResult() { return actualResult; }
    public void setActualResult(String actualResult) { this.actualResult = actualResult; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getFailCount() { return failCount; }
    public void setFailCount(int failCount) { this.failCount = failCount; }

    public int getResplitDepth() { return resplitDepth; }
    public void setResplitDepth(int resplitDepth) { this.resplitDepth = resplitDepth; }
}
