package com.example.jingjingai2.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测运行器。本阶段不启动应用、不调模型。
 *
 * <p>真正跑的时候，{@link AttemptRunner} 的生产实现应复用现有隔离：
 * {@code scripts/eval/env.sh} 把 {@code WORKSPACE_ROOT} 指到仓库外，
 * {@code EVAL_PORT} 决定端口，{@code scripts/eval/run_all.sh} 经 {@code run.sh} 起应用，
 * 再用 {@code /ai/workspace/bind} 把会话绑到该任务的临时目录。
 * 临时库的口令只放进程环境变量，不写进运行记录或结果文件。
 *
 * <p>已完成的「任务 × 次数」记在结果目录的 {@code runs.json} 里，再次调用会跳过。
 */
public final class EvalRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EvalRunner() {}

    /** 一次尝试。生产实现才会去起应用；测试里用假数据。 */
    public interface AttemptRunner {
        RunRecord run(String taskId, int attempt, RunSettings settings) throws IOException;
    }

    /**
     * @param port           应用端口，对应现有的 EVAL_PORT
     * @param databaseName   临时库名，不是开发库
     * @param contextMaxTokens 压缩预算，写入运行记录
     * @param evalForceMode  SUBAGENT_EVAL_FORCE_MODE，基线必须是 off
     */
    public record RunSettings(
            int port,
            String databaseName,
            String model,
            String commit,
            int contextMaxTokens,
            String evalForceMode) {}

    public static List<RunRecord> run(List<String> taskIds, int repeats, Path resultsDir,
                                      AttemptRunner attempts, RunSettings settings) throws IOException {
        Files.createDirectories(resultsDir);
        Path store = resultsDir.resolve("runs.json");
        List<RunRecord> done = new ArrayList<>(load(store));
        for (String taskId : taskIds) {
            for (int attempt = 1; attempt <= repeats; attempt++) {
                if (contains(done, taskId, attempt)) continue;
                done.add(attempts.run(taskId, attempt, settings));
                save(store, done);
            }
        }
        return List.copyOf(done);
    }

    /** 还没跑过的「任务#次数」。桥接层用这个决定要不要续跑。 */
    public static List<String> pending(Path store, List<String> taskIds, int repeats) throws IOException {
        List<RunRecord> done = load(store);
        List<String> out = new ArrayList<>();
        for (String taskId : taskIds) {
            for (int attempt = 1; attempt <= repeats; attempt++) {
                if (!contains(done, taskId, attempt)) out.add(taskId + "#" + attempt);
            }
        }
        return out;
    }

    static boolean contains(List<RunRecord> records, String taskId, int attempt) {
        for (RunRecord record : records) {
            if (record.taskId().equals(taskId) && record.attempt() == attempt) return true;
        }
        return false;
    }

    private static List<RunRecord> load(Path store) throws IOException {
        if (!Files.isRegularFile(store)) return List.of();
        RunRecord[] rows = JSON.readValue(store.toFile(), RunRecord[].class);
        List<RunRecord> out = new ArrayList<>();
        for (RunRecord row : rows) out.add(row);
        return out;
    }

    private static void save(Path store, List<RunRecord> records) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(store.toFile(), records);
    }

    /** 给报告用的精简导出：不含口令。 */
    public static void exportSummary(Path file, List<RunRecord> records) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RunRecord record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", record.taskId());
            row.put("attempt", record.attempt());
            row.put("model", record.model());
            row.put("commit", record.commit());
            row.put("contextMaxTokens", record.contextMaxTokens());
            row.put("evalForceMode", record.evalForceMode());
            row.put("passed", record.passed());
            row.put("failureKind", record.failureKind() == null ? null : record.failureKind().name());
            row.put("turns", record.turns());
            row.put("rounds", record.rounds());
            row.put("totalTokens", record.totalTokens());
            row.put("configJson", record.configJson());
            rows.add(row);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), rows);
    }
}
