package com.example.jingjingai2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作区长期记忆的开关（前缀 {@code app.memory.*}）。
 *
 * <p>形态对齐 Claude Code / Workbuddy：当前工作区下有一个 {@code memory/} 文件夹，
 * 里面是 markdown。每次在这个工作区对话，就把这些 md 注入主循环。
 * 没有文件夹、或文件夹是空的 → 零开销（不注入、模型也看不到规则——规则随总开关摘掉）。
 *
 * <p>不做向量检索、不落 MySQL：记忆就是工作区里的文件，跟项目走、换会话还在。
 */
@Component
@ConfigurationProperties(prefix = "app.memory")
public class MemoryProperties {

    /**
     * 总开关。关闭时<strong>工具不下发、提示词里也不提</strong> saveMemory，
     * 也不会去读 {@code memory/}（模型连试都不会试）。
     */
    private boolean enabled = true;

    /** 相对工作区根的目录名。默认 {@code memory}，不要改成绝对路径。 */
    private String dir = "memory";

    /**
     * 索引文件名（始终最先注入）。Claude Code / Workbuddy 都用 {@code MEMORY.md}
     * 放「现在做到哪、下一步做什么」。
     */
    private String indexName = "MEMORY.md";

    /** 一次最多注入几个 md。超出的按文件名排在后面的丢掉（索引永远优先）。 */
    private int maxFiles = 12;

    /** 单个文件注入上限（字符）。超了截断并标注，避免一篇日记把窗口吃光。 */
    private int maxFileChars = 8000;

    /**
     * 全部记忆合计上限（字符）。约等于 Claude Code 自动记忆索引的 25KB 量级，
     * 防止 {@code memory/} 被当成垃圾桶之后每轮都付一份固定税。
     */
    private int maxTotalChars = 25000;

    /** 读盘前的字节硬顶：超过就跳过这个文件（防二进制/超大 md 进内存）。 */
    private int maxFileBytes = 65536;

    /**
     * 有实质进展时（改了文件 / 推进了计划 / implement 子代理）本轮结束后
     * 自动让模型更新 {@code MEMORY.md}。闲聊不触发。关掉则只保留「模型自己调 saveMemory」。
     */
    private boolean autoConsolidate = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }

    public int getMaxFileChars() { return maxFileChars; }
    public void setMaxFileChars(int maxFileChars) { this.maxFileChars = maxFileChars; }

    public int getMaxTotalChars() { return maxTotalChars; }
    public void setMaxTotalChars(int maxTotalChars) { this.maxTotalChars = maxTotalChars; }

    public int getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(int maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    public boolean isAutoConsolidate() { return autoConsolidate; }
    public void setAutoConsolidate(boolean autoConsolidate) { this.autoConsolidate = autoConsolidate; }
}
