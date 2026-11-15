package com.example.jingjingai2.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理的**观测台**：成本 + 冗余读提示。
 *
 * 两件事，都只跟"这次用户请求"有关，请求结束/新请求开始就清掉：
 *  1. **成本**：把每个子代理的真实用量累加（{@link SubagentUsage}），请求结束时打一行汇总，
 *     和主 agent 那一行 {@code [Usage]} 并排看 —— 这正是本模块要回答的问题：
 *     "派子代理到底省了还是花了"。价格表在 {@link SubagentProperties} 里配。
 *  2. **读文件计数**（两条口径，不要混）：
 *     <ul>
 *       <li>{@code mainFullReadCount}：主 agent 所有整文件 {@code readFile}（没带 offset/limit）的次数。
 *           对照组也要这个数，才能比"开了子代理之后主 agent 还读了多少全文"。</li>
 *       <li>{@code redundantReadCount}：其中路径已在某个子代理台账里的次数（才叫"白读"）。
 *           没派子代理时这条恒为 0，所以<strong>不能</strong>拿它跟对照组比。</li>
 *     </ul>
 *     带行范围的局部读两边都不计。提示仍然**不拦截**。
 *
 * 线程模型：子代理在别的线程上跑，用 ConcurrentHashMap / AtomicInteger。
 */
@Component
public class SubagentTelemetry {

    /** 一个会话在一次用户请求内的观测数据 */
    private static final class Conv {
        final SubagentUsage childUsage = new SubagentUsage();
        /** 归一化路径 → 读过它的子代理 id（谁先读算谁的） */
        final Map<String, String> readByChild = new ConcurrentHashMap<>();
        final AtomicInteger redundantReads = new AtomicInteger();
        /** 主 agent 整文件 readFile 次数（不论台账里有没有这条路径） */
        final AtomicInteger mainFullReads = new AtomicInteger();
        /** SUCCEEDED 完工单因命令对不上 / 退出码不一致被降级的次数 */
        final AtomicInteger handoffDemotions = new AtomicInteger();
        final AtomicInteger childFixedOverheadTokens = new AtomicInteger();
        final AtomicInteger childRuns = new AtomicInteger();
        final AtomicInteger partialRuns = new AtomicInteger();
    }

    private final Map<String, Conv> convs = new ConcurrentHashMap<>();

    /**
     * 会话级「主 agent 已读正文」字符累计（跨多次用户请求保留，直到 {@link #releaseConversation}）。
     * <p>给条件注入阈值用：落库历史只有最终助手正文，不含 readFile 工具返回的文件内容；
     * 若不把已读字数计入阈值，S5 这类「先读一堆大文件再派子代理」永远触发不了注入。
     */
    private final Map<String, AtomicInteger> mainReadCharsByConv = new ConcurrentHashMap<>();

    private Conv conv(String conversationId) {
        if (conversationId == null) return null;
        return convs.computeIfAbsent(conversationId, k -> new Conv());
    }

    /** 新用户请求开始：上一轮的观测数据翻篇（<strong>不</strong>清已读字数台账——那是跨请求的） */
    public void beginUserRequest(String conversationId) {
        if (conversationId == null) return;
        convs.remove(conversationId);
    }

    public void releaseConversation(String conversationId) {
        if (conversationId == null) return;
        convs.remove(conversationId);
        mainReadCharsByConv.remove(conversationId);
    }

    /**
     * 主 agent 一次 readFile 返回的正文长度（字符）。带 range 的局部读也计 ——
     * 它们同样进过当轮 messages，跨请求时用累计字数近似「会话里已经灌进过多少原文」。
     */
    public void recordMainReadChars(String conversationId, int chars) {
        if (conversationId == null || chars <= 0) return;
        mainReadCharsByConv.computeIfAbsent(conversationId, k -> new AtomicInteger())
                .addAndGet(chars);
    }

    /** 已读正文折算成估算 token（Java 源码偏 ASCII → 约 4 字符/token） */
    public long estimatedMainReadTokens(String conversationId) {
        if (conversationId == null) return 0;
        AtomicInteger n = mainReadCharsByConv.get(conversationId);
        if (n == null) return 0;
        int chars = n.get();
        return Math.max(0, (chars + 3) / 4);
    }

    /** 一个子代理跑完：记用量、记它读过哪些文件、记固定开销 */
    public void recordChildRun(String conversationId, String subagentId, SubagentResult result,
                               SubagentUsage usage) {
        Conv c = conv(conversationId);
        if (c == null) return;
        c.childRuns.incrementAndGet();
        if (result != null && result.completeness() == SubagentReport.Status.PARTIAL) {
            c.partialRuns.incrementAndGet();
        }
        if (usage != null && usage.hasData()) {
            c.childUsage.add(usage.promptTokens(), usage.cachedPromptTokens(), usage.completionTokens());
        }
        if (result != null) {
            c.childFixedOverheadTokens.addAndGet(Math.max(0, result.fixedOverheadTokens()));
            markReadTo(c, subagentId, result.filesRead());
        }
    }

    /** 记下"这些文件被子代理 X 读过" */
    public void markRead(String conversationId, String subagentId, List<String> paths) {
        markReadTo(conv(conversationId), subagentId, paths);
    }

