package com.example.jingjingai2.identity;

import org.springframework.stereotype.Component;

/**
 * 本机单用户身份：固定返回 {@code 1L}。
 *
 * <p>只在 {@code app.auth.mode=local} 时才注册成 Bean。默认（{@code jwt}）下容器里
 * 没有 {@link CurrentUserProvider}，{@code JwtInterceptor} 用 {@code ObjectProvider}
 * 取，所以不会因为缺少这个 Bean 而启动失败；也不会和将来新增的身份实现同时存在。
 *
 * <p>不查 users 表、不要求数据库里有注册记录、不读 JWT、不看任何请求头或参数——
 * 客户端没有任何途径把它改成别的值。
 */
@Component
public class LocalUserProvider implements CurrentUserProvider {

    /** 本机模式固定使用的用户 ID。 */
    public static final Long LOCAL_USER_ID = 1L;

    @Override
    public Long getCurrentUserId() {
        return LOCAL_USER_ID;
    }
}
