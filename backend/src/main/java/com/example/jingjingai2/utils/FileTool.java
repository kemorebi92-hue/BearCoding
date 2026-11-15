package com.example.jingjingai2.utils;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import com.example.jingjingai2.agent.SubagentProperties;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.sandbox.SandboxViolationException;
import com.example.jingjingai2.service.EditAuditService;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolSnapshotIntegrity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 文件级工具：Read / Write / Edit（带 diff）。
 *
 * 这是"Code Agent"和"只会敲命令的脚本"的分水岭：
 * 让模型用结构化、可校验的方式读写文件，而不是用 echo/sed 这种易出错且不可预览的命令。
 */
@Component
public class FileTool {

    private static final int MAX_READ_CHARS = 20000;

    /**
     * 代码检索时默认跳过的目录：版本控制 / 依赖 / 构建产物。
     * 不跳过这些目录，一次 grep 就会把 node_modules、target 里的几万个文件全扫一遍，
     * 既慢又会把上下文淹没在无关结果里。
     */
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "dist", "build",
            "out", ".idea", ".vscode", "__pycache__", ".gradle");

    /** 单个被扫描文件的大小上限（超过则跳过，避免把二进制/大文件读进内存） */
    private static final long MAX_SCAN_FILE_BYTES = 2L * 1024 * 1024;

    /** 一次检索最多遍历的文件数，防止误对超大目录执行时卡死 */
    private static final int MAX_SCAN_FILES = 5000;

    public static final String GREP_WIDE_HIT_TIP =
            "命中较多，建议缩小范围（指定目录/文件类型/更具体的关键词），或对不同目录分别派 explore。";

    private final PathSandbox sandbox;
    private final EditAuditService editAudit;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SubagentProperties subagentProperties;

    /** 单测 / 无 Spring 场景使用：不做编辑审计 */
    public FileTool(PathSandbox sandbox) {
        this(sandbox, null);
    }

    @Autowired
    public FileTool(PathSandbox sandbox, EditAuditService editAudit) {
        this.sandbox = sandbox;
        this.editAudit = editAudit;
    }

    @Autowired(required = false)
    public void setSubagentProperties(SubagentProperties subagentProperties) {
        this.subagentProperties = subagentProperties;
    }

    /** 记录一次编辑审计；editAudit 可为空（单测场景） */
    private void audit(String path, boolean applied, String diff) {
        if (editAudit != null) {
            editAudit.record(path, applied, diff);
        }
    }

    @Tool(description = "读取文件内容。适用于查看源码、配置文件等。支持 offset(起始行,从1开始) 和 limit(最多读取行数) 来只读部分内容，避免大文件占满上下文。path 也可以传工具结果里的可信引用（[artifact-ref]…[/artifact-ref]），此时按引用里的路径读取并校验内容哈希，不一致则拒绝返回。注意：只能访问工作区或本次会话允许的只读目录，越界会被沙箱拦截。读取结果会直接返回文件文本（带行号）。")
    public String readFile(@ToolParam(description = "文件路径（绝对或相对于工作区），或工具结果里的可信引用") String path,
                           @ToolParam(description = "起始行号(从1开始)，可选，默认1", required = false) Integer offset,
                           @ToolParam(description = "最多读取的行数，可选，默认2000", required = false) Integer limit) {
        ToolArtifactRef.Ref ref = signedRefOf(path);
        if (path != null && path.contains(ToolArtifactRef.OPEN) && ref == null) {
            return "读取失败: 引用签名无效或已被篡改，拒绝按引用读取";
        }
        String target = ref == null ? path : ref.path();
        Path p;
        // 引用读取：验签、路径授权、存在性、内容哈希在同一次读取里完成。
        // 不能因为退休时校验过就跳过——那次校验和这次读取之间可能隔了好几轮。
        ToolSnapshotIntegrity.Check checked = null;
        if (ref != null) {
            checked = ToolSnapshotIntegrity.verify(ref, sandbox);
            if (!checked.ok()) return snapshotFailure(checked);
            p = checked.path();
        } else {
            try {
                p = sandbox.resolveForRead(target);
            } catch (SandboxViolationException e) {
                return "读取失败: " + e.getMessage();
            }
        }
        try {
            byte[] bytes;
            if (checked != null) {
                // 正文必须来自刚校验过的那份字节，避免「校验完再读一次」被替换。
                bytes = checked.content();
            } else {
                if (!Files.exists(p)) return "读取失败: 文件不存在 -> " + target;
                if (Files.isDirectory(p)) return "读取失败: 这是一个目录，不是文件 -> " + target;
                bytes = Files.readAllBytes(p);
            }
            // 哈希覆盖原始字节。展示按 readAllLines 的语义切行，避免 CRLF 行尾被留在正文里，
            // 也避免文件以换行结束时比原先多出一行空行。
            java.util.List<String> allLines = linesOf(bytes);
            int start = (offset == null || offset < 1) ? 0 : Math.min(offset - 1, allLines.size());
            int end = (limit == null || limit < 1) ? allLines.size() : Math.min(start + limit, allLines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append("\t").append(allLines.get(i)).append("\n");
                if (sb.length() > MAX_READ_CHARS) {
                    sb.append("...[内容过长已截断]\n");
                    break;
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "读取失败: " + e.getMessage();
        }
    }

    /**
     * 输入本身是一条完整的签名引用时返回它。普通路径、缺签名和签名不匹配都返回 null。
     * 引用里的路径随后仍要过只读沙箱，签名不能扩大可读范围。
     */
    private static ToolArtifactRef.Ref signedRefOf(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (!trimmed.startsWith(ToolArtifactRef.OPEN) || !trimmed.endsWith(ToolArtifactRef.CLOSE)) return null;
        return ToolArtifactRef.parse(trimmed);
    }

    /**
     * 退休前的可读性/完整性探测。走的是**与 readFile 完全相同**的沙箱与哈希校验，
     * 因此结论等价于「模型现在按这条引用读取会成功」。
     *
     * <p>工作集策略用它决定能不能把工具正文换成可恢复引用；返回非 OK 就不许退休。
     */
    public ToolSnapshotIntegrity.Status snapshotStatus(ToolArtifactRef.Ref ref) {
        return ToolSnapshotIntegrity.verify(ref, sandbox).status();
    }

    /** 引用读不出来时的明确失败说明。每种状态都有自己的原因，不返回任何正文。 */
    private static String snapshotFailure(ToolSnapshotIntegrity.Check check) {
        return switch (check.status()) {
            case OK -> "";
            case NO_HASH -> "读取失败: 引用缺少内容哈希，无法证明快照完整，拒绝按引用读取";
            case PATH_UNSAFE -> "读取失败: 引用里的路径不合法（" + check.detail() + "）";
            case NOT_AUTHORIZED -> "读取失败: " + check.detail();
            case MISSING -> "读取失败: 文件不存在 -> " + check.path();
            case UNREADABLE -> "读取失败: 快照读取失败（" + check.detail() + "） -> " + check.path();
            case HASH_MISMATCH -> "读取失败: 快照内容校验不一致 -> " + check.path();
        };
    }

    /**
     * 按 {@code Files.readAllLines} 的语义切行：\n、\r\n、\r 都算换行，末尾的换行不多出一行空行。
     * 编码错误仍然抛 {@link IOException}，和直接 readAllLines 的行为一致（二进制文件读不了就是读不了）。
     */
    private static java.util.List<String> linesOf(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes == null ? new byte[0] : bytes))
                .toString();
        java.util.List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') continue;
            out.add(text.substring(start, i));
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            start = i + 1;
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    @Tool(description = "写入/创建文件（整体覆盖式）。【重要】请先判断用户意图再选用：1) 修改已有文件的某几行 → 用 editFile；2) 只是往已有文件末尾追加内容 → 用 appendFile；3) 仅当需要“新建文件”或“整体重写/彻底替换”文件时，才用 writeFile。writeFile 会完全覆盖同名文件的原有内容；若文件已存在且你只是想补充或局部修改，请改用 appendFile 或 editFile，不要用 writeFile 覆盖。注意：只能访问工作区内的文件，越界会被沙箱拦截。")
    public String writeFile(@ToolParam(description = "文件路径（绝对或相对于工作区）") String path,
                            @ToolParam(description = "要写入的完整文件内容") String content) {
        Path p;
        try {
            p = sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "写入失败: " + e.getMessage();
        }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            boolean existed = Files.exists(p);
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
            long bytes = Files.size(p);
            return "✅ 已" + (existed ? "覆盖写入" : "创建") + "文件: " + path + " (大小 " + bytes + " 字节, " + Files.readAllLines(p, StandardCharsets.UTF_8).size() + " 行)";
        } catch (IOException e) {
            return "写入失败: " + e.getMessage();
        }
    }

    @Tool(description = "【结构化编辑·改已有文件】把文件中的一段内容精确替换：oldStr → newStr。要求 oldStr 在文件中【唯一匹配】（纯字符串精确匹配，不支持正则，必须与原文完全一致、含空格与换行）。若匹配到 0 处或多处，会直接返回错误并告诉你匹配到几处、分别在第几行，此时不会猜测应用哪一处——请用 grepFile 重新定位，并把 oldStr 扩大到包含前后几行使其唯一后重试。newStr 传空字符串表示【删除】该片段。修改后会返回 diff 差异，并写入编辑审计日志。注意：只能访问工作区内的文件，越界会被沙箱拦截。")
    public String editFile(@ToolParam(description = "文件路径（相对工作区）") String path,
                           @ToolParam(description = "要被替换掉的原始文本片段，必须与文件内容完全一致且全文唯一") String oldStr,
                           @ToolParam(description = "替换后的新文本片段；传空字符串表示删除该片段") String newStr) {
        Path p;
        try {
            p = sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "修改失败: " + e.getMessage();
        }
        try {
            if (!Files.exists(p)) return "修改失败: 文件不存在 -> " + path;
            if (Files.isDirectory(p)) return "修改失败: 这是一个目录，不是文件 -> " + path;
            if (oldStr == null || oldStr.isEmpty()) return "修改失败: oldStr 不能为空";

            String original = Files.readString(p, StandardCharsets.UTF_8);

            // 精确统计 oldStr 的出现次数（纯字符串匹配，不走正则；不做 trim 回退猜测）
            List<Integer> hits = new ArrayList<>();
            int from = 0;
            while (true) {
                int idx = original.indexOf(oldStr, from);
                if (idx < 0) break;
                hits.add(idx);
                from = idx + Math.max(1, oldStr.length());
            }

            if (hits.isEmpty()) {
                return "修改失败: oldStr 在文件中匹配到 0 处（未命中），未做任何修改。\n"
                        + "请先用 grepFile 定位原文，并让 oldStr 与文件内容完全一致（注意空格/缩进/换行）后重试。\n"
                        + "文件: " + path;
            }
            if (hits.size() > 1) {
                StringBuilder where = new StringBuilder();
                int show = Math.min(hits.size(), 20);
                for (int i = 0; i < show; i++) {
                    where.append("  第 ").append(lineNumberOf(original, hits.get(i))).append(" 行\n");
                }
                if (hits.size() > show) {
                    where.append("  ...（共 ").append(hits.size()).append(" 处，此处只列出前 ").append(show).append(" 处）\n");
                }
                return "修改失败: oldStr 在文件中匹配到 " + hits.size() + " 处（不唯一），为避免改错位置，未做任何修改。\n"
                        + "匹配位置（行号）:\n" + where
                        + "请扩大 oldStr 的上下文（带上前后独特的几行）使其在文件中唯一，或先用 grepFile 精确定位后重试。\n"
                        + "文件: " + path;
            }

            int idx = hits.get(0);
            boolean deletion = (newStr == null || newStr.isEmpty());
            String updated = original.substring(0, idx)
                    + (newStr == null ? "" : newStr)
                    + original.substring(idx + oldStr.length());

            // 先算 diff（留痕），再落盘：这样即使后续要做"人工确认后再应用"，diff 也已经准备好了
            String diff = buildDiff(path, original, idx, oldStr, newStr);
            Files.writeString(p, updated, StandardCharsets.UTF_8);
            audit(path, true, diff);

            return "✅ 已修改文件: " + path + (deletion ? "（删除片段）" : "") + "\n差异(diff):\n" + diff;
        } catch (IOException e) {
            return "修改失败: " + e.getMessage();
        }
    }

    @Tool(description = "【结构化编辑·新建文件】新建一个文件并写入内容。仅用于【新建】：若目标文件已存在会直接报错，不会覆盖（要改已有文件请用 editFile；要整体重写请用 writeFile）。父目录不存在会自动创建。返回 diff 并写入编辑审计日志。注意：只能在工作区内创建，越界会被沙箱拦截。")
    public String createFile(@ToolParam(description = "文件路径（相对工作区）") String path,
                             @ToolParam(description = "要写入的文件内容") String content) {
        Path p;
        try {
            p = sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "新建失败: " + e.getMessage();
        }
        try {
            if (Files.exists(p)) {
                return Files.isDirectory(p)
                        ? "新建失败: 同名目录已存在 -> " + path
                        : "新建失败: 文件已存在 -> " + path + "（要修改已有文件请用 editFile；要整体重写请用 writeFile）";
            }
            if (p.getParent() != null) Files.createDirectories(p.getParent());

            String body = content == null ? "" : content;
            String diff = buildCreateDiff(path, body);
            Files.writeString(p, body, StandardCharsets.UTF_8);
            audit(path, true, diff);

            return "✅ 已新建文件: " + path + "（" + Files.readAllLines(p, StandardCharsets.UTF_8).size()
                    + " 行, " + Files.size(p) + " 字节）\n差异(diff):\n" + diff;
        } catch (IOException e) {
            return "新建失败: " + e.getMessage();
        }
    }

    /** 字符下标 → 行号（从 1 开始） */
    private int lineNumberOf(String text, int charIdx) {
        int line = 1;
        int limit = Math.min(charIdx, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * 生成一段类似 git diff 的 unified diff 文本，让人/模型一眼看懂改了什么。
     * 只展示变更点附近的上下文（前后各 2 行），不输出整份文件，省 token 也更易读。
     *
     * 注意：oldStr 常常只是"半行"（例如只替换某行中间的一段），所以新内容要拼回该行的
     * 前缀与后缀，否则 diff 里会丢掉缩进、看起来像把整行换掉了。
     *
     * @param idx oldStr 在 original 中唯一出现的位置（字符下标）
     */
    private String buildDiff(String path, String original, int idx, String oldStr, String newStr) {
        String safeNew = newStr == null ? "" : newStr;
        int endIdx = idx + oldStr.length();

        // oldStr 覆盖的行范围（若以换行结尾，不把下一行算进来）
        int startLine = lineNumberOf(original, idx) - 1;
        int endLine = lineNumberOf(original, Math.max(idx, endIdx - 1)) - 1;

        int lineStart = lineStartOffsetOf(original, startLine);
        int endLineStart = lineStartOffsetOf(original, endLine);
        int endLineEnd = lineEndOffsetOf(original, endLineStart);
        String prefix = original.substring(lineStart, idx);
        String suffix = original.substring(Math.min(endIdx, endLineEnd), endLineEnd);
        String newBlock = prefix + safeNew + suffix;

        String[] origLines = original.split("\n", -1);
        int context = 2;
        int from = Math.max(0, startLine - context);
        int to = Math.min(origLines.length, endLine + context + 1);

        int oldSpan = to - from;
        int newLines = newBlock.isEmpty() ? 0 : newBlock.split("\n", -1).length;
        int newSpan = (startLine - from) + newLines + (to - (endLine + 1));

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(path).append("\n");
        sb.append("+++ b/").append(path).append("\n");
        sb.append("@@ -").append(from + 1).append(",").append(oldSpan)
                .append(" +").append(from + 1).append(",").append(newSpan).append(" @@\n");

        for (int i = from; i < startLine; i++) {
            sb.append(" ").append(origLines[i]).append("\n");
        }
        for (int i = startLine; i <= endLine && i < origLines.length; i++) {
            sb.append("-").append(origLines[i]).append("\n");
        }
        if (!newBlock.isEmpty()) {
            for (String nl : newBlock.split("\n", -1)) {
                sb.append("+").append(nl).append("\n");
            }
        }
        for (int i = endLine + 1; i < to; i++) {
            sb.append(" ").append(origLines[i]).append("\n");
        }
        return sb.toString();
    }

    /** 第 lineIdx 行（0 基）的行首字符下标 */
    private int lineStartOffsetOf(String text, int lineIdx) {
        int line = 0, i = 0;
        while (i < text.length() && line < lineIdx) {
            if (text.charAt(i) == '\n') line++;
            i++;
        }
        return i;
    }

    /** 从行首 lineStart 开始，该行内容的结束下标（不含换行符） */
    private int lineEndOffsetOf(String text, int lineStart) {
        int i = lineStart;
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    /** 新建文件的 diff（等价于 git 的 "新文件" 形式）。超长内容只记录前 500 行，避免审计日志爆炸。 */
    private String buildCreateDiff(String path, String content) {
        String[] lines = content.split("\n", -1);
        int limit = Math.min(lines.length, 500);
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(path).append("\n");
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (int i = 0; i < limit; i++) {
            sb.append("+").append(lines[i]).append("\n");
        }
        if (lines.length > limit) {
            sb.append("...（另有 ").append(lines.length - limit).append(" 行未记录）\n");
        }
        return sb.toString();
    }

    @Tool(description = "向文件末尾追加内容（不会覆盖原有内容）。文件不存在时会自动创建。适合在已有文件后面补充内容。注意：只能访问工作区内的文件，越界会被沙箱拦截。")
    public String appendFile(@ToolParam(description = "文件路径（绝对或相对于工作区）") String path,
                             @ToolParam(description = "要追加的内容") String content) {
        Path p;
        try {
            p = sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "追加失败: " + e.getMessage();
        }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            boolean existed = Files.exists(p);
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            long bytes = Files.size(p);
            return "✅ 已" + (existed ? "追加内容到" : "创建并写入") + "文件: " + path + "（当前大小 " + bytes + " 字节）";
        } catch (IOException e) {
            return "追加失败: " + e.getMessage();
        }
    }

    @Tool(description = "删除文件或目录。【安全默认】recursive 默认为 false：删除目录时若目录【非空】，直接返回错误提示、不做任何删除；只有显式传 recursive=true 才会连同子内容一起删除。注意：只能访问工作区内的路径，越界会被沙箱拦截。")
    public String deletePath(@ToolParam(description = "要删除的文件/目录路径") String path,
                             @ToolParam(description = "是否递归删除非空目录，默认 false（非空目录会被拒绝）", required = false) Boolean recursive) {
        Path p;
        try {
            p = sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "删除失败: " + e.getMessage();
        }
        try {
            if (!Files.exists(p)) return "删除失败: 路径不存在 -> " + path;
            boolean rec = Boolean.TRUE.equals(recursive);
            if (Files.isDirectory(p)) {
                boolean empty;
                try (Stream<Path> s = Files.list(p)) { empty = s.findAny().isEmpty(); }
                if (!empty && !rec) {
                    return "删除失败: 目录非空，未执行任何删除 -> " + path
                            + "（如确认要连同内容一起删除，请设置 recursive=true）";
                }
                deleteRecursively(p);
            } else {
                Files.delete(p);
            }
            return "✅ 已删除: " + path;
        } catch (IOException e) {
            return "删除失败: " + e.getMessage();
        }
    }

    @Tool(description = "新建目录，支持多级创建（例如 a/b/c）。若目录已存在则提示已存在。注意：只能在工作区内创建，越界会被沙箱拦截。")
    public String createDirectory(@ToolParam(description = "要创建的目录路径（绝对或相对于工作区）") String path) {
        Path p;
        try {
            p = sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "创建目录失败: " + e.getMessage();
        }
        try {
            if (Files.exists(p)) {
                return Files.isDirectory(p) ? "目录已存在: " + path : "创建失败: 同名文件已存在 -> " + path;
            }
            Files.createDirectories(p);
            return "✅ 已创建目录: " + path;
        } catch (IOException e) {
            return "创建目录失败: " + e.getMessage();
        }
    }

    @Tool(description = "移动或重命名文件/目录（from -> to）。目标父目录不存在会自动创建；若目标已存在则报错、不覆盖。注意：from、to 都必须在工作区内，越界会被沙箱拦截。")
    public String movePath(@ToolParam(description = "源路径") String from,
                           @ToolParam(description = "目标路径") String to) {
        Path src, dst;
        try {
            src = sandbox.resolveWithin(from);
            dst = sandbox.resolveWithin(to);
        } catch (SandboxViolationException e) {
            return "移动失败: " + e.getMessage();
        }
        try {
            if (!Files.exists(src)) return "移动失败: 源路径不存在 -> " + from;
            if (Files.exists(dst)) return "移动失败: 目标已存在 -> " + to;
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            return "✅ 已移动: " + from + " -> " + to;
        } catch (IOException e) {
            return "移动失败: " + e.getMessage();
        }
    }

    @Tool(description = "复制文件或目录到目标路径（目录会连同其内容一起复制）。目标已存在则报错、不覆盖。注意：from、to 都必须在工作区内，越界会被沙箱拦截。")
    public String copyPath(@ToolParam(description = "源路径") String from,
                           @ToolParam(description = "目标路径") String to) {
        Path src, dst;
        try {
            // 源是读语义：允许从只读根读取；目标仍是写语义，只读根禁止写入。
            src = sandbox.resolveForRead(from);
            dst = sandbox.resolveWithin(to);
        } catch (SandboxViolationException e) {
            return "复制失败: " + e.getMessage();
        }
        try {
            if (!Files.exists(src)) return "复制失败: 源路径不存在 -> " + from;
            if (Files.exists(dst)) return "复制失败: 目标已存在 -> " + to;
            if (Files.isDirectory(src)) {
                copyRecursively(src, dst);
            } else {
                if (dst.getParent() != null) Files.createDirectories(dst.getParent());
                Files.copy(src, dst);
            }
            return "✅ 已复制: " + from + " -> " + to;
        } catch (Exception e) {
            return "复制失败: " + e.getMessage();
        }
    }

    @Tool(description = "列出目录下的文件与子目录，返回结构化数据（每项含 name 名称、type 类型 file/dir、size 字节数）。path 留空表示工作区根目录。注意：只能访问工作区内的目录，越界会被沙箱拦截。")
    public String listDirectory(@ToolParam(description = "目录路径（相对工作区），留空表示工作区根目录", required = false) String path) {
        Path dir;
        try {
            dir = (path == null || path.isBlank()) ? sandbox.root() : sandbox.resolveWithin(path);
        } catch (SandboxViolationException e) {
            return "列目录失败: " + e.getMessage();
        }
        try {
            if (!Files.exists(dir)) return "列目录失败: 目录不存在 -> " + (path == null || path.isBlank() ? "(工作区根)" : path);
            if (!Files.isDirectory(dir)) return "列目录失败: 不是目录 -> " + path;

            List<Map<String, Object>> items = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    boolean isDir = Files.isDirectory(p);
                    long size = 0L;
                    if (!isDir) {
                        try { size = Files.size(p); } catch (IOException ignored) {}
                    }
                    m.put("name", p.getFileName().toString());
                    m.put("type", isDir ? "dir" : "file");
                    m.put("size", size);
                    items.add(m);
                });
            }
            items.sort(Comparator
                    .comparing((Map<String, Object> m) -> !"dir".equals(m.get("type")))
                    .thenComparing(m -> String.valueOf(m.get("name"))));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", (path == null || path.isBlank()) ? "(工作区根)" : path);
            result.put("count", items.size());
            result.put("items", items);
            // 递归规模（跳过忽略目录），供 V2「探一眼」用可观察标准
            long[] stats = recursiveFileStats(dir);
            result.put("totalFilesRecursive", stats[0]);
            result.put("totalBytesRecursive", stats[1]);
            result.put("scaleNote", scaleNote((int) Math.min(stats[0], Integer.MAX_VALUE), stats[1]));
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "列目录失败: " + e.getMessage();
        }
    }

    @Tool(description = "【定位文件用】按文件名/路径的「通配符」搜索工作区内有哪些文件（只匹配文件名，不搜文件内容）。使用场景：当你不知道目标文件在哪、需要先摸清项目结构时，先用它列出候选文件，再 grepFile/readFile 精读。pattern 用 glob 语法：`**/*.java` 表示任意层级的 java 文件，`src/**/*.xml`、`*.md`、`pom.xml` 均可；多个模式用逗号分隔（如 `*.java,*.xml`）。默认会跳过 .git、node_modules、target、dist、build 等目录。返回的是相对工作区的路径列表。")
    public String globFile(@ToolParam(description = "glob 通配模式，例如 **/*.java 或 src/**/*.xml；多个用逗号分隔") String pattern,
                           @ToolParam(description = "搜索起始目录（相对工作区），可选，默认工作区根目录", required = false) String path,
                           @ToolParam(description = "最多返回的文件数，可选，默认 200", required = false) Integer limit) {
        if (pattern == null || pattern.isBlank()) return "搜索失败: pattern 不能为空";

        Path start;
        try {
            start = (path == null || path.isBlank()) ? sandbox.root() : sandbox.resolveForRead(path);
        } catch (SandboxViolationException e) {
            return "搜索失败: " + e.getMessage();
        }
        if (!Files.exists(start)) return "搜索失败: 路径不存在 -> " + path;
        if (!Files.isDirectory(start)) return "搜索失败: globFile 需要以目录作为搜索起点 -> " + path;

        List<PathMatcher> matchers = new ArrayList<>();
        for (String raw : pattern.split(",")) {
            String g = raw.trim();
            if (g.isEmpty()) continue;
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
                // Java 的 glob 里 `**/*.java` 不匹配根目录下的 A.java，补一条去掉前缀的规则
                if (g.startsWith("**/")) {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g.substring(3)));
                }
            } catch (RuntimeException e) {
                return "搜索失败: 非法的 glob 模式 -> " + g;
            }
        }
        if (matchers.isEmpty()) return "搜索失败: pattern 不能为空";

        int max = (limit == null || limit < 1) ? 200 : limit;
        Path root = sandbox.root();
        List<String> hits = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> s = Files.walk(start)) {
            Iterator<Path> it = s.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) {
                Path f = it.next();
                if (underIgnoredDir(f, start)) continue;
                Path rel = root.relativize(f);
                if (matchesAny(matchers, rel)) {
                    if (hits.size() >= max) { truncated = true; break; }
                    hits.add(toUnix(rel));
                }
            }
        } catch (IOException e) {
            return "搜索失败: " + e.getMessage();
        }

        if (hits.isEmpty()) {
            return "未找到匹配的文件（模式: " + pattern + "，范围: "
                    + (path == null || path.isBlank() ? "(工作区根)" : path) + "）";
        }
        hits.sort(Comparator.naturalOrder());
        long totalBytes = 0L;
        for (String h : hits) {
            try {
                totalBytes += Files.size(root.resolve(h.replace('/', java.io.File.separatorChar)));
            } catch (Exception ignored) {}
        }
        // 若截断，再扫一遍只计数不列入（近似）
        int listed = hits.size();
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(listed).append(" 个文件");
        if (truncated) sb.append("（已达上限 ").append(max).append("，结果可能不全）");
        sb.append("，合计约 ").append(totalBytes).append(" 字节");
        sb.append("。").append(scaleNote(listed, totalBytes));
        sb.append("\n");
        for (String h : hits) sb.append(h).append('\n');
        return sb.toString();
    }

    @Tool(description = "【定位代码用】按「内容」搜索工作区内的文件，返回「文件路径 + 行号 + 上下文行」。使用场景：改代码前先精确定位——比如想知道某个方法/类名/字符串在哪定义、被谁引用。pattern 支持正则表达式（非法正则会自动按字面量处理）。默认跳过 .git、node_modules、target、dist、build 等目录，并忽略 2MB 以上的文件。返回格式：每个文件一段，匹配行前缀为 `行号: `，上下文行前缀为 `行号| `，不连续区域之间用 `--` 分隔。先用 globFile 缩小范围，再用 readFile 精读命中的文件，是本工具推荐用法。")
    public String grepFile(@ToolParam(description = "要搜索的内容，支持正则表达式") String pattern,
                           @ToolParam(description = "搜索起点：目录或单个文件（相对工作区），可选，默认工作区根目录", required = false) String path,
                           @ToolParam(description = "只搜索这些后缀的文件，多个用逗号分隔，例如 java,xml,yml（不填则搜全部文本文件）", required = false) String include,
                           @ToolParam(description = "是否区分大小写，可选，默认 false", required = false) Boolean caseSensitive,
                           @ToolParam(description = "每个匹配行前后展示的上下文行数，可选，默认 2，最大 10", required = false) Integer contextLines,
                           @ToolParam(description = "最多返回的匹配行数，可选，默认 100", required = false) Integer limit) {
        if (pattern == null || pattern.isBlank()) return "搜索失败: pattern 不能为空";

        Path target;
        try {
            target = (path == null || path.isBlank()) ? sandbox.root() : sandbox.resolveForRead(path);
        } catch (SandboxViolationException e) {
            return "搜索失败: " + e.getMessage();
        }
        if (!Files.exists(target)) return "搜索失败: 路径不存在 -> " + path;

        int flags = Boolean.TRUE.equals(caseSensitive) ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern re;
        try {
            re = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            re = Pattern.compile(Pattern.quote(pattern), flags);   // 非法正则 → 退化为字面量搜索
        }

        Set<String> exts = parseExtensions(include);
        int ctx = (contextLines == null || contextLines < 0) ? 2 : Math.min(contextLines, 10);
        int max = (limit == null || limit < 1) ? 100 : limit;

        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            files.add(target);
        } else {
            try (Stream<Path> s = Files.walk(target)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> !underIgnoredDir(f, target))
                        .filter(f -> matchesExtension(f, exts))
                        .limit(MAX_SCAN_FILES)
                        .forEach(files::add);
            } catch (IOException e) {
                return "搜索失败: " + e.getMessage();
            }
        }

        Path root = sandbox.root();
        StringBuilder sb = new StringBuilder();
        int totalMatches = 0;
        int fileHits = 0;
        int displayedMatches = 0;
        boolean truncated = false;

        for (Path f : files) {
            List<String> lines;
            try {
                if (Files.size(f) > MAX_SCAN_FILE_BYTES) continue;
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;   // 二进制文件/编码异常 → 跳过
            }

            boolean[] isMatch = new boolean[lines.size()];
            int hitInFile = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (re.matcher(lines.get(i)).find()) {
                    isMatch[i] = true;
                    hitInFile++;
                }
            }
            if (hitInFile == 0) continue;

            fileHits++;
            totalMatches += hitInFile;

            // 已达展示上限：只累计总数，不再往结果里追加正文
            if (displayedMatches >= max) {
                truncated = true;
                continue;
            }

            // 要展示的行 = 匹配行 ∪ 其前后 ctx 行
            boolean[] show = new boolean[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                if (!isMatch[i]) continue;
                int from = Math.max(0, i - ctx);
                int to = Math.min(lines.size() - 1, i + ctx);
                for (int k = from; k <= to; k++) show[k] = true;
            }

            sb.append(toUnix(root.relativize(f))).append('\n');
            int prev = -2;
            for (int i = 0; i < lines.size(); i++) {
                if (!show[i]) continue;
                if (prev >= 0 && i > prev + 1) sb.append("  --\n");
                sb.append("  ").append(i + 1).append(isMatch[i] ? ": " : "| ").append(lines.get(i)).append('\n');
                prev = i;
            }
            displayedMatches += hitInFile;
            if (displayedMatches >= max) truncated = true;
        }

        if (fileHits == 0) {
            return "未找到匹配的内容（pattern: " + pattern + "，范围: "
                    + (path == null || path.isBlank() ? "(工作区根)" : path)
                    + (exts.isEmpty() ? "" : "，后缀过滤: " + include) + "）";
        }
        sb.append("\n共 ").append(totalMatches).append(" 处匹配，命中 ").append(fileHits).append(" 个文件");
        if (truncated) sb.append("（正文已截断，仅展示前约 ").append(max).append(" 处）");
        int lineThr = grepLineThreshold();
        int fileThr = grepFileThreshold();
        if (truncated || totalMatches >= lineThr || fileHits >= fileThr) {
            sb.append('\n').append(GREP_WIDE_HIT_TIP);
        }
        return sb.toString();
    }

    private int grepLineThreshold() {
        return subagentProperties == null ? 100 : subagentProperties.effectiveGrepHitLineThreshold();
    }

    private int grepFileThreshold() {
        return subagentProperties == null ? 30 : subagentProperties.effectiveGrepHitFileThreshold();
    }

    private int probeFileThreshold() {
        return subagentProperties == null ? 20 : subagentProperties.getProbeFileThreshold();
    }

    private long probeBytesThreshold() {
        return subagentProperties == null ? 102400L : subagentProperties.getProbeBytesThreshold();
    }

    private String scaleNote(int files, long bytes) {
        int ft = probeFileThreshold();
        long bt = probeBytesThreshold();
        if (files > ft || bytes > bt) {
            return "规模提示：文件数=" + files + "、总字节=" + bytes
                    + "，已超过探测阈值（文件>" + ft + " 或 字节>" + bt + "），开放式探索应派 explore。";
        }
        return "规模提示：文件数=" + files + "、总字节=" + bytes
                + "，未超过探测阈值（文件≤" + ft + " 且 字节≤" + bt + "），开放式探索可自己读。";
    }

    /** @return long[0]=fileCount, long[1]=totalBytes */
    private long[] recursiveFileStats(Path dir) {
        long count = 0;
        long bytes = 0L;
        try (Stream<Path> s = Files.walk(dir)) {
            Iterator<Path> it = s.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) {
                Path f = it.next();
                if (underIgnoredDir(f, dir)) continue;
                count++;
                try { bytes += Files.size(f); } catch (IOException ignored) {}
                if (count >= MAX_SCAN_FILES) break;
            }
        } catch (IOException ignored) {}
        return new long[]{count, bytes};
    }

    /** 文件路径中是否含有被忽略的目录（相对 base 判断，保证显式进入某目录时仍可搜索） */
    private boolean underIgnoredDir(Path file, Path base) {
        Path rel;
        try {
            rel = base.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path seg : rel) {
            if (IGNORED_DIRS.contains(seg.toString())) return true;
        }
        return false;
    }

    /** 相对路径是否命中任一条 glob 规则（同时拿相对路径与文件名各试一次，兼容 `*.java` 这类写法） */
    private boolean matchesAny(List<PathMatcher> matchers, Path rel) {
        Path name = rel.getFileName();
        if (name == null) return false;
        for (PathMatcher m : matchers) {
            if (m.matches(rel) || m.matches(name)) return true;
        }
        return false;
    }

    /** 解析 include 参数（如 "java,xml" 或 "*.java"）为小写后缀集合；空集合表示不过滤 */
    private Set<String> parseExtensions(String include) {
        Set<String> exts = new HashSet<>();
        if (include == null || include.isBlank()) return exts;
        for (String raw : include.split(",")) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.startsWith("*.")) e = e.substring(2);
            while (e.startsWith(".")) e = e.substring(1);
            if (!e.isEmpty()) exts.add(e);
        }
        return exts;
    }

    private boolean matchesExtension(Path file, Set<String> exts) {
        if (exts.isEmpty()) return true;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return exts.contains(name.substring(dot + 1));
    }

    /** 统一显示为 / 分隔的相对路径，避免 Windows 反斜杠在提示词里造成误解 */
    private String toUnix(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** 递归删除目录（含其全部内容） */
    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    /** 递归复制目录 */
    private void copyRecursively(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            s.forEach(p -> {
                Path target = dst.resolve(src.relativize(p).toString());
                try {
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
