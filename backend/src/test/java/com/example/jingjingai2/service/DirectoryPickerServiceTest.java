package com.example.jingjingai2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件夹选择器的“并发 / 取消 / 错误”边界；不真的弹 Swing 窗口。
 * 真窗口只在人工验收时点一次，避免 CI/测试机被模态对话框挂住。
 */
class DirectoryPickerServiceTest {

    @Test
    @DisplayName("选中路径：原样返回，并释放 picking 状态")
    void returnsSelectedPath() {
        DirectoryPickerService service = new DirectoryPickerService(dir -> "D:\\proj");
        assertEquals("D:\\proj", service.pick(null));
        assertFalse(service.isPicking());
    }

    @Test
    @DisplayName("用户取消：返回 null，不是错误")
    void returnsNullWhenCancelled() {
        DirectoryPickerService service = new DirectoryPickerService(dir -> null);
        assertNull(service.pick(null));
    }

    @Test
    @DisplayName("DIRECTORY_ONLY 没点中子目录：回落到当前目录")
    void fallsBackToCurrentDirectory(@TempDir Path dir) {
        String expected = dir.toAbsolutePath().normalize().toString();
        assertEquals(expected,
                DirectoryPickerService.SwingChooser.resolveSelected(null, dir.toFile()));
    }

    @Test
    @DisplayName("点中了子目录：优先用选中的目录")
    void prefersSelectedDirectory(@TempDir Path parent) throws Exception {
        Path child = java.nio.file.Files.createDirectory(parent.resolve("child"));
        assertEquals(child.toAbsolutePath().normalize().toString(),
                DirectoryPickerService.SwingChooser.resolveSelected(child.toFile(), parent.toFile()));
    }

    @Test
    @DisplayName("已有窗口在等待：第二次调用直接拒绝，不叠加对话框")
    void rejectsSecondCallWhilePicking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DirectoryPickerService service = new DirectoryPickerService(dir -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "D:\\chosen";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> service.pick(null));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "第一个调用应已进入选择器");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.pick(null));
            assertTrue(e.getMessage().contains("已经有一个"), e.getMessage());
            release.countDown();
            assertEquals("D:\\chosen", first.get(2, TimeUnit.SECONDS));
            assertFalse(service.isPicking(), "结束后必须释放状态");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("选择器抛错：异常原样抛给 Controller，状态仍释放")
    void releasesStateAfterFailure() {
        DirectoryPickerService service = new DirectoryPickerService(dir -> {
            throw new UnsupportedOperationException("无图形界面");
        });
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> service.pick(null));
        assertEquals("无图形界面", e.getMessage());
        assertFalse(service.isPicking());
    }
}
