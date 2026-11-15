package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageHit;
import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.service.TaskPlanService;
import com.example.jingjingai2.usermemory.UserMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 旧上下文的统一只读入口。模型自己决定什么时候搜、搜什么、读哪一条。
 *
 * <p>userId 和 conversationId 只从 {@link ConversationContext} 取，不出现在工具参数里。
 * 搜索只返回短索引；正文必须再读一次，而且读取时重新校验归属，不把搜索结果里的 id 当授权。
 *
 * <p>本阶段不开放工具快照：默认启动没有稳定签名密钥，进程内保留标记重启即丢，
 * TTL 清理也可能删掉已退休的原文。磁盘上有文件不等于引用可读。
 */
@Component
public class ContextAssetTool {

    public static final String NAME = "searchContext";

    static final int DEFAULT_LIMIT = 3;
    static final int MAX_LIMIT = 5;
    /** 一条命中直接带回的正文片段。够回答就不用再 read。 */
    static final int SNIPPET_CHARS = 400;
    /** 一次搜索全部命中片段的总字符上限。 */
    static final int TOTAL_SNIPPET_CHARS = 1200;
    /** 摘要里关键词前面保留的字符数，让命中位置落在摘要中间附近。 */
    static final int SNIPPET_BEFORE = 30;
    /** 单次读取的硬上限，避免一次读取把上下文重新撑满。默认与最大值相同。 */
    static final int READ_CHARS = 4000;

    /** 同一版本、同一范围、没有进展的立即重复读取。超过这个次数才拦，多页恢复不受影响。 */
    static final int REPEAT_READ_LIMIT = 2;

    private static final String TYPE_MESSAGE = "message";
    private static final String TYPE_MEMORY = "user_memory";
    private static final String TYPE_PLAN = "plan";

    private final ConversationMapper conversationMapper;
    private final UserMemoryService userMemoryService;
    private final TaskPlanService taskPlanService;
    /** 本线程最近的读取：版本 + 范围。工具是单例，不能把各请求的记录混在一起。 */
    private static final ThreadLocal<java.util.Deque<String>> recentReads =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    public ContextAssetTool(ConversationMapper conversationMapper,
                            UserMemoryService userMemoryService,
                            TaskPlanService taskPlanService) {
        this.conversationMapper = conversationMapper;
        this.userMemoryService = userMemoryService;
        this.taskPlanService = taskPlanService;
    }

    /** 请求或子代理线程结束时丢掉最近读取，避免线程池复用串到下一单。 */
    public static void endRequest() {
        recentReads.remove();
    }

    @Tool(name = NAME, description = "搜索并读取当前上下文里没有的旧信息。只读，不能写入、修改或删除。"
            + "用户和会话由系统绑定，不能指定别的用户或别的会话。"
            + "什么时候用：当前消息、已注入的记忆和当前计划不够，需要更早的决定、旧编号、用户约束或过去的执行结果。"
            + "当前上下文已经够用时不要调用，直接执行。"
            + "query 可以带多个关键词，数据库按或匹配、去重并统一排序后再分页；不要把整句话当成一个精确关键词。"
            + "operation=search 返回少量高相关结果，每条带角色、消息 id、locator 和一段正文片段。"
            + "片段已经够回答时直接用，不要再 read。"
            + "片段不够时用 operation=read，把 locator 原样传回。正文一次最多 " + READ_CHARS + " 字符；"
            + "返回里的 hasMore 为 true 时，把同一个 locator 和返回的 nextOffset 再读一次，直到 hasMore 为 false。"
            + "offset 是 read 的正文起始字符位置，从 0 开始，可空，默认 0。"
            + "type 可空：message（本会话历史消息）/ user_memory（当前用户记忆）/ plan（本会话当前计划）。"
            + "空 type 搜索全部可搜索类型。同一话题有多条说法时，用时间更晚的那条。"
            + "搜索结果里的 hasMore=true 表示这一页之后还有命中，把同一个 query 加上返回的 nextCursor 再搜一次；"
            + "翻页不会重复也不会漏项。order=desc（默认）从最新往最早找，order=asc 从最早往最新找，"
            + "想找最早的旧原话时用 order=asc。cursor 只对 message 有效。"
            + "如果提示里给出 message|<id> 这种 locator，说明那条旧消息已知，直接用 operation=read 读它，"
            + "不要拿那个 id 当关键词搜索，也不要自己编 locator。"
            + "已经检索过但仍没找到精确旧原话时，继续换关键词或翻页检索；确实找不到就说明无法确认，不要编造。"
            + "计划步骤的 DONE 只是模型上报的状态，actualResult 是模型自己写的，不是系统核验过的证据。"
            + "工具快照本阶段不可搜索。")
    public String searchContext(
            @ToolParam(description = "search 或 read") String operation,
            @ToolParam(description = "资产类型，可空：message / user_memory / plan。search 时空表示全部", required = false) String type,
            @ToolParam(description = "search 的关键词，可空。空则按时间列出短索引", required = false) String query,
            @ToolParam(description = "search 的条数，默 3，最大 5。要更多结果用 cursor 翻页", required = false) Integer limit,
            @ToolParam(description = "read 时传 search 结果里的 locator，原样传回", required = false) String locator,
            @ToolParam(description = "read 的起始字符位置，从 0 开始，可空，默认 0。长正文用上一次返回的 nextOffset", required = false) Integer offset,
            @ToolParam(description = "search 翻页起点，可空，默认 0。用上一次 search 返回的 nextCursor", required = false) Integer cursor,
            @ToolParam(description = "search 的排序：desc（默认，最新在前）或 asc（最早在前）", required = false) String order) {
        try {
            Long userId = ConversationContext.userId();
            String conversationId = ConversationContext.conversationId();
            if (userId == null || conversationId == null || conversationId.isBlank()) {
                return "当前不在会话上下文中，无法检索。";
            }
            String op = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
            if ("search".equals(op)) return search(userId, conversationId, type, query, limit, cursor, order);
            if ("read".equals(op)) return read(userId, conversationId, locator, offset);
            return "operation 只接受 search 或 read。";
        } catch (Exception e) {
            return "检索失败：" + e.getMessage();
        }
    }

