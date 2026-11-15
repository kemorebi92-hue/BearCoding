package com.example.jingjingai2.memory;

import com.example.jingjingai2.DTO.ChatResponse;
import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.utils.PlanNudgePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本轮结束后把「核心 / 当前进度 / 下一步」自动写进 {@code MEMORY.md}。
 *
 * <p>用户不用手写进度：有实质进展（改了文件、推进了计划、implement 子代理）才花一次短总结；
 * 闲聊 / 只读调研 / 模型自己已经写过索引 → 零开销。失败全部吞掉，不影响对用户的回答。
 */
@Component
public class MemoryConsolidator {

    /** 模型必须用这对标记把正文包起来，方便从啰嗦输出里抠出 markdown。 */
    public static final String SYSTEM_PROMPT =
            "你是项目进度记录员。根据本轮工作更新 MEMORY.md。"
                    + "必须用这三个标题：# 核心 / # 当前进度 / # 下一步。\n"
                    + "规则：\n"
                    + "- 旧记忆里仍然成立的事实要保留（尤其是核心约定和用户偏好）\n"
                    + "- 当前进度写「已经做完什么、卡在哪」，不要过程流水账\n"
                    + "- 下一步只写马上要做的 1～3 件\n"
                    + "- 不要编造输入里没出现的事实；不要写密钥、口令、完整代码、git 历史\n"
                    + "- 全文不超过 4000 字\n"
                    + "只输出：\n"
                    + "<<<MEMORY\n"
                    + "（markdown 正文）\n"
                    + "MEMORY>>>";

    private static final Pattern MARK = Pattern.compile(
            "<<<MEMORY\\s*([\\s\\S]*?)MEMORY>>>");
    private static final Pattern FENCE = Pattern.compile(
            "^```(?:markdown|md)?\\s*|\\s*```$", Pattern.MULTILINE);
    /** 正文里像密钥的，拒绝落盘（文件名过滤挡不住内容）。 */
    private static final Pattern SECRET_BODY = Pattern.compile(
            "(?i)(api[_-]?key\\s*[:=]|sk-[A-Za-z0-9]{16,}|-----BEGIN |password\\s*[:=]\\s*\\S+)");

    private static final int USER_CHARS = 600;
    private static final int ASSISTANT_CHARS = 1500;
    private static final int TOOL_RESULT_CHARS = 160;
    private static final int MAX_TOOLS_LISTED = 10;

    private final MemoryProperties props;
    private final WorkspaceMemory workspaceMemory;
    private final ObjectMapper mapper = new ObjectMapper();

    public MemoryConsolidator(MemoryProperties props, WorkspaceMemory workspaceMemory) {
        this.props = props;
        this.workspaceMemory = workspaceMemory;
    }

    public static final class Decision {
        public final boolean run;
        public final String reason;

        Decision(boolean run, String reason) {
            this.run = run;
            this.reason = reason == null ? "" : reason;
        }

        static Decision go(String reason) { return new Decision(true, reason); }
        static Decision skip(String reason) { return new Decision(false, reason); }
    }

    /**
     * 该不该花一次总结调用。纯规则，不读盘、不调模型。
     */
    public Decision decide(List<ChatResponse.ToolCallRecord> records) {
        if (!props.isEnabled()) return Decision.skip("disabled");
        if (!props.isAutoConsolidate()) return Decision.skip("no-auto");
        if (records == null || records.isEmpty()) return Decision.skip("no-progress");

        boolean savedIndex = false;
        boolean durable = false;
        for (ChatResponse.ToolCallRecord r : records) {
            if (r == null || r.getToolName() == null) continue;
            String name = r.getToolName();
            if (!r.isSuccess()) continue;
            if ("saveMemory".equals(name)) {
                if (isIndexName(jsonField(r.getInput(), "name"))) {
                    savedIndex = true;
                } else {
                    durable = true;   // 写了专题文件，索引也该提一句
                }
                continue;
            }
            if (PlanNudgePolicy.WRITE_TOOLS.contains(name)
                    || "createPlan".equals(name)
                    || "updatePlanStep".equals(name)
                    || isImplementSpawn(name, r.getInput())) {
                durable = true;
            }
        }
        if (savedIndex) return Decision.skip("already-saved");
        if (durable) return Decision.go("progress");
        return Decision.skip("no-progress");
    }

