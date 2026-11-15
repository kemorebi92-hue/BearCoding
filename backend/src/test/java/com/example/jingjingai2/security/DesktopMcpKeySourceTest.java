package com.example.jingjingai2.security;

import com.example.jingjingai2.config.AppDataProperties;
import com.example.jingjingai2.config.SqliteDataDirectoryConfig;
import com.example.jingjingai2.mcp.McpCredentialCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopMcpKeySourceTest {

    @TempDir
    Path tempDir;

    private final ReversingProtector protector = new ReversingProtector();

    @Test
    @DisplayName("首次安装生成密钥，数据库文件尚未创建")
    void firstInstallCreatesKeyBeforeDatabase() {
        Path root = tempDir.resolve("app");
        MockEnvironment env = desktop();

        String material = DesktopMcpKey.resolve(root, database(root), protector);
        apply(env, root, material);

        Path key = root.resolve("secrets").resolve("mcp-credential.key");
        assertTrue(Files.isRegularFile(key));
        assertTrue(Files.notExists(database(root)));
        assertFalse(new String(read(key), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(env.getProperty(DesktopMcpKey.PROPERTY)));
        assertEquals(44, env.getProperty(DesktopMcpKey.PROPERTY).length());
    }

    @Test
    @DisplayName("重启读取同一密钥，不覆盖文件")
    void restartKeepsKey() {
        Path root = tempDir.resolve("app");
        MockEnvironment firstEnv = desktop();
        String first = DesktopMcpKey.resolve(root, database(root), protector);
        apply(firstEnv, root, first);
        touch(database(root));
        byte[] before = read(root.resolve("secrets").resolve("mcp-credential.key"));

        MockEnvironment second = desktop();
        String again = DesktopMcpKey.resolve(root, database(root), protector);

        assertEquals(first, again);
        assertArrayEquals(before, read(root.resolve("secrets").resolve("mcp-credential.key")));
        McpCredentialCrypto crypto = crypto(again, "env-secret-must-not-win-0123456789");
        assertEquals("token", crypto.decrypt(crypto.encrypt("token")));
    }

    @Test
    @DisplayName("数据库已在而密钥缺失时失败，不生成替代密钥")
    void existingDatabaseWithoutKeyFails() {
        Path root = tempDir.resolve("app");
        touch(root.resolve("data").resolve("jingjing.db"));
        MockEnvironment env = desktop();

        assertThrows(DpapiException.class, () -> DesktopMcpKey.resolve(root, database(root), protector));

        assertTrue(Files.notExists(root.resolve("secrets").resolve("mcp-credential.key")));
        assertEquals("jdbc:sqlite:./data/jingjing.db", env.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("密钥损坏或解密失败时失败，原文件保留")
    void corruptKeyFails() {
        Path root = tempDir.resolve("app");
        Path key = root.resolve("secrets").resolve("mcp-credential.key");
        touch(database(root));
        write(key, new byte[] {'J', 'J', 'D', 'P', 1, 1});

        assertThrows(DpapiException.class, () -> DesktopMcpKey.resolve(root, database(root), protector));

        assertArrayEquals(new byte[] {'J', 'J', 'D', 'P', 1, 1}, read(key));
    }

    @Test
    @DisplayName("development 不生成桌面密钥，旧环境变量仍然有效")
    void developmentIgnoresDesktopKey() {
        Path root = tempDir.resolve("app");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:sqlite:./data/jingjing.db");
        env.setProperty("app.data.mode", "development");

        AppDataProperties props = new AppDataProperties();
        props.setMode(env.getProperty("app.data.mode"));
        AppDataProperties.Layout layout = props.resolve(Map.of("JINGJING_DATA_DIR", root.toString()));

        assertFalse(layout.enforcesRoot());
        assertEquals(null, env.getProperty(DesktopMcpKey.PROPERTY));
        McpCredentialCrypto crypto = new McpCredentialCrypto("development-key-material-0123456789");
        assertEquals("token", crypto.decrypt(crypto.encrypt("token")));
    }

    @Test
    @DisplayName("桌面材料优先于构造时已经读到的环境变量")
    void desktopMaterialOverridesEnvironment() {
        McpCredentialCrypto crypto = crypto(Base64.getEncoder().encodeToString(new byte[32]),
                "environment-key-material-0123456789");
        String encrypted = crypto.encrypt("desktop");
        McpCredentialCrypto other = new McpCredentialCrypto("environment-key-material-0123456789");
        assertThrows(com.example.jingjingai2.mcp.McpCredentialException.class, () -> other.decrypt(encrypted));
    }

    private static MockEnvironment desktop() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:sqlite:./data/jingjing.db");
        env.setProperty("app.data.mode", "desktop");
        env.setProperty("MCP_CREDENTIAL_KEY", "environment-key-material-0123456789");
        return env;
    }

    private static Path database(Path root) {
        return root.resolve("data").resolve("jingjing.db");
    }

    private static void apply(MockEnvironment env, Path root, String material) {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");
        AppDataProperties.Layout layout = props.resolve(Map.of("JINGJING_DATA_DIR", root.toString()));
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "desktop", SqliteDataDirectoryConfig.desktopOverrides(database(root), layout, material)));
    }

    private static McpCredentialCrypto crypto(String desktop, String ignoredEnvironment) {
        McpCredentialCrypto crypto = new McpCredentialCrypto(ignoredEnvironment);
        MockEnvironment env = new MockEnvironment();
        env.setProperty(DesktopMcpKey.PROPERTY, desktop);
        crypto.setEnvironment(env);
        return crypto;
    }

    private static void touch(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, new byte[] {1});
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
}
