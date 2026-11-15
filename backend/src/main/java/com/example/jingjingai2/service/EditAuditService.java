package com.example.jingjingai2.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 编辑审计：把每一次「结构化编辑」的 diff 落盘留痕。
 *
 * 为什么需要它：
 *  - 当前阶段编辑是**自动应用**的，模型到底改了什么必须可追溯（排错、复盘、面试演示都要用）；
 *  - 更重要的是**为"人工确认后再应用"预留接口**：{@link Entry#applied} 字段已经能同时表达
 *    "待确认(applied=false)" 与 "已应用(applied=true)" 两种状态。后续要做人工审核，只需：
 *      1) 在真正写盘前调用 {@code record(path, false, diff)} 并把编辑挂起；
 *      2) 新增一个"确认/拒绝"接口，确认时再执行写盘并 {@code record(path, true, diff)}。
 *    也就是说：审计层已经把状态和 diff 都准备好，上层只差一个开关。
 */
@Service
public class EditAuditService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 内存中保留的最近编辑条数（供后续"查看待确认修改"的接口使用） */
    private static final int MAX_RECENT = 200;

    /** 审计日志文件（app.edit.audit-log 可配置；logs/ 已在 .gitignore 中） */
    private final Path logFile;

    /** 最近若干次编辑（线程安全包装；SSE 线程池会并发访问） */
    private final List<Entry> recent = Collections.synchronizedList(new ArrayList<>());

    /** 一条编辑记录：时间 / 文件 / 是否已落盘 / diff 文本 */
    public record Entry(String time, String path, boolean applied, String diff) {}

    @Autowired
    public EditAuditService(@Value("${app.edit.audit-log:logs/edit-audit.log}") String logPath) {
        this.logFile = Paths.get(logPath).toAbsolutePath().normalize();
    }

    /** 便捷构造：默认日志路径（单测 / 无 Spring 场景使用） */
    public EditAuditService() {
        this("logs/edit-audit.log");
    }

    /**
     * 记录一次编辑。
     *
     * @param path    目标文件（相对工作区路径）
     * @param applied true=已实际写入磁盘；false=仅生成 diff、尚未应用（人工确认流程用）
     * @param diff    可读的 diff 文本
     */
    public void record(String path, boolean applied, String diff) {
        Entry e = new Entry(LocalDateTime.now().format(TS), path, applied, diff == null ? "" : diff);
        recent.add(e);
        synchronized (recent) {
            while (recent.size() > MAX_RECENT) recent.remove(0);
        }
        appendToFile(e);
    }

    /** 最近的编辑记录（副本，供上层展示 / 后续人工确认使用） */
    public List<Entry> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** 审计日志文件路径（便于排查"日志写哪去了"） */
    public Path logFile() {
        return logFile;
    }

    private void appendToFile(Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(e.time()).append("  ")
                .append(e.applied() ? "[已应用]" : "[待确认]").append("  ")
                .append(e.path()).append(" ====\n");
        sb.append(e.diff()).append('\n');
        try {
            if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 审计写失败绝不能让编辑本身失败
        }
    }
}
