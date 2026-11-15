package com.example.jingjingai2.context;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影子观测的统一读取口。默认只留在本次请求的内存里；
 * 设置 {@code app.context.working-set.observation-path} 才追加 JSONL。
 * JSONL 只写估算、哈希、身份和原因，不写 Prompt 明文。
 */
public final class ContextObservationLog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<Map<String, Object>> records = new ArrayList<>();
    private final Path path;

    public ContextObservationLog(Path path) {
        this.path = path;
    }

    public synchronized void add(Map<String, Object> record) {
        Map<String, Object> copy = new LinkedHashMap<>(record);
        records.add(copy);
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            Files.writeString(path, JSON.writeValueAsString(copy) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[ContextObservation] 写入失败（不影响对话）: " + e.getClass().getSimpleName());
        }
    }

    public synchronized List<Map<String, Object>> records() {
        return List.copyOf(records);
    }
}
