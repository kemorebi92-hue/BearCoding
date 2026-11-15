package com.example.jingjingai2.search;

import com.example.jingjingai2.security.SearchCredentialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 一次性导入/替换/停用的纯逻辑：消息只给路径与原因，不回显 Key。 */
class SearchKeyImportServiceTest {

    private static final String FAKE_KEY = "fake-search-key-import-0123456789";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("导入成功：消息含保存路径，不含 Key；文件可再次解密")
    void importSucceedsWithoutLeakingKey() {
        SearchCredentialStore store = store();
        SearchKeyImportService.Result result = SearchKeyImportService.importKey(store, FAKE_KEY, "tavily", false);

        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains(store.path().toString()), result.message());
        assertFalse(result.message().contains(FAKE_KEY), result.message());
        assertEquals(FAKE_KEY, store.load().apiKey());
        assertEquals("tavily", store.load().provider());
    }

    @Test
    @DisplayName("已有密文时默认不覆盖；force=true 才替换")
    void existingCredentialNotOverwritten() {
        SearchCredentialStore store = store();
        assertTrue(SearchKeyImportService.importKey(store, FAKE_KEY, "tavily", false).success());

        SearchKeyImportService.Result blocked =
                SearchKeyImportService.importKey(store, "another-fake-key-999999", "serper", false);
        assertFalse(blocked.success());
        assertTrue(blocked.message().contains("拒绝覆盖"), blocked.message());
        assertFalse(blocked.message().contains("another-fake-key-999999"));
        assertEquals(FAKE_KEY, store.load().apiKey());

        assertTrue(SearchKeyImportService.importKey(store, "another-fake-key-999999", "serper", true).success());
        assertEquals("another-fake-key-999999", store.load().apiKey());
    }

    @Test
    @DisplayName("空 Key 失败且不写文件")
    void blankKeyFails() {
        SearchCredentialStore store = store();
        SearchKeyImportService.Result result = SearchKeyImportService.importKey(store, "  ", "tavily", false);
        assertFalse(result.success());
        assertFalse(store.exists());
    }

    @Test
    @DisplayName("停用：删除密文；重复停用提示无需删除")
    void removeDisables() {
        SearchCredentialStore store = store();
        SearchKeyImportService.importKey(store, FAKE_KEY, "tavily", false);

        SearchKeyImportService.Result removed = SearchKeyImportService.remove(store);
        assertTrue(removed.success());
        assertTrue(removed.message().contains("已删除"), removed.message());
        assertFalse(removed.message().contains(FAKE_KEY));
        assertFalse(store.exists());

        SearchKeyImportService.Result again = SearchKeyImportService.remove(store);
        assertTrue(again.success());
        assertTrue(again.message().contains("没有加密搜索凭证"), again.message());
    }

    private SearchCredentialStore store() {
        return new SearchCredentialStore(tempDir.resolve("search-credential.bin"),
                new SearchServiceCredentialTest.XorProtector((byte) 7));
    }
}
