package com.example.jingjingai2.usermemory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户记忆开关与硬顶（前缀 {@code app.user-memory.*}）。
 *
 * <p>不要叫 {@code app.memory}：那个前缀留给以后的工作区 / 项目级记忆。
 * 默认 {@code enabled=false}：关掉时工具不下发、提示词整段不出现。
 * 本类的数字硬顶在 Service 里执行，不靠 DB 约束。
 */
@Component
@ConfigurationProperties(prefix = "app.user-memory")
public class UserMemoryProperties {

    /**
     * 总开关。关闭时工具 + 提示词规则一起消失（对齐子代理：规则在、工具不在会白烧一轮）。
     * 数据层本身不看这个开关。
     */
    private boolean enabled = false;

    /** 每用户最多多少条。满了之后拒绝新 key（不静默 LRU），覆盖已有 key 仍允许。 */
    private int maxItems = 40;

    /** 单条 content 字数硬顶，超出截断（不拒）。 */
    private int maxItemChars = 1500;

    /**
     * 注入合计字数硬顶。先 constraint → preference → profile → project_context，
     * 超了后面的丢掉（见 {@link UserMemoryInjector}）。
     */
    private int maxInjectChars = 6000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }

    public int getMaxItemChars() { return maxItemChars; }
    public void setMaxItemChars(int maxItemChars) { this.maxItemChars = maxItemChars; }

    public int getMaxInjectChars() { return maxInjectChars; }
    public void setMaxInjectChars(int maxInjectChars) { this.maxInjectChars = maxInjectChars; }
}
