package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.tool.ToolCallback;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主动断开之后，断开之前已经开始的连接不能再把槽位装回去。
 * 闸门卡在 openSession 里，不靠睡眠猜时序。
 */
class StaleConnectInstallTest {

    private McpClientManager manager;
    private final AtomicInteger opens = new AtomicInteger();
    private final List<FakeMcpSession> opened = new CopyOnWriteArrayList<>();
    /** 一次被卡住的连接：entered 放行时 session 已经写好，测试等它就能拿到这一次的会话。 */
    private static final class Hold {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile FakeMcpSession session;
    }

    private volatile Hold hold;

    @BeforeEach
    void setUp() {
        opens.set(0);
        opened.clear();
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        manager = new McpClientManager(props) {
            @Override
            McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
                int n = opens.incrementAndGet();
                Hold waiting = hold;
                if (waiting != null) {
                    waiting.entered.countDown();
                    try {
                        if (!waiting.release.await(5, TimeUnit.SECONDS)) {
                            throw new McpException("测试闸门超时");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new McpException("测试闸门被打断");
                    }
                }
                FakeMcpSession session = new FakeMcpSession(serverName, List.of(
                        new McpRemoteTool("echo", "n" + n, "{\"type\":\"object\"}")))
                        .handler((name, args) -> McpCallResult.ok("n" + n));
                if (waiting != null) waiting.session = session;
                opened.add(session);
                return session;
            }
        };
        manager.start();
    }

    @AfterEach
    void tearDown() {
        if (hold != null) hold.release.countDown();
        if (manager != null) manager.stop();
    }

    @Test
    @Timeout(10)
    @DisplayName("A：连接已进入 openSession 但还没返回时断开，放行后槽位仍是断开，旧会话被关掉")
    void disconnectWhileConnectingDoesNotInstall() throws Exception {
        Hold first = holdNextOpen();
        Thread connect = async(() -> manager.addServer(0L, "alpha", spec(), true));
        assertTrue(first.entered.await(3, TimeUnit.SECONDS), "连接还没进入 openSession");
        assertEquals(1, opens.get());
        assertEquals("connecting", stateOf("alpha"), "闸门没放之前不能算连上");

        assertFalse(manager.disconnectServer("alpha").rejected());
        assertEquals("disconnected", stateOf("alpha"));

        first.release.countDown();
        connect.join(4000);
        assertFalse(connect.isAlive());
        assertEquals("disconnected", stateOf("alpha"));
        assertEquals(1, opens.get(), "迟到的完成不能再开一次连接");
        assertTrue(first.session.isClosed(), "迟到的会话必须关掉");
        assertNull(callback("mcp__alpha__echo"), "断开后的工具不能再出现在清单里");
    }

    @Test
    @Timeout(10)
    @DisplayName("B：停用走同一条断开，在途连接完成后同样不能恢复")
    void disableWhileConnectingDoesNotInstall() throws Exception {
        Hold first = holdNextOpen();
        Thread connect = async(() -> manager.addServer(0L, "alpha", spec(), true));
        assertTrue(first.entered.await(3, TimeUnit.SECONDS));

        // setEnabled(false) 在槽位还在时调用的就是 disconnectServer。这里覆盖的是这条运行时路径，不是 HTTP。
        assertFalse(manager.disconnectServer("alpha").rejected());
        first.release.countDown();
        connect.join(4000);
        assertFalse(connect.isAlive());

        assertEquals("disconnected", stateOf("alpha"));
        assertTrue(first.session.isClosed());
        assertNull(callback("mcp__alpha__echo"));
    }

    @Test
    @Timeout(10)
    @DisplayName("C：断开后再显式连接，旧连接后完成也不能盖掉新连接")
    void explicitReconnectWinsOverStaleConnect() throws Exception {
        Hold first = holdNextOpen();
        Thread connect = async(() -> manager.addServer(0L, "alpha", spec(), true));
        assertTrue(first.entered.await(3, TimeUnit.SECONDS));
        assertFalse(manager.disconnectServer("alpha").rejected());

        hold = null;
        assertFalse(manager.connectServer("alpha").rejected());
        assertEquals(2, opens.get());
        assertEquals("connected", stateOf("alpha"));
        assertEquals("n2", callback("mcp__alpha__echo").call("{}"));

        first.release.countDown();
        connect.join(4000);
        assertFalse(connect.isAlive());

        assertEquals("connected", stateOf("alpha"));
        assertEquals("n2", callback("mcp__alpha__echo").call("{}"), "旧连接不能盖掉新连接");
        assertTrue(first.session.isClosed(), "先开始的那次必须关掉");
        assertFalse(manager.sessionOf("alpha") == null || !manager.sessionOf("alpha").isAlive(),
                "新连接应仍是当前会话");
        assertEquals(2, opens.get());
    }

