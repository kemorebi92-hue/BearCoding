package com.example.jingjingai2.sandbox;

import com.example.jingjingai2.context.ConversationContext;
import com.example.jingjingai2.service.ConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 文件路径沙箱：把 Agent 的文件读写限制在工作区根目录内，防止"路径逃逸"。
 *
 * <p>为什么需要：模型的输出不可信。一句"读取 C:\Windows\System32\..." 就能越权读任意文件。
 *
 * <p>实现要点：对入参路径做 {@code toAbsolutePath().normalize()} 后，用 {@code startsWith(root)}
 * 判定是否在工作区内，挡住 {@code ..} 穿越和外部绝对路径。
 *
 * <p>只读例外（{@link #resolveForRead}）：工作区之外多允许若干「只读根」，
 * 目前只有 MCP 超限结果落盘的当前会话目录。例外有两道额外约束：
 *  - 只对真实路径（{@code toRealPath}，会解析符号链接/目录联接）判前缀，不做字符串比较；
 *  - 显式拒绝 UNC（{@code \\server\...}）和「冒号不在盘符位置」的路径（NTFS 备用数据流）。
 * 写、改、删、命令工具仍走 {@link #resolveWithin}，拿不到这个例外。
 *
 * <p>局限（诚实说明）：这是"路径级"沙箱，不是"进程级"沙箱。
 */
@Component
public class PathSandbox {

    /** 兜底根目录：没有请求级工作区上下文时使用（配置项 app.sandbox.root，默认当前工作目录） */
    private final Path fallbackRoot;
    private final List<ReadOnlyRootProvider> readProviders;
    /** 可选：读当前会话权限模式。为空时始终按非 FULL_ACCESS 处理。 */
    private ConversationService conversations;

    public PathSandbox(@Value("${app.sandbox.root:.}") String root) {
        this(root, List.of());
    }

    @Autowired
    public PathSandbox(@Value("${app.sandbox.root:.}") String root,
                       List<ReadOnlyRootProvider> readProviders) {
        this.fallbackRoot = Paths.get(root).toAbsolutePath().normalize();
        this.readProviders = readProviders == null ? List.of() : List.copyOf(readProviders);
    }

    /** 可选注入：没有会话服务时，FULL_ACCESS 不生效，路径仍限制在工作区内。 */
    @Autowired(required = false)
    public void setConversationService(ConversationService conversations) {
        this.conversations = conversations;
    }

    /**
     * 当前生效的工作区根：
     *  - 优先取请求级上下文 {@link WorkspaceContext}（每个会话一个独立工作区）；
     *  - 没有上下文时回退到全局配置根（兼容非会话场景与单元测试）。
     */
    public Path root() {
        Path ctx = WorkspaceContext.get();
        return ctx != null ? ctx : fallbackRoot;
    }

    /**
     * 校验入参路径并返回归一化后的绝对路径。
     *
     * <p>写、改、删、移、复制都用它。FULL_ACCESS 允许工作区外普通路径，但只读根
     * （MCP 落盘目录）无论哪种模式都不允许写入、删除、移动，包括它的祖先目录。
     *
     * @throws SandboxViolationException 路径为空、越界或触碰只读根时抛出
     */
    public Path resolveWithin(String input) {
        Path root = root();
        Path abs = normalize(input, root);
        // 只读根保护优先于「工作区内直接放行」：两者重叠时也不能写只读根。
        if (touchesReadOnlyRoot(abs)) {
            throw new SandboxViolationException(
                    "路径越界已拦截: " + input + "（只读目录，不允许写入或删除）");
        }
        if (abs.startsWith(root)) return abs;
        if (fullAccessNow()) return abs;
        throw new SandboxViolationException(
                "路径越界已拦截: " + input + "（只允许访问工作区 " + root + "）");
    }

    /**
     * 读文件 / 搜索工具用：工作区之外，额外允许只读根之下的文件。写类工具不要用这个方法。
     * FULL_ACCESS 允许读取工作区外普通路径；空路径、UNC、非法冒号仍然拒绝。
     *
     * @throws SandboxViolationException 越界、UNC、备用数据流、符号链接逃逸时抛出
     */
    public Path resolveForRead(String input) {
        Path root = root();
        Path abs = normalize(input, root);
        if (abs.startsWith(root)) return abs;
        for (Path allowed : readOnlyRoots()) {
            if (insideRealPath(allowed, input, abs)) return abs;
        }
        if (fullAccessNow()) return abs;
        throw new SandboxViolationException(
                "路径越界已拦截: " + input + "（只允许访问工作区 " + root + " 或本次会话的 MCP 落盘目录）");
    }

    /** 当前会话是否明确为 FULL_ACCESS。终端工具据此决定命令路径参数是否放开。 */
    public boolean allowsOutsideWorkspace() {
        return fullAccessNow();
    }

    private Path normalize(String input, Path root) {
        if (input == null || input.isBlank()) {
            throw new SandboxViolationException("路径为空，请提供有效路径");
        }
        if (isUnc(input)) {
            throw new SandboxViolationException("非法路径（不支持 UNC）: " + input);
        }
        if (hasIllegalColon(input)) {
            throw new SandboxViolationException("非法路径（含备用数据流或非法冒号）: " + input);
        }
        Path p;
        try {
            p = Paths.get(input);
        } catch (RuntimeException e) {
            throw new SandboxViolationException("非法路径: " + input);
        }
        return (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
    }

    /** UNC：以 {@code \\} 或 {@code //} 开头。公开给快照校验复用同一套路径规则。 */
    public static boolean isUnc(String input) {
        return input.startsWith("\\\\") || input.startsWith("//");
    }

    /**
     * 冒号只允许出现在盘符位置（索引 1）且整串只有一个。其余（{@code a:b}、{@code file.txt:stream}）拒绝。
     * 公开给快照校验复用同一套路径规则，避免另写一套更宽松的判断。
     */
    public static boolean hasIllegalColon(String input) {
        int first = input.indexOf(':');
        if (first < 0) return false;
        if (input.indexOf(':', first + 1) >= 0) return true;
        return first != 1 || !isDriveLetter(input.charAt(0));
    }

    private static boolean isDriveLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private List<Path> readOnlyRoots() {
        List<Path> out = new ArrayList<>();
        for (ReadOnlyRootProvider p : readProviders) {
            if (p == null) continue;
            List<Path> roots = p.readOnlyRoots();
            if (roots != null) out.addAll(roots);
        }
        return out;
    }

    /**
     * 当前会话是否明确为 FULL_ACCESS。用 ConversationContext 取身份，不缓存、不跨会话共享。
     * 缺身份、没有会话服务、查询异常、模式非法，一律返回 false（保持原工作区限制）。
     */
    private boolean fullAccessNow() {
        if (conversations == null) return false;
        Long userId = ConversationContext.userId();
        String conversationId = ConversationContext.conversationId();
        if (userId == null || conversationId == null || conversationId.isBlank()) return false;
        try {
            return ConversationService.MODE_FULL_ACCESS.equals(
                    conversations.permissionMode(userId, conversationId));
        } catch (RuntimeException e) {
            System.err.println("[Sandbox] 读取会话权限失败，按非 FULL_ACCESS 处理: " + e.getMessage());
            return false;
        }
    }

    /**
     * 写操作是否会触碰只读根：目标落在只读根内，或目标是只读根的祖先（删除/移动祖先会连带破坏它）。
     * 只读根确定不存在时跳过；存在但真实路径解析失败时用规范化路径兜底，不直接放行。
     * 目标路径解析失败时同样用规范化路径兜底，命中即拒绝（fail-closed）。
     */
    private boolean touchesReadOnlyRoot(Path abs) {
        for (Path allowed : readOnlyRoots()) {
            if (allowed == null || !allowed.isAbsolute()) continue;
            Path normalizedRoot = allowed.toAbsolutePath().normalize();
            // 只读根确定不存在：没有内容可保护，跳过。访问错误与不存在要分开，不能静默放行。
            try {
                Files.readAttributes(allowed, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                continue;
            } catch (IOException e) {
                System.err.println("[Sandbox] 只读根存在性无法确认，按存在处理: "
                        + allowed + " (" + e.getMessage() + ")");
            }

            Path realRoot;
            try {
                realRoot = allowed.toRealPath();
            } catch (IOException e) {
                // 解析失败不能跳过保护：退回规范化路径继续判断
                System.err.println("[Sandbox] 只读根真实路径解析失败，改用规范化路径判断: "
                        + allowed + " (" + e.getMessage() + ")");
                realRoot = normalizedRoot;
            }

            Path probe = realPathOfNearestExisting(abs);
            if (probe == null) {
                // 目标路径解析失败：用规范化路径做双向前缀兜底，命中即拒绝
                System.err.println("[Sandbox] 目标路径真实路径解析失败，改用规范化路径判断: " + abs);
                if (abs.startsWith(normalizedRoot) || normalizedRoot.startsWith(abs)) return true;
                continue;
            }
            if (probe.startsWith(realRoot)) return true;
            if (realRoot.startsWith(probe)) return true;
        }
        return false;
    }

    /** 目标不存在时，向上找到最近存在的祖先做 realpath，再把剩余层级拼回去。 */
    private static Path realPathOfNearestExisting(Path path) {
        Path cur = path;
        Deque<Path> tail = new ArrayDeque<>();
        while (cur != null) {
            if (Files.exists(cur)) {
                try {
                    Path real = cur.toRealPath();
                    Iterator<Path> it = tail.descendingIterator();
                    while (it.hasNext()) real = real.resolve(it.next());
                    return real.normalize();
                } catch (IOException e) {
                    return null;
                }
            }
            Path name = cur.getFileName();
            if (name == null) break;
            tail.addLast(name);
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * 真实路径前缀判断。候选必须存在（读场景成立），且严格位于只读根之内。
     * {@code toRealPath} 会解析符号链接与目录联接，指向外面的会被前缀判断挡下。
     */
    static boolean insideRealPath(Path allowedRoot, String input, Path candidate) {
        if (allowedRoot == null) return false;
        if (isUnc(input) || hasIllegalColon(input)) return false;
        if (!allowedRoot.isAbsolute()) return false;
        try {
            if (!Files.exists(allowedRoot)) return false;
            Path realRoot = allowedRoot.toRealPath();
            Path real = candidate.toRealPath();
            if (real.equals(realRoot)) return false;   // 只读根目录本身不开放
            if (Files.isDirectory(real)) return false; // 只读例外只给文件
            return real.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }
}
