package com.example.jingjingai2;

import com.example.jingjingai2.context.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * token 估算器测试：验证启发式估算的合理性与边界。
 */
class TokenEstimatorTest {

    @Test
    @DisplayName("空串 / null 估算为 0")
    void emptyIsZero() {
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate(null));
    }

    @Test
    @DisplayName("中文按每字约 1 token 估算")
    void cjkRoughlyOnePerChar() {
        int n = TokenEstimator.estimate("你好世界");
        assertEquals(4, n, "四个汉字应估算为约 4 token");
    }

    @Test
    @DisplayName("英文按约 4 字符 1 token 估算")
    void englishRoughlyFourPerToken() {
        int n = TokenEstimator.estimate("abcdefgh"); // 8 个英文字符
        assertEquals(2, n, "8 个英文字符应估算为约 2 token");
    }

    @Test
    @DisplayName("估算值随文本长度单调增加")
    void longerTextMeansMoreTokens() {
        int shortText = TokenEstimator.estimate("短");
        int longText = TokenEstimator.estimate("这是一段明显更长的中文文本，用来验证估算值会随长度增加");
        assertTrue(longText > shortText);
    }
}
