package com.example.jingjingai2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDatabasePrepTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("目标不存在时复制，源文件保留且两边指纹相同")
    void copiesWhenTargetMissing() throws IOException {
        Path source = file("legacy/jingjing.db", "sqlite-bytes");
        Path target = tempDir.resolve("desktop/data/jingjing.db");
        String before = SqliteDatabasePrep.sha256(source);

        assertTrue(SqliteDatabasePrep.prepare(target, source));

        assertEquals(before, SqliteDatabasePrep.sha256(source));
        assertEquals(before, SqliteDatabasePrep.sha256(target));
        assertEquals("sqlite-bytes", Files.readString(source));
        assertTrue(Files.notExists(target.resolveSibling("jingjing.db.copying")));
    }

    @Test
    @DisplayName("目标已存在时不覆盖")
    void doesNotReplaceExistingTarget() throws IOException {
        Path source = file("legacy/jingjing.db", "new");
        Path target = file("desktop/data/jingjing.db", "already");

        assertFalse(SqliteDatabasePrep.prepare(target, source));

        assertEquals("already", Files.readString(target));
        assertEquals("new", Files.readString(source));
    }

    @Test
    @DisplayName("开发库不存在时不复制，也不创建空目标")
    void missingSourceDoesNothing() {
        Path target = tempDir.resolve("desktop/data/jingjing.db");

        assertFalse(SqliteDatabasePrep.prepare(target, tempDir.resolve("missing.db")));

        assertTrue(Files.notExists(target));
    }

    @Test
    @DisplayName("目标父级被文件占住时失败，不留下临时文件")
    void directoryFailureLeavesNoPartial() throws IOException {
        Path source = file("legacy/jingjing.db", "content");
        Path blocked = file("blocked", "not-a-directory");
        Path target = blocked.resolve("jingjing.db");

        assertThrows(IllegalStateException.class, () -> SqliteDatabasePrep.prepare(target, source));

        assertEquals("content", Files.readString(source));
        assertTrue(Files.notExists(tempDir.resolve("blocked.copying")));
        assertTrue(Files.notExists(target));
    }

    @Test
    @DisplayName("源旁边有 wal、shm 或 journal 时拒绝复制")
    void rejectsSidecars() throws IOException {
        Path source = file("legacy/jingjing.db", "main");
        Files.writeString(tempDir.resolve("legacy/jingjing.db-wal"), "wal");
        Path target = tempDir.resolve("desktop/data/jingjing.db");

        assertThrows(IllegalStateException.class, () -> SqliteDatabasePrep.prepare(target, source));

        assertEquals("main", Files.readString(source));
        assertTrue(Files.notExists(target));
        assertTrue(Files.notExists(tempDir.resolve("legacy/jingjing.db.copying")));
    }

    @Test
    @DisplayName("数据库路径固定在数据根的 data 子目录")
    void databaseLivesUnderData() {
        Path root = tempDir.resolve("JingJingAI");
        assertEquals(root.resolve("data").resolve("jingjing.db"), SqliteDatabasePrep.databaseFile(root));
        assertThrows(IllegalStateException.class, () -> SqliteDatabasePrep.databaseFile(Path.of("relative")));
    }

    private Path file(String relative, String content) throws IOException {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
