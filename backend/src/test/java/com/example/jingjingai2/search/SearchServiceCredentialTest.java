package com.example.jingjingai2.search;

import com.example.jingjingai2.security.DpapiException;
import com.example.jingjingai2.security.DpapiProtector;
import com.example.jingjingai2.security.SearchCredentialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchService 与本地加密凭证的接线：优先密文、回退配置、解密失败明确提示、
 * 日志不出现 Key、未配置时不再指向开发者路径。
 */
class SearchServiceCredentialTest {

    private static final String FAKE_KEY = "fake-search-key-abcdef0123456789";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("本地加密凭证优先于配置/环境变量，并用于 provider 请求")
    void encryptedCredentialWins() {
        SearchCredentialStore store = store(tempDir.resolve("search.bin"), new XorProtector((byte) 9));
        store.save("", FAKE_KEY, false);
        SearchServiceTest.FakeProvider provider = new SearchServiceTest.FakeProvider();
        Offline service = new Offline(List.of(provider), store, "fake", "configured-key-should-lose");

        assertTrue(service.isConfigured());
        String text = service.search("q", 2);

        assertEquals("Bearer " + FAKE_KEY, provider.lastAuthHeader);
        assertTrue(text.contains("标题A"), text);
    }

    @Test
    @DisplayName("没有本地密文时回退到配置/环境变量（现有开发环境兼容）")
    void fallsBackToConfiguredKey() {
        SearchCredentialStore store = store(tempDir.resolve("missing.bin"), new XorProtector((byte) 9));
        SearchServiceTest.FakeProvider provider = new SearchServiceTest.FakeProvider();
        Offline service = new Offline(List.of(provider), store, "fake", "configured-key");

        assertTrue(service.isConfigured());
        service.search("q", 1);
        assertEquals("Bearer configured-key", provider.lastAuthHeader);
    }

    @Test
    @DisplayName("本地密文无法解密：明确提示、不静默换 Key、不把 Key 写进消息")
    void undecryptableCredentialIsExplicit() throws IOException {
        Path file = tempDir.resolve("broken.bin");
        store(file, new XorProtector((byte) 1)).save("", FAKE_KEY, false);
        SearchCredentialStore otherUser = new SearchCredentialStore(file, new FailingProtector());
        Offline service = new Offline(List.of(new SearchServiceTest.FakeProvider()), otherUser, "fake", "");

        assertFalse(service.isConfigured());
        String text = service.search("q", null);
        assertTrue(text.contains("无法解密"), text);
        assertFalse(text.contains(FAKE_KEY), text);
        assertTrue(Files.isRegularFile(file), "失败时不能删除或重建用户文件");
    }

    @Test
    @DisplayName("完全没 Key：提示导入一次，不出现 src/main/resources/application-local.yml")
    void missingKeyMessageHasNoDeveloperPath() {
        SearchCredentialStore store = store(tempDir.resolve("none.bin"), new XorProtector((byte) 1));
        Offline service = new Offline(List.of(new SearchServiceTest.FakeProvider()), store, "fake", "");

        String text = service.search("q", null);

        assertTrue(text.contains("尚未配置搜索 API Key"), text);
        assertFalse(text.contains("application-local.yml"), text);
        assertFalse(text.contains("src/main/resources"), text);
        assertTrue(text.contains("docs/DESKTOP-PACKAGING.md"), text);
    }

    @Test
    @DisplayName("启动状态日志只写来源/provider，不写 Key")
    void startupLogNeverContainsKey() {
        SearchCredentialStore store = store(tempDir.resolve("log.bin"), new XorProtector((byte) 2));
        store.save("", FAKE_KEY, false);
        Offline service = new Offline(List.of(new SearchServiceTest.FakeProvider()), store, "fake", "");

        String out = captureStdout(service::logCredentialStatus);

        assertTrue(out.contains("搜索已启用"), out);
        assertFalse(out.contains(FAKE_KEY), out);
    }

    @Test
    @DisplayName("服务商错误体如果回显 Key，返回前会被替换成 <已隐藏>")
    void providerErrorEchoIsRedacted() {
        SearchCredentialStore store = store(tempDir.resolve("echo.bin"), new XorProtector((byte) 4));
        store.save("", FAKE_KEY, false);
        SearchService service = new EchoingFailure(store, FAKE_KEY);

        String text = service.search("q", null);

        assertTrue(text.contains("已隐藏"), text);
        assertFalse(text.contains(FAKE_KEY), text);
    }

    @Test
    @DisplayName("密文里保存的 provider 优先于配置")
    void storedProviderWins() {
        SearchCredentialStore store = store(tempDir.resolve("provider.bin"), new XorProtector((byte) 3));
        store.save("serper", FAKE_KEY, false);
        Offline service = new Offline(List.of(new SearchServiceTest.FakeProvider()), store, "fake", "");

        assertEquals("serper", service.currentProvider());
        assertTrue(service.search("q", null).contains("未知的搜索服务提供方"));
    }

    private static SearchCredentialStore store(Path file, DpapiProtector protector) {
        return new SearchCredentialStore(file, protector);
    }

    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** 覆写 httpPost，离线跑通整条链路。 */
    static final class Offline extends SearchService {
        Offline(List<SearchProvider> providers, SearchCredentialStore store,
                String providerName, String configuredKey) {
            super(providers, store, providerName, configuredKey, 5, 5, "");
        }

        @Override
        String httpPost(HttpCall call) {
            return "{\"ok\":true}";
        }
    }

    /** 模拟服务商把 Key 回显在错误体里。 */
    static final class EchoingFailure extends SearchService {
        private final String key;

        EchoingFailure(SearchCredentialStore store, String key) {
            super(List.of(new SearchServiceTest.FakeProvider()), store, "fake", "", 5, 5, "");
            this.key = key;
        }

        @Override
        String httpPost(HttpCall call) throws IOException {
            throw new IOException("upstream echoed " + key);
        }
    }

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

    static final class FailingProtector implements DpapiProtector {
        @Override public boolean supported() { return true; }
        @Override public byte[] protect(byte[] plaintext) { return plaintext.clone(); }
        @Override public byte[] unprotect(byte[] protectedData) {
            throw new DpapiException(DpapiException.Kind.UNPROTECT_FAILED, "DPAPI 解密失败");
        }
    }
}
