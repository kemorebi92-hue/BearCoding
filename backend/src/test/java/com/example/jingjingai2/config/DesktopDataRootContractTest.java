package com.example.jingjingai2.config;

import com.example.jingjingai2.security.DpapiProtector;
import com.example.jingjingai2.security.SearchCredentialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据根契约：JINGJING_DATA_DIR 传的是“数据根本身”，
 * 不能额外再拼一层 data（否则会变成 data\data\jingjing.db）。
 */
class DesktopDataRootContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("JINGJING_DATA_DIR=<root> → <root>\\data\\jingjing.db，且子目录都在同一根下")
    void dataRootIsNotDoubled() {
        Path root = tempDir.resolve("BearCoding");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:sqlite:./data/jingjing.db");
        env.setProperty("app.data.mode", AppDataProperties.MODE_DESKTOP);
        env.setProperty("app.data.copy-legacy-database", "false");

        SqliteDataDirectoryConfig.applyDesktopUrl(env,
                Map.of(AppDataProperties.ENV_DATA_DIR, root.toString()),
                tempDir.resolve("missing-legacy.db"), new FakeProtector());

        Path database = root.resolve("data").resolve("jingjing.db").normalize();
        assertEquals("jdbc:sqlite:" + database, env.getProperty("spring.datasource.url"));
        assertFalse(env.getProperty("spring.datasource.url").contains("data" + java.io.File.separator + "data"),
                "不能出现 data\\data");
        assertEquals(root.resolve("workspaces").normalize().toString(), env.getProperty("app.workspace.root"));
        assertEquals(root.resolve("mcp-output").normalize().toString(), env.getProperty("app.mcp.output.dir"));
        assertEquals(root.resolve("logs").resolve("edit-audit.log").normalize().toString(),
                env.getProperty("app.edit.audit-log"));

        // 加密材料统一在 {root}\secrets
        assertTrue(Files.isRegularFile(root.resolve("secrets").resolve("mcp-credential.key")));
        assertTrue(Files.isRegularFile(root.resolve("secrets").resolve("model-aes.key")));
        assertTrue(Files.isRegularFile(root.resolve("secrets").resolve("artifact-ref.key")));

        AppDataProperties props = new AppDataProperties();
        props.setMode(AppDataProperties.MODE_DESKTOP);
        props.setRoot(root.toString());
        assertEquals(root.resolve("secrets").resolve(SearchCredentialStore.FILE_NAME).normalize(),
                SearchCredentialStore.resolveFile(props, ""));
    }

    @Test
    @DisplayName("LOCALAPPDATA 回退：数据根是 %LOCALAPPDATA%\\BearCoding，不是 ...\\data")
    void localAppDataFallbackIsRoot() {
        AppDataProperties props = new AppDataProperties();
        props.setMode(AppDataProperties.MODE_DESKTOP);
        Path local = tempDir.resolve("Local");

        AppDataProperties.Layout layout = props.resolve(Map.of(AppDataProperties.ENV_LOCAL_APPDATA, local.toString()));

        Path expected = local.resolve(AppDataProperties.DESKTOP_DIR_NAME).normalize();
        assertEquals(expected, layout.root());
        assertEquals(expected.resolve("data").resolve("jingjing.db"), SqliteDatabasePrep.databaseFile(layout.root()));
    }

    private static final class FakeProtector implements DpapiProtector {
        @Override public boolean supported() { return true; }
        @Override public byte[] protect(byte[] plaintext) { return plaintext.clone(); }
        @Override public byte[] unprotect(byte[] protectedData) { return protectedData.clone(); }
    }
}
