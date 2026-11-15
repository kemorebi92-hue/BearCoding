package com.example.jingjingai2.mcp;

import com.example.jingjingai2.sandbox.ReadOnlyRootProvider;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 超限结果落盘：写文件、给模型的预览与说明、按会话统计、清理。
 *
 * <p>真正的文件写入与清理已抽到 {@link ToolOutputStore}（内置命令输出共用同一目录与规则）；
 * 本类保留 MCP 侧的业务：预览文案、server/tool 统计、SSE 事件需要的元数据。
 *
 * <p>目录：{@code <dir>/<会话目录>/<工具名>-<时间戳>-<随机>.txt}。会话目录名不直接用
 * conversationId —— 合法 id 才原样用，否则用 SHA-256 前 32 位，绝不出现路径分隔符、冒号、{@code ..}。
 *
 * <p>统计放在内存里，重启丢，可接受。
 */
@Component
public class McpOutputStore implements ReadOnlyRootProvider {

    private final McpOutputProperties props;
    private final ToolOutputStore outputStore;
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    public McpOutputStore(McpOutputProperties props) {
        this(props, new ToolOutputStore(props));
    }

    @Autowired
    public McpOutputStore(McpOutputProperties props, ToolOutputStore outputStore) {
        this.props = props == null ? new McpOutputProperties() : props;
        this.outputStore = outputStore == null ? new ToolOutputStore(this.props) : outputStore;
    }

    /** 启动时清理过期文件与空目录。关掉时不碰磁盘。 */
    @PostConstruct
    public void cleanupOnStartup() {
        if (!props.isSpillEnabled()) return;
        try {
            int removed = cleanupExpired();
            if (removed > 0) System.out.println("[MCP] 启动清理落盘文件 " + removed + " 个");
        } catch (Exception e) {
            System.err.println("[MCP] 启动清理落盘文件失败: " + e.getMessage());
        }
    }

    public Path root() {
        return outputStore.root();
    }

    /** 会话目录名。合法 id 原样，否则哈希，保证不含路径分隔符/冒号/{@code ..}。 */
    public static String directoryName(String conversationId) {
        return ToolOutputStore.directoryName(conversationId);
    }

    public Path sessionRoot(String conversationId) {
        return outputStore.sessionRoot(conversationId);
    }

    /** 只读例外只覆盖「当前会话」的目录；没有任何上下文时不给例外。 */
    @Override
    public List<Path> readOnlyRoots() {
        return outputStore.readOnlyRoots();
    }

    /**
     * 超限就落盘，否则原样返回。{@code inlineLimit ≤ 0} 或结果没超 → 什么都不做。
     * 写盘失败退回旧的截断，不抛异常。
     */
    public McpSpill handle(String server, String tool, String fullText, int inlineLimit) {
        String text = fullText == null ? "" : fullText;
        if (!props.isSpillEnabled() || inlineLimit <= 0 || text.length() <= inlineLimit) {
            return McpSpill.notSpilled(McpContentMapper.truncate(text, inlineLimit));
        }
        String conversationId = ToolOutputStore.currentConversation();
        if (conversationId == null || conversationId.isBlank()) {
            return McpSpill.notSpilled(McpContentMapper.truncate(text, inlineLimit));
        }
        int previewChars = props.effectivePreviewChars(inlineLimit);
        try {
            ToolOutputStore.Stored stored = outputStore.store(conversationId, tool, text);
            if (stored == null) {
                return McpSpill.notSpilled(McpContentMapper.truncate(text, inlineLimit));
            }
            String preview = previewWithNotice(text, previewChars, inlineLimit,
                    stored.path().toString(), tool, stored.sha256());
            record(conversationId, server, tool, stored.path(), text);
            return McpSpill.spilled(
                    preview, server, tool, stored.fileName(), stored.path().toString(),
                    text.length(), previewChars);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("[MCP] 结果落盘失败，退回截断: " + reason);
            String fallback = McpContentMapper.truncate(text, inlineLimit)
                    + "\n（完整内容保存失败：" + reason + "）";
            return McpSpill.failed(fallback, server, tool, reason);
        }
    }

