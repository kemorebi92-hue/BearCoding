package com.example.jingjingai2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePathsTest {

    @Test
    @DisplayName("空串 / 空白拒绝")
    void blankRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.normalize("   "));
    }

    @Test
    @DisplayName("去掉末尾分隔符")
    void trailingSeparatorStripped() {
        Path tmp = Paths.get(".").toAbsolutePath().normalize();
        String withSlash = tmp + (WorkspacePaths.isWindows() ? "\\" : "/");
        String a = WorkspacePaths.normalize(tmp.toString());
        String b = WorkspacePaths.normalize(withSlash);
        assertEquals(a, b);
        assertFalse(a.endsWith("\\") || a.endsWith("/"));
    }

    @Test
    @DisplayName("Windows 下大小写与斜杠写法归一到同一键")
    void windowsCaseAndSlashCollapse() {
        if (!WorkspacePaths.isWindows()) return;
        Path tmp = Paths.get(".").toAbsolutePath().normalize();
        String mixed = tmp.toString().replace('\\', '/');
        String upper = tmp.toString().toUpperCase();
        assertEquals(WorkspacePaths.normalize(tmp.toString()), WorkspacePaths.normalize(mixed));
        assertEquals(WorkspacePaths.normalize(tmp.toString()), WorkspacePaths.normalize(upper));
    }

    @Test
    @DisplayName("显示名取路径最后一段")
    void displayNameIsLastSegment() {
        String n = WorkspacePaths.normalize(Paths.get(".").toAbsolutePath().toString());
        assertEquals(Paths.get(n).getFileName().toString(), WorkspacePaths.displayName(n));
    }
}
