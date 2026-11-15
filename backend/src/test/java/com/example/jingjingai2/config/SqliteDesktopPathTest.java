package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDesktopPathTest {

    private static final String ORIGINAL = "jdbc:sqlite:./data/jingjing.db";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("development 不改 JDBC URL，环境变量也不切换路径")
    void developmentKeepsYamlUrl() {
        MockEnvironment env = sqliteEnv();
        env.setProperty("app.data.mode", "development");

        SqliteDataDirectoryConfig.applyDesktopUrl(env, Map.of(
                "JINGJING_DATA_DIR", tempDir.resolve("elsewhere").toString()),
                tempDir.resolve("missing-legacy.db"));

        assertEquals(ORIGINAL, env.getProperty("spring.datasource.url"));
        assertFalse(env.getPropertySources().contains(SqliteDataDirectoryConfig.DESKTOP_URL_SOURCE));
        assertEquals("./workspaces", env.getProperty("app.workspace.root", "./workspaces"));
        assertEquals("./mcp-output", env.getProperty("app.mcp.output.dir", "./mcp-output"));
    }

    @Test
    @DisplayName("desktop 先改成绝对 URL，再创建这个 URL 的父目录")
    void desktopRewritesUrlBeforeDirectoryInit() {
        Path root = tempDir.resolve("JingJingAI");
        MockEnvironment env = sqliteEnv();
        env.setProperty("app.data.mode", "desktop");

        SqliteDataDirectoryConfig.prepare(env, Map.of("JINGJING_DATA_DIR", root.toString()),
                tempDir.resolve("missing-legacy.db"));

        Path database = root.resolve("data").resolve("jingjing.db").normalize();
        assertEquals("jdbc:sqlite:" + database, env.getProperty("spring.datasource.url"));
        assertEquals(root.resolve("workspaces").normalize().toString(), env.getProperty("app.workspace.root"));
        assertEquals(root.resolve("mcp-output").normalize().toString(), env.getProperty("app.mcp.output.dir"));
        assertTrue(Files.isDirectory(database.getParent()));
        assertTrue(Files.notExists(database));
        assertTrue(Files.notExists(root.resolve("workspaces")));
        assertTrue(Files.notExists(root.resolve("mcp-output")));
        assertEquals(Ordered.HIGHEST_PRECEDENCE, new SqliteDataDirectoryConfig().getOrder());
    }

    @Test
    @DisplayName("相对数据目录在桌面模式失败，URL 保持原值")
    void relativeDesktopRootFailsBeforeRewrite() {
        MockEnvironment env = sqliteEnv();
        env.setProperty("app.data.mode", "desktop");
        env.setProperty("app.data.root", "relative/data");

        assertThrows(IllegalStateException.class,
                () -> SqliteDataDirectoryConfig.applyDesktopUrl(env, Map.of(),
                        tempDir.resolve("missing-legacy.db")));

        assertEquals(ORIGINAL, env.getProperty("spring.datasource.url"));
        assertEquals("./workspaces", env.getProperty("app.workspace.root", "./workspaces"));
        assertEquals("./mcp-output", env.getProperty("app.mcp.output.dir", "./mcp-output"));
    }

    @Test
    @DisplayName("development 自定义路径不被桌面目录覆盖")
    void developmentCustomPathsStay() {
        MockEnvironment env = sqliteEnv();
        env.setProperty("app.data.mode", "development");
        env.setProperty("app.workspace.root", "D:/custom/workspaces");
        env.setProperty("app.mcp.output.dir", "D:/custom/mcp-output");

        SqliteDataDirectoryConfig.applyDesktopUrl(env, Map.of(
                "JINGJING_DATA_DIR", tempDir.resolve("elsewhere").toString()),
                tempDir.resolve("missing-legacy.db"));

        assertEquals("D:/custom/workspaces", env.getProperty("app.workspace.root"));
        assertEquals("D:/custom/mcp-output", env.getProperty("app.mcp.output.dir"));
    }

    @Test
    @DisplayName("desktop 覆盖 YAML 里的旧相对路径，且不创建这两个目录")
    void desktopOverridesConfiguredRelativePaths() {
        Path root = tempDir.resolve("JingJingAI");
        MockEnvironment env = sqliteEnv();
        env.setProperty("app.data.mode", "desktop");
        env.setProperty("app.workspace.root", "./workspaces");
        env.setProperty("app.mcp.output.dir", "./mcp-output");

        SqliteDataDirectoryConfig.prepare(env, Map.of("JINGJING_DATA_DIR", root.toString()),
                tempDir.resolve("missing-legacy.db"));

        assertEquals(root.resolve("workspaces").normalize().toString(), env.getProperty("app.workspace.root"));
        assertEquals(root.resolve("mcp-output").normalize().toString(), env.getProperty("app.mcp.output.dir"));
        assertTrue(Files.notExists(root.resolve("workspaces")));
        assertTrue(Files.notExists(root.resolve("mcp-output")));
    }

    private static MockEnvironment sqliteEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", ORIGINAL);
        return env;
    }
}