    /**
     * 预览 + 固定说明。预览按 70% 头、30% 尾切开，整体不超过 inline-limit。
     * 中间段在落盘文件里，用 readFile 的 offset/limit 按行读。
     */
    static String previewWithNotice(String fullText, int previewChars, int inlineLimit, String path) {
        return previewWithNotice(fullText, previewChars, inlineLimit, path, null, null);
    }

    static String previewWithNotice(String fullText, int previewChars, int inlineLimit,
                                    String path, String tool) {
        return previewWithNotice(fullText, previewChars, inlineLimit, path, tool, null);
    }

    static String previewWithNotice(String fullText, int previewChars, int inlineLimit,
                                    String path, String tool, String sha256) {
        String text = fullText == null ? "" : fullText;
        int total = text.length();
        int cap = inlineLimit <= 0 ? total : inlineLimit;
        // 系统签名引用必须排在第三方原始内容之后，避免尾部伪造引用覆盖真实引用。
        String marker = tool == null ? "" : ToolArtifactRef.marker(path, total, tool, sha256);
        int reserve = marker.isEmpty() ? 0 : marker.length() + 1;
        int budget = Math.max(0, Math.min(previewChars <= 0 ? cap : previewChars, cap) - reserve);
        int head = 0;
        int tail = 0;
        String note = notice(0, 0, total, path);
        for (int i = 0; i < 4; i++) {
            int room = Math.max(0, Math.min(budget, cap) - note.length());
            if (room >= total) {
                head = total;
                tail = 0;
            } else {
                head = (int) Math.round(room * 0.7);
                tail = room - head;
                if (head + tail > total) {
                    head = total;
                    tail = 0;
                }
            }
            note = notice(head, tail, total, path);
        }
        if (head >= total && marker.isEmpty()) return text;
        StringBuilder out = new StringBuilder(head + tail + note.length() + reserve);
        out.append(text, 0, head);
        out.append(note);
        if (tail > 0) {
            int from = Math.max(head, total - tail);
            out.append(text, from, total);
        }
        if (!marker.isEmpty()) out.append('\n').append(marker);
        return out.toString();
    }

    static String notice(int head, int tail, int total, String path) {
        return "\n【结果过长，完整内容已保存。总字符数 " + total
                + "；已显示前 " + head + " 个字符和末尾 " + tail + " 个字符。"
                + "需要中间的内容时，请用 readFile 按 offset/limit 分段读取"
                + "（offset 是起始行，从 1 开始），或用搜索工具按关键词定位，不要整个文件读入。"
                + "请原样使用下面这个完整路径（绝对路径），不要改写或使用相对路径：\n" + path + "】";
    }

    /** 会话删除时删对应目录。路径必须落在 dir 之内。 */
    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        outputStore.deleteConversation(conversationId);
        stats.keySet().removeIf(k -> k.startsWith(conversationId + "\0"));
    }

    /** 删掉超过 TTL 的文件和随之变空的会话目录。返回删掉的文件数。 */
    public int cleanupExpired() {
        return outputStore.cleanupExpired();
    }

    private void record(String conversationId, String server, String tool, Path file, String text) {
        Stats s = stats.computeIfAbsent(key(conversationId, server), k -> new Stats());
        s.count++;
        s.lastTool = tool == null ? "" : tool;
        s.lastChars = text.length();
        s.lastBytes = size(file);
    }

    /** 该会话 + 该服务的落盘统计。没有记录时是零值。 */
    public Snapshot stats(String conversationId, String server) {
        Stats s = stats.get(key(conversationId, server));
        if (s == null) return new Snapshot(0, "", 0, 0);
        return new Snapshot(s.count, s.lastTool, s.lastChars, s.lastBytes);
    }

    private static String key(String conversationId, String server) {
        return (conversationId == null ? "" : conversationId) + "\0" + (server == null ? "" : server);
    }

    private static long size(Path p) {
        try {
            return java.nio.file.Files.size(p);
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /** 统计快照。{@code lastTool} 为空表示还没有落盘过。 */
    public static final class Snapshot {
        public final int count;
        public final String lastTool;
        public final int lastChars;
        public final long lastBytes;

        Snapshot(int count, String lastTool, int lastChars, long lastBytes) {
            this.count = count;
            this.lastTool = lastTool;
            this.lastChars = lastChars;
            this.lastBytes = lastBytes;
        }
    }

    private static final class Stats {
        int count;
        String lastTool = "";
        int lastChars;
        long lastBytes;
    }
}
