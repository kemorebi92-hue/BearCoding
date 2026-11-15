package com.example.jingjingai2.tool;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.permission.ToolCallContext;
import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase M3-2：工具快照的生命周期。
 *
 * <p>退休后模型手里只剩一条引用，快照被清掉就等于引用失效。这里验证三件事：
 * <ol>
 *   <li>已经以引用形式发出去的快照，在**有限租约**内不会被 TTL/容量清理提前删掉；</li>
 *   <li>租约到期、正常结束、进程重启（进程内保留状态丢失）后，仍按原 TTL/容量策略清理，
 *       不会无限泄漏；</li>
 *   <li>清理之后恢复必须明确失败，绝不返回一段伪造正文。</li>
 * </ol>
 *
 * <p>不用真实时间等待 TTL：直接改文件 mtime / 用毫秒级租约，确定性触发边界。
 */
class ToolSnapshotLifecycleTest {

    private static final String CONV = "conv-life";
    private static final String BODY = "编译错误：缺少符号 Invoice\n位置：src/Bill.java:18\n";

    @TempDir
    Path tmp;

    private McpOutputProperties props;
    private ToolOutputStore store;
    private ToolCallContext.Scope scope;

    @BeforeEach
    void setUp() {
        props = new McpOutputProperties();
        props.setDir(tmp.resolve("tool-output").toString());
        props.setTtlHours(1);
        // 先关掉容量上限，测试里按需打开，避免落盘顺序影响结果。
        props.setMaxBytesPerConversation(0);
        store = new ToolOutputStore(props);
        scope = ToolCallContext.open(new ToolCallContext(1L, CONV, false, false, null, () -> false));
    }

    @AfterEach
    void tearDown() {
        if (scope != null) scope.close();
    }

    @Test
    @DisplayName("活动请求里已退休的快照：容量清理不会提前删掉它")
    void retainedSnapshotSurvivesCapacityCleanup() throws Exception {
        Path retained = store("a", filler(700));
        ToolOutputStore.retain(retained);
        Path b = store("b", filler(700));
        Path c = store("c", filler(700));
        age(retained, Duration.ofMinutes(30));
        age(b, Duration.ofMinutes(20));
        age(c, Duration.ofMinutes(10));

        props.setMaxBytesPerConversation(1500);
        Path d = store("d", filler(700));   // 触发容量清理：最旧的先删，保留项跳过

        assertTrue(Files.exists(retained), "已退休的快照在租约内不能被容量清理删掉");
        assertFalse(Files.exists(b), "更旧、且没有被引用的快照应被删掉");
        assertTrue(Files.exists(d), "刚写入的文件永远不删");
    }

    @Test
    @DisplayName("租约到期后不再保护：TTL 清理照常删除")
    void expiredLeaseStopsProtecting() throws Exception {
        Path file = store("lease", BODY);
        ToolOutputStore.retain(file, Duration.ofMillis(30));
        assertTrue(ToolOutputStore.isRetained(file), "租约内受保护");

        age(file, Duration.ofHours(3));
        store.cleanupExpired();
        assertTrue(Files.exists(file), "租约未到期时 TTL 清理跳过它");

        Thread.sleep(60);
        assertFalse(ToolOutputStore.isRetained(file), "租约到期后自动失效");
        assertTrue(store.cleanupExpired() >= 1, "租约到期后必须按 TTL 清掉");
        assertFalse(Files.exists(file), "过期租约不能变成永久保留");
    }

