package com.example.jingjingai2.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 桌面实例鉴权配置（{@code app.auth.*}）。
 *
 * <p>两种模式必须分清：
 * <ul>
 *   <li>{@code local}（默认）—— 浏览器开发/自动化验收环境。本机单用户，不做令牌校验，
 *       行为和加鉴权之前完全一致。</li>
 *   <li>{@code desktop} —— Electron 正式启动。所有本地 API 必须带
 *       {@code X-BearCoding-Token}，值等于启动时由 Electron 主进程生成并通过
 *       {@code BEARCODING_LOCAL_TOKEN} 传入的随机令牌。</li>
 * </ul>
 *
 * <p><strong>fail-closed：</strong>desktop 模式下令牌缺失、空白或短于
 * {@link #MIN_TOKEN_CHARS} 个字符时，应用直接启动失败，绝不回退成“无鉴权”。
 * 本类不记录、不打印令牌内容。
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class DesktopAuthProperties {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_DESKTOP = "desktop";

    /** Electron 主进程传令牌用的环境变量名（application.yml 里做占位）。 */
    public static final String ENV_TOKEN = "BEARCODING_LOCAL_TOKEN";

    /** 请求头名。Electron 用 session.webRequest.onBeforeSendHeaders 注入。 */
    public static final String TOKEN_HEADER = "X-BearCoding-Token";

    /** 至少 32 个字符：Electron 侧用 32 字节随机数的十六进制（64 字符）即可。 */
    public static final int MIN_TOKEN_CHARS = 32;

    private String mode = MODE_LOCAL;
    private String localToken = "";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getLocalToken() { return localToken; }
    public void setLocalToken(String localToken) { this.localToken = localToken; }

    /** 当前是否处于 Electron 桌面鉴权模式。大小写与空白容忍。 */
    public boolean desktopMode() {
        return mode != null && MODE_DESKTOP.equalsIgnoreCase(mode.trim());
    }

    /**
     * 取用于常量时间比较的令牌字节。只在 {@link #desktopMode()} 为 true 时调用。
     *
     * @throws IllegalStateException 令牌缺失、空白或强度不足（fail-closed）
     */
    public byte[] requireTokenBytes() {
        if (!desktopMode()) {
            throw new IllegalStateException("当前不是桌面鉴权模式，没有启动令牌");
        }
        String token = localToken == null ? "" : localToken.trim();
        if (token.isEmpty()) {
            throw new IllegalStateException(
                    "app.auth.mode=desktop 但未提供启动令牌。请由 Electron 主进程生成随机令牌，"
                            + "并通过环境变量 " + ENV_TOKEN + " 传入；不会回退成无鉴权模式。");
        }
        if (token.length() < MIN_TOKEN_CHARS) {
            throw new IllegalStateException(
                    "桌面启动令牌强度不足：至少需要 " + MIN_TOKEN_CHARS + " 个字符（建议 32 字节随机数的十六进制）。");
        }
        return token.getBytes(StandardCharsets.UTF_8);
    }
}