    /** 兼容旧调用点：不带分页参数时等同于 cursor=0、order=desc。 */
    public String searchContext(String operation, String type, String query, Integer limit,
                                String locator, Integer offset) {
        return searchContext(operation, type, query, limit, locator, offset, null, null);
    }

    private String search(Long userId, String conversationId, String type, String query, Integer limit,
                          Integer cursor, String order) {
        int capped = cap(limit);
        int offset = capCursor(cursor);
        boolean ascending = "asc".equals(normalizeOrder(order));
        String wanted = normalizeType(type);
        if (type != null && !type.isBlank() && wanted == null) {
            return "未知 type，只接受 message / user_memory / plan。";
        }
        boolean keyword = query != null && !query.isBlank();
        List<String> terms = keywords(query);
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int[] budget = {TOTAL_SNIPPET_CHARS};
        boolean pagingMessage = wanted == null || TYPE_MESSAGE.equals(wanted);
        int messageReturned = 0;
        boolean hasMore = false;

        if (pagingMessage) {
            if (!keyword) {
                notes.add("message 需要关键词，未搜索历史消息");
            } else {
                // 多个关键词在同一条 SQL 里 OR 匹配、按消息去重、统一排序后再分页。
                // 各关键词分别翻页再合并，全局顺序不稳定，会重复也会漏。
                // 多取 1 条只为判断 hasMore：刚好满一页且没有更多时必须是 false。
                List<String> patterns = new ArrayList<>();
                for (String term : terms) patterns.add(likePattern(term));
                int fetch = capped + 1;
                List<MessageHit> hits = ascending
                        ? conversationMapper.searchMessagesAnyAsc(userId, conversationId,
                                patterns, terms, SNIPPET_BEFORE, SNIPPET_CHARS, fetch, offset)
                        : conversationMapper.searchMessagesAnyDesc(userId, conversationId,
                                patterns, terms, SNIPPET_BEFORE, SNIPPET_CHARS, fetch, offset);
                if (hits == null) hits = List.of();
                hasMore = hits.size() > capped;
                int page = Math.min(capped, hits.size());
                for (int i = 0; i < page; i++) items.add(messageItem(hits.get(i), budget));
                messageReturned = page;
                if (offset > 0) {
                    notes.add("message 从第 " + offset + " 条命中开始，order="
                            + (ascending ? "asc" : "desc"));
                }
                if (hasMore) {
                    notes.add("message 还有更多命中，把 cursor 设为 "
                            + (offset + messageReturned) + " 继续翻页");
                }
            }
        }
        if (wanted == null || TYPE_MEMORY.equals(wanted)) {
            int before = items.size();
            for (UserMemory row : filterMemories(userMemoryService.listByUser(userId), query)) {
                if (items.size() - before >= capped) {
                    notes.add("user_memory 已达 limit，可能不完整");
                    break;
                }
                if (budget[0] <= 0) {
                    notes.add("片段总长度已达上限，其余命中未返回");
                    break;
                }
                items.add(memoryItem(row, budget));
            }
        }
        if (wanted == null || TYPE_PLAN.equals(wanted)) {
            int before = items.size();
            collectPlan(taskPlanService.get(userId, conversationId), query, capped, items, budget, notes);
            if (items.size() - before >= capped) notes.add("plan 已达 limit，可能不完整");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation", "search");
        out.put("count", items.size());
        if (pagingMessage) {
            out.put("order", ascending ? "asc" : "desc");
            out.put("cursor", offset);
            out.put("limit", capped);
            out.put("hasMore", hasMore);
            if (hasMore) out.put("nextCursor", offset + messageReturned);
        }
        out.put("truncated", hasMore || notes.stream().anyMatch(
                n -> n.contains("可能不完整") || n.contains("上限")));
        out.put("items", items);
        if (!notes.isEmpty()) out.put("notes", notes);
        out.put("note", "每条的 snippet 是命中处的正文片段。够回答时直接用，不要再 read；"
                + "不够时才按 locator 分页读。hasMore=true 时用 nextCursor 翻下一页，order=asc 从最早开始。");
        if (items.isEmpty()) out.put("empty", keyword ? "没有匹配结果" : "没有可列出的资产");
        return Json.write(out);
    }

    private String read(Long userId, String conversationId, String locator, Integer offset) {
        Locator loc = Locator.parse(locator);
        if (loc == null) return "locator 无效。请使用 search 结果里的 locator，不要自己编。";
        String blocked = repeatedRead(userId, conversationId, loc, offset);
        if (blocked != null) return blocked;
        return switch (loc.type) {
            case TYPE_MESSAGE -> readMessage(userId, conversationId, loc, offset);
            case TYPE_MEMORY -> readMemory(userId, loc, offset);
            case TYPE_PLAN -> readPlan(userId, conversationId, loc, offset);
            default -> "locator 的类型不可读。";
        };
    }

    private String readMessage(Long userId, String conversationId, Locator loc, Integer offset) {
        long id;
        try {
            id = Long.parseLong(loc.a);
        } catch (NumberFormatException e) {
            return "locator 无效。";
        }
        MessageRecord row = conversationMapper.selectMessage(userId, conversationId, id);
        if (row == null) return "没有这条消息，或它不属于当前会话。";
        Map<String, Object> out = baseRead(TYPE_MESSAGE, loc.raw);
        out.put("role", row.getRole());
        putTime(out, "createdAt", row.getTimestamp());
        putSlice(out, row.getContent(), offset);
        return Json.write(out);
    }

    private String readMemory(Long userId, Locator loc, Integer offset) {
        UserMemory row = userMemoryService.getOne(userId, loc.a, loc.b);
        if (row == null) return "没有这条记忆，或它不属于当前用户。";
        Map<String, Object> out = baseRead(TYPE_MEMORY, loc.raw);
        out.put("category", row.getCategory());
        out.put("key", row.getMemKey());
        putTime(out, "updatedAt", row.getUpdatedAt());
        putSlice(out, row.getContent(), offset);
        return Json.write(out);
    }

    private String readPlan(Long userId, String conversationId, Locator loc, Integer offset) {
        TaskPlan plan = taskPlanService.get(userId, conversationId);
        if (plan == null) return "当前会话没有计划。";
        Map<String, Object> out = baseRead(TYPE_PLAN, loc.raw);
        out.put("note", "DONE 是模型上报的状态，actualResult 是模型自己写的，不是系统核验过的证据。");
        if ("overview".equals(loc.a)) {
            putSlice(out, planOverview(plan), offset);
            return Json.write(out);
        }
        PlanStep step = plan.findStep(loc.a);
        if (step == null) return "当前计划里没有这一步。已完成并清空的历史计划不在检索范围内。";
        out.put("stepId", step.getStepId());
        out.put("status", step.getStatus());
        putSlice(out, stepDetail(step), offset);
        return Json.write(out);
    }

    /**
     * 同一版本、同一范围连续读超过 {@link #REPEAT_READ_LIMIT} 次才拦。
     * 换 offset 就是翻页，不算重复；中间夹了别的读取也不算。
     */
    private String repeatedRead(Long userId, String conversationId, Locator loc, Integer offset) {
        int start = offset == null || offset < 0 ? 0 : offset;
        String key = userId + "/" + conversationId + "/" + loc.raw + "@" + start;
        java.util.Deque<String> recent = recentReads.get();
        int streak = 0;
        for (String seen : recent) {
            if (!seen.equals(key)) break;
            streak++;
        }
        recent.addFirst(key);
        while (recent.size() > REPEAT_READ_LIMIT + 1) recent.removeLast();
        if (streak >= REPEAT_READ_LIMIT) {
            return "同一段刚刚已经读过 " + streak + " 次，没有新内容。换 nextOffset 继续往后读，或换一个 locator。";
        }
        return null;
    }

    private static Map<String, Object> messageItem(MessageHit hit, int[] budget) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", TYPE_MESSAGE);
        item.put("role", hit.getRole());
        item.put("id", hit.getId());
        item.put("snippet", takeSnippet(hit.getSnippet(), budget));
        putTime(item, "createdAt", hit.getTimestamp());
        item.put("locator", Locator.of(TYPE_MESSAGE, hit.getId() == null ? "" : Long.toString(hit.getId()), null));
        item.put("source", "conversation_message");
        return item;
    }

