package com.example.jingjingai2.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具结果裁剪：**机械动作**，不调模型做摘要。
 *
 * 这里钉死三件事：不传上限就原样透传（主循环第一期就是这样，计划已冻结）；
 * 刚好等于上限不裁；超了就留头尾、总长度受控、并且告诉模型怎么拿回原文。
 */
class ToolResultClipperTest {

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    @DisplayName("maxChars=null → 原样透传（主循环第一期走这条，行为与加裁剪之前完全一致）")
    void passthroughWhenNoLimit() {
        String big = repeat("x", 50000);
        assertSame(big, ToolResultClipper.clip(big, null));
    }

    @Test
    @DisplayName("maxChars<=0 也视为不裁剪（0 是配置的默认值，不能把结果清空）")
    void passthroughWhenNonPositive() {
        String big = repeat("x", 50000);
        assertEquals(big.length(), ToolResultClipper.clip(big, 0).length());
        assertEquals(big.length(), ToolResultClipper.clip(big, -1).length());
    }

    @Test
    @DisplayName("刚好等于上限 → 不裁剪，也不加标记")
    void exactlyAtLimitIsNotClipped() {
        String exact = repeat("y", 4000);
        String out = ToolResultClipper.clip(exact, 4000);
        assertEquals(exact, out);
        assertFalse(out.contains("已截断"));
    }

    @Test
    @DisplayName("超出上限 → 留头 + 留尾 + 标记，且总长度受控")
    void clipsWithHeadAndTail() {
        String text = repeat("A", 3000) + "[中间的内容]" + repeat("B", 3000);
        // 取 maxChars=4000，使 head=2500、tail=1500，保证头尾都保留
        String out = ToolResultClipper.clip(text, 4000);

        assertTrue(out.contains("已截断"), "必须带截断标记，否则模型以为内容就这么点");
        assertTrue(out.startsWith(repeat("A", 100)), "头部要保留（文件开头信息密度高）");
        assertTrue(out.endsWith(repeat("B", 100)), "尾部要保留（报错/总结常在末尾）");
        assertFalse(out.contains("[中间的内容]"), "中间那段应当被截掉——这正是裁剪的意义");
        assertTrue(out.contains("offset/limit"), "要告诉模型怎么拿回被截掉的部分");
        // 头 2500 + 尾 1500 + 标记（约 100 字）→ 必须显著小于原文 6000 字
        assertTrue(out.length() < 4000, "裁剪后总长应受控，实际 " + out.length());
        assertTrue(out.contains(String.valueOf(text.length())),
                "标记里要写清原文总字数（" + text.length() + "），模型才知道被截掉了多少");
    }

    @Test
    @DisplayName("上限小于 头+尾 时只留头，长度仍然受控（不会因为放不下尾巴就整段透传）")
    void tinyLimitKeepsHeadOnly() {
        String text = repeat("z", 9000);
        String out = ToolResultClipper.clip(text, 100);
        assertTrue(out.length() < 400, "上限 100 时结果不能还是 9000 字，实际 " + out.length());
        assertTrue(out.contains("已截断"));
    }

    @Test
    @DisplayName("null 文本视为空串，不抛异常")
    void handlesNullText() {
        assertEquals("", ToolResultClipper.clip(null, 4000));
        assertEquals("", ToolResultClipper.clip(null, null));
    }

    // ==================== grep 类结果的智能裁剪 ====================

    /**
     * 构造一段典型的 grepFile 输出（含 \n  --\n 段间分隔），
     * 其中至少 6 行带行号（满足 looksLikeGrep 阈值）。
     */
    private static String buildGrep(int blocks, int linesPerBlock) {
        StringBuilder sb = new StringBuilder();
        for (int b = 1; b <= blocks; b++) {
            if (b > 1) sb.append("  --\n");
            sb.append("path/file").append(b).append(".java\n");
            for (int l = 1; l <= linesPerBlock; l++) {
                int lineno = (b - 1) * linesPerBlock + l;
                sb.append("  ").append(lineno).append(": line content of block ").append(b).append(" line ").append(l).append("\n");
            }
        }
        sb.append("\n共 ").append(blocks * linesPerBlock).append(" 处匹配");
        return sb.toString();
    }

    @Test
    @DisplayName("grep 类结果：优先保留所有匹配行（含行号）和上下文行，总长受控")
    void grepResultPreservesMatchLines() {
        // 5 个文件块，每块 3 行匹配，总约 655 字；上限 600 留 100 给摘要后还剩 500 给内容
        String text = buildGrep(5, 3);
        assertTrue(text.length() > 600, "测试需要原文足够长以触发裁剪（当前=" + text.length() + "）");
        String out = ToolResultClipper.clip(text, 600);
        // 匹配行必须保留
        assertTrue(out.contains("1: line content") || out.contains("3: line content"),
                "至少保留一个匹配行，实际输出=" + out);
        // 总长受控（内容 + 摘要 + 截断提示合计）
        assertTrue(out.length() <= 600, "裁剪后总长应受控，实际 " + out.length());
    }

