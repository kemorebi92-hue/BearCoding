package com.example.jingjingai2.security;

import com.example.jingjingai2.config.AppDataProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 搜索 Key 本地加密存储：密文不含明文、重启可读、拒绝覆盖、换密钥/损坏明确失败。
 * 用可逆测试替身代替 Windows DPAPI（真实 DPAPI 由 Windows 专用测试覆盖）。
 */
class SearchCredentialStoreTest {

    private static final String FAKE_KEY = "fake-search-key-0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("保存后文件不含明文，读取能恢复 provider 与 key")
    void roundTripHidesPlaintext() throws IOException {
        Path file = tempDir.resolve("secrets/search-credential.bin");
        SearchCredentialStore store = new SearchCredentialStore(file, new XorProtector((byte) 0x5A));

        store.save("tavily", FAKE_KEY, false);

        byte[] raw = Files.readAllBytes(file);
        assertFalse(contains(raw, FAKE_KEY.getBytes(StandardCharsets.UTF_8)), "文件里不能出现明文 Key");
        assertTrue(new String(raw, 0, 4, StandardCharsets.US_ASCII).equals(SearchCredentialStore.MAGIC));

        SearchCredentialStore.StoredCredential loaded = store.load();
        assertEquals("tavily", loaded.provider());
        assertEquals(FAKE_KEY, loaded.apiKey());
        assertFalse(loaded.toString().contains(FAKE_KEY), "toString 不能泄露 Key");
        assertTrue(Files.notExists(file.resolveSibling("search-credential.bin.writing")));
    }

    @Test
    @DisplayName("默认拒绝覆盖已有密文；显式 replace 才替换")
    void refusesOverwriteByDefault() {
        Path file = tempDir.resolve("search-credential.bin");
        SearchCredentialStore store = new SearchCredentialStore(file, new XorProtector((byte) 1));
        store.save("tavily", FAKE_KEY, false);

        DpapiException ex = assertThrows(DpapiException.class,
                () -> store.save("serper", "another-fake-key-9999999999", false));
        assertEquals(DpapiException.Kind.IO, ex.kind());
        assertEquals(FAKE_KEY, store.load().apiKey());

        store.save("serper", "another-fake-key-9999999999", true);
        assertEquals("serper", store.load().provider());
        assertEquals("another-fake-key-9999999999", store.load().apiKey());
    }

    @Test
    @DisplayName("删除即停用；重复删除不报错")
    void deleteDisables() {
        Path file = tempDir.resolve("search-credential.bin");
        SearchCredentialStore store = new SearchCredentialStore(file, new XorProtector((byte) 2));
        store.save("tavily", FAKE_KEY, false);

        assertTrue(store.delete());
        assertFalse(store.exists());
        assertFalse(store.delete());
        DpapiException ex = assertThrows(DpapiException.class, store::load);
        assertEquals(DpapiException.Kind.NOT_FOUND, ex.kind());
    }

    @Test
    @DisplayName("换用户/换机器（另一把 DPAPI 密钥）解密失败，消息不含 Key，也不会自动重建")
    void wrongProtectorFailsExplicitly() {
        Path file = tempDir.resolve("search-credential.bin");
        new SearchCredentialStore(file, new XorProtector((byte) 3)).save("tavily", FAKE_KEY, false);
        SearchCredentialStore otherUser = new SearchCredentialStore(file, new DpapiProtector() {
            @Override public boolean supported() { return true; }
            @Override public byte[] protect(byte[] plaintext) { return plaintext.clone(); }
            @Override public byte[] unprotect(byte[] protectedData) {
                throw new DpapiException(DpapiException.Kind.UNPROTECT_FAILED, "DPAPI 解密失败");
            }
        });

        DpapiException ex = assertThrows(DpapiException.class, otherUser::load);

        assertTrue(ex.getMessage().contains("无法解密"), ex.getMessage());
        assertFalse(ex.getMessage().contains(FAKE_KEY));
        assertTrue(Files.isRegularFile(file), "失败时不能删除或重建用户文件");
    }

    @Test
    @DisplayName("空文件、错误魔数、错误版本明确失败")
    void rejectsCorruptFiles() throws IOException {
        Path empty = tempDir.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        assertKind(empty, DpapiException.Kind.FORMAT);

        Path magic = tempDir.resolve("magic.bin");
        Files.write(magic, new byte[]{'X', 'X', 'X', 'X', 1, 9, 9});
        assertKind(magic, DpapiException.Kind.FORMAT);

        Path version = tempDir.resolve("version.bin");
        Files.write(version, new byte[]{'J', 'J', 'S', 'K', 2, 9, 9});
        assertKind(version, DpapiException.Kind.VERSION);
    }

    @Test
    @DisplayName("桌面模式默认路径 = {数据根}\\secrets\\search-credential.bin；开发模式在 ./data/secrets")
    void resolvesUnderDataRoot() {
        AppDataProperties desktop = new AppDataProperties();
        desktop.setMode(AppDataProperties.MODE_DESKTOP);
        desktop.setRoot(tempDir.resolve("BearCoding").toString());

        assertEquals(tempDir.resolve("BearCoding").resolve("secrets").resolve(SearchCredentialStore.FILE_NAME),
                SearchCredentialStore.resolveFile(desktop, ""));

        AppDataProperties development = new AppDataProperties();
        development.setMode(AppDataProperties.MODE_DEVELOPMENT);
        Path dev = SearchCredentialStore.resolveFile(development, "");
        assertTrue(dev.isAbsolute());
        assertTrue(dev.endsWith(Path.of("data", "secrets", SearchCredentialStore.FILE_NAME)), dev.toString());

        assertThrows(IllegalStateException.class,
                () -> SearchCredentialStore.resolveFile(desktop, "relative/search.bin"));
    }

    @Test
    @DisplayName("空 Key、带换行的 Key 被拒绝，不会写出文件")
    void rejectsInvalidInput() {
        Path file = tempDir.resolve("search-credential.bin");
        SearchCredentialStore store = new SearchCredentialStore(file, new XorProtector((byte) 5));

        assertThrows(DpapiException.class, () -> store.save("tavily", "  ", false));
        assertThrows(DpapiException.class, () -> store.save("tavily", "line1\nline2", false));
        assertTrue(Files.notExists(file));
    }

    private void assertKind(Path file, DpapiException.Kind kind) {
        SearchCredentialStore store = new SearchCredentialStore(file, new XorProtector((byte) 6));
        DpapiException ex = assertThrows(DpapiException.class, store::load);
        assertEquals(kind, ex.kind());
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean same = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) { same = false; break; }
            }
            if (same) return true;
        }
        return false;
    }

    /** 可逆测试替身，不是 DPAPI。不同 mask 模拟“另一个 Windows 用户”。 */
    static final class XorProtector implements DpapiProtector {
        private final byte mask;

        XorProtector(byte mask) { this.mask = mask; }

        @Override public boolean supported() { return true; }

        @Override public byte[] protect(byte[] plaintext) {
            byte[] out = plaintext.clone();
            for (int i = 0; i < out.length; i++) out[i] ^= mask;
            return out;
        }

        @Override public byte[] unprotect(byte[] protectedData) {
            if (protectedData == null || protectedData.length < 4) {
                throw new DpapiException(DpapiException.Kind.UNPROTECT_FAILED, "测试数据损坏");
            }
            return protect(protectedData);
        }
    }
}
