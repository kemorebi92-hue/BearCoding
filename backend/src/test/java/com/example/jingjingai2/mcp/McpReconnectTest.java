package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpReconnectTest {

    @Test
    @DisplayName("stdio close 杀掉子孙（npx→node 形）：父与子都死")
    void closeKillsDescendants() throws Exception {
        McpServerSpec spec = McpTestProcess.echoSpec("--spawn-child");
        McpProperties props = McpTestProcess.shortTimeoutProps();
        List<ProcessHandle> kids;
        long pid;
        try (StdioMcpSession session = StdioMcpSession.start("echo", spec, props)) {
            pid = session.processPid();
            assertTrue(pid > 0);
            ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
            Thread.sleep(300); // 等 spawnChild
            kids = handle.descendants().toList();
            assertFalse(kids.isEmpty(), "应至少有一个子孙进程");
            session.close();
        }
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            boolean parentDead = ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true);
            boolean kidsDead = kids.stream().noneMatch(ProcessHandle::isAlive);
            if (parentDead && kidsDead) return;
            Thread.sleep(100);
        }
        fail("关闭后仍有活进程 parent=" + ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                + " kids=" + kids.stream().filter(ProcessHandle::isAlive).toList());
    }

    @Test
    @DisplayName("20 个线程打一条死会话：只新拉 1 个进程，都拿到确定结果，无死锁")
    void concurrentReconnectSingleFlight() throws Exception {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        p.setInitializeTimeoutMs(8_000);
        p.setRequestTimeoutMs(2_000);
        McpServerSpec spec = McpTestProcess.echoSpec();
        java.util.Map<String, McpServerSpec> servers = new java.util.LinkedHashMap<>();
        servers.put("echo", spec);
        p.setServers(servers);

        AtomicInteger opens = new AtomicInteger();
        McpClientManager m = new McpClientManager(p) {
            @Override
            McpSession openSession(String serverName, McpServerSpec s) {
                opens.incrementAndGet();
                return super.openSession(serverName, s);
            }
        };
        m.start();
        assertEquals(1, opens.get(), "首次连接一次");
        assertTrue(m.callbacks().length >= 1, "至少暴露 echo");
        McpSession first = m.sessionOf("echo");
        assertNotNull(first);
        first.close();
        assertFalse(first.isAlive());

        int n = 20;
        CyclicBarrier barrier = new CyclicBarrier(n);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicReference<String> sample = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                    String r = m.invoke("echo", "echo", "{\"text\":\"hi\"}", 8000);
                    sample.compareAndSet(null, r);
                    if (r != null && !r.isBlank()) ok.incrementAndGet();
                } catch (Exception e) {
                    sample.compareAndSet(null, "EX:" + e.getMessage());
                } finally {
                    done.countDown();
                }
            }, "mcp-re-n");
            t.start();
        }
        assertTrue(done.await(40, TimeUnit.SECONDS), "不应死锁");
        assertEquals(n, ok.get(), "每条线程都要有确定结果: " + sample.get());
        assertEquals(2, opens.get(), "重连只新拉 1 个进程, opens=" + opens.get());
        for (String r : List.of(sample.get())) {
            assertFalse(r.startsWith("EX:"), r);
        }
        m.stop();
    }

    @Test
    @DisplayName("boot() 不阻塞：openSession 很慢时立刻返回，connecting 不进本轮清单")
    void bootDoesNotBlock() throws Exception {
        McpProperties p = new McpProperties();
        p.setEnabled(true);
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        java.util.Map<String, McpServerSpec> servers = new java.util.LinkedHashMap<>();
        servers.put("slow", spec);
        p.setServers(servers);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeMcpSession fake = new FakeMcpSession("slow", List.of(
                new McpRemoteTool("echo", "Echo", "{\"type\":\"object\"}")));
        McpClientManager m = new McpClientManager(p) {
            @Override
            McpSession openSession(String serverName, McpServerSpec s) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return fake;
            }
        };
        long t0 = System.currentTimeMillis();
        m.boot();
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 1500, "boot 应马上返回, elapsed=" + elapsed);
        assertEquals(0, m.callbacks().length, "connecting 时不应下发工具");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        List<McpServerStatus> st = m.statuses();
        assertEquals(1, st.size());
        assertEquals("connecting", st.get(0).getState());
        release.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && m.callbacks().length == 0) {
            Thread.sleep(50);
        }
        assertEquals(1, m.callbacks().length);
        assertEquals("connected", m.statuses().get(0).getState());
        m.stop();
    }

    @Test
    @DisplayName("启动 warn：enabled-tools 废弃提示；高危工具按实际暴露提示；敏感 env 键名；ask 不再降级")
    void bootWarns() {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        System.setErr(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            McpProperties p = new McpProperties();
            p.setEnabled(true);
            p.setUntrustedContentPolicy("ask");
            McpServerSpec spec = new McpServerSpec();
            spec.setCommand("java");
            spec.setTrust("untrusted");
            spec.setEnabledTools(List.of("browser_evaluate"));
            spec.getEnv().put("API_TOKEN", "should-not-appear");
            java.util.Map<String, McpServerSpec> servers = new java.util.LinkedHashMap<>();
            servers.put("pw", spec);
            p.setServers(servers);
            FakeMcpSession fake = new FakeMcpSession("pw", List.of(
                    new McpRemoteTool("browser_evaluate", "eval", "{}")));
            McpClientManager m = new McpClientManager(p) {
                @Override
                McpSession openSession(String serverName, McpServerSpec s) {
                    return fake;
                }
            };
            m.start();
            String err = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(err.contains("降级"), err);
            assertTrue(err.contains("browser_evaluate"), err);
            assertTrue(err.contains("API_TOKEN"), err);
            assertFalse(err.contains("should-not-appear"), err);
            assertEquals("ask", p.effectiveUntrustedPolicy());
            m.stop();
        } finally {
            System.setErr(old);
        }
    }
}
