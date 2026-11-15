package com.example.jingjingai2;

import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.service.EditAuditService;
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
 * 结构化编辑协议测试：editFile（唯一匹配语义）与 createFile（仅新建）。
 *
 * 重点验证"不猜测"这一设计：oldStr 匹配 0 处或多处时都必须拒绝修改，
 * 并把"匹配到几处、分别在哪几行"如实返回，把决策权交还给模型。
 */
class FileEditToolTest {

    private Path root;
    private Path auditLog;
    private FileTool fileTool;

    private static final String SERVICE =
            "public class Service {\n" +
            "    int value = 1;\n" +
            "    int step() {\n" +
            "        return value + 1;\n" +
            "    }\n" +
            "}\n";

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("edit-tool-test");
        // 审计日志放在工作区之外，避免污染被测工作区
        auditLog = Files.createTempFile("edit-audit-", ".log");
        fileTool = new FileTool(new PathSandbox(root.toString()),
                new EditAuditService(auditLog.toString()));
        write("demo/Service.java", SERVICE);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
        try { Files.deleteIfExists(auditLog); } catch (Exception ignored) {}
    }

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private String read(String rel) throws Exception {
        return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
    }

    // ==================== editFile：唯一匹配 ====================

    @Test
    @DisplayName("edit: 唯一匹配 → 写入成功并返回 diff")
    void editUniqueMatchWritesAndReturnsDiff() throws Exception {
        String r = fileTool.editFile("demo/Service.java", "int value = 1;", "int value = 42;");
        assertTrue(r.contains("✅"), r);
        assertTrue(r.contains("--- a/demo/Service.java"), r);
        assertTrue(r.contains("+++ b/demo/Service.java"), r);
        assertTrue(r.contains("-    int value = 1;"), r);
        assertTrue(r.contains("+    int value = 42;"), r);
        assertTrue(read("demo/Service.java").contains("int value = 42;"));
        assertFalse(read("demo/Service.java").contains("int value = 1;"));
    }

    @Test
    @DisplayName("edit: 0 处匹配 → 报错且文件不变")
    void editZeroMatchRejects() throws Exception {
        String r = fileTool.editFile("demo/Service.java", "int value = 999;", "int value = 0;");
        assertTrue(r.contains("0 处"), r);
        assertTrue(r.contains("未做任何修改"), r);
        assertEquals(SERVICE, read("demo/Service.java"));
    }

    @Test
    @DisplayName("edit: 多处匹配 → 报错并给出行号，文件不变（不猜测应用哪一处）")
    void editMultipleMatchRejectsWithLineNumbers() throws Exception {
        String r = fileTool.editFile("demo/Service.java", "int ", "long ");
        assertTrue(r.contains("2 处"), r);
        assertTrue(r.contains("第 2 行"), r);
        assertTrue(r.contains("第 3 行"), r);
        assertEquals(SERVICE, read("demo/Service.java"));
    }

    @Test
    @DisplayName("edit: newStr 为空 → 删除该片段")
    void editEmptyNewStrDeletes() throws Exception {
        String r = fileTool.editFile("demo/Service.java", "    int value = 1;\n", "");
        assertTrue(r.contains("删除片段"), r);
        assertFalse(read("demo/Service.java").contains("int value = 1;"));
    }

    @Test
    @DisplayName("edit: 空 oldStr / 目标是目录 → 明确报错")
    void editBadInputs() throws Exception {
        assertTrue(fileTool.editFile("demo/Service.java", "", "x").contains("oldStr 不能为空"));
        assertTrue(fileTool.editFile("demo", "a", "b").contains("目录"));
        assertTrue(fileTool.editFile("demo/None.java", "a", "b").contains("文件不存在"));
    }

    @Test
    @DisplayName("edit: 越界路径被沙箱拦截")
    void editBlocksTraversal() {
        String r = fileTool.editFile("../outside.txt", "a", "b");
        assertTrue(r.contains("越界"), r);
    }

    // ==================== createFile：仅新建 ====================

    @Test
    @DisplayName("create: 新建成功，返回 /dev/null 形式的 diff")
    void createNewFile() throws Exception {
        String r = fileTool.createFile("demo/New.java", "public class New {}\n");
        assertTrue(r.contains("✅"), r);
        assertTrue(r.contains("--- /dev/null"), r);
        assertEquals("public class New {}\n", read("demo/New.java"));
    }

    @Test
    @DisplayName("create: 文件已存在 → 报错且不覆盖")
    void createRefusesExisting() throws Exception {
        String r = fileTool.createFile("demo/Service.java", "overwritten!");
        assertTrue(r.contains("已存在"), r);
        assertEquals(SERVICE, read("demo/Service.java"));
    }

    @Test
    @DisplayName("create: 越界路径被沙箱拦截")
    void createBlocksTraversal() {
        assertTrue(fileTool.createFile("../outside.txt", "x").contains("越界"));
    }

    // ==================== 编辑审计 ====================

    @Test
    @DisplayName("audit: 实际写入后，审计日志留痕（含 [已应用] 与 diff）")
    void editWritesAuditLog() throws Exception {
        fileTool.editFile("demo/Service.java", "int value = 1;", "int value = 7;");
        String log = Files.readString(auditLog, StandardCharsets.UTF_8);
        assertTrue(log.contains("[已应用]"), log);
        assertTrue(log.contains("demo/Service.java"), log);
        assertTrue(log.contains("int value = 7;"), log);
    }

    @Test
    @DisplayName("audit: 被拒绝的编辑不留痕（因为没有实际发生修改）")
    void rejectedEditDoesNotWriteAudit() throws Exception {
        fileTool.editFile("demo/Service.java", "int value = 999;", "x");
        String log = Files.readString(auditLog, StandardCharsets.UTF_8);
        assertTrue(log.isEmpty(), "被拒绝的编辑不应写入审计日志: " + log);
    }

    @Test
    @DisplayName("audit: recent() 可取出记录，且能表达“待确认”状态（为人工审核预留）")
    void auditServiceSupportsPendingState() {
        EditAuditService svc = new EditAuditService(auditLog.toString());
        svc.record("a.java", false, "-old\n+new\n");
        assertEquals(1, svc.recent().size());
        assertFalse(svc.recent().get(0).applied());
        assertTrue(svc.recent().get(0).diff().contains("+new"));
    }
}
