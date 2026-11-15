package com.example.jingjingai2.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 本机身份实现是纯常量，不启动 Spring、不连数据库、不解析 JWT。
 */
class LocalUserProviderTest {

    @Test
    @DisplayName("固定返回 1")
    void returnsFixedUserId() {
        CurrentUserProvider provider = new LocalUserProvider();
        assertNotNull(provider.getCurrentUserId());
        assertEquals(1L, provider.getCurrentUserId());
    }

    @Test
    @DisplayName("多次调用结果一致，且等于公开常量")
    void stableAcrossCalls() {
        LocalUserProvider provider = new LocalUserProvider();
        assertEquals(LocalUserProvider.LOCAL_USER_ID, provider.getCurrentUserId());
        assertEquals(provider.getCurrentUserId(), provider.getCurrentUserId());
    }

    @Test
    @DisplayName("不依赖任何外部状态即可构造")
    void constructibleWithoutDependencies() {
        assertEquals(1L, new LocalUserProvider().getCurrentUserId());
    }
}
