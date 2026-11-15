package com.example.jingjingai2;

import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.utils.FileTool;
import org.junit.jupiter.api.AfterEach;
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
 * 代码检索工具测试：globFile（按名字找文件）与 grepFile（按内容定位行）。
 *
 * 用一个临时目录模拟"多文件小项目"，并把 node_modules / target 也造出来，
 * 用于验证：递归通配、行号定位、后缀过滤、忽略目录、越界拦截。
 */
class FileSearchToolTest {

    private Path root;
    private FileTool fileTool;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("fs-tool-test");
        // 真实源文件（含待定位的标记）
        write("src/main/java/demo/Alpha.java", "public class Alpha {\n    // MARKER_TOKEN here\n    int x = 1;\n}\n");
        write("src/main/java/demo/Beta.java", "public class Beta {\n    String tag = \"UNIQUE_BETA_STRING\";\n}\n");
        write("src/test/java/demo/AlphaTest.java", "class AlphaTest {\n    // MARKER_TOKEN\n}\n");
        write("README.md", "# demo\nMARKER_TOKEN in readme\n");
        write("config/app.yml", "app:\n  name: demo\n");
        // 应被忽略的目录
        write("node_modules/pkg/index.js", "// MARKER_TOKEN generated\n");
        write("target/classes/Alpha.class", "MARKER_TOKEN\n");
        fileTool = new FileTool(new PathSandbox(root.toString()));
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

    // ==================== globFile ====================

    @Test
    @DisplayName("glob: **/*.java 递归匹配全部 java 文件")
    void globRecursiveJava() {
        String r = fileTool.globFile("**/*.java", null, null);
        assertTrue(r.contains("src/main/java/demo/Alpha.java"), r);
        assertTrue(r.contains("src/main/java/demo/Beta.java"), r);
        assertTrue(r.contains("src/test/java/demo/AlphaTest.java"), r);
        assertTrue(r.contains("找到 3 个文件"), r);
    }

    @Test
    @DisplayName("glob: 根目录单层模式 *.md")
    void globSingleLevel() {
        String r = fileTool.globFile("*.md", null, null);
        assertTrue(r.contains("README.md"), r);
        assertFalse(r.contains("Alpha.java"), r);
    }

    @Test
    @DisplayName("glob: 多模式逗号分隔")
    void globMultiPattern() {
        String r = fileTool.globFile("**/*.java, **/*.md", null, null);
        assertTrue(r.contains("Alpha.java"), r);
        assertTrue(r.contains("README.md"), r);
        assertTrue(r.contains("找到 4 个文件"), r);
    }

    @Test
    @DisplayName("glob: 默认跳过 node_modules / target")
    void globSkipsIgnoredDirs() {
        String r = fileTool.globFile("**/*.js", null, null);
        assertFalse(r.contains("node_modules"), r);
        assertTrue(r.contains("未找到匹配的文件"), r);

        String r2 = fileTool.globFile("**/*.class", null, null);
        assertFalse(r2.contains("target"), r2);
    }

    @Test
    @DisplayName("glob: 空 pattern 报错")
    void globEmptyPattern() {
        assertTrue(fileTool.globFile("", null, null).contains("搜索失败"));
        assertTrue(fileTool.globFile(null, null, null).contains("搜索失败"));
    }

    @Test
    @DisplayName("glob: 越界路径被拦截")
    void globBlocksTraversal() {
        String r = fileTool.globFile("**/*.java", "../outside", null);
        assertTrue(r.contains("越界"), r);
    }

    @Test
    @DisplayName("glob: 不存在的路径报错")
    void globMissingPath() {
        String r = fileTool.globFile("**/*.java", "no/such/dir", null);
        assertTrue(r.contains("路径不存在"), r);
    }

    // ==================== grepFile ====================

    @Test
    @DisplayName("grep: 返回文件路径 + 行号，并跳过忽略目录")
    void grepReturnsPathAndLine() {
        String r = fileTool.grepFile("MARKER_TOKEN", null, null, null, null, null);
        assertTrue(r.contains("src/main/java/demo/Alpha.java"), r);
        assertTrue(r.contains("2: "), r);                       // Alpha.java 第 2 行
        assertTrue(r.contains("README.md"), r);
        assertFalse(r.contains("node_modules"), r);
        assertFalse(r.contains("target/"), r);
        assertTrue(r.contains("命中 3 个文件"), r);
    }

