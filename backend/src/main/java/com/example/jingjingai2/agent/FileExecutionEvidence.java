package com.example.jingjingai2.agent;

import com.example.jingjingai2.sandbox.WorkspaceContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves only explicit workspace file targets. Ambiguous references cannot prove a write. */
final class FileExecutionEvidence {
    private static final Pattern TARGET = Pattern.compile(
            "`([^`\\r\\n]+)`|\"([^\"\\r\\n]+)\"|“([^”\\r\\n]+)”"
                    + "|(?<![\\p{L}\\p{N}_./\\\\:-])((?:[A-Za-z]:)?[\\p{L}\\p{N}_./\\\\-]+"
                    + "\\.[\\p{L}\\p{N}][\\p{L}\\p{N}_-]*"
                    + "|(?:[A-Za-z]:)?[\\p{L}\\p{N}_./\\\\-]*[/\\\\][\\p{L}\\p{N}_.-]+"
                    + "|Dockerfile|Makefile|README|LICENSE)"
                    + "(?![\\p{L}\\p{N}_/\\\\:-])");
    private static final Pattern AMBIGUOUS_PREFIX = Pattern.compile(
            "(?i)(?:[\\w./\\\\:-]*[/\\\\][\\w.-]*|(?<![\\w.-])"
                    + "(?!(?:write|create|edit|save|generate|update|append|to|in|and|file|files|written|saved|created|generated)\\s)"
                    + "[a-z0-9_][a-z0-9_-]*)[ \\t]+$");

    private static final Pattern UNRESOLVED_LIST_ITEM = Pattern.compile(
            "(?iu)(?:[、，,与和及或]|\\b(?:and|or)\\b)[ \\t]*[a-z_][a-z0-9_-]*(?=[ \\t]*(?:[、，,。；;]|$))");
    /**
     * 引号/反引号里夹着代码符号的，是一段表达式而不是文件名。
     *
     * <p>P2：最终回答的表格里出现 {@code `price * (1.0 - rate)`} 时，它按「引用了一个目标」算，
     * 但又解析不成工作区路径（Windows 上 {@code *} 本来就是非法路径字符），于是整行被判定
     * 「声称写了文件却拿不出证据」。这类片段既不是文件，也不该参与交付核对。</p>
     *
     * <p>两点边界：①不要包含 {@code :}，{@code C:\work\a.txt} 是合法路径写法；
     * ②不要包含空格 —— 工作区里确实可以有「{@code notes/weekly a.md}」这种带空格的文件名，
     * 用空格判定会把真实文件挡在证据之外。</p>
     */
    private static final Pattern CODE_EXPRESSION = Pattern.compile("[*+<>=()\\[\\]{}'\"|&;~^%$#@!?]");
    /** 纯数字（{@code 1.0}、{@code 3.14}）不是「文件名.扩展名」，不能当成目标。 */
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");

    private FileExecutionEvidence() {}

    /** 这条引用能不能当作「工作区里的一个文件」来核对。 */
    static boolean pathShaped(String raw) {
        if (raw == null || raw.isBlank()) return false;
        if (CODE_EXPRESSION.matcher(raw).find()) return false;
        return !NUMERIC.matcher(raw).matches();
    }

    static boolean mentionsTarget(String text) {
        Matcher matcher = TARGET.matcher(text);
        while (matcher.find()) {
            String quoted = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (pathShaped(quoted == null ? matcher.group(4) : quoted)) return true;
        }
        return false;
    }

    static Set<Path> targets(String text) {
        Set<Path> paths = new LinkedHashSet<>();
        Matcher matcher = TARGET.matcher(text);
        StringBuilder unmatched = new StringBuilder(text);
        while (matcher.find()) {
            String quoted = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String raw = quoted == null ? matcher.group(4) : quoted;
            if (quoted == null && AMBIGUOUS_PREFIX.matcher(text.substring(0, matcher.start())).find())
                return Set.of();
            unmatched.replace(matcher.start(), matcher.end(), " ".repeat(matcher.end() - matcher.start()));
            // 表达式和数字不是文件目标：跳过，但也别让它们把同句里真正的文件名一起拖下水。
            if (!pathShaped(raw)) continue;
            Path path = path(raw);
            // 任何引用里夹了空格、冒号或括号，就是一句说明而不是路径
            // （例如「已创建文件: a.txt (大小 13 字节)」）。解析不了时跳过，
            // 不因此丢掉同句里真正的文件名。
            if (path == null) {
                if (quoted != null && quoted.matches("(?iu).*(文件|file).*") && !quoted.contains(" ")) return Set.of();
                continue;
            }
            paths.add(path);
        }
        return UNRESOLVED_LIST_ITEM.matcher(unmatched).find() ? Set.of() : paths;
    }

    static Path path(String raw) {
        Path root = WorkspaceContext.get();
        if (root == null || raw == null || raw.isBlank()) return null;
        try {
            root = root.toAbsolutePath().normalize();
            Path path = root.resolve(raw).normalize();
            if (!path.startsWith(root) || path.equals(root)) return null;
            // Resolve existing aliases, including a symlinked ancestor of a missing leaf.
            Path ancestor = path;
            while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (ancestor == null || !Files.exists(root)) return path;
            Path real = ancestor.toRealPath().resolve(ancestor.relativize(path));
            return real.startsWith(root.toRealPath()) ? real : null;
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    static String fingerprint(Path path) {
        try {
            Path root = WorkspaceContext.get();
            if (root == null) return null;
            Path real = path.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real)) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(Files.newInputStream(real), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unreadable) {
            return null;
        }
    }
}
