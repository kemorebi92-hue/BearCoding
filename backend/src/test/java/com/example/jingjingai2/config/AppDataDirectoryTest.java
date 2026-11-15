package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("默认是 development，不强制数据根，也不创建目录")
    void developmentLeavesCurrentLayoutAlone() {
        AppDataProperties props = new AppDataProperties();
        assertEquals("development", props.getMode());
        assertEquals("", props.getRoot());

        AppDataProperties.Layout layout = props.resolve(Map.of(
                "LOCALAPPDATA", tempDir.resolve("Local").toString(),
                "JINGJING_DATA_DIR", tempDir.resolve("override").toString()));

        assertEquals("development", layout.mode());
        assertFalse(layout.enforcesRoot());
        assertThrows(IllegalStateException.class, layout::root);
        assertTrue(Files.notExists(tempDir.resolve("Local").resolve("BearCoding")));
        assertTrue(Files.notExists(tempDir.resolve("override")));
    }

    @Test
    @DisplayName("desktop 默认使用 LOCALAPPDATA/BearCoding 的绝对路径")
    void desktopUsesLocalAppData() {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");
        Path local = tempDir.resolve("AppData").resolve("Local");

        AppDataProperties.Layout layout = props.resolve(Map.of("LOCALAPPDATA", local.toString()));

        Path root = local.resolve("BearCoding").normalize();
        assertEquals(root, layout.root());
        assertTrue(layout.root().isAbsolute());
        assertEquals(root.resolve("data"), layout.data());
        assertEquals(root.resolve("workspaces"), layout.workspaces());
        assertEquals(root.resolve("mcp-output"), layout.mcpOutput());
        assertEquals(root.resolve("logs"), layout.logs());
        assertEquals(root.resolve("config"), layout.config());
        assertEquals(root.resolve("secrets"), layout.secrets());
        assertTrue(Files.notExists(root));
    }

    @Test
    @DisplayName("JINGJING_DATA_DIR 覆盖 LOCALAPPDATA，且优先于 app.data.root")
    void explicitDirWins() {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");
        props.setRoot(tempDir.resolve("from-property").toString());
        Path chosen = tempDir.resolve("from-env");

        AppDataProperties.Layout layout = props.resolve(Map.of(
                "JINGJING_DATA_DIR", chosen.toString(),
                "LOCALAPPDATA", tempDir.resolve("Local").toString()));

        assertEquals(chosen.normalize(), layout.root());
        assertTrue(Files.notExists(chosen));
        assertTrue(Files.notExists(tempDir.resolve("from-property")));
    }

    @Test
    @DisplayName("桌面模式拒绝相对路径，不用工作目录补全")
    void desktopRejectsRelativePath() {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");
        props.setRoot("data/jingjing");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.resolve(Map.of("LOCALAPPDATA", tempDir.toString())));

        assertTrue(ex.getMessage().contains("相对"));
    }

    @Test
    @DisplayName("环境变量本身是相对路径时也失败，不改去读 LOCALAPPDATA")
    void relativeEnvDoesNotFallThrough() {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");

        assertThrows(IllegalStateException.class, () -> props.resolve(Map.of(
                "JINGJING_DATA_DIR", "relative-dir",
                "LOCALAPPDATA", tempDir.toString())));
    }

    @Test
    @DisplayName("没有 LOCALAPPDATA 且没有显式目录时，桌面模式失败")
    void missingLocalAppDataFails() {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.resolve(Map.of()));

        assertTrue(ex.getMessage().contains("LOCALAPPDATA"));
        assertFalse(ex.getMessage().toLowerCase().contains("fallback"));
    }

    @Test
    @DisplayName("正斜杠会被规范成当前系统的绝对路径")
    void normalizesSeparators() throws IOException {
        AppDataProperties props = new AppDataProperties();
        props.setMode("desktop");
        String slashy = tempDir.toAbsolutePath().toString().replace('\\', '/') + "/JingJing/custom";

        Path root = props.resolve(Map.of("JINGJING_DATA_DIR", slashy)).root();

        assertTrue(root.isAbsolute());
        assertEquals(tempDir.resolve("JingJing").resolve("custom").normalize(), root);
        assertFalse(root.toString().contains("custom/"));
        assertTrue(Files.notExists(root));
    }
}