    @Test
    @DisplayName("grep: 精确定位唯一字符串（只命中一个文件）")
    void grepPinpointsSingleFile() {
        String r = fileTool.grepFile("UNIQUE_BETA_STRING", null, null, null, null, null);
        assertTrue(r.contains("src/main/java/demo/Beta.java"), r);
        assertTrue(r.contains("2: "), r);
        assertFalse(r.contains("Alpha.java"), r);
        assertTrue(r.contains("共 1 处匹配，命中 1 个文件"), r);
    }

    @Test
    @DisplayName("grep: include 后缀过滤只搜指定类型")
    void grepIncludeFilter() {
        String r = fileTool.grepFile("MARKER_TOKEN", null, "java", null, null, null);
        assertTrue(r.contains("Alpha.java"), r);
        assertFalse(r.contains("README.md"), r);
        assertTrue(r.contains("命中 2 个文件"), r);
    }

    @Test
    @DisplayName("grep: 默认不区分大小写")
    void grepCaseInsensitiveByDefault() {
        String r = fileTool.grepFile("marker_token", null, null, null, null, null);
        assertTrue(r.contains("Alpha.java"), r);
    }

    @Test
    @DisplayName("grep: 区分大小写时不命中")
    void grepCaseSensitive() {
        String r = fileTool.grepFile("marker_token", null, null, true, null, null);
        assertTrue(r.contains("未找到匹配的内容"), r);
    }

    @Test
    @DisplayName("grep: 上下文行前缀为 |")
    void grepContextLines() {
        String r = fileTool.grepFile("MARKER_TOKEN", null, "java", null, 2, null);
        assertTrue(r.contains("| "), r);   // 上下文行
        assertTrue(r.contains(": "), r);   // 匹配行
    }

    @Test
    @DisplayName("grep: 空 pattern / 不存在路径 / 越界 均报错")
    void grepBadInputs() {
        assertTrue(fileTool.grepFile("", null, null, null, null, null).contains("搜索失败"));
        assertTrue(fileTool.grepFile("x", "no/such/file.txt", null, null, null, null).contains("路径不存在"));
        assertTrue(fileTool.grepFile("x", "../outside.txt", null, null, null, null).contains("越界"));
    }

    @Test
    @DisplayName("grep: 未命中时返回明确提示")
    void grepNoMatch() {
        String r = fileTool.grepFile("ZZZ_NOT_EXIST_ZZZ", null, null, null, null, null);
        assertTrue(r.contains("未找到匹配的内容"), r);
    }

    @Test
    @DisplayName("grep: 可只搜单个文件")
    void grepSingleFile() {
        String r = fileTool.grepFile("MARKER_TOKEN", "src/main/java/demo/Alpha.java", null, null, null, null);
        assertTrue(r.contains("Alpha.java"), r);
        assertFalse(r.contains("AlphaTest.java"), r);
    }

    @Test
    @DisplayName("glob: 输出含文件数与总字节规模提示")
    void globIncludesScaleNote() {
        String r = fileTool.globFile("**/*.java", null, null);
        assertTrue(r.contains("个文件"), r);
        assertTrue(r.contains("字节"), r);
        assertTrue(r.contains("规模提示"), r);
    }

    @Test
    @DisplayName("listDirectory: JSON 含递归文件数/字节与 scaleNote")
    void listDirectoryIncludesRecursiveStats() throws Exception {
        String r = fileTool.listDirectory(null);
        assertTrue(r.contains("totalFilesRecursive"), r);
        assertTrue(r.contains("totalBytesRecursive"), r);
        assertTrue(r.contains("scaleNote"), r);
    }

    @Test
    @DisplayName("grep: 未超阈值不附命中较多提示；超文件阈值则附提示")
    void grepWideHitTipOnlyWhenOverThreshold() {
        com.example.jingjingai2.agent.SubagentProperties props =
                new com.example.jingjingai2.agent.SubagentProperties();
        props.setGrepHitLineThreshold(1000);
        props.setGrepHitFileThreshold(1000);
        fileTool.setSubagentProperties(props);
        String small = fileTool.grepFile("MARKER_TOKEN", null, "java", null, null, 50);
        assertFalse(small.contains(FileTool.GREP_WIDE_HIT_TIP), small);

        props.setGrepHitLineThreshold(1);
        props.setGrepHitFileThreshold(1);
        fileTool.setSubagentProperties(props);
        String wide = fileTool.grepFile("MARKER_TOKEN", null, null, null, null, 5);
        assertTrue(wide.contains("共 "), wide);
        assertTrue(wide.contains(FileTool.GREP_WIDE_HIT_TIP), wide);
    }
}
