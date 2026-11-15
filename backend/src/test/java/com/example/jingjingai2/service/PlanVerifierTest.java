package com.example.jingjingai2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划步骤的自动核对逻辑测试。
 *
 * 为什么这块必须测：判错会累加 failCount，而 failCount 到 3 会触发重拆甚至 blocked ——
 * **误判的代价是"把做好的步骤当成失败的"**，所以这里的原则是"宁可放过，不要误判"，
 * 测试重点就是"不该判失败的情况一律要通过"。
 */
class PlanVerifierTest {

    @Test
    @DisplayName("没有产出 → 不通过")
    void emptyActualFails() {
        assertFalse(PlanVerifier.verify("编译通过", "").passed());
        assertFalse(PlanVerifier.verify("编译通过", "   ").passed());
    }

    @Test
    @DisplayName("出现失败信号且无成功信号 → 不通过")
    void failSignalOnly() {
        PlanVerifier.Result r = PlanVerifier.verify("编译通过", "javac 报错：cannot find symbol");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("失败信号"));
    }

    @Test
    @DisplayName("先失败后成功（有成功信号）→ 通过，不能误判")
    void failureThenSuccessPasses() {
        PlanVerifier.Result r = PlanVerifier.verify("编译通过",
                "第一次编译失败：缺少 Student 类；补上后重新编译通过，exit=0");
        assertTrue(r.passed(), "出现过失败但最终成功，不应判失败");
    }

    @Test
    @DisplayName("实际产出完全没沾预期要点且无成功信号 → 不通过")
    void zeroCoverageFails() {
        PlanVerifier.Result r = PlanVerifier.verify("生成 README 文件说明用法",
                "已列出当前目录：src test.sh");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("没覆盖"));
    }

    @Test
    @DisplayName("措辞不同但要点命中 → 通过（容忍「编译通过」vs「已成功编译」）")
    void wordingVariancePasses() {
        assertTrue(PlanVerifier.verify("编译通过", "javac 已成功编译，无告警").passed());
        assertTrue(PlanVerifier.verify("测试全部通过", "40 个断言全部通过 ✅").passed());
    }

    @Test
    @DisplayName("预期为空 → 只做失败信号核对")
    void emptyExpectedOnlyChecksFailSignal() {
        assertTrue(PlanVerifier.verify("", "随便什么输出").passed());
        assertTrue(PlanVerifier.verify(null, "已创建文件 a.txt").passed());
    }

    @Test
    @DisplayName("要点部分命中即可（不要求 100% 覆盖）")
    void partialCoveragePasses() {
        PlanVerifier.Result r = PlanVerifier.verify("创建 src 目录并生成 StudentManager.java",
                "已创建 src 目录");
        assertTrue(r.passed(), "命中一部分要点就应算通过，避免误判");
    }

    @Test
    @DisplayName("回归：上报里提到「抛 Exception / 异常处理」不能被误判为失败")
    void benignExceptionMentionDoesNotFail() {
        // 真实踩过的误判：s1 的描述里提到除零会抛 IllegalArgumentException，结果被当成失败信号
        PlanVerifier.Result r = PlanVerifier.verify("创建 Calculator.java 并实现加减乘除",
                "已创建 Calculator.java，实现四个方法；除零会抛 IllegalArgumentException，已在注释里说明");
        assertTrue(r.passed(), "提到异常类型是正常描述，不该判失败：" + r.reason());
    }

    @Test
    @DisplayName("回归：真正的编译报错仍然要判失败")
    void realCompileErrorStillFails() {
        assertFalse(PlanVerifier.verify("编译通过", "javac error: cannot find symbol").passed());
        assertFalse(PlanVerifier.verify("运行输出正确", "执行失败 (exit=1): Exception in thread \"main\"").passed());
    }

    @Test
    @DisplayName("关键词抽取会去掉虚词与标点")
    void keywordsExtraction() {
        var kws = PlanVerifier.keywords("把生成的 Java 文件编译，确保没有报错");
        assertTrue(kws.contains("生成的") || kws.contains("Java") || kws.contains("文件编译"),
                "应抽出实词：" + kws);
        assertFalse(kws.contains("把"), "虚词不该出现在关键词里：" + kws);
    }
}
