package com.example.jingjingai2.eval;

/** 一次任务运行的记录。口令不进这个对象。 */
public record RunRecord(
        String taskId,
        int attempt,
        String model,
        String commit,
        int contextMaxTokens,
        String evalForceMode,
        boolean passed,
        FailureKind failureKind,
        int turns,
        int rounds,
        long totalTokens,
        String configJson) {

    /** 没有配置快照的记录。 */
    public RunRecord(String taskId, int attempt, String model, String commit, int contextMaxTokens,
                     String evalForceMode, boolean passed, FailureKind failureKind,
                     int turns, int rounds, long totalTokens) {
        this(taskId, attempt, model, commit, contextMaxTokens, evalForceMode, passed, failureKind,
                turns, rounds, totalTokens, null);
    }
}
