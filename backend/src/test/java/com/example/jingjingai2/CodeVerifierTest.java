package com.example.jingjingai2;

import com.example.jingjingai2.service.CodeVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译校验器测试：验证「改完自动编译」这一环能如实判断通过/失败，
 * 并且不会把构建产物目录里的文件也算进来。
 *
 * 若本机没有 javac（例如只装了 JRE），相关用例会自动 skip 而不是失败。
 */
class CodeVerifierTest {

    private Path root;
    private final CodeVerifier verifier = new CodeVerifier();

    private static final String GOOD =
            "package demo;\n" +
            "public class Good {\n" +
            "    public int add(int a, int b) { return a + b; }\n" +
            "}\n";

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("verify-test");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("编译通过：合法源码返回 success")
    void compilesValidSource() throws Exception {
        write("src/main/java/demo/Good.java", GOOD);
        CodeVerifier.Result r = verifier.verify(root);
        Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
        assertTrue(r.success(), r.output());
        assertTrue(r.output().contains("编译通过"), r.output());
    }

    @Test
    @DisplayName("编译失败：非法源码返回 failure，且报错里带出错文件名")
    void reportsCompileFailure() throws Exception {
        write("src/main/java/demo/Bad.java", "package demo;\npublic class Bad { int x = ; }\n");
        CodeVerifier.Result r = verifier.verify(root);
        Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
        assertFalse(r.success());
        assertTrue(r.output().contains("编译失败"), r.output());
        assertTrue(r.output().contains("Bad.java"), r.output());
    }

    @Test
    @DisplayName("无 Java 源码：跳过校验（不干扰前端/脚本类项目）")
    void skipsWhenNoJavaFiles() throws Exception {
        write("README.md", "# 前端项目，没有 Java\n");
        CodeVerifier.Result r = verifier.verify(root);
        assertFalse(r.ran());
        assertTrue(r.success());
        assertTrue(r.output().contains("跳过"), r.output());
    }

    @Test
    @DisplayName("构建产物目录（target 等）里的坏文件不参与校验")
    void ignoresBuildOutputDirs() throws Exception {
        write("src/main/java/demo/Good.java", GOOD);
        write("target/classes/Broken.java", "class Broken { int x = ; }\n");
        write("node_modules/pkg/Junk.java", "class Junk { int y = ; }\n");

        CodeVerifier.Result r = verifier.verify(root);
        Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
        assertTrue(r.success(), "target / node_modules 下的坏文件不应影响校验: " + r.output());
    }

    @Test
    @DisplayName("工作区不存在：跳过而不是抛异常")
    void skipsWhenRootMissing() {
        CodeVerifier.Result r = verifier.verify(root.resolve("not-exists"));
        assertFalse(r.ran());
        assertTrue(r.success());
    }

    @Test
    @DisplayName("工作区路径含【空格】与【中文】时也能编译（argfile 转义/编码回归）")
    void handlesPathsWithSpacesAndChinese() throws Exception {
        Path tricky = Files.createTempDirectory("verify 中文 目录-");
        try {
            Path src = tricky.resolve("src/main/java/demo");
            Files.createDirectories(src);
            Files.writeString(src.resolve("Good.java"), GOOD, StandardCharsets.UTF_8);

            CodeVerifier.Result r = verifier.verify(tricky);
            Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
            assertTrue(r.success(), "含空格/中文的路径应当也能编译通过，实际输出：\n" + r.output());
        } finally {
            try (Stream<Path> s = Files.walk(tricky)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    @DisplayName("工作区路径含反斜杠转义陷阱（形如 a\\t 的目录名）也不会被 argfile 吃掉")
    void handlesBackslashEscapeTrap() throws Exception {
        // 目录名里放一个 "t"，配合上级路径形成 "…\t" 的形态，专治"反斜杠被当转义符"
        Path base = Files.createTempDirectory("verify-escape-");
        Path tricky = Files.createDirectories(base.resolve("t dir"));
        try {
            Path src = tricky.resolve("src/main/java/demo");
            Files.createDirectories(src);
            Files.writeString(src.resolve("Good.java"), GOOD, StandardCharsets.UTF_8);
            CodeVerifier.Result r = verifier.verify(tricky);
            Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
            assertTrue(r.success(), "路径中的反斜杠不应被 argfile 当作转义符，实际输出：\n" + r.output());
        } finally {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    @DisplayName("白名单外的同名冲突类（如 scripts/eval 夹具）不参与校验")
    void ignoresNameConflictOutsideWhitelist() throws Exception {
        write("src/main/java/demo/Main.java",
                "package demo;\npublic class Main {\n    public static void main(String[] args) {}\n}\n");
        write("scripts/eval/templates/Main.java",
                "package demo;\npublic class Main { int x = ; }\n");
        write("scripts/eval/templates2/Main.java",
                "package demo;\npublic class Main { int y = ; }\n");

        CodeVerifier.Result r = verifier.verify(root);
        Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
        assertTrue(r.success(), "scripts/eval 里故意同名的坏 Main.java 不应让校验失败: " + r.output());
        assertTrue(r.output().contains("编译通过"), r.output());
    }

    @Test
    @DisplayName("只有夹具、没有生产源码根：跳过而不是全树扫描去编")
    void skipsWhenOnlyFixtureJavaExists() throws Exception {
        write("scripts/eval/templates/Main.java", "package demo;\npublic class Main {}\n");
        CodeVerifier.Result r = verifier.verify(root);
        assertFalse(r.ran());
        assertTrue(r.success());
        assertTrue(r.output().contains("src/main/java"), r.output());
    }

    @Test
    @DisplayName("src/test/java 与 src/main/java 联编")
    void compilesTestSourcesTogetherWithMain() throws Exception {
        write("src/main/java/demo/Good.java", GOOD);
        write("src/test/java/demo/GoodTest.java",
                "package demo;\npublic class GoodTest {\n    public void ping() { new Good().add(1, 2); }\n}\n");
        CodeVerifier.Result r = verifier.verify(root);
        Assumptions.assumeTrue(r.ran(), "本机没有 javac，跳过该用例");
        assertTrue(r.success(), r.output());
        assertTrue(r.output().contains("2 个"), "联编应编到 main + test，实际：" + r.output());
    }

    @Test
    @DisplayName("isWriteTool：识别会改文件的工具，用于决定是否触发编译校验")
    void detectsWriteTools() {
        assertTrue(CodeVerifier.isWriteTool("editFile"));
        assertTrue(CodeVerifier.isWriteTool("edit_file"));
        assertTrue(CodeVerifier.isWriteTool("createFile"));
        assertTrue(CodeVerifier.isWriteTool("writeFile"));
        assertTrue(CodeVerifier.isWriteTool("appendFile"));
        assertTrue(CodeVerifier.isWriteTool("deletePath"));
        assertTrue(CodeVerifier.isWriteTool("movePath"));
        assertFalse(CodeVerifier.isWriteTool("readFile"));
        assertFalse(CodeVerifier.isWriteTool("grepFile"));
        assertFalse(CodeVerifier.isWriteTool("globFile"));
        assertFalse(CodeVerifier.isWriteTool("executeCommand"));
        assertFalse(CodeVerifier.isWriteTool(null));
    }
}
