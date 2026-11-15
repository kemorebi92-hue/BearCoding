package com.example.jingjingai2.mcp;

import com.example.jingjingai2.entity.McpServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接安装阶段的代际竞态。
 *
 * <p>用可控的假会话把「卡在某一步」变成确定性等待，不依赖真实网络时序。
 */
class McpInstallRaceTest {

    private static final long OWNER = 7L;
    private static final String SERVER = "svc";

    @Test
    @DisplayName("过期连接卡在 listTools 期间被超越，不得关闭或覆盖新会话")
    void staleInstallMustNotCloseNewSession() throws Exception {
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);
        ScriptedSession a = new ScriptedSession("A", "a_tool", aEntered, aRelease);
        ScriptedSession c = new ScriptedSession("C", "c_tool", null, null);

        ScriptedManager manager = new ScriptedManager(props(), i -> i == 0 ? a : c);

        Thread oldConnect = new Thread(
                () -> manager.addServer(OWNER, SERVER, stdioSpec(), true), "old-connect");
        oldConnect.start();
        assertTrue(aEntered.await(5, TimeUnit.SECONDS), "A 应进入 listTools");

        // A 卡住的这段时间：断开，再连接，C 装上
        manager.disconnectServer(OWNER, SERVER);
        manager.connectServer(OWNER, SERVER);
        assertEquals("connected", stateOf(manager), "前置：C 应已连上");
        assertEquals(List.of("c_tool"), toolNames(manager), "前置：工具应来自 C");

        // 放行 A：它已经过期
        aRelease.countDown();
        oldConnect.join(10_000);
        assertFalse(oldConnect.isAlive(), "旧连接线程应结束");

        assertEquals("connected", stateOf(manager), "过期连接不得把槽位置为断开");
        assertEquals(List.of("c_tool"), toolNames(manager), "工具必须仍是 C 的");
        assertEquals(1, manager.callbacks(OWNER).length, "只应有 C 的一个工具");
        assertFalse(c.closed, "新会话不得被过期连接关闭");
        assertTrue(a.closed, "过期会话必须被关闭");
    }

    @Test
    @DisplayName("过期连接不得清掉新一代连接正在途的 inflight")
    void staleConnectMustNotClearNewInflight() throws Exception {
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);
        CountDownLatch cEntered = new CountDownLatch(1);
        CountDownLatch cRelease = new CountDownLatch(1);
        CountDownLatch dEntered = new CountDownLatch(1);
        CountDownLatch dRelease = new CountDownLatch(1);

        ScriptedSession a = new ScriptedSession("A", "a_tool", aEntered, aRelease);
        ScriptedSession c = new ScriptedSession("C", "c_tool", null, null);
        ScriptedSession d = new ScriptedSession("D", "d_tool", null, null);

        // i=0 → A（在 listTools 卡住）；i=1 → C（在 openSession 卡住，保持 inflight 在途）；
        // i>=2 → D，只有 inflight 被错误清空时才会出现。
        ScriptedManager manager = new ScriptedManager(props(), i -> {
            if (i == 1) {
                cEntered.countDown();
                awaitQuietly(cRelease);
            }
            if (i >= 2) {
                dEntered.countDown();
                awaitQuietly(dRelease);
            }
            if (i == 0) return a;
            return i == 1 ? c : d;
        });

        Thread t1 = new Thread(() -> manager.addServer(OWNER, SERVER, stdioSpec(), true), "old-connect");
        Thread t2 = new Thread(() -> manager.connectServer(OWNER, SERVER), "new-connect");
        Thread t3 = new Thread(() -> manager.connectServer(OWNER, SERVER), "third-connect");
        try {
            t1.start();
            assertTrue(aEntered.await(5, TimeUnit.SECONDS), "A 应进入 listTools");

            manager.disconnectServer(OWNER, SERVER);
            t2.start();
            assertTrue(cEntered.await(5, TimeUnit.SECONDS), "C 应停在 openSession，inflight 仍在途");

            aRelease.countDown();          // 放行 A，它已过期
            t1.join(10_000);
            assertFalse(t1.isAlive(), "A 的过期分支应执行完");

            t3.start();
            // C 还卡着，inflight 未完成。单飞正确时 t3 会复用它、不会新建连接。
            boolean startedAnother = dEntered.await(2, TimeUnit.SECONDS);
            assertFalse(startedAnother, "过期连接清掉了新一代的 inflight，导致多起了一次连接");
        } finally {
            cRelease.countDown();
            dRelease.countDown();
            aRelease.countDown();
            t1.join(5_000);
            t2.join(5_000);
            t3.join(5_000);
        }

        assertEquals("connected", stateOf(manager));
        assertEquals(List.of("c_tool"), toolNames(manager), "最终应是 C 的工具");
    }

    // ---------------- 装配 ----------------

    private static McpProperties props() {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setInitializeTimeoutMs(5_000);
        p.setRequestTimeoutMs(5_000);
        p.setMaxResultChars(8_000);
        return p;
    }

    private static McpServerSpec stdioSpec() {
        McpServerSpec spec = new McpServerSpec();
        spec.setTransport(McpServerConfig.TRANSPORT_STDIO);
        spec.setCommand("npx");
        spec.setConnectTimeoutSeconds(5);
        spec.setMaxResultChars(8_000);
        return spec;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stateOf(McpClientManager manager) {
        List<McpServerStatus> statuses = manager.statuses(OWNER);
        assertEquals(1, statuses.size(), "应只有一个槽位");
        return statuses.get(0).getState();
    }

    private static List<String> toolNames(McpClientManager manager) {
        List<String> names = new ArrayList<>();
        for (McpListedTool tool : manager.listedTools(OWNER, SERVER)) {
            names.add(tool.originalName);
        }
        return names;
    }

    /** 按第几次 openSession 决定返回哪个会话。 */
    private static final class ScriptedManager extends McpClientManager {
        private final AtomicInteger opens = new AtomicInteger();
        private final IntFunction<McpSession> script;
        ScriptedManager(McpProperties props, IntFunction<McpSession> script) {
            super(props);
            this.script = script;
        }
        @Override McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
            return script.apply(opens.getAndIncrement());
        }
    }

    /** 可以卡住 listTools 的假会话。 */
    private static final class ScriptedSession implements McpSession {
        private final String label;
        private final String toolName;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        volatile boolean closed;

        ScriptedSession(String label, String toolName, CountDownLatch entered, CountDownLatch release) {
            this.label = label;
            this.toolName = toolName;
            this.entered = entered;
            this.release = release;
        }

        @Override public String serverName() { return label; }

        @Override public List<McpRemoteTool> listTools() {
            if (entered != null) {
                entered.countDown();
                awaitQuietly(release);
            }
            return List.of(new McpRemoteTool(toolName, "tool of " + label, "{\"type\":\"object\"}"));
        }

        @Override public McpCallResult callTool(String originalName, String argumentsJson) {
            return McpCallResult.ok(label + ":" + originalName);
        }

        @Override public boolean isAlive() { return !closed; }

        @Override public void close() { closed = true; }
    }
}