    /** 给总结模型看的用户侧材料：旧索引 + 本轮用户话 + 结论 + 工具要点。 */
    public String buildUserPayload(Path workspace, String userMessage, String assistantText,
                                   List<ChatResponse.ToolCallRecord> records, String planSnapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("【旧 MEMORY.md】\n")
                .append(blankTo(readIndex(workspace), "（暂无，请按本轮工作新建）"))
                .append("\n\n【本轮用户】\n")
                .append(clip(userMessage, USER_CHARS))
                .append("\n\n【本轮结论】\n")
                .append(clip(assistantText, ASSISTANT_CHARS));
        if (planSnapshot != null && !planSnapshot.isBlank()) {
            sb.append("\n\n【当前计划】\n").append(clip(planSnapshot, 1200));
        }
        sb.append("\n\n【本轮工具】\n");
        int n = 0;
        if (records != null) {
            for (ChatResponse.ToolCallRecord r : records) {
                if (r == null || r.getToolName() == null) continue;
                if (n >= MAX_TOOLS_LISTED) {
                    sb.append("…另有 ").append(records.size() - n).append(" 次未列出\n");
                    break;
                }
                n++;
                sb.append(r.isSuccess() ? "ok " : "fail ")
                        .append(r.getToolName());
                String path = firstPath(r.getInput());
                if (path != null) sb.append(' ').append(path);
                String result = clip(r.getResult(), TOOL_RESULT_CHARS);
                if (!result.isBlank()) sb.append(" → ").append(result.replace('\n', ' '));
                sb.append('\n');
            }
        }
        if (n == 0) sb.append("（无）\n");
        return sb.toString();
    }

    /** 从模型输出里抠出 markdown 正文；抠不出就返回 null（调用方不写盘）。 */
    public String parseBody(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = MARK.matcher(raw);
        String body;
        if (m.find()) {
            body = m.group(1).trim();
        } else {
            String stripped = FENCE.matcher(raw.trim()).replaceAll("").trim();
            if (!looksLikeIndex(stripped)) return null;
            body = stripped;
        }
        if (body.isBlank()) return null;
        if (SECRET_BODY.matcher(body).find()) return null;
        int cap = Math.max(1, props.getMaxFileChars());
        if (body.length() > cap) {
            body = body.substring(0, cap) + "\n…（已截断）";
        }
        return body;
    }

    /** 覆盖写入索引。body 为空或像密钥时拒绝。 */
    public String writeIndex(Path workspace, String body) {
        if (body == null || body.isBlank()) {
            return "写入失败: 总结为空。";
        }
        if (SECRET_BODY.matcher(body).find()) {
            return "写入失败: 正文像密钥，已拒绝。";
        }
        return workspaceMemory.save(workspace, props.getIndexName(), body, false);
    }

    String readIndex(Path workspace) {
        if (workspace == null) return "";
        try {
            Path file = workspace.resolve(props.getDir()).resolve(props.getIndexName());
            if (!Files.isRegularFile(file)) return "";
            String s = Files.readString(file, StandardCharsets.UTF_8);
            return clip(s, props.getMaxFileChars());
        } catch (Exception e) {
            return "";
        }
    }

    static boolean looksLikeIndex(String text) {
        return text != null
                && text.contains("当前进度")
                && (text.contains("下一步") || text.contains("核心"));
    }

    boolean isIndexName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || "MEMORY".equalsIgnoreCase(name)) return true;
        String index = props.getIndexName() == null ? "MEMORY.md" : props.getIndexName();
        return name.equalsIgnoreCase(index) || (name + ".md").equalsIgnoreCase(index);
    }

    static boolean isImplementSpawn(String toolName, String input) {
        if (!"spawnSubagent".equals(toolName)) return false;
        String mode = jsonFieldStatic(input, "mode");
        return mode != null && "implement".equalsIgnoreCase(mode.trim());
    }

    String jsonField(String json, String field) {
        return readJsonField(mapper, json, field);
    }

    static String jsonFieldStatic(String json, String field) {
        return readJsonField(new ObjectMapper(), json, field);
    }

    private static String readJsonField(ObjectMapper om, String json, String field) {
        if (json == null || json.isBlank() || field == null) return null;
        try {
            JsonNode n = om.readTree(json);
            JsonNode v = n.get(field);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    String firstPath(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = mapper.readTree(json);
            for (String k : new String[]{"path", "file", "target"}) {
                JsonNode v = n.get(k);
                if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
            }
        } catch (Exception ignored) {
            // 不是 JSON 就不当路径
        }
        return null;
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max)) + "…";
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
