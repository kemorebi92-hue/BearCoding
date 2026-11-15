package com.example.jingjingai2.agent;

import com.example.jingjingai2.tool.ToolArtifactRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果的**机械裁剪**（纯函数）。
 *
 * <p>为什么需要它：每个工具自己都有上限（readFile 2 万字、executeCommand 6 千字），
 * 但<strong>把结果追加进 messages 的那一步没有上限</strong> —— 一个"合法但仍然很大"的结果会被整段塞进窗口。
 * 主循环还能靠 ContextCompactor 压历史，子代理房间不读历史、也没有 Compactor，
 * 8 轮 × 每次 2 万字的 readFile 就能把隔离省下来的 token 又烧回去。
 *
 * <p>设计取舍：
 * <ul>
 *  <li><strong>按工具语义做差异化裁剪</strong>：
 *   <ul>
 *    <li>grep 类结果（文件匹配 + 行号 + 上下文行）：优先<strong>保留所有匹配行及其上下文行</strong>，
 *      压缩非匹配的空白行，并在结果末尾列出「被截断的文件/匹配总数」；
 *      这样模型拿到的是"完整命中图"，不会因为截断而误以为内容就这么多。</li>
 *    <li>readFile / listDirectory / globFile 等连续性文本：保持原来的
 *      <strong>头 + 尾 + 标记</strong>策略。这类结果中间通常是连贯的，头尾信息密度高。</li>
 *   </ul>
 *  <li>不调模型做摘要。多一次 LLM 调用 = 多一个失败点、多一份延迟、
 *    还会把"子代理的结论"变成"又一次不可控的生成"。裁剪只做机械动作。</li>
 *  <li>标记里<strong>明确告诉模型怎么拿回完整内容</strong>（offset/limit / 换关键词重搜），
 *    否则它会以为内容就这么多 —— 这是假阴性之外另一个常见误导来源。</li>
 *  <li>{@code maxChars == null} 表示"这一层不限"。V1 主循环传 null；
     *      {@code app.subagent.dispatch-v2=true} 时主循环传入 {@code main-tool-result-chars}。</li>
 * </ul>
 */
public final class ToolResultClipper {

    /** 头部保留字数（连续型结果） */
    public static final int HEAD_CHARS = 2500;
    /** 尾部保留字数（连续型结果） */
    public static final int TAIL_CHARS = 1000;

    /** 连续空白行的最大保留数，超出后开始压缩 */
    private static final int MAX_BLANK_RUN = 3;

    private ToolResultClipper() {}

    // ==================== 公开入口 ====================

    /**
     * 裁剪入口。根据内容形态自动选择策略：
     * <ul>
     *   <li>{@link #looksLikeGrep} → {@link #clipGrep}</li>
     *   <li>否则 → {@link #clipHeadTail}</li>
     * </ul>
     */
    public static String clip(String text, Integer maxChars) {
        if (text == null) return "";
        if (maxChars == null || maxChars <= 0) return text;
        if (text.length() <= maxChars) return text;

        // 落盘引用必须活过裁剪：它是模型找回完整内容的唯一线索。
        // 内容哈希必须一起带过去：丢了 sha256 的引用无法证明快照没被替换，
        // 读取端会按「不可证明完整」拒绝，等于把恢复路径裁没了。
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(text);
        String marker = ref == null ? "" : ToolArtifactRef.marker(
                ref.path(), ref.chars() >= 0 ? ref.chars() : text.length(), ref.tool(), ref.sha256());
        if (!marker.isEmpty() && marker.length() + 1 >= maxChars) return marker;
        int budget = marker.isEmpty() ? maxChars : Math.max(1, maxChars - marker.length() - 1);

        String clipped = looksLikeGrep(text)
                ? clipGrep(text, budget)
                : clipHeadTail(text, budget);
        return marker.isEmpty() ? clipped : clipped + "\n" + marker;
    }

    // ==================== 连续型结果：头 + 尾 + 标记 ====================

    private static String clipHeadTail(String text, int maxChars) {
        int head = Math.min(HEAD_CHARS, maxChars);
        int tail = Math.min(TAIL_CHARS, Math.max(0, maxChars - head));
        if (tail <= 0) {
            // 上限小到放不下头+尾：只留头，保证结果长度受控
            return text.substring(0, head) + clipMarker(text.length());
        }
        return text.substring(0, head)
                + clipMarker(text.length())
                + text.substring(text.length() - tail);
    }

    // ==================== grep 类结果：保留所有匹配行 ====================

