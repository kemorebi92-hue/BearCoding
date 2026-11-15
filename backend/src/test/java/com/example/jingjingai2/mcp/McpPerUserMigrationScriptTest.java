package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移脚本的静态检查。不连接数据库，也不解析 SQL。
 *
 * <p>授权删除只看从 {@code DELETE FROM mcp_user_policy} 到下一个分号之间的文字
 * （注释标记会先去掉，避免把危险语句藏在注释里）。能认出的危险形态：没有 WHERE、
 * 缺少 user_id 或 server_name、{@code NOT EXISTS}、{@code NOT IN}、再去查
 * mcp_server_config。看不懂字符串里的分号，也认不出 {@code OR 1=1} 这种绕过。
 */
class McpPerUserMigrationScriptTest {

    @Test
    @DisplayName("仓库里的迁移脚本没有无范围授权删除，唯一索引在同一条 ALTER 里替换")
    void scriptDoesNotBulkDeletePoliciesAndSwapsIndexAtomically() throws Exception {
        String raw = Files.readString(script(), StandardCharsets.UTF_8);
        assertNull(policyDeleteProblem(raw), policyDeleteProblem(raw));
        assertTrue(raw.contains("禁止批量删除"));
        assertTrue(raw.contains("YAML"));
        assertIndexSwapIsOneStatement(stripLineComments(raw));
    }

    @Test
    @DisplayName("无条件、只按服务名、NOT EXISTS、NOT IN 的授权删除都要被拒绝")
    void unsafePolicyDeletesAreRejected() {
        assertTrue(problem("DELETE FROM mcp_user_policy;").contains("WHERE"));
        assertTrue(problem("""
                DELETE FROM mcp_user_policy p
                 WHERE NOT EXISTS (SELECT 1 FROM mcp_server_config c
                                    WHERE c.name = p.server_name AND c.created_by = p.user_id);
                """).contains("配置表"));
        assertTrue(problem("DELETE FROM mcp_user_policy WHERE server_name = 'playwright';")
                .contains("user_id"));
        assertTrue(problem("DELETE FROM mcp_user_policy WHERE user_id = 7;")
                .contains("server_name"));
        assertTrue(problem("""
                DELETE FROM mcp_user_policy
                 WHERE server_name NOT IN (SELECT name FROM mcp_server_config);
                """).contains("配置表"));
    }

    @Test
    @DisplayName("写明 user_id 和 server_name 的人工清理不会被误判")
    void scopedManualDeleteIsAccepted() {
        assertNull(policyDeleteProblem("""
                -- 人工核对之后才可以执行：
                -- DELETE FROM mcp_user_policy
                --  WHERE user_id = 7 AND server_name = 'checked-name';
                """));
        assertNull(policyDeleteProblem("""
                DELETE FROM mcp_user_policy
                 WHERE user_id = <uid> AND server_name = '<已核对的服务名>';
                """));
    }

    private static String problem(String sql) {
        String found = policyDeleteProblem(sql);
        assertTrue(found != null, sql);
        return found;
    }

    /**
     * @return null 表示这段文字里的授权删除都带了 user_id 和 server_name，
     *         并且没有按配置表做批量匹配；否则返回原因
     */
    static String policyDeleteProblem(String sql) {
        String lowered = sql.toLowerCase(Locale.ROOT);
        String needle = "delete from mcp_user_policy";
        int from = 0;
        while (from < lowered.length()) {
            int at = indexOfDelete(lowered, needle, from);
            if (at < 0) return null;
            int end = lowered.indexOf(';', at);
            if (end < 0) end = lowered.length();
            String window = stripLineCommentMarkers(sql.substring(at, end))
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            if (window.contains("not exists") || window.contains("not in")
                    || window.contains("mcp_server_config")) {
                return "授权删除在按配置表批量匹配";
            }
            if (!window.contains(" where ")) return "授权删除缺少 WHERE";
            if (!window.contains("user_id")) return "授权删除没有 user_id";
            if (!window.contains("server_name")) return "授权删除没有 server_name";
            from = end + 1;
        }
        return null;
    }

    /** 忽略空白差异，例如 DELETE 和 FROM 之间换行。 */
    private static int indexOfDelete(String lowered, String needle, int from) {
        String compactNeedle = needle.replace(" ", "");
        for (int i = from; i < lowered.length(); i++) {
            if (lowered.charAt(i) != 'd' && lowered.charAt(i) != 'D') continue;
            StringBuilder compact = new StringBuilder();
            int last = i;
            for (int j = i; j < lowered.length() && compact.length() < compactNeedle.length(); j++) {
                char c = lowered.charAt(j);
                if (Character.isWhitespace(c)) continue;
                compact.append(c);
                last = j;
            }
            if (compact.toString().equals(compactNeedle)) return i;
            if (last == i) continue;
        }
        return -1;
    }

    private static void assertIndexSwapIsOneStatement(String executable) {
        boolean sawCombined = false;
        for (String stmt : executable.split(";")) {
            String lower = stmt.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (lower.isEmpty()) continue;
            boolean drops = lower.contains("drop index uk_name");
            boolean adds = lower.contains("add unique key uk_owner_name");
            if (!drops && !adds) continue;
            assertTrue(drops && adds, "uk_name 与 uk_owner_name 必须出现在同一条语句里: " + lower);
            assertTrue(lower.startsWith("alter table mcp_server_config"), lower);
            sawCombined = true;
        }
        assertTrue(sawCombined, "缺少合并后的索引替换语句");
    }

    private static String stripLineComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder();
        for (String line : noBlock.split("\\R", -1)) {
            int mark = line.indexOf("--");
            out.append(mark < 0 ? line : line.substring(0, mark)).append('\n');
        }
        return out.toString();
    }

    /** 去掉每行行首的 SQL 注释标记，保留文字，方便检查写在注释里的语句。 */
    private static String stripLineCommentMarkers(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) trimmed = trimmed.substring(2);
            out.append(trimmed).append('\n');
        }
        return out.toString();
    }

    /** 从当前工作目录往上找，兼容在仓库根目录或子目录里跑测试。 */
    private static Path script() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(Path.of("docs", "sql", "2026-10-23-mcp-per-user-services.sql"));
            if (Files.isRegularFile(candidate)) return candidate;
        }
        Assumptions.assumeTrue(false, "MySQL 迁移脚本不进入 V3");
        return null;
    }
}