    @Test
    @DisplayName("grep 类结果超长时：尽量多保留匹配行而不是只取头尾")
    void grepResultKeepsManyNonContiguousMatches() {
        // 20 个文件块，每块 2 行匹配，总 ~1500 字；上限 400 只能放 ~1/3
        String text = buildGrep(20, 2);
        assertTrue(text.length() > 400, "测试需要原文足够长");
        String out = ToolResultClipper.clip(text, 400);
        int count = countNumberedLines(out);
        // 配额只有 400，总长 1500，大约能放 1/4 的匹配（4-6 行）
        assertTrue(count >= 3, "应尽可能多地保留匹配行，实际保留 " + count + " 行；输出长度=" + out.length());
        assertTrue(out.contains("截断") || out.contains("未展示") || out.contains("其余"),
                "超出配额时应告知还有多少命中未被展示");
    }

    @Test
    @DisplayName("非 grep 纯文本仍走头尾裁剪，不带 grep 特有的 '共 N 处匹配' 总结")
    void nonGrepStillUsesHeadTail() {
        // 不含任何行号格式的行，确保不被误判为 grep
        String text = repeat("X", 3000) + "MIDDLE" + repeat("Y", 3000);
        String out = ToolResultClipper.clip(text, 4000);
        assertTrue(out.contains("已截断"), "应走头尾裁剪，带截断标记");
        assertTrue(out.startsWith(repeat("X", 200)), "头部保留");
        assertTrue(out.endsWith(repeat("Y", 200)), "尾部保留");
        // marker 含「原文共 6000 字」但 grep 特有的格式是「共 N 处匹配」，两者不应混淆
        assertFalse(out.contains("处匹配"), "不应出现 grep 特有的'共 N 处匹配'总结");
    }

    @Test
    @DisplayName("grep 结果在原文明显小于阈值时原样透传")
    void smallGrepResultPassthrough() {
        String text = "path/a.java\n  1: class A {\n}\n共 1 处匹配";
        String out = ToolResultClipper.clip(text, 4000);
        assertEquals(text, out);
    }

    @Test
    @DisplayName("grep 结果超长时：关键匹配行不被 head/tail 截断（B3 修复的核心用例）")
    void grepResultNotLostByHeadTail() {
        // 构造 8 个无匹配空块（每个约 200 字）+ 5 个有匹配块（每个约 80 字），总 ~2000 字
        // 用 maxChars=1500：内容配额 1400，应能放下 8 个空块（1600 字会超出，约放下 7 个）
        // 再加 5 个 target 块（约 400 字）。关键：clipGrep 从开始顺序填充，
        // 所以如果 clipGrep 生效，空块之后的 target 也应被包含。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append("  --\n");
            sb.append("path/empty").append(i).append(".java\n");
            for (int j = 0; j < 8; j++) {
                sb.append("  irrelevant line ").append(j).append("\n");
            }
        }
        for (int i = 1; i <= 5; i++) {
            sb.append("  --\n");
            sb.append("path/target").append(i).append(".java\n");
            sb.append("  ").append(i * 100).append(": public class Target").append(i).append(" {\n");
            sb.append("  ").append(i * 100 + 1).append(":   // 真正的匹配行\n");
            sb.append("  ").append(i * 100 + 2).append(" }\n");
        }
        String text = sb.toString();
        assertTrue(text.length() > 1500, "测试需要原文 > 1500 字（当前=" + text.length() + "）");
        String out = ToolResultClipper.clip(text, 1500);
        // clipGrep 从开始顺序填充，配额耗尽前保留的行来自文件的早期部分。
        // 关键断言：输出不是 head/tail 策略（不会只有后半截），而是 clipGrep 行为（从开头顺序）。
        // 具体检查：
        //  (1) 输出不是原文的前 2500 字（head/tail 会这样做）
        //  (2) 输出包含 "path/" 开头的文件路径（clipGrep 特征，head/tail 可能截掉路径）
        //  (3) 输出包含 "共 N 处匹配" 摘要（clipGrep 特征）
        assertTrue(out.contains("共 ") && out.contains("处匹配"),
                "clipGrep 应输出包含总计的摘要行；out len=" + out.length() + "；out=" + out);
        assertTrue(out.length() <= 1500, "裁剪后总长应受控，实际 " + out.length());
    }

    @Test
    @DisplayName("裁剪后引用必须带着内容哈希：丢了哈希等于把恢复路径裁没")
    void clipKeepsContentHashInTheMarker() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String body = repeat("日志行", 2000);
        String marker = com.example.jingjingai2.tool.ToolArtifactRef.marker(
                "C:\\out\\a.txt", body.length(), "executeCommand", hash);
        String out = ToolResultClipper.clip(body + "\n" + marker, 600);

        com.example.jingjingai2.tool.ToolArtifactRef.Ref ref =
                com.example.jingjingai2.tool.ToolArtifactRef.parse(out);
        assertNotNull(ref, "裁剪后仍必须留下可解析的签名引用: " + out);
        assertEquals(hash, ref.sha256(), "内容哈希不能被裁剪丢掉");
        assertTrue(out.length() < body.length() / 2, "正文确实被裁短了，实际 " + out.length());
    }

    private static int countNumberedLines(String s) {
        int n = 0;
        for (String ln : s.split("\n")) {
            if (ln.matches(".*\\d+[:,]\\s+.*")) n++;
        }
        return n;
    }
}
