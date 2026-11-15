package com.example.jingjingai2.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalFixtureLineEndingTest {

    @Test
    @DisplayName("评测素材必须是 LF，检查 .gitattributes")
    void fixturesAreLf() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String dir : List.of("docs/eval/fixtures", "docs/eval/tasks")) {
            try (Stream<Path> walk = Files.walk(Path.of(dir))) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    if (Files.readAllBytes(path).length > 0
                            && new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.ISO_8859_1).indexOf('\r') >= 0) {
                        bad.add(path.toString());
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "评测素材必须是 LF，检查 .gitattributes: " + bad);
    }
}
