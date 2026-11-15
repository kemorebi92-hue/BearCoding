package com.example.jingjingai2.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 一条机器可读的期望。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Check(
        String type,
        String path,
        String text,
        String command,
        String stepId,
        String sha256,
        String tool,
        Integer minCount,
        Boolean precondition) {

    /** 兼容只写前六个字段的构造。 */
    public Check(String type, String path, String text, String command, String stepId, String sha256) {
        this(type, path, text, command, stepId, sha256, null, null, null);
    }

    public static ExpectFile loadFile(Path json) throws IOException {
        ExpectFile file = new ObjectMapper().readValue(json.toFile(), ExpectFile.class);
        if (file.checks == null) {
            throw new IllegalArgumentException("期望文件没有 checks: " + json);
        }
        return file;
    }

    public static List<Check> load(Path json) throws IOException {
        return loadFile(json).checks();
    }

    /** 期望文件的根。forbiddenTools 只有该任务声明了才有，别的任务不要填。 */
    public record ExpectFile(List<String> forbiddenTools, List<Check> checks) {}
}