    private static Map<String, Object> memoryItem(UserMemory row, int[] budget) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", TYPE_MEMORY);
        item.put("id", row.getCategory() + "/" + row.getMemKey());
        item.put("snippet", takeSnippet(row.getContent(), budget));
        putTime(item, "updatedAt", row.getUpdatedAt());
        item.put("locator", Locator.of(TYPE_MEMORY, row.getCategory(), row.getMemKey()));
        item.put("source", "user_memory");
        return item;
    }

    /** 计划只有当前这一份。关键词命中概要或某一步才列入；没有关键词时给概要和全部步骤。 */
    private static void collectPlan(TaskPlan plan, String query, int capped, List<Map<String, Object>> items,
                                     int[] budget, List<String> notes) {
        if (plan == null || plan.getSteps() == null) return;
        boolean keyword = query != null && !query.isBlank();
        List<String> terms = keywords(query);
        int added = 0;
        if (!keyword || terms.stream().anyMatch(term -> contains(planOverview(plan), term.toLowerCase(Locale.ROOT)))) {
            if (budget[0] <= 0) {
                notes.add("片段总长度已达上限，其余命中未返回");
                return;
            }
            items.add(planItem("overview", planOverview(plan), budget));
            added++;
        }
        for (PlanStep step : plan.getSteps()) {
            if (added >= capped) return;
            if (keyword && terms.stream().noneMatch(term -> contains(stepText(step), term.toLowerCase(Locale.ROOT)))) continue;
            if (budget[0] <= 0) {
                notes.add("片段总长度已达上限，其余命中未返回");
                return;
            }
            items.add(planItem(step.getStepId(), step.getDescription(), budget));
            added++;
        }
    }

    private static Map<String, Object> planItem(String id, String body, int[] budget) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", TYPE_PLAN);
        item.put("id", id);
        item.put("snippet", takeSnippet(body, budget));
        item.put("locator", Locator.of(TYPE_PLAN, id, null));
        item.put("source", "current_plan");
        return item;
    }

    private static List<UserMemory> filterMemories(List<UserMemory> rows, String query) {
        if (rows == null || rows.isEmpty()) return List.of();
        if (query == null || query.isBlank()) return rows;
        List<String> terms = keywords(query);
        List<UserMemory> out = new ArrayList<>();
        for (UserMemory row : rows) {
            String hay = (nullToEmpty(row.getCategory()) + " " + nullToEmpty(row.getMemKey())
                    + " " + nullToEmpty(row.getContent())).toLowerCase(Locale.ROOT);
            if (terms.stream().anyMatch(term -> hay.contains(term.toLowerCase(Locale.ROOT)))) out.add(row);
        }
        return out;
    }

    private static String planOverview(TaskPlan plan) {
        int done = 0;
        for (PlanStep s : plan.getSteps()) if (PlanStep.DONE.equals(s.getStatus())) done++;
        return "进度 " + done + "/" + plan.getSteps().size()
                + "，预算 " + plan.getMaxIterations() + " 轮";
    }

    private static String stepDetail(PlanStep step) {
        return "description: " + nullToEmpty(step.getDescription())
                + "\nexpected: " + nullToEmpty(step.getExpected())
                + "\nstatus: " + nullToEmpty(step.getStatus())
                + "\nactualResult（模型上报，未经系统核验）: " + nullToEmpty(step.getActualResult());
    }

    private static String stepText(PlanStep step) {
        return nullToEmpty(step.getStepId()) + " " + nullToEmpty(step.getDescription()) + " "
                + nullToEmpty(step.getExpected()) + " " + nullToEmpty(step.getStatus()) + " "
                + nullToEmpty(step.getActualResult());
    }

    private static Map<String, Object> baseRead(String type, String locator) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation", "read");
        out.put("type", type);
        out.put("locator", locator);
        return out;
    }

    private static void putTime(Map<String, Object> item, String key, Date value) {
        if (value != null) item.put(key, value.toInstant().toString());
    }

    private static int cap(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /** 翻页起点。空和负数按 0。不把起点截在 200，否则更早的历史会永远翻不到。单次查询仍只取一页。 */
    private static int capCursor(Integer cursor) {
        if (cursor == null || cursor <= 0) return 0;
        return cursor;
    }

    /** 排序方向。只认 asc，其余（含空）都是 desc，保持旧行为。 */
    private static String normalizeOrder(String order) {
        if (order == null || order.isBlank()) return "desc";
        return "asc".equals(order.trim().toLowerCase(Locale.ROOT)) ? "asc" : "desc";
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) return null;
        String t = type.trim().toLowerCase(Locale.ROOT);
        if (TYPE_MESSAGE.equals(t) || TYPE_MEMORY.equals(t) || TYPE_PLAN.equals(t)) return t;
        return null;
    }

    /** 按空白和常见标点拆开。整句不再当成一个精确关键词；中文连续词仍由调用方分开传。 */
    static List<String> keywords(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : query.trim().split("[\\s,，。；;、]+")) {
            if (!part.isBlank() && !out.contains(part)) out.add(part);
        }
        return out.isEmpty() ? List.of(query.trim()) : out;
    }

    /** 关键词里的 \ % _ 按字面处理，避免模型用通配符扫全表。 */
    static String likePattern(String query) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static boolean contains(String hay, String needle) {
        return hay != null && needle != null && hay.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 从总预算里取一段片段。预算用完后返回空串，调用方据此不再加条目。 */
    private static String takeSnippet(String text, int[] budget) {
        String t = nullToEmpty(text).replaceAll("\\s+", " ").trim();
        int room = Math.max(0, budget[0]);
        if (t.length() > room) t = t.substring(0, room);
        budget[0] -= t.length();
        return t;
    }

    /**
     * 一次最多 {@link #READ_CHARS} 个字符。offset 小于 0 从开头读，超过正文长度则本段为空。
     * 返回总长度、起止位置、是否还有下一段和下一段偏移，调用方用 nextOffset 继续读。
     */
    private static void putSlice(Map<String, Object> out, String text, Integer offset) {
        String full = text == null ? "" : text;
        int total = full.length();
        int start = offset == null || offset < 0 ? 0 : Math.min(offset, total);
        int end = Math.min(start + READ_CHARS, total);
        out.put("content", full.substring(start, end));
        out.put("totalChars", total);
        out.put("start", start);
        out.put("end", end);
        boolean hasMore = end < total;
        out.put("hasMore", hasMore);
        if (hasMore) out.put("nextOffset", end);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** type、第一段、可选第二段。记忆的 key 允许字母数字和 . _ -，用 | 分隔不会撞上。 */
    record Locator(String type, String a, String b, String raw) {
        static String of(String type, String a, String b) {
            String loc = type + "|" + (a == null ? "" : a);
            if (b != null) loc += "|" + b;
            return loc;
        }

        static Locator parse(String raw) {
            if (raw == null) return null;
            String[] parts = raw.split("\\|", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
            String type = parts[0];
            if (!TYPE_MESSAGE.equals(type) && !TYPE_MEMORY.equals(type) && !TYPE_PLAN.equals(type)) return null;
            if (TYPE_MEMORY.equals(type) && parts.length < 3) return null;
            return new Locator(type, parts[1], parts.length == 3 ? parts[2] : null, raw);
        }
    }
}
