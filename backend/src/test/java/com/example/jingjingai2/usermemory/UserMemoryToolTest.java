package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.context.ConversationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserMemoryTool 单测：假 Mapper + ConversationContext，不碰 MySQL、不启 Spring。
 *
 * 钉死：userId 只从上下文取；非法 category/key 返回错误字符串、不抛。
 */
class UserMemoryToolTest {

    private static final Long USER = 7L;
    private static final String CONV = "conv-tool-1";

    private UserMemoryTool tool;
    private UserMemoryService service;

    @BeforeEach
    void setUp() {
        UserMemoryProperties props = new UserMemoryProperties();
        service = new UserMemoryService(new FakeUserMemoryMapper(), props);
        tool = new UserMemoryTool(service);
        ConversationContext.set(USER, CONV);
    }

    @AfterEach
    void tearDown() {
        ConversationContext.clear();
    }

    @Test
    @DisplayName("合法写入返回已记住，并落库到上下文里的 userId")
    void saveHappyPath() {
        String r = tool.saveUserMemory("preference", "reply_lang", "中文");
        assertTrue(r.startsWith("已记住"), r);
        assertEquals(1, service.countByUser(USER));
        assertEquals("中文", service.getOne(USER, "preference", "reply_lang").getContent());
    }

    @Test
    @DisplayName("非法 category 返回错误字符串，不抛、不落库")
    void illegalCategoryReturnsErrorString() {
        String r = assertDoesNotThrow(() -> tool.saveUserMemory("progress", "next_step", "下一步编译"));
        assertNotNull(r);
        assertTrue(r.contains("未知 category"), r);
        assertEquals(0, service.countByUser(USER));

        String forget = assertDoesNotThrow(() -> tool.forgetUserMemory("mood", "today"));
        assertTrue(forget.contains("未知 category"), forget);
    }

    @Test
    @DisplayName("非法 key 返回错误字符串，不抛")
    void illegalKeyReturnsErrorString() {
        String r = assertDoesNotThrow(() -> tool.saveUserMemory("preference", "../secret", "x"));
        assertNotNull(r);
        assertTrue(r.contains("mem_key"), r);
        assertEquals(0, service.countByUser(USER));
    }

    @Test
    @DisplayName("没有会话上下文时返回错误字符串，不抛")
    void missingContextReturnsErrorString() {
        ConversationContext.clear();
        String save = assertDoesNotThrow(() -> tool.saveUserMemory("preference", "reply_lang", "中文"));
        assertTrue(save.contains("当前不在会话上下文中"), save);
        String forget = assertDoesNotThrow(() -> tool.forgetUserMemory("preference", "reply_lang"));
        assertTrue(forget.contains("当前不在会话上下文中"), forget);
        assertEquals(0, service.countByUser(USER));
    }

    @Test
    @DisplayName("forget：有则已删除，没有则说明，不抛")
    void forgetExistingAndMissing() {
        assertTrue(tool.saveUserMemory("constraint", "build", "用 test.sh").startsWith("已记住"));
        String gone = tool.forgetUserMemory("constraint", "build");
        assertEquals("已删除", gone);
        assertEquals(0, service.countByUser(USER));

        String missing = assertDoesNotThrow(() -> tool.forgetUserMemory("constraint", "build"));
        assertEquals("没有这条记忆", missing);
    }

    @Test
    @DisplayName("密钥和口令不落库；普通偏好里提到「不要打印密钥」可以记")
    void rejectsCredentialsButKeepsOrdinaryPreference() {
        String secret = tool.saveUserMemory("constraint", "api", "api_key=sk-abcdefghijklmnopqrstuvwxyz");
        assertTrue(secret.contains("拒绝写入"), secret);
        assertEquals(0, service.countByUser(USER));

        String password = tool.saveUserMemory("profile", "pw", "密码是 hunter2");
        assertTrue(password.contains("拒绝写入"), password);
        assertNull(service.getOne(USER, "profile", "pw"));

        String ok = tool.saveUserMemory("constraint", "secrets", "不要打印密钥");
        assertTrue(ok.startsWith("已记住"), ok);
        assertEquals("不要打印密钥", service.getOne(USER, "constraint", "secrets").getContent());
    }

    @Test
    @DisplayName("userId 只来自会话上下文，换用户看不到上一条")
    void memoryStaysWithTheContextUser() {
        assertTrue(tool.saveUserMemory("preference", "reply_lang", "中文").startsWith("已记住"));
        ConversationContext.set(8L, "other");
        assertNull(service.getOne(8L, "preference", "reply_lang"));
        assertEquals(0, service.countByUser(8L));
        assertEquals(1, service.countByUser(USER));
        String forgotten = tool.forgetUserMemory("preference", "reply_lang");
        assertEquals("没有这条记忆", forgotten);
        assertNotNull(service.getOne(USER, "preference", "reply_lang"));
    }
}
