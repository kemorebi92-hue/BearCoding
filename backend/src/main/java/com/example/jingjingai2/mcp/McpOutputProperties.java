package com.example.jingjingai2.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 超限结果落盘（前缀 {@code app.mcp.output.*}）。
 *
 * <p>触发阈值不在这里：沿用各服务已有的 {@code max-result-chars}（全局默认 8000，
 * Playwright 配 6000）。这里只决定「超限之后怎么办」。
 */
@Component
@ConfigurationProperties(prefix = "app.mcp.output")
public class McpOutputProperties {

    /**
     * {@code false} = 回到旧的「头+尾截断」，不写盘。给 A/B 对比用。默认开。
     */
    private boolean spillEnabled = true;

    /**
     * 预览长度。≤0 表示取该服务的 inline-limit（等于旧截断长度，上下文预算不变）。
     * 配置成更小的值时也会被 inline-limit 夹住上限。
     */
    private int previewChars = 0;

    /**
     * 落盘根目录。默认与 {@code ./workspaces}、{@code ./logs} 同级，放应用数据目录下。
     */
    private String dir = "./mcp-output";

    /** 启动时清理超过这个小时数的文件。≤0 表示不按 TTL 清理。默认 24。 */
    private int ttlHours = 24;

    /** 单个会话的落盘总量上限（字节）。超过就删该会话最旧的文件。默认 50MB。 */
    private long maxBytesPerConversation = 50L * 1024 * 1024;

    public boolean isSpillEnabled() { return spillEnabled; }
    public void setSpillEnabled(boolean spillEnabled) { this.spillEnabled = spillEnabled; }

    public int getPreviewChars() { return previewChars; }
    public void setPreviewChars(int previewChars) { this.previewChars = previewChars; }

    public String getDir() { return dir; }
    public void setDir(String dir) {
        this.dir = dir == null || dir.isBlank() ? "./mcp-output" : dir.trim();
    }

    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }

    public long getMaxBytesPerConversation() { return maxBytesPerConversation; }
    public void setMaxBytesPerConversation(long maxBytesPerConversation) {
        this.maxBytesPerConversation = maxBytesPerConversation;
    }

    /** 实际预览长度：配置值优先，但不超过该服务的 inline-limit。 */
    public int effectivePreviewChars(int inlineLimit) {
        int inline = inlineLimit <= 0 ? 8000 : inlineLimit;
        if (previewChars <= 0) return inline;
        return Math.min(previewChars, inline);
    }
}
