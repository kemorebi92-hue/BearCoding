package com.example.jingjingai2.identity;

/**
 * 当前用户身份的唯一入口。
 *
 * <p>只回答「这个请求是谁」，不负责认证、不负责授权、不负责数据隔离——
 * 那三件事仍然由各自的层完成（JWT 校验、{@code ToolPermissionGate}、Mapper 里的
 * {@code WHERE user_id = ?}）。
 *
 * <p>刻意不依赖 {@code HttpServletRequest}、{@code ConversationContext}、ThreadLocal
 * 和数据库：身份来源换成什么都可以，调用方不需要跟着改。
 *
 * <p>当前只有一个实现 {@link LocalUserProvider}（本机固定用户）。
 * 将来接入 Web 登录时新增一个实现，并在装配处按配置选择，接口本身不需要改。
 */
public interface CurrentUserProvider {

    /**
     * 当前请求的用户 ID。
     *
     * @return 用户 ID；返回值会原样写进 request attribute {@code userId}，
     *         与原有 JWT 模式注入的是同一个属性
     */
    Long getCurrentUserId();
}
