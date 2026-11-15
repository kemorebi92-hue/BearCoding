package com.example.jingjingai2.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotalScopeBothPathsTest {

    @Test
    @DisplayName("非流式和流式在 total 口径下都先 assemble，不先按历史预算压缩")
    void bothEntryPointsSkipHistoryCompactionWhenTotal() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/example/jingjingai2/controller/ChatController.java"));
        int assemble = count(src, "contextCompactor.assemble(");
        int build = count(src, "contextCompactor.buildMessages(");
        assertEquals(2, assemble, "非流式和流式各应有一处 assemble");
        assertEquals(2, build, "非流式和流式各应有一处 buildMessages");
        int from = 0;
        int guarded = 0;
        while (true) {
            int at = src.indexOf("contextCompactor.buildMessages(", from);
            if (at < 0) break;
            String before = src.substring(Math.max(0, at - 180), at);
            assertTrue(before.contains("totalScope()"), "buildMessages 必须在 totalScope() 的另一支上: " + before);
            guarded++;
            from = at + 1;
        }
        assertEquals(2, guarded);
    }

    private static int count(String src, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = src.indexOf(needle, from);
            if (at < 0) return n;
            n++;
            from = at + needle.length();
        }
    }
}