    private void markReadTo(Conv c, String subagentId, List<String> paths) {
        if (c == null || paths == null || paths.isEmpty()) return;
        String id = (subagentId == null || subagentId.isBlank()) ? "?" : subagentId;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            c.readByChild.putIfAbsent(normalizePath(p), id);
        }
    }

    /**
     * 路径归一化。子代理记的是它自己看的那条路径（可能是相对路径，也可能是
     * {@code D:/java/practice/jingjingAI2/src/...} 这样的绝对路径），主 agent 回过头来
     * 多半写的是**另一种写法** —— 不归一化，这个提示一次都不会命中。
     */
    static String normalizePath(String p) {
        if (p == null) return "";
        String s = p.trim().replace(SEP, '/').toLowerCase();
        while (s.startsWith("./")) s = s.substring(2);
        while (s.contains("//")) s = s.replace("//", "/");
        return s;
    }

    /** Windows 的反斜杠单独拎出来，免得在正则/字面量里到处转义 */
    private static final char SEP = (char) 92;

    /**
     * 找"这个路径是不是被子代理读过"。
     * 先精确命中，再做**后缀匹配**（相对路径 vs 绝对路径）——两边只要有一边是另一边的路径后缀
     * 就算同一个文件（例如 {@code src/main/java/A.java} 与 {@code d:/x/y/src/main/java/A.java}）。
     */
    private String whoRead(Conv c, String path) {
        String key = normalizePath(path);
        if (key.isEmpty()) return null;
        String exact = c.readByChild.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : c.readByChild.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("/" + key) || key.endsWith("/" + k)) return e.getValue();
        }
        return null;
    }

    /**
     * 主 agent 一次 {@code readFile} 的观测。
     * <ul>
     *   <li>带 offset/limit → 两边都不计、不提示（局部核对）。</li>
     *   <li>整文件读 → {@code mainFullReadCount +1}；若路径已在子代理台账里，
     *       再 {@code redundantReadCount +1} 并返回一行提醒（**不拦截**）。</li>
     * </ul>
     *
     * @return 冗余读提醒；不该提醒时返回 null
     */
    public String hintForWholeFileRead(String conversationId, String path, boolean hasRange) {
        if (hasRange) return null;
        Conv c = conv(conversationId);
        if (c == null || path == null || path.isBlank()) return null;
        c.mainFullReads.incrementAndGet();
        String who = whoRead(c, path);
        if (who == null) return null;
        c.redundantReads.incrementAndGet();
        return "[提示] 此文件已被子代理 " + who + " 读过，其结论见上文；"
                + "如果只是要那个结论，不必重读全文（下面是文件内容，仅供核对）。\n";
    }

    public int redundantReadCount(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? 0 : c.redundantReads.get();
    }

    /** 主 agent 整文件 readFile 次数（对照组比较用；含没有台账可对的那些） */
    public int mainFullReadCount(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? 0 : c.mainFullReads.get();
    }

    public void noteHandoffDemotion(String conversationId) {
        Conv c = conv(conversationId);
        if (c != null) c.handoffDemotions.incrementAndGet();
    }

    public int handoffDemotionCount(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? 0 : c.handoffDemotions.get();
    }

    public SubagentUsage childUsage(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? new SubagentUsage() : c.childUsage;
    }

    public int childRuns(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? 0 : c.childRuns.get();
    }

    public int partialRuns(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? 0 : c.partialRuns.get();
    }

    public int childFixedOverheadTokens(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? 0 : c.childFixedOverheadTokens.get();
    }

    /**
     * 请求结束时的一行汇总（纯字符串，便于单测）。
     * 主 agent 的用量由 {@code ChatController} 的 {@link com.example.jingjingai2.utils.UsageTracker} 给出，
     * 这里只报**子代理侧**，两者相减就是"这次派发净赚/净亏"。
     */
    public String summaryLine(String conversationId, Long mainPromptTokens,
                              Long mainCompletionTokens, SubagentProperties props) {
        Conv c = conv(conversationId);
        SubagentUsage u = c == null ? new SubagentUsage() : c.childUsage;
        SubagentUsage.Cost cost = u.cost(props);
        StringBuilder sb = new StringBuilder();
        sb.append("[SubagentCost] 子代理 runs=").append(c == null ? 0 : c.childRuns.get())
          .append(" partial=").append(c == null ? 0 : c.partialRuns.get())
          .append(" | 子代理 prompt=").append(u.promptTokens())
          .append("（命中缓存 ").append(u.cachedPromptTokens()).append("）")
          .append(" completion=").append(u.completionTokens())
          .append(" | 固定开销估算=").append(c == null ? 0 : c.childFixedOverheadTokens.get()).append(" tokens");
        if (mainPromptTokens != null) {
            sb.append(" | 主 agent prompt=").append(mainPromptTokens)
              .append(" completion=").append(mainCompletionTokens == null ? 0 : mainCompletionTokens);
        }
        sb.append(" | 子代理加权成本=").append(cost)
          .append(" | mainFullReadCount=").append(c == null ? 0 : c.mainFullReads.get())
          .append(" redundantReadCount=").append(c == null ? 0 : c.redundantReads.get())
          .append(" handoffDemotionCount=").append(c == null ? 0 : c.handoffDemotions.get())
          .append("  【价格为配置项，请以实际服务商为准】");
        return sb.toString();
    }

    /** 诊断：会话 → 读过它的子代理（测试用） */
    public Map<String, String> readMap(String conversationId) {
        Conv c = conv(conversationId);
        return c == null ? Map.of() : new LinkedHashMap<>(c.readByChild);
    }
}