    @Test
    @Timeout(10)
    @DisplayName("C：新连接更晚完成时，仍是新连接生效，旧连接被关掉")
    void newerConnectFinishingLaterStillWins() throws Exception {
        Hold first = holdNextOpen();
        Thread connect = async(() -> manager.addServer(0L, "alpha", spec(), true));
        assertTrue(first.entered.await(3, TimeUnit.SECONDS));
        assertFalse(manager.disconnectServer("alpha").rejected());

        Hold second = holdNextOpen();
        Thread reconnect = async(() -> manager.connectServer("alpha"));
        assertTrue(second.entered.await(3, TimeUnit.SECONDS), "第二次连接还没开始");

        // 先放第一次。它更早开始，但必须在第二次还没完成时就被丢掉。
        first.release.countDown();
        connect.join(4000);
        assertFalse(connect.isAlive());
        assertEquals("connecting", stateOf("alpha"), "第一次的迟到结果不能抢先装上");
        assertTrue(first.session.isClosed());

        second.release.countDown();
        reconnect.join(4000);
        assertFalse(reconnect.isAlive());
        assertEquals("connected", stateOf("alpha"));
        assertEquals("n2", callback("mcp__alpha__echo").call("{}"));
        assertFalse(second.session.isClosed());
    }

    @Test
    @Timeout(10)
    @DisplayName("E：删掉并重建之后，删除前那次在途连接不能装到新槽位上")
    void removedSlotDoesNotReceiveStaleInstall() throws Exception {
        Hold first = holdNextOpen();
        Thread connect = async(() -> manager.addServer(0L, "alpha", spec(), true));
        assertTrue(first.entered.await(3, TimeUnit.SECONDS));
        assertFalse(manager.removeServer("alpha").rejected());

        hold = null;
        assertFalse(manager.addServer(0L, "alpha", spec(), true).rejected());
        assertEquals("n2", callback("mcp__alpha__echo").call("{}"));

        first.release.countDown();
        connect.join(4000);
        assertFalse(connect.isAlive());

        assertEquals("n2", callback("mcp__alpha__echo").call("{}"));
        assertTrue(first.session.isClosed());
        assertTrue(manager.sessionOf("alpha") != null && manager.sessionOf("alpha").isAlive(),
                "重建后的会话应仍是当前会话");
    }

    @Test
    @Timeout(10)
    @DisplayName("关掉迟到会话时抛出，也不能把已断开的槽位标成已连接")
    void closeFailureDoesNotReconnect() throws Exception {
        Hold first = new Hold();
        hold = first;
        manager = new McpClientManager(new McpProperties() {{ setEnabled(true); }}) {
            @Override
            McpSession openSession(long ownerId, String serverName, McpServerSpec spec) {
                opens.incrementAndGet();
                Hold waiting = hold;
                if (waiting != null) waiting.entered.countDown();
                try {
                    if (waiting != null && !waiting.release.await(5, TimeUnit.SECONDS)) {
                        throw new McpException("测试闸门超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new McpException("测试闸门被打断");
                }
                return new FakeMcpSession(serverName, List.of(
                        new McpRemoteTool("echo", "echo", "{\"type\":\"object\"}"))).failOnClose();
            }
        };
        manager.start();
        Thread connect = async(() -> manager.addServer(0L, "alpha", spec(), true));
        assertTrue(first.entered.await(3, TimeUnit.SECONDS));
        assertFalse(manager.disconnectServer("alpha").rejected());
        first.release.countDown();
        connect.join(4000);
        assertFalse(connect.isAlive());
        assertEquals("disconnected", stateOf("alpha"));
        assertNull(callback("mcp__alpha__echo"));
    }

    private Hold holdNextOpen() {
        Hold next = new Hold();
        hold = next;
        return next;
    }

    private String stateOf(String name) {
        for (McpServerStatus status : manager.statuses()) {
            if (name.equals(status.getName())) return status.getState();
        }
        return null;
    }

    private ToolCallback callback(String name) {
        for (ToolCallback cb : manager.callbacks()) {
            if (cb != null && cb.getToolDefinition() != null && name.equals(cb.getToolDefinition().name())) {
                return cb;
            }
        }
        return null;
    }

    private static McpServerSpec spec() {
        McpServerSpec spec = new McpServerSpec();
        spec.setCommand("java");
        return spec;
    }

    private static Thread async(Runnable work) {
        Thread thread = new Thread(work);
        thread.start();
        return thread;
    }
}
