package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubagentSpecTest {

    @Test
    @DisplayName("implement / write / 中文 写改 → IMPLEMENT；空与 explore → EXPLORE")
    void parseKeepsLegacyAliases() {
        assertEquals(SubagentSpec.Mode.IMPLEMENT, SubagentSpec.Mode.parse("implement"));
        assertEquals(SubagentSpec.Mode.IMPLEMENT, SubagentSpec.Mode.parse("impl"));
        assertEquals(SubagentSpec.Mode.IMPLEMENT, SubagentSpec.Mode.parse("write"));
        assertEquals(SubagentSpec.Mode.IMPLEMENT, SubagentSpec.Mode.parse("写文件"));
        assertEquals(SubagentSpec.Mode.IMPLEMENT, SubagentSpec.Mode.parse("改代码"));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("explore"));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse(""));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse(null));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("readonly"));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("generate"),
                "不要把 general 前缀误伤成 implement");
    }

    @Test
    @DisplayName("general_purpose 不再是 implement 别名")
    void parseGeneralPurposeIsNotAMode() {
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("general_purpose"));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("general-purpose"));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("GeneralPurpose"));
        assertEquals(SubagentSpec.Mode.EXPLORE, SubagentSpec.Mode.parse("gp"));
        assertEquals(2, SubagentSpec.Mode.values().length);
    }
}
