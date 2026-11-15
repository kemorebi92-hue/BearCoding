package com.example.jingjingai2;

import com.example.jingjingai2.sandbox.CommandPathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandPathValidator 的 allowOutside 重载：只放开工作区外路径范围，其余检查不变。
 */
class CommandPathValidatorFullAccessTest {

    private Path root;
    private Path outside;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("cpv-ws");
        outside = Files.createTempDirectory("cpv-out");
        Files.writeString(outside.resolve("secret.txt"), "x");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        delete(root);
        delete(outside);
    }

    @Test
    @DisplayName("默认与 allowOutside=false 都拒绝工作区外路径")
    void outsideDeniedByDefault() {
        String cmd = "type " + outside.resolve("secret.txt");
        assertFalse(CommandPathValidator.validate(cmd, root).allowed());
        assertFalse(CommandPathValidator.validate(cmd, root, false).allowed());
    }

    @Test
    @DisplayName("allowOutside=true 允许工作区外普通路径")
    void outsideAllowedForFullAccess() {
        String cmd = "type " + outside.resolve("secret.txt");
        assertTrue(CommandPathValidator.validate(cmd, root, true).allowed());
    }

    @Test
    @DisplayName("allowOutside=true 仍拒绝 cd / pushd / popd")
    void directoryChangeStillDenied() {
        for (String cmd : new String[]{"cd ..", "chdir x", "pushd C:\\", "popd"}) {
            assertFalse(CommandPathValidator.validate(cmd, root, true).allowed(), cmd);
        }
    }

    @Test
    @DisplayName("allowOutside=true 仍拒绝驱动器相对路径和非法冒号")
    void hardChecksStillApply() {
        assertFalse(CommandPathValidator.validate("type D:foo", root, true).allowed());
        // 盘符开头的非法冒号（NTFS 备用数据流形式）仍被拒绝
        assertFalse(CommandPathValidator.validate("type C:\\file.txt:stream", root, true).allowed());
    }

    @Test
    @DisplayName("工作区内路径在两种模式下都允许")
    void insideAllowedAlways() {
        String cmd = "dir src";
        assertTrue(CommandPathValidator.validate(cmd, root).allowed());
        assertTrue(CommandPathValidator.validate(cmd, root, true).allowed());
    }

    private static void delete(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (Exception ignored) {}
            });
        }
    }
}
