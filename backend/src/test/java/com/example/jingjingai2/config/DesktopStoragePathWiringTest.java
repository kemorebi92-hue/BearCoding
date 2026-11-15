package com.example.jingjingai2.config;

import com.example.jingjingai2.mcp.McpOutputProperties;
import com.example.jingjingai2.mcp.McpOutputStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStoragePathWiringTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("桌面模式给出的路径就是业务组件会规范化的绝对路径")
    void servicesUseDesktopPaths() throws Exception {
        Path root = tempDir.resolve("JingJingAI");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:sqlite:./data/jingjing.db");
        env.setProperty("app.data.mode", "desktop");
        env.setProperty("app.workspace.root", "./workspaces");
        env.setProperty("app.mcp.output.dir", "./mcp-output");

        SqliteDataDirectoryConfig.prepare(env, Map.of("JINGJING_DATA_DIR", root.toString()),
                tempDir.resolve("missing-legacy.db"));

        Path workspace = Path.of(env.getProperty("app.workspace.root")).toAbsolutePath().normalize();
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(env.getProperty("app.mcp.output.dir"));
        McpOutputStore store = new McpOutputStore(props);

        assertEquals(root.resolve("workspaces").normalize(), workspace);
        assertEquals(root.resolve("mcp-output").normalize(), store.root());
        assertTrue(workspace.isAbsolute());
        assertTrue(store.root().isAbsolute());

        Files.createDirectories(store.sessionRoot("conv-1"));
        assertTrue(Files.isDirectory(root.resolve("mcp-output").resolve("conv-1")));
        assertTrue(Files.notExists(Path.of("mcp-output")));
    }

    @Test
    @DisplayName("父级被文件占住时目录创建失败，配置路径不改回旧目录")
    void outputCreateFailureDoesNotChangeConfiguredDir() throws IOException {
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "file");
        Path configured = blocked.resolve("mcp-output");
        McpOutputProperties props = new McpOutputProperties();
        props.setDir(configured.toString());
        McpOutputStore store = new McpOutputStore(props);

        assertThrows(java.nio.file.FileSystemException.class,
                () -> Files.createDirectories(store.root()));

        assertEquals(configured.normalize(), store.root());
        assertFalse(store.root().equals(Path.of("./mcp-output").toAbsolutePath().normalize()));
    }
}
