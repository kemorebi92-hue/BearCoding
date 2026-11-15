package com.example.jingjingai2.usermemory;

import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.UserMemoryMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户记忆的读写与校验。只认调用方传入的 {@code userId}（工具层从
 * ConversationContext 取，模型不准自己填「记到谁头上」）。
 *
 * <p>工具层见 {@link UserMemoryTool}。注入见 {@link UserMemoryInjector}。
 */
@Service
public class UserMemoryService {

    /** mem_key：字母数字 + 点/下划线/连字符，1~64。{@code ../} 这类路径字符直接被正则挡掉。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    static final String SOURCE_EXPLICIT = "explicit";

    private final UserMemoryMapper mapper;
    private final UserMemoryProperties props;

    public UserMemoryService(UserMemoryMapper mapper, UserMemoryProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 写入或覆盖一条记忆。v1 source 固定 {@code explicit}。
     *
     * <ul>
     *   <li>未知 category / 非法 key / 空内容 → 拒绝，不抛</li>
     *   <li>已有 (user, category, key) → 覆盖 content</li>
     *   <li>新 key 且已满 {@code maxItems} → 拒绝，提示先删一条</li>
     *   <li>content 超 {@code maxItemChars} → 截断后再写</li>
     * </ul>
     */
    public WriteResult upsert(Long userId, String category, String memKey, String content,
                              String conversationId) {
        if (userId == null) return WriteResult.reject("userId 不能为空");

        UserMemoryCategory cat = UserMemoryCategory.fromWire(category);
        if (cat == null) {
            return WriteResult.reject("未知 category，只接受 profile / preference / constraint / project_context");
        }

        String keyError = validateKey(memKey);
        if (keyError != null) return WriteResult.reject(keyError);
        String key = memKey.trim();

        if (content == null || content.isBlank()) {
            return WriteResult.reject("content 不能为空");
        }
        String body = truncate(content, props.getMaxItemChars());

        UserMemory existing = mapper.getOne(userId, cat.wire(), key);
        if (existing == null) {
            int cap = Math.max(1, props.getMaxItems());
            if (mapper.countByUser(userId) >= cap) {
                return WriteResult.reject("已满 " + cap + " 条，拒绝新 key。请先删一条再写。");
            }
        }

        mapper.upsert(userId, cat.wire(), key, body, SOURCE_EXPLICIT, conversationId);
        UserMemory saved = mapper.getOne(userId, cat.wire(), key);
        return WriteResult.ok(saved);
    }

    /** 该用户全部记忆（≤ maxItems 行；注入层以后再按字数裁）。userId 空则空列表。 */
    public List<UserMemory> listByUser(Long userId) {
        if (userId == null) return Collections.emptyList();
        List<UserMemory> rows = mapper.listByUser(userId);
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * 按注入顺序分组：constraint → preference → profile → project_context。
     * 空类目不出现在 map 里。库里若有未知 category（理论上不会），直接丢掉。
     */
    public Map<UserMemoryCategory, List<UserMemory>> listGrouped(Long userId) {
        LinkedHashMap<UserMemoryCategory, List<UserMemory>> grouped = new LinkedHashMap<>();
        for (UserMemory row : listByUser(userId)) {
            UserMemoryCategory cat = UserMemoryCategory.fromWire(row.getCategory());
            if (cat == null) continue;
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(row);
        }
        // 按枚举声明顺序重排，避免 mapper 的 ORDER BY category 与注入顺序偶发不一致时测不出来
        LinkedHashMap<UserMemoryCategory, List<UserMemory>> ordered = new LinkedHashMap<>();
        for (UserMemoryCategory cat : UserMemoryCategory.values()) {
            List<UserMemory> items = grouped.get(cat);
            if (items != null && !items.isEmpty()) ordered.put(cat, items);
        }
        return ordered;
    }

    public UserMemory getOne(Long userId, String category, String memKey) {
        UserMemoryCategory cat = UserMemoryCategory.fromWire(category);
        if (userId == null || cat == null || memKey == null || memKey.isBlank()) return null;
        return mapper.getOne(userId, cat.wire(), memKey.trim());
    }

    public boolean deleteOne(Long userId, String category, String memKey) {
        UserMemoryCategory cat = UserMemoryCategory.fromWire(category);
        if (userId == null || cat == null || memKey == null || memKey.isBlank()) return false;
        return mapper.deleteOne(userId, cat.wire(), memKey.trim()) > 0;
    }

    /**
     * 删一条并返回给模型看的句子。非法 category / key 说明原因；没有这条记忆也说清楚。不抛。
     */
    public String forget(Long userId, String category, String memKey) {
        if (userId == null) return "userId 不能为空";
        UserMemoryCategory cat = UserMemoryCategory.fromWire(category);
        if (cat == null) {
            return "未知 category，只接受 profile / preference / constraint / project_context";
        }
        String keyError = validateKey(memKey);
        if (keyError != null) return keyError;
        boolean deleted = mapper.deleteOne(userId, cat.wire(), memKey.trim()) > 0;
        return deleted ? "已删除" : "没有这条记忆";
    }

    public int deleteByCategory(Long userId, String category) {
        UserMemoryCategory cat = UserMemoryCategory.fromWire(category);
        if (userId == null || cat == null) return 0;
        return mapper.deleteByCategory(userId, cat.wire());
    }

    public int countByUser(Long userId) {
        if (userId == null) return 0;
        return mapper.countByUser(userId);
    }

    static String validateKey(String memKey) {
        if (memKey == null || memKey.isBlank()) return "mem_key 不能为空";
        String key = memKey.trim();
        if (key.length() > 64) return "mem_key 最长 64 字符";
        if (!KEY_PATTERN.matcher(key).matches()) {
            return "mem_key 只允许字母、数字、点、下划线、连字符";
        }
        if (key.contains("..")) return "mem_key 不能包含 ..";
        if (isSecretLikeFilename(key)) return "mem_key 不能是密钥形文件名";
        return null;
    }

    /**
     * 密钥形文件名：.env、私钥、证书、credentials 这类。正则已挡掉路径分隔符，
     * 这里补的是「看起来像密钥文件」的短名。
     */
    static boolean isSecretLikeFilename(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith(".env")) return true;
        if (k.startsWith("id_rsa") || k.startsWith("id_dsa")
                || k.startsWith("id_ecdsa") || k.startsWith("id_ed25519")) return true;
        if (k.endsWith(".pem") || k.endsWith(".key") || k.endsWith(".p12")
                || k.endsWith(".pfx") || k.endsWith(".jks")) return true;
        return k.equals("credentials") || k.equals("secrets.json")
                || k.equals(".npmrc") || k.equals(".netrc");
    }

    static String truncate(String content, int maxChars) {
        int cap = Math.max(1, maxChars);
        if (content.length() <= cap) return content;
        return content.substring(0, cap);
    }

    /** 写入结果：拒绝时带给调用方（以后的工具）看的句子，不抛异常。 */
    public static final class WriteResult {
        private final boolean accepted;
        private final String message;
        private final UserMemory item;

        private WriteResult(boolean accepted, String message, UserMemory item) {
            this.accepted = accepted;
            this.message = message;
            this.item = item;
        }

        static WriteResult ok(UserMemory item) {
            return new WriteResult(true, "已记住", item);
        }

        static WriteResult reject(String message) {
            return new WriteResult(false, message, null);
        }

        public boolean isAccepted() { return accepted; }
        public String getMessage() { return message; }
        public UserMemory getItem() { return item; }
    }
}
