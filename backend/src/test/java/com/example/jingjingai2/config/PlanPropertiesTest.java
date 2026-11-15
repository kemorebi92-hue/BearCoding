package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划开关的**默认值**契约。
 *
 * 为什么值得单独测：这些默认值直接决定"强制规划"这条链路能不能真的发出去。
 * 尤其 `disableThinking` —— 实测 DeepSeek 的思考模式会 400 拒绝 tool_choice，
 * 一旦有人把它默认改成 false，DeepSeek 上的 HARD 就会静默退化（每次都走异常回退），
 * 而且日志里看起来"只是没生效"，极难发现。用测试把它钉住。
 */
class PlanPropertiesTest {

    @Test
    @DisplayName("Java 对象默认与 application.yml 对齐：nudge.mode=soft / strategy=stripWriteTools / rounds=12 / 关思考")
    void runtimeAlignedDefaults() {
        PlanProperties props = new PlanProperties();
        assertEquals("soft", props.getNudge().getMode());
        assertTrue(props.getNudge().isEnabled(), "soft 也是启用 nudge 的");
        assertFalse(props.getNudge().isHardAllowed(), "soft 不允许升级 HARD");
        assertEquals(12, props.getNudge().getRoundsThreshold());

        PlanProperties.Nudge.Hard hard = props.getNudge().getHard();
        assertEquals("stripWriteTools", hard.getStrategy());
        assertFalse(hard.isToolChoice(), "默认应走 stripWriteTools 逃生舱");
        assertTrue(hard.isDisableThinking(),
                "默认必须关思考：DeepSeek 思考模式会直接拒绝 tool_choice（实测 400）");
    }

    @Test
    @DisplayName("strategy 只有写成 stripWriteTools 才走逃生舱（其余一律 tool_choice，且大小写不敏感）")
    void strategySwitch() {
        PlanProperties.Nudge.Hard hard = new PlanProperties().getNudge().getHard();
        hard.setStrategy("stripWriteTools");
        assertFalse(hard.isToolChoice());
        hard.setStrategy("STRIPWRITETOOLS");
        assertFalse(hard.isToolChoice(), "大小写不敏感");
        hard.setStrategy("toolChoice");
        assertTrue(hard.isToolChoice());
    }

    @Test
    @DisplayName("disableThinking 可关（留给不认识的端点）")
    void disableThinkingCanBeTurnedOff() {
        PlanProperties.Nudge.Hard hard = new PlanProperties().getNudge().getHard();
        hard.setDisableThinking(false);
        assertFalse(hard.isDisableThinking());
        hard.setDisableThinking(true);
        assertTrue(hard.isDisableThinking());
    }
}