    @Test
    @DisplayName("任务正常结束释放保留：随后按 TTL 清理，不会泄漏")
    void releaseAtRequestEndAllowsCleanup() throws Exception {
        Path file = store("released", BODY);
        ToolOutputStore.retain(file);
        age(file, Duration.ofHours(3));
        store.cleanupExpired();
        assertTrue(Files.exists(file));

        ToolOutputStore.release(file);      // 正常结束
        assertFalse(ToolOutputStore.isRetained(file));
        assertTrue(store.cleanupExpired() >= 1);
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("进程重启（保留状态丢失）后不虚假保证可恢复：读取明确失败")
    void lostRetainStateDoesNotFakeRecoverability() throws Exception {
        Path file = store("restart", BODY);
        String marker = markerOf(file, BODY);
        assertTrue(new FileTool(sandbox()).readFile(marker, null, null).contains("缺少符号 Invoice"),
                "重启前读得回来");

        ToolOutputStore.release(file);      // 模拟重启：进程内保留状态全丢
        age(file, Duration.ofHours(3));
        store.cleanupExpired();
        assertFalse(Files.exists(file), "没有租约保护的快照会被 TTL 清理");

        String read = new FileTool(sandbox()).readFile(marker, null, null);
        assertTrue(read.startsWith("读取失败"), read);
        assertTrue(read.contains("文件不存在"), read);
        assertFalse(read.contains("缺少符号 Invoice"), "失效引用不得返回任何正文");
    }

    @Test
    @DisplayName("检查后被删除（TOCTOU）：恢复明确失败，不返回旧正文")
    void deleteAfterCheckFailsCleanly() throws Exception {
        Path file = store("race", BODY);
        String marker = markerOf(file, BODY);
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(marker);
        assertNotNull(ref);
        assertEquals(ToolSnapshotIntegrity.Status.OK, new FileTool(sandbox()).snapshotStatus(ref),
                "退休检查这一刻是可读的");

        Files.delete(file);                 // 检查之后、模型读取之前被清理

        assertEquals(ToolSnapshotIntegrity.Status.MISSING, new FileTool(sandbox()).snapshotStatus(ref),
                "下一次检查必须发现快照已经不在");
        String read = new FileTool(sandbox()).readFile(marker, null, null);
        assertTrue(read.startsWith("读取失败"), read);
        assertFalse(read.contains("缺少符号 Invoice"), "竞态失败时不能返回伪造正文");
    }

    @Test
    @DisplayName("会话删除会连带清掉保留状态，保留表不跟着进程一直长")
    void deleteConversationDropsLeases() throws Exception {
        Path file = store("conv-del", BODY);
        ToolOutputStore.retain(file);
        assertTrue(ToolOutputStore.isRetained(file));

        store.deleteConversation(CONV);
        assertFalse(Files.exists(file));
        assertFalse(ToolOutputStore.isRetained(file), "会话没了，保留项也要丢掉");
    }

    @Test
    @DisplayName("大量过期租约会被惰性清掉，保留表有界")
    void expiredLeasesArePrunedNotAccumulated() throws Exception {
        int before = ToolOutputStore.retainedCount();
        for (int i = 0; i < 300; i++) {
            ToolOutputStore.retain(tmp.resolve("lease-" + i + ".txt"), Duration.ofMillis(1));
        }
        Thread.sleep(30);
        ToolOutputStore.pruneExpired();
        assertTrue(ToolOutputStore.retainedCount() <= before + 1,
                "过期租约必须被清掉，不能只增不减");
    }

    @Test
    @DisplayName("不同会话各用自己的快照：清理一个会话不影响另一个")
    void retentionIsPerSnapshotNotPerConversation() throws Exception {
        Path mine = store("mine", BODY);
        ToolOutputStore.retain(mine);

        Path otherDir = store.sessionRoot("conv-other");
        Files.createDirectories(otherDir);
        Files.writeString(otherDir.resolve("other.txt"), "别人的快照", StandardCharsets.UTF_8);
        store.deleteConversation("conv-other");

        assertTrue(Files.exists(mine), "另一个会话被清理不影响本会话已退休的快照");
        assertTrue(ToolOutputStore.isRetained(mine));
    }

    // ===================== helpers =====================

    /** 在当前会话目录写一份快照。 */
    private Path store(String name, String body) throws Exception {
        ToolOutputStore.Stored stored = store.store(name, body);
        assertNotNull(stored, "落盘必须成功");
        return stored.path();
    }

    private static String filler(int size) {
        return "x".repeat(size);
    }

    private PathSandbox sandbox() {
        return new PathSandbox(tmp.toString(), List.of(store));
    }

    private static String markerOf(Path file, String body) throws Exception {
        return ToolArtifactRef.marker(file.toString(), body.length(), "executeCommand", sha256(body));
    }

    private static void age(Path file, Duration duration) throws Exception {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(duration)));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
