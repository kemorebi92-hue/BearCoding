package com.example.jingjingai2.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 辅助调用（子代理 / 摘要 / 重拆）与主调用共用同一份额度的口径。
 *
 * <p>2026-11-10 用户看到"子代理因辅助模型额度不可用失败"，误以为项目用了第二个模型；
 * 2026-11-11 起取消"副卡"：辅助调用直接花主任务那一个 {@code limit}，谁先花谁占。
 * 这个用例钉住三件事：①不再有"辅助最多占一半"的单独闸门；②进入收尾后一律拒绝；
 * ③主任务剩余调用 ≤ 2 时也拒绝，保证主任务和最终交付的名额。</p>
 */
class TaskExecutionAuxiliaryShareTest {

    @Test void auxiliaryCallsShareTheMainLimitInsteadOfAHalfShare() {
        TaskExecution task = new TaskExecution(20, 100_000, 100_000);
        // 旧口径下副卡是 (20-1)/2 = 9 次；现在共享整张卡，12 次辅助调用必须全部放行。
        for (int i = 0; i < 12; i++) task.acquire(1, true);
        assertEquals(12, task.summary().get("auxiliaryCalls"));
        assertEquals(12, task.calls());
        assertFalse(task.auxiliaryShareExhausted(), "还有 7 次余额，辅助调用不该被拒");
        task.acquire(1, true);
        assertEquals(13, task.calls());
    }

    @Test void finalizingClosesAuxiliaryCalls() {
        TaskExecution task = new TaskExecution(20, 100_000, 100_000);
        task.beginFinal("交付");
        assertTrue(task.auxiliaryShareExhausted(), "收尾阶段不再接受辅助调用");
        var refused = assertThrows(TaskExecution.BudgetExceeded.class, () -> task.acquire(1, true));
        assertFalse(refused.getMessage().contains("辅助模型"), "不要再说成'辅助模型'：" + refused.getMessage());
        assertTrue(refused.getMessage().contains("暂不可用"), "措辞要说明是本次请求不可用：" + refused.getMessage());
    }

    @Test void auxiliaryInputSharesTheWholeBudgetInsteadOfHalf() {
        TaskExecution task = new TaskExecution(20, 100_000, 100_000);
        // 旧口径下输入副卡是 inputLimit/2 = 50000，60000 会被拒；现在共用整张卡，必须放行。
        task.acquire(60_000, true);
        assertEquals(60_000L, task.summary().get("auxiliaryInputTokens"));
        assertFalse(task.auxiliaryShareExhausted());
    }

    @Test void remainingMainCallsAlsoCloseTheShare() {
        TaskExecution task = new TaskExecution(6, 100_000, 100_000);
        // 主任务连跑到只剩 2 个执行名额 → 辅助调用一律拒绝（给主任务和交付留位置）
        for (int i = 0; i < 3; i++) task.acquire(1, false);
        assertTrue(task.auxiliaryShareExhausted());
        assertThrows(TaskExecution.BudgetExceeded.class, () -> task.acquire(1, true));
    }
}
