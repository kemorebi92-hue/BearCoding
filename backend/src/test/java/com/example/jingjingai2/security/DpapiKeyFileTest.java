package com.example.jingjingai2.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DpapiKeyFileTest {

    @TempDir
    Path tempDir;

    private final DpapiKeyFile files = new DpapiKeyFile(new ReversingProtector());

    @Test
    @DisplayName("生成 32 字节，且连续两次不同")
    void generatesDistinctMaterial() {
        byte[] first = files.generate();
        byte[] second = files.generate();
        assertEquals(32, first.length);
        assertEquals(32, second.length);
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    @DisplayName("保存后文件不含明文，读取能恢复")
    void roundTripHidesPlaintext() throws IOException {
        byte[] material = files.generate();
        Path target = tempDir.resolve("secrets/mcp-credential.key");

        files.create(target, material);

        byte[] raw = DpapiKeyFile.readRaw(target);
        assertEquals(DpapiKeyFile.MAGIC[0], raw[0]);
        assertFalse(contains(raw, material));
        assertArrayEquals(material, files.load(target));
        assertTrue(Files.notExists(target.resolveSibling("mcp-credential.key.writing")));
    }

    @Test
    @DisplayName("文件不存在时不自动生成")
    void missingFileDoesNotCreate() {
        Path target = tempDir.resolve("missing.key");
        DpapiException ex = assertThrows(DpapiException.class, () -> files.load(target));
        assertEquals(DpapiException.Kind.NOT_FOUND, ex.kind());
        assertTrue(Files.notExists(target));
    }

    @Test
    @DisplayName("空文件、错误魔数、不支持的版本和损坏内容都会失败")
    void rejectsBadFiles() throws IOException {
        assertKind(write("empty.key", new byte[0]), DpapiException.Kind.FORMAT);
        assertKind(write("magic.key", new byte[] {'X', 'X', 'X', 'X', 1, 9}), DpapiException.Kind.FORMAT);
        assertKind(write("version.key", new byte[] {'J', 'J', 'D', 'P', 2, 9}), DpapiException.Kind.VERSION);
        assertKind(write("broken.key", new byte[] {'J', 'J', 'D', 'P', 1, 7}), DpapiException.Kind.UNPROTECT_FAILED);
    }

    @Test
    @DisplayName("已有文件拒绝覆盖，失败时原文件还在")
    void refusesToReplaceExisting() throws IOException {
        Path target = tempDir.resolve("kept.key");
        byte[] first = files.generate();
        files.create(target, first);
        byte[] before = DpapiKeyFile.readRaw(target);

        DpapiException ex = assertThrows(DpapiException.class, () -> files.create(target, files.generate()));

        assertEquals(DpapiException.Kind.IO, ex.kind());
        assertArrayEquals(before, DpapiKeyFile.readRaw(target));
        assertTrue(Files.notExists(target.resolveSibling("kept.key.writing")));
    }

    @Test
    @DisplayName("回读校验失败时删除临时文件，不留下目标")
    void failedVerificationRemovesTemp() {
        DpapiKeyFile dishonest = new DpapiKeyFile(new DishonestProtector());
        Path target = tempDir.resolve("new.key");

        assertThrows(DpapiException.class, () -> dishonest.create(target, dishonest.generate()));

        assertTrue(Files.notExists(target));
        assertTrue(Files.notExists(target.resolveSibling("new.key.writing")));
    }

    @Test
    @DisplayName("非 Windows 的真实实现明确失败，不降级")
    void unsupportedPlatformFails() {
        WindowsDpapiProtector windows = new WindowsDpapiProtector();
        if (windows.supported()) return;
        DpapiException ex = assertThrows(DpapiException.class, () -> windows.protect(new byte[32]));
        assertEquals(DpapiException.Kind.UNSUPPORTED, ex.kind());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 上用假材料真实调用 CurrentUser DPAPI")
    void windowsCurrentUserRoundTrip() throws IOException {
        WindowsDpapiProtector windows = new WindowsDpapiProtector();
        byte[] material = new DpapiKeyFile(windows).generate();
        byte[] protectedBytes = windows.protect(material);

        assertFalse(Arrays.equals(material, protectedBytes));
        assertArrayEquals(material, windows.unprotect(protectedBytes));
        assertThrows(DpapiException.class, () -> windows.unprotect(new byte[] {1, 2, 3, 4}));

        DpapiKeyFile store = new DpapiKeyFile(windows);
        Path target = tempDir.resolve("win.key");
        store.create(target, material);
        assertArrayEquals(material, store.load(target));
        assertTrue(DpapiKeyFile.restrictToCurrentUser(target));
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private void assertKind(Path path, DpapiException.Kind kind) {
        DpapiException ex = assertThrows(DpapiException.class, () -> files.load(path));
        assertEquals(kind, ex.kind());
    }

    private static boolean contains(byte[] file, byte[] material) {
        for (int i = 0; i <= file.length - material.length; i++) {
            boolean same = true;
            for (int j = 0; j < material.length; j++) {
                if (file[i + j] != material[j]) { same = false; break; }
            }
            if (same) return true;
        }
        return false;
    }

    /** 单元测试用的可逆包装，不是 DPAPI。 */
    private static final class ReversingProtector implements DpapiProtector {
        @Override public boolean supported() { return true; }
        @Override public byte[] protect(byte[] plaintext) {
            byte[] out = plaintext.clone();
            for (int i = 0; i < out.length; i++) out[i] = (byte) ~out[i];
            return out;
        }
        @Override public byte[] unprotect(byte[] protectedData) {
            if (protectedData.length < 32) {
                throw new DpapiException(DpapiException.Kind.UNPROTECT_FAILED, "测试数据损坏");
            }
            return protect(protectedData);
        }
    }

    /** 回读时故意返回另一份材料，用来验证失败清理。 */
    private static final class DishonestProtector implements DpapiProtector {
        @Override public boolean supported() { return true; }
        @Override public byte[] protect(byte[] plaintext) { return plaintext.clone(); }
        @Override public byte[] unprotect(byte[] protectedData) { return new byte[32]; }
    }
}
