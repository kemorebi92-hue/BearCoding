package com.example.jingjingai2.eval;

import java.util.List;

/** 一次判定。前提没满足是 INVALID，不是通过，也不是普通的不通过。 */
public record JudgeResult(Outcome outcome, List<Item> items) {

    public JudgeResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 没有前提条件时的两种结果。 */
    public JudgeResult(boolean passed, List<Item> items) {
        this(passed ? Outcome.PASS : Outcome.FAIL, items);
    }

    public boolean passed() {
        return outcome == Outcome.PASS;
    }

    public enum Outcome { PASS, FAIL, INVALID }

    public record Item(Check check, boolean passed, String detail) {}
}