    /**
     * grep 类结果的裁剪：逐行扫描，在配额耗尽前保留所有行（包括空白），
     * 超出后加截断提示并终止。
     *
     * <p>grep 输出结构（来自 {@code FileTool.grepFile}）：
     * <pre>
     * path/to/file.java
     *   42: class Foo {
     *   43:   // body
     *   44: }
     *   --
     * path/to/other.java
     *   ...
     * 共 N 处匹配
     * </pre>
     * 分段符 {@code \n  --\n}（前后可能有空白），不是行首锚点正则。
     */
    private static String clipGrep(String text, int maxChars) {
        // 1. 解析总匹配数
        int totalMatches = 0;
        for (String ln : text.split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("共 (\\d+) 处匹配").matcher(ln);
            if (m.find()) {
                totalMatches = Integer.parseInt(m.group(1));
                break;
            }
        }
        if (totalMatches == 0) {
            for (String ln : text.split("\n")) {
                if (ln.matches(".*\\d+[:,]\\s+.*")) totalMatches++;
            }
        }

        // 2. 预留给摘要的字符数（含截断提示）：约 100 字
        // 这样剩余配额专门用于内容行，确保总输出不超 maxChars。
        final int SUMMARY_RESERVED = 100;
        int contentMax = Math.max(0, maxChars - SUMMARY_RESERVED);

        // 3. 逐行扫描，累积输出（不含末尾截断提示时不超 maxChars）
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int outChars = 0;
        boolean quotaHit = false;

        for (String ln : lines) {
            if (quotaHit) break;
            int lineLen = sb.length() > 0 ? ln.length() + 1 : ln.length();
            if (outChars + lineLen > contentMax) {
                // 当前行装不下，追加截断提示（strip 尾部换行避免超出配额）
                if (sb.length() > 0) {
                    String rawHint = "\n\n……[grep 结果过长，已截断；剩余匹配行已压缩，如需查看更多请用 grepFile 换关键词重搜]……\n\n";
                    int hintLen = rawHint.length();
                    while (hintLen > 0 && (rawHint.charAt(hintLen - 1) == '\n' || rawHint.charAt(hintLen - 1) == '\r')) {
                        hintLen--;
                    }
                    if (outChars + hintLen <= contentMax) {
                        sb.append(rawHint, 0, hintLen);
                        outChars += hintLen;
                    }
                }
                quotaHit = true;
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(ln);
            outChars += lineLen;
        }

        // 3. 统计已展示的匹配数并加摘要
        int shownMatches = countNumberedLines(sb.toString());
        int remaining = Math.max(0, totalMatches - shownMatches);
        if (remaining > 0 || quotaHit) {
            sb.append("\n\n共 ").append(totalMatches).append(" 处匹配，已展示 ").append(shownMatches);
            if (quotaHit) sb.append(" 处（已截断）");
            else sb.append(" 处");
            if (remaining > 0) sb.append("，其余被截断");
            sb.append("。如需查看全部，请用 grepFile 分次搜索（缩小 scope 或换关键词）。\n");
        }

        return sb.toString();
    }

    /** 统计带行号的匹配行数。 */
    private static int countNumberedLines(String s) {
        int n = 0;
        for (String ln : s.split("\n")) {
            if (ln.matches(".*\\d+[:,]\\s+.*")) n++;
        }
        return n;
    }

    // ==================== 辅助 ====================

    /** 头尾截断的标记文案。 */
    private static String clipMarker(int totalChars) {
        return "\n\n……[工具结果过长，已截断；原文共 " + totalChars + " 字。"
                + "如需查看被截掉的中间部分，请用 readFile 的 offset/limit 只读需要的那一段]……\n\n";
    }

    /**
     * 判定内容是否为 grep 类结果。
     *
     * <p>启发式：grep 输出通常包含"路径/行号: 内容"这种结构（{@code \d+: }），
     * 且有多处出现；同时也兼容"路径行号 | 内容"格式（{@code \d+| }）。
     * 为避免普通文本（如一段包含数字的说明文字）被误判，要求<strong>至少 6 个带行号的行</strong>
     * 或<strong>至少 3 个带行号的行 + 段间分隔（"  --"）</strong>。
     */
    static boolean looksLikeGrep(String text) {
        int numbered = 0;
        boolean hasSeparators = false;
        String[] lines = text.split("\n");
        for (String ln : lines) {
            if (ln.matches(".*\\d+[:,]\\s+.*") && !ln.startsWith("共 ") && !ln.startsWith("找到 ")) {
                numbered++;
            }
            if (!hasSeparators && ln.matches("^  --$")) hasSeparators = true;
        }
        return numbered >= 6 || (numbered >= 3 && hasSeparators);
    }
}
