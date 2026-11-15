package com.example.jingjingai2.tool;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.sandbox.ReadOnlyRootProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 通用工具输出落盘：MCP 超限结果和内置命令超限输出共用同一个目录与清理规则。
 *
 * <p>职责只有“把一段完整文本写成会话目录下的快照文件，并告诉调用方真实路径”。
 * 预览怎么拼、给模型看什么说明，由各工具自己决定；这里不做 MCP 业务判断，也不解析结果内容。
 *
 * <p>目录：{@code <dir>/<会话目录>/<工具名>-<时间戳>-<随机>.txt}。会话目录名不直接用
 * conversationId —— 合法 id 才原样用，否则用 SHA-256 前 32 位，绝不出现路径分隔符、冒号、{@code ..}。
 *
 * <p>清理沿用原有规则：启动时按 TTL 清理；单会话超过字节上限删最旧的文件，刚写入的不删。
 * 统计仍在 {@code McpOutputStore} 里（MCP 侧需要 server/tool 维度），本类不重复维护。
 *
 * <p>已退休成「可恢复引用」的快照会被 {@link #retain} 打上一个**有限租约**：租约内 TTL 和
 * 容量清理都跳过它，避免引用刚发给模型、快照就被同会话的下一次落盘清掉。租约到期自动失效，
 * 之后按原 TTL/容量策略清理。租约只活在进程内，重启即丢——重启后不保证旧引用可读，
 * 读取端（{@code FileTool}）会重新校验并明确报错，不会返回伪造正文。
 */
@Component
public class ToolOutputStore implements ReadOnlyRootProvider {

    /** 合法会话目录名：只允许字母数字、下划线、连字符。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /**
     * 本次进程写入、且已经以「可恢复引用」形式发给模型的快照：容量和 TTL 清理都会跳过它。
     *
     * <p>值是**保留租约的到期时刻**，不是永久标记。租约到期后自动失效并在这里被惰性清掉：
     * 既保证活动请求期间引用不会因为容量清理提前失效，也不会让保留状态无限增长。
     * 这是进程内状态，重启即丢——重启后不保证旧引用还能读回，读取端会明确报错。
     */
    private static final java.util.Map<Path, Instant> RETAINED = new java.util.concurrent.ConcurrentHashMap<>();
    /** 一次退休默认保护这么久。远大于单次请求的剩余轮次，又小于快照自己的 TTL。 */
    static final java.time.Duration DEFAULT_RETAIN_LEASE = java.time.Duration.ofHours(1);
    /** 租约表超过这个规模就顺手清一次过期项，避免长时间运行只增不减。 */
    private static final int PRUNE_THRESHOLD = 256;

    private final McpOutputProperties props;
    private final AtomicLong fileSeq = new AtomicLong();

    public ToolOutputStore(McpOutputProperties props) {
        this.props = props == null ? new McpOutputProperties() : props;
    }

    /** 本次进程写入并要求在请求内存活的快照。租约已过期返回 false。 */
    public static boolean isRetained(Path file) {
        if (file == null) return false;
        Path key = file.toAbsolutePath().normalize();
        Instant until = RETAINED.get(key);
        if (until == null) return false;
        if (until.isAfter(Instant.now())) return true;
        RETAINED.remove(key, until);
        return false;
    }

    /** 一次落盘的结果。{@code totalChars} 是完整文本的 UTF-16 字符数。 */
    public record Stored(Path path, String fileName, int totalChars, String sha256) {}

    public Path root() {
        return Paths.get(props.getDir()).toAbsolutePath().normalize();
    }

    /** 会话目录名。合法 id 原样，否则哈希，保证不含路径分隔符/冒号/{@code ..}。 */
    public static String directoryName(String conversationId) {
        if (conversationId != null && SAFE_ID.matcher(conversationId).matches()) return conversationId;
        return "h-" + sha256(conversationId == null ? "" : conversationId).substring(0, 32);
    }

    public Path sessionRoot(String conversationId) {
        return root().resolve(directoryName(conversationId));
    }

    /** 当前调用的会话 id。子代理线程上挂的是父会话链路；拿不到时不落盘。 */
    public static String currentConversation() {
        ToolCallContext ctx = ToolCallContext.current();
        return ctx == null ? null : ctx.conversationId;
    }

    /** 只读例外只覆盖「当前会话」的目录；没有任何上下文时不给例外。 */
    @Override
    public List<Path> readOnlyRoots() {
        if (!props.isSpillEnabled()) return List.of();
        String conversationId = currentConversation();
        if (conversationId == null || conversationId.isBlank()) return List.of();
        Path dir = sessionRoot(conversationId);
        return Files.isDirectory(dir) ? List.of(dir) : List.of();
    }

    /**
     * 把完整文本写成快照文件。
     *
     * @return null 表示当前配置关闭、没有会话上下文或路径为空（调用方退回旧截断）
     * @throws IOException 写盘失败（调用方决定降级方式）
     */
    public Stored store(String conversationId, String tool, String fullText) throws IOException {
        if (!props.isSpillEnabled()) return null;
        if (conversationId == null || conversationId.isBlank()) return null;
        String text = fullText == null ? "" : fullText;
        Path dir = sessionRoot(conversationId);
        Files.createDirectories(dir);
        String fileName = uniqueName(tool);
        Path file = dir.resolve(fileName);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        enforcePerConversationLimit(conversationId, file);
        return new Stored(file, fileName, text.length(), sha256(text));
    }

    /** 用当前线程的会话上下文落盘。 */
    public Stored store(String tool, String fullText) throws IOException {
        return store(currentConversation(), tool, fullText);
    }

    private String uniqueName(String tool) {
        String base = com.example.jingjingai2.mcp.McpNames.sanitize(
                tool == null || tool.isBlank() ? "tool" : tool);
        if (base.length() > 40) base = base.substring(0, 40);
        long seq = fileSeq.incrementAndGet();
        String rand = Long.toHexString(Double.doubleToLongBits(Math.random()) ^ seq);
        if (rand.length() > 6) rand = rand.substring(rand.length() - 6);
        return base + "-" + System.currentTimeMillis() + "-" + rand + ".txt";
    }

    /** 超过上限就删该会话最旧的文件，刚写的这个(file)不删。 */
    private void enforcePerConversationLimit(String conversationId, Path keep) {
        long max = props.getMaxBytesPerConversation();
        if (max <= 0) return;
        List<Path> files = listFiles(conversationId);
        long total = 0;
        for (Path f : files) total += size(f);
        if (total <= max) return;
        files.sort(Comparator.comparingLong(ToolOutputStore::lastModified));
        for (Path f : files) {
            if (total <= max) break;
            if (f.equals(keep) || isRetained(f)) continue;
            long len = size(f);
            if (deleteInside(f)) total -= len;
        }
    }

    private List<Path> listFiles(String conversationId) {
        Path dir = sessionRoot(conversationId);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException ignored) {
            // 读不到就当空的
        }
        return out;
    }

    /** 会话删除时删对应目录。路径必须落在 dir 之内。 */
    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Path dir = sessionRoot(conversationId);
        if (!insideRoot(dir)) return;
        // 会话没了，保留租约也一起丢掉，避免保留表跟着进程一直长。
        RETAINED.keySet().removeIf(p -> p.startsWith(dir));
        deleteTree(dir);
    }

    /** 删掉超过 TTL 的文件和随之变空的会话目录。返回删掉的文件数。 */
    public int cleanupExpired() {
        // 先丢掉过期的保留租约：租约到期后这些快照必须重新受 TTL/容量策略约束。
        pruneExpired();
        int ttl = props.getTtlHours();
        if (ttl <= 0) return 0;
        Path root = root();
        if (!Files.isDirectory(root)) return 0;
        Instant cutoff = Instant.now().minus(ttl, ChronoUnit.HOURS);
        int removed = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                if (!insideRoot(dir)) continue;
                removed += deleteOldFiles(dir, cutoff);
                if (isEmpty(dir) && insideRoot(dir) && !dir.equals(root)) {
                    try { Files.delete(dir); } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {
            // 根目录读不到就算了
        }
        return removed;
    }

    private int deleteOldFiles(Path dir, Instant cutoff) {
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (!Files.isRegularFile(f)) continue;
                Instant mtime = modified(f);
                if (mtime != null && mtime.isBefore(cutoff) && insideRoot(f) && !isRetained(f)) {
                    if (deleteInside(f)) removed++;
                }
            }
        } catch (IOException ignored) {
            // 单个会话目录读不到不影响其他会话
        }
        return removed;
    }

    /** 删除前再确认一次落在 dir 之内，防止符号链接指向外面。 */
    private boolean deleteInside(Path file) {
        if (!insideRoot(file)) return false;
        try {
            Files.delete(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean insideRoot(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Path root = root();
        if (abs.equals(root)) return false;
        if (!abs.startsWith(root)) return false;
        try {
            Path real = abs.toRealPath();
            Path realRoot = Files.exists(root) ? root.toRealPath() : root;
            return real.startsWith(realRoot) && !real.equals(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            if (Files.exists(dir)) deleteInside(dir);
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) deleteTree(p);
                else deleteInside(p);
            }
        } catch (IOException ignored) {
            return;
        }
        try { Files.deleteIfExists(dir); } catch (IOException ignored) {}
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isEmpty(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 标记一份快照已被退休成可恢复引用，清理不能在租约内删它。
     * 只保护一段时间（{@link #DEFAULT_RETAIN_LEASE}），不是永久保留。
     */
    public static void retain(Path file) {
        retain(file, DEFAULT_RETAIN_LEASE);
    }

    /** 带租约的保留。租约为空或非正数时视为「不保留」。 */
    public static void retain(Path file, java.time.Duration lease) {
        if (file == null || lease == null || lease.isZero() || lease.isNegative()) return;
        Path key = file.toAbsolutePath().normalize();
        RETAINED.put(key, Instant.now().plus(lease));
        if (RETAINED.size() > PRUNE_THRESHOLD) pruneExpired();
    }

    /** 立刻释放保留（正常结束、测试或需要按原 TTL 清理时用）。 */
    public static void release(Path file) {
        if (file == null) return;
        RETAINED.remove(file.toAbsolutePath().normalize());
    }

    /** 丢掉所有已过期的保留项。清理入口会先调用它。 */
    public static void pruneExpired() {
        Instant now = Instant.now();
        RETAINED.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }

    /** 当前仍在保护期的快照数。只用于观测和测试。 */
    public static int retainedCount() {
        pruneExpired();
        return RETAINED.size();
    }

    private static String sha256(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode() & 0x7fffffff) + "0000000000000000000000000000";
        }
    }
}
