package com.example.jingjingai2.context;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolOutputStore;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2/M3 复核：快照保留租约与容量上限的真实关系，以及投影里重复读取快照的开销量级。
 *
 * <p>两个问题都只记录事实，不新增优化系统：
 * <ol>
 *   <li>退休后快照会被打上 1 小时进程内租约，租约内容量清理跳过它。
 *       所以「单会话字节上限」在租约窗口内<strong>不是硬上限</strong>：它的上界是
 *       「1 小时内被退休的快照数量 × 单条快照大小」，租约到期后照常回落。这里把它测出来。</li>
 *   <li>每次投影都要为每个退休候选重新读一遍快照并算 SHA-256。量级是
 *       O(候选数 × 快照大小)，这里测一个宽松上界，防止出现病态退化。</li>
 * </ol>
 */
class SnapshotLeaseAndProjectionCostTest {

    @TempDir
    Path tmp;

    private static final String CONV = "conv-lease";

    @Test
    @DisplayName("租约让容量上限在窗口内失效，但到期后会重新受约束")
    void leaseDefeatsCapacityWithinWindowButNotForever() throws Exception {
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(tmp.resolve("out").toString());
        props.setMaxBytesPerConversation(300);   // 故意设得很小
        props.setTtlHours(24);
        ToolOutputStore store = new ToolOutputStore(props);

        String body = "线".repeat(100);   // UTF-8 下 300 字节

        // 阶段 1：没有租约时，容量清理把总量压回上限附近。
        try (ToolCallContext.Scope ignored = context()) {
            for (int i = 0; i < 3; i++) store.store("executeCommand", body + "plain" + i);
        }
        long before = totalBytes(store);
        assertTrue(before <= 300 + 400, "没有租约时容量清理会把总量压回上限附近，实际=" + before);

        // 阶段 2：每次落盘后立刻「退休并发出引用」→ 打上租约，容量清理跳过它。
        List<Path> retained = new ArrayList<>();
        try (ToolCallContext.Scope ignored = context()) {
            for (int i = 0; i < 3; i++) {
                ToolOutputStore.Stored stored = store.store("executeCommand", body + "lease" + i);
                assertNotNull(stored);
                ToolOutputStore.retain(stored.path());
                retained.add(stored.path());
            }
            for (int i = 0; i < 2; i++) store.store("executeCommand", body + "extra" + i);
        }
        long duringLease = totalBytes(store);
        assertTrue(duringLease > 300, "租约窗口内容量上限被跳过，总量会超过上限，实际=" + duringLease);
        assertTrue(ToolOutputStore.retainedCount() >= 3, "租约表里应能看到被保护的快照");
        for (Path file : retained) assertTrue(Files.exists(file), "租约内的快照不能被容量清理删掉");

        // 阶段 3：租约到期（这里用 release 模拟到期后的惰性清理）→ 清理重新生效。
        for (Path file : retained) ToolOutputStore.release(file);
        try (ToolCallContext.Scope ignored = context()) {
            store.store("executeCommand", body + "after-lease");
        }
        long after = totalBytes(store);
        assertTrue(after <= duringLease, "租约到期后容量清理重新生效，总量不应继续增长，实际=" + after);
        // 保留表是进程级静态状态，同一 JVM 里别的用例也会留下租约，所以只断言「本次这几个」已经释放。
        for (Path file : retained) assertFalse(ToolOutputStore.isRetained(file), "释放后不应再被保护：" + file);

        store.deleteConversation(CONV);
        assertFalse(Files.exists(store.sessionRoot(CONV)), "删除会话应连快照目录一起清掉");
    }

    @Test
    @DisplayName("投影里重复读取快照算哈希：8 个候选 × 14KB 的重复投影在毫秒级，不是瓶颈")
    void projectionCostWithManySnapshotCandidatesIsSmall() throws Exception {
        Path root = tmp.resolve("ws");
        Files.createDirectories(root);
        PathSandbox sandbox = new PathSandbox(root.toString());
        FileTool fileTool = new FileTool(sandbox);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new UserMessage("当前问题"));
        for (int i = 1; i <= 8; i++) {
            StringBuilder sb = new StringBuilder();
            for (int line = 1; line <= 140; line++) sb.append("FILE-").append(i).append(" LINE-").append(line)
                    .append('-').append("内".repeat(80)).append('\n');
            String body = sb.toString();
            Path snapshot = root.resolve("snap-" + i + ".txt");
            Files.writeString(snapshot, body, StandardCharsets.UTF_8);
            String marker = ToolArtifactRef.marker(snapshot.toString(), body.length(), "executeCommand",
                    sha256(body));
            messages.add(new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall("c" + i, "function", "executeCommand",
                            "{\"command\":\"type f" + i + ".txt\"}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("c" + i, "executeCommand", body + "\n" + marker))));
        }

        ContextSourceLedger ledger = new ContextSourceLedger(CONV);
        int round = 1;
        for (int i = 0; i < 8; i++) {
            ledger.absorb(new ArrayList<>(messages.subList(0, 2 + (i + 1) * 2)), round++, null);
        }

        // 预热一次，避免把类加载算进测量。
        ContextProjectionPolicy.project(ledger, messages, null, 20, 0, 1, 0, false, true,
                fileTool::snapshotStatus);

        int repeats = 20;
        long start = System.nanoTime();
        ContextProjectionPolicy.Result last = null;
        for (int i = 0; i < repeats; i++) {
            last = ContextProjectionPolicy.project(ledger, messages, null, 20, 0, 1, 0, false, true,
                    fileTool::snapshotStatus);
        }
        long averageMs = (System.nanoTime() - start) / 1_000_000 / repeats;

        assertNotNull(last);
        assertEquals(8, last.decisions().stream()
                .filter(d -> d.kind() == ContextProjectionPolicy.DecisionKind.RETIRE).count(),
                "8 个候选都应被判定为可退休（否则测的不是最坏情况）");
        System.out.println("[M4-Perf] 8 个快照候选 × 14KB 的重复投影平均 " + averageMs + " ms/次");
        assertTrue(averageMs < 200, "重复读取 + 哈希不应出现病态退化，实际 " + averageMs + " ms/次");
        for (int i = 1; i <= 8; i++) ToolOutputStore.release(root.resolve("snap-" + i + ".txt"));
    }

    // ===================== helpers =====================

    private static ToolCallContext.Scope context() {
        return ToolCallContext.open(new ToolCallContext(1L, CONV, false, false, null, () -> false));
    }

    private long totalBytes(ToolOutputStore store) throws Exception {
        Path dir = store.sessionRoot(CONV);
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception e) {
                    return 0;
                }
            }).sum();
        }
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
