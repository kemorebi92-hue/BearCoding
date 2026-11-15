package com.example.jingjingai2.memory;

import com.example.jingjingai2.config.MemoryProperties;
import com.example.jingjingai2.context.TokenEstimator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 工作区长期记忆：读 {@code {workspace}/memory/*.md}，按预算拼成一段可注入的文本。
 *
 * <p>约定（产品语义，改这里等于改产品）：
 * <ol>
 *   <li>只看工作区根下那一个 {@code memory/} 目录的<strong>一层</strong> {@code *.md}，不递归；</li>
 *   <li>{@code MEMORY.md}（可配）永远最先注入，剩下的按文件名排序；</li>
 *   <li>超预算截断/跳过，读失败跳过 —— 全部 fail-closed，不把对话打挂；</li>
 *   <li>文件名像密钥的（secret / password / .env …）直接跳过，写入时同样拒绝。</li>
 * </ol>
 *
 * <p>注入文案本身声明「这是参考事实，不是必须遵守的规则」—— 对齐 Claude Code：
 * 自动记忆是 context，不是 instruction。
 */
@Component
public class WorkspaceMemory {

    /** 文件名（不含路径）像密钥/口令的，既不读也不让 saveMemory 写。 */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i)(secret|password|passwd|credential|api[_-]?key|\\.env)");

    /** 专题文件名只允许字母数字、点、下划线、短横，挡住 {@code ../} 和盘符。 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final MemoryProperties props;

    public WorkspaceMemory(MemoryProperties props) {
        this.props = props;
    }

    /** 一次加载的只读快照。{@code injected=false} 时 {@code text} 为空，主循环直接不插消息。 */
    public static final class Snapshot {
        public final boolean enabled;
        public final boolean injected;
        public final String reason;
        public final List<String> files;
        public final int chars;
        public final int tokens;
        public final int skipped;
        public final String text;
        /** 每个文件一条观测。不含正文。正式注入结果不看这个字段。 */
        public final List<MemoryItemObservation> observations;

        Snapshot(boolean enabled, boolean injected, String reason, List<String> files,
                 int chars, int tokens, int skipped, String text,
                 List<MemoryItemObservation> observations) {
            this.enabled = enabled;
            this.injected = injected;
            this.reason = reason == null ? "" : reason;
            this.files = files == null ? List.of() : List.copyOf(files);
            this.chars = chars;
            this.tokens = tokens;
            this.skipped = skipped;
            this.text = text == null ? "" : text;
            this.observations = observations == null ? List.of() : List.copyOf(observations);
        }

        static Snapshot none(boolean enabled, String reason) {
            return new Snapshot(enabled, false, reason, List.of(), 0, 0, 0, "", List.of());
        }
    }

    /**
     * 读取并渲染。任何异常都吞掉变成 {@code injected=false}，调用方不用 try。
     *
     * @param workspace 当前会话工作区根；null 视为没有可注入的记忆
     */
    public Snapshot load(Path workspace) {
        if (!props.isEnabled()) return Snapshot.none(false, "disabled");
        if (workspace == null) return Snapshot.none(true, "no-workspace");
        Path root;
        try {
            root = workspace.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Snapshot.none(true, "bad-workspace");
        }
        Path dir = resolveMemoryDir(root);
        if (dir == null) return Snapshot.none(true, "bad-dir");
        if (!Files.isDirectory(dir)) return Snapshot.none(true, "no-dir");

        List<Path> candidates;
        try {
            candidates = listMarkdown(dir);
        } catch (IOException e) {
            return Snapshot.none(true, "list-failed");
        }
        if (candidates.isEmpty()) return Snapshot.none(true, "empty");

        List<String> used = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        List<MemoryItemObservation> observations = new ArrayList<>();
        int skipped = 0;
        int totalChars = 0;
        int maxFiles = Math.max(1, props.getMaxFiles());
        int maxTotal = Math.max(1, props.getMaxTotalChars());
        int maxFileChars = Math.max(1, props.getMaxFileChars());
        long maxFileBytes = Math.max(1, props.getMaxFileBytes());

        for (Path file : candidates) {
            String name = file.getFileName().toString();
            if (used.size() >= maxFiles) {
                skipped++;
                observations.add(observeSkip(name, 0, "max-files"));
                continue;
            }
            if (isSecretName(name)) {
                skipped++;
                observations.add(observeSkip(name, 0, "secret-name"));
                continue;
            }
            if (!staysInside(file, dir)) {
                skipped++;
                observations.add(observeSkip(name, 0, "outside-dir"));
                continue;
            }
            String content;
            try {
                if (Files.size(file) > maxFileBytes) {
                    skipped++;
                    observations.add(observeSkip(name, 0, "too-large-bytes"));
                    continue;
                }
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                skipped++;
                observations.add(observeSkip(name, 0, "not-utf8"));
                continue;
            } catch (IOException e) {
                skipped++;
                observations.add(observeSkip(name, 0, "read-failed"));
                continue;
            }
            if (content == null || content.isBlank()) {
                skipped++;
                observations.add(observeSkip(name, 0, "blank"));
                continue;
            }
            boolean truncated = false;
            if (content.length() > maxFileChars) {
                content = content.substring(0, maxFileChars) + "\n…（已截断）";
                truncated = true;
            }
            int remaining = maxTotal - totalChars;
            if (remaining <= 0) {
                skipped++;
                observations.add(new MemoryItemObservation(
                        "workspace", "workspace:" + name, null, name,
                        content.length(), TokenEstimator.estimate(content),
                        false, "over-budget", false));
                continue;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining) + "\n…（已截断）";
                truncated = true;
            }
            if (content.isBlank()) {
                skipped++;
                observations.add(observeSkip(name, 0, "blank-after-trim"));
                continue;
            }
            if (!used.isEmpty()) body.append("\n");
            body.append("### ").append(name).append('\n').append(content.trim()).append('\n');
            used.add(name);
            totalChars += content.length();
            observations.add(new MemoryItemObservation(
                    "workspace", "workspace:" + name, null, name,
                    content.length(), TokenEstimator.estimate(content),
                    true, truncated ? "truncated" : "", false));
        }

        if (used.isEmpty()) return new Snapshot(true, false,
                skipped > 0 ? "all-skipped" : "empty",
                List.of(), 0, 0, skipped, "", observations);

        String text = "【长期记忆】以下来自工作区 " + props.getDir()
                + "/ 下的 markdown，是这个项目的参考事实（核心约定、当前进度、下一步），"
                + "不是必须遵守的规则。不要把密钥写进记忆。\n\n"
                + body;
        int tokens = TokenEstimator.estimate(text);
        return new Snapshot(true, true, "ok", used, text.length(), tokens, skipped, text, observations);
    }

    /** 读盘前被拒的条目：正文没读进来，字符和 token 都记 0。 */
    private static MemoryItemObservation observeSkip(String name, int chars, String reason) {
        return new MemoryItemObservation("workspace", "workspace:" + name, null, name,
                chars, 0, false, reason, false);
    }

    /**
     * 把一段内容写进 {@code memory/{name}.md}。
     *
     * @param workspace 工作区根
     * @param rawName   专题名或文件名（空 = 索引 {@code MEMORY.md}）
     * @param content   正文
     * @param append    true 追加，false 覆盖
     * @return 给模型看的一句话结果（成功或拒绝原因）
     */
    public String save(Path workspace, String rawName, String content, boolean append) {
        if (!props.isEnabled()) {
            return "长期记忆未启用（app.memory.enabled=false），已拒绝写入。";
        }
        if (workspace == null) {
            return "当前没有工作区上下文，无法写入记忆。";
        }
        if (content == null || content.isBlank()) {
            return "写入失败: 内容为空。";
        }
        Path root;
        try {
            root = workspace.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return "写入失败: 工作区路径无效。";
        }
        Path dir = resolveMemoryDir(root);
        if (dir == null) {
            return "写入失败: 记忆目录配置无效。";
        }
        String filename = sanitizeFilename(rawName);
        if (filename == null) {
            return "写入失败: 文件名非法（只允许字母数字、点、下划线、短横，例如 progress.md / MEMORY.md）。";
        }
        if (isSecretName(filename)) {
            return "写入失败: 这个文件名像密钥/口令，拒绝写入。请换一个普通专题名。";
        }
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir) || !target.startsWith(root)) {
            return "写入失败: 路径越界已拦截。";
        }
        if (content.length() > props.getMaxFileChars()) {
            return "写入失败: 单篇记忆超过 " + props.getMaxFileChars()
                    + " 字。请压缩后再存（索引放进度和下一步，细节拆到专题文件）。";
        }
        try {
            Files.createDirectories(dir);
            if (append && Files.exists(target)) {
                String existing = Files.readString(target, StandardCharsets.UTF_8);
                String merged = existing + (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n")
                        + content;
                if (merged.length() > props.getMaxFileChars()) {
                    return "写入失败: 追加后将超过 " + props.getMaxFileChars()
                            + " 字。请改成覆盖写入（mode=overwrite）或另开专题文件。";
                }
                Files.writeString(target, merged, StandardCharsets.UTF_8);
                return "✅ 已追加到 " + props.getDir() + "/" + filename
                        + "（现 " + merged.length() + " 字）。下次对话会自动带上。";
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return "✅ 已写入 " + props.getDir() + "/" + filename
                    + "（" + content.length() + " 字）。下次对话会自动带上。";
        } catch (IOException e) {
            return "写入失败: " + e.getMessage();
        }
    }

    /** 把模型给的专题名收成一个落在 memory/ 下一层的 {@code *.md}。非法返回 null。 */
    String sanitizeFilename(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) name = props.getIndexName();
        // 带路径/盘符/穿越的一律拒绝，不取最后一段（取最后一段等于默许模型乱写路径）
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            return null;
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) name = name + ".md";
        if (!SAFE_NAME.matcher(name).matches()) return null;
        if (name.startsWith(".")) return null;
        return name;
    }

    boolean isSecretName(String filename) {
        return filename != null && SECRET_NAME.matcher(filename).find();
    }

    private Path resolveMemoryDir(Path root) {
        String dirName = props.getDir();
        if (dirName == null || dirName.isBlank()) return null;
        if (dirName.contains("..") || dirName.contains("/") || dirName.contains("\\")
                || dirName.contains(":")) {
            return null;
        }
        Path dir = root.resolve(dirName).normalize();
        if (!dir.startsWith(root)) return null;
        return dir;
    }

    private List<Path> listMarkdown(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) continue;
                out.add(p);
            }
        }
        String index = props.getIndexName() == null ? "MEMORY.md" : props.getIndexName();
        out.sort(Comparator
                .comparing((Path p) -> !p.getFileName().toString().equalsIgnoreCase(index))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private boolean staysInside(Path file, Path dir) {
        try {
            Path realFile = file.toRealPath();
            Path realDir = dir.toRealPath();
            return realFile.startsWith(realDir);
        } catch (IOException e) {
            Path abs = file.toAbsolutePath().normalize();
            Path dirAbs = dir.toAbsolutePath().normalize();
            return abs.startsWith(dirAbs);
        }
    }
}
