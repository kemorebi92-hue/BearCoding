package com.example.jingjingai2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话标题自动生成测试。
 * 标题是会话列表里唯一展示给用户的文字，生成规则必须稳定、可预期。
 */
class ConversationServiceTest {

    @Test
    @DisplayName("null / 空白消息 → 兜底标题")
    void blankMessageFallsBack() {
        assertEquals("新对话", ConversationService.deriveTitle(null));
        assertEquals("新对话", ConversationService.deriveTitle(""));
        assertEquals("新对话", ConversationService.deriveTitle("   \n\t "));
    }

    @Test
    @DisplayName("普通短消息原样作为标题")
    void shortMessageKeptAsIs() {
        assertEquals("帮我改一下登录逻辑", ConversationService.deriveTitle("帮我改一下登录逻辑"));
    }

    @Test
    @DisplayName("换行与连续空格压成单个空格")
    void whitespaceCollapsed() {
        assertEquals("帮我把这个文件 改成 UTF-8", ConversationService.deriveTitle("帮我把这个文件\n\n  改成  UTF-8"));
    }

    @Test
    @DisplayName("去掉 markdown 前缀符号（# > - *）")
    void markdownPrefixStripped() {
        assertEquals("帮我修复这个 bug", ConversationService.deriveTitle("### 帮我修复这个 bug"));
        assertEquals("引用的一段话", ConversationService.deriveTitle("> 引用的一段话"));
        assertEquals("列表项", ConversationService.deriveTitle("- 列表项"));
    }

    @Test
    @DisplayName("超长消息截断到 24 字并加省略号")
    void longMessageTruncated() {
        String longMsg = "请帮我把这个项目里所有使用 JDBC 的地方都改成 MyBatis 注解式写法并补上单元测试";
        String title = ConversationService.deriveTitle(longMsg);
        assertEquals(ConversationService.MAX_TITLE_LEN + 1, title.length(), "24 个字 + 省略号");
        assertTrue(title.endsWith("…"));
        assertTrue(longMsg.startsWith(title.substring(0, ConversationService.MAX_TITLE_LEN)));
    }

    @Test
    @DisplayName("恰好 24 字时不截断")
    void exactlyMaxLenNotTruncated() {
        String exactly = "一二三四五六七八九十一二三四五六七八九十一二三四";
        assertEquals(ConversationService.MAX_TITLE_LEN, exactly.length());
        assertEquals(exactly, ConversationService.deriveTitle(exactly));
    }
}
