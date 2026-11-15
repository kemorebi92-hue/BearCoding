package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubagentPropertiesTest {

    @Test
    @DisplayName("关开关：档位仍是 24/48/72，硬顶 96，默认 48")
    void gpOffKeepsLegacyBudget() {
        SubagentProperties p = new SubagentProperties();
        assertEquals(24, p.roundsForThoroughness("quick", true));
        assertEquals(48, p.roundsForThoroughness("medium", true));
        assertEquals(72, p.roundsForThoroughness("thorough", true));
        assertEquals(48, p.effectiveMaxRounds(0, true));
        assertEquals(96, p.effectiveMaxRounds(100, true));
        assertEquals(24, p.roundsForThoroughness("quick"));
        assertEquals(48, p.roundsForThoroughness("medium"));
    }

    @Test
    @DisplayName("dispatchV2 默认关；主循环截断默认 12000")
    void dispatchV2Defaults() {
        SubagentProperties p = new SubagentProperties();
        assertFalse(p.isDispatchV2());
        assertEquals(12000, p.getMainToolResultChars());
        assertEquals(4000, p.getMaxToolResultChars());
        p.setDispatchV2(true);
        p.setMainToolResultChars(8000);
        p.setMaxToolResultChars(20000);
        assertTrue(p.isDispatchV2());
        assertEquals(8000, p.getMainToolResultChars());
        assertEquals(20000, p.getMaxToolResultChars());
    }

    @Test
    @DisplayName("软提示：须 V2 总开关也开才 active")
    void softHintRequiresDispatchV2() {
        SubagentProperties p = new SubagentProperties();
        assertFalse(p.isDispatchV2SoftHint());
        assertEquals(15000, p.getSoftHintThresholdTokens());
        p.setDispatchV2SoftHint(true);
        assertFalse(p.softHintActive(), "仅 soft-hint 不够");
        p.setDispatchV2(true);
        assertTrue(p.softHintActive());
    }

    @Test
    @DisplayName("评测强制模式默认 off")
    void evalForceModeDefaultsOff() {
        SubagentProperties p = new SubagentProperties();
        assertEquals("off", p.getEvalForceMode());
        assertFalse(p.isEvalForceDispatch());
        assertFalse(p.isEvalForceNoSpawn());
        p.setEvalForceMode("force_dispatch");
        assertTrue(p.isEvalForceDispatch());
        p.setEvalForceMode("force_no_spawn");
        assertTrue(p.isEvalForceNoSpawn());
        assertFalse(p.isEvalForceDispatch());
    }
}
