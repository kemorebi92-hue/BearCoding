package com.example.jingjingai2.security;

import com.example.jingjingai2.JingjingAi2Application;
import com.example.jingjingai2.mcp.McpCredentialCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class DesktopDpapiSpringIntegrationTest {

    private static final String UNUSED_ENV_KEY = "integration-env-key-must-not-win-0123456789";

    @Test
    @DisplayName("真实 Spring 桌面模式：生成、重启解密、缺密钥和损坏都按规则处理")
    void springDesktopUsesWindowsDpapi() throws IOException {
        Path root = Files.createTempDirectory("jingjing-dpapi-integration-");
        Path emptyWork = Files.createTempDirectory("jingjing-dpapi-work-");
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", emptyWork.toString());
        try {
            freshStartAndRestart(root);
            missingKeyFails(root);
            corruptKeyFails();
            ignoresLegacyDatabaseBesideWorkDir();
        } finally {
            System.setProperty("user.dir", previous);
            delete(root);
            delete(emptyWork);
        }
    }

    private void freshStartAndRestart(Path root) {
        ConfigurableApplicationContext first = start(root);
        Path database;
        Path key;
        String encrypted;
        String token = "test-token-" + root.getFileName();
        byte[] keyHash;
        try {
            database = Path.of(first.getEnvironment().getProperty("spring.datasource.url")
                    .substring("jdbc:sqlite:".length()));
            key = database.getParent().getParent().resolve("secrets").resolve("mcp-credential.key");
            assertTrue(database.startsWith(root));
            assertTrue(key.startsWith(root));
            assertTrue(Files.isRegularFile(database));
            assertTrue(Files.isRegularFile(key));
            byte[] material = new DpapiKeyFile(new WindowsDpapiProtector()).load(key);
            assertEquals(32, material.length);
            McpCredentialCrypto crypto = first.getBean(McpCredentialCrypto.class);
            encrypted = crypto.encrypt(token);
            assertEquals(token, crypto.decrypt(encrypted));
            McpCredentialCrypto fromFile = new McpCredentialCrypto(
                    java.util.Base64.getEncoder().encodeToString(material));
            assertEquals(token, fromFile.decrypt(encrypted));
            McpCredentialCrypto fromEnv = new McpCredentialCrypto(UNUSED_ENV_KEY);
            assertThrows(com.example.jingjingai2.mcp.McpCredentialException.class,
                    () -> fromEnv.decrypt(encrypted));
            keyHash = sha(key);
        } finally {
            first.close();
        }

        ConfigurableApplicationContext second = start(root);
        try {
            assertArrayEquals(keyHash, sha(key));
            assertEquals(token, second.getBean(McpCredentialCrypto.class).decrypt(encrypted));
        } finally {
            second.close();
        }
    }

    private void missingKeyFails(Path root) throws IOException {
        Path key = root.resolve("secrets").resolve("mcp-credential.key");
        Path database = root.resolve("data").resolve("jingjing.db");
        byte[] databaseBefore = Files.readAllBytes(database);
        Files.delete(key);

        assertThrows(RuntimeException.class, () -> start(root));

        assertFalse(Files.exists(key));
        assertArrayEquals(databaseBefore, Files.readAllBytes(database));
    }

    private void corruptKeyFails() throws IOException {
        Path root = Files.createTempDirectory("jingjing-dpapi-corrupt-");
        try {
            Path database = root.resolve("data").resolve("jingjing.db");
            Path key = root.resolve("secrets").resolve("mcp-credential.key");
            Files.createDirectories(database.getParent());
            Files.createDirectories(key.getParent());
            Files.write(database, new byte[] {1, 2, 3});
            byte[] broken = new byte[] {'J', 'J', 'D', 'P', 1, 9, 9};
            Files.write(key, broken);

            assertThrows(RuntimeException.class, () -> start(root));

            assertArrayEquals(broken, Files.readAllBytes(key));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(database));
        } finally {
            delete(root);
        }
    }

    private void ignoresLegacyDatabaseBesideWorkDir() throws IOException {
        Path work = Files.createTempDirectory("jingjing-dpapi-legacy-work-");
        Path legacy = work.resolve("data").resolve("jingjing.db");
        Files.createDirectories(legacy.getParent());
        byte[] marker = "legacy-database-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(legacy, marker);
        Path root = Files.createTempDirectory("jingjing-dpapi-fresh-");
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", work.toString());
        ConfigurableApplicationContext context = null;
        try {
            context = start(root);
            Path database = Path.of(context.getEnvironment().getProperty("spring.datasource.url")
                    .substring("jdbc:sqlite:".length()));
            assertTrue(database.startsWith(root));
            assertFalse(java.util.Arrays.equals(marker, Files.readAllBytes(database)));
            assertArrayEquals(marker, Files.readAllBytes(legacy));
        } finally {
            if (context != null) context.close();
            System.setProperty("user.dir", previous);
            delete(work);
            delete(root);
        }
    }

    private static ConfigurableApplicationContext start(Path root) {
        SpringApplication app = new SpringApplication(JingjingAi2Application.class);
        return app.run(
                "--spring.profiles.active=sqlite",
                "--server.port=0",
                "--app.auth.mode=local",
                "--app.data.mode=desktop",
                "--app.data.root=" + root,
                "--DEEPSEEK_API_KEY=integration-placeholder-not-a-real-key",
                "--MCP_CREDENTIAL_KEY=" + UNUSED_ENV_KEY);
    }

    private static byte[] sha(Path path) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败由测试结束后的目录检查报告。
                }
            });
        }
    }
}
