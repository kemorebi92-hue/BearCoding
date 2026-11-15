package com.example.jingjingai2.tool;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果落盘后的**机器可读引用**。只由工具/落盘层写入，压缩层读取。
 *
 * <p>格式：{@code [artifact-ref]path=<urlencoded>&chars=<n>&tool=<urlencoded>&sig=<hex>[/artifact-ref]}
 *
 * <p>为什么用 URL 编码的 key=value，而不是 JSON：{@code executeCommand} 的结果会被
 * {@code formatToolMarker} 包进一层 JSON 字符串，JSON 里的引号和反斜杠会被转义；
 * URL 编码不含引号和反斜杠，能原样穿过这层包装。
 *
 * <p><b>可信度：</b>每个引用都带 HMAC-SHA256 签名，签名覆盖 path/chars/tool 的原始
 * key=value 串。签名密钥优先来自桌面稳定密钥派生的子密钥（见
 * {@link ArtifactRefKeyProvider}），因此应用重启后历史引用仍可验证；没有稳定密钥时
 * 退回进程内随机密钥，重启后旧引用一律验证失败、按“不可信”处理。
 *
 * <p>解析只认这个固定标记 + 有效签名；不解析预览里的自然语言路径，也不从模型生成的
 * 文本里提取。一段工具结果里出现多个标记时取**最后一个**；落盘层会把系统签名引用
 * 放在预览最末尾，保证它排在第三方原始内容之后。
 */
public final class ToolArtifactRef {

    public static final String OPEN = "[artifact-ref]";
    public static final String CLOSE = "[/artifact-ref]";
    /** 一条消息里最多认这么多引用。超出的不静默丢掉，由压缩层放弃本次压缩。 */
    public static final int MAX_REFS_PER_MESSAGE = 64;

    /** 省略/清除标记的前缀。一旦结果已是其中一种，后续压缩器不再改写，避免路径被二次清除。 */
    private static final String[] OMISSION_PREFIXES = {
            "[旧工具结果已省略：", "[旧页面内容已省略：", "[已清除的工具结果："
    };

    private static final int MIN_KEY_BYTES = 16;
    private static final int RANDOM_KEY_BYTES = 32;

    /** 稳定子密钥或进程内随机密钥；由 {@link ArtifactRefKeyProvider} 或首次使用时初始化。 */
    private static volatile byte[] SIGNING_KEY;

    /**
     * @param path  完整内容所在的绝对路径（快照文件）
     * @param chars 完整内容的字符数；未知为 -1
     * @param tool  工具名，可为空
     * @param sha256 落盘时原始 UTF-8 内容的 SHA-256；旧引用或未校验时为空
     */
    public record Ref(String path, int chars, String tool, String sha256) {
        public Ref(String path, int chars, String tool) {
            this(path, chars, tool, "");
        }

        /** 只有签名覆盖了内容哈希时，才可能证明快照未经替换。 */
        public boolean hasContentHash() {
            return sha256 != null && sha256.matches("[0-9a-fA-F]{64}");
        }
    }

    private ToolArtifactRef() {}

    /** 生成带签名的机器引用。路径为空时返回空串，调用方据此判断“没有可恢复内容”。 */
    public static String marker(String path, int chars, String tool) {
        return marker(path, chars, tool, "");
    }

    /**
     * @param sha256 原始内容 SHA-256。为空时保持旧引用格式；这种引用不能证明内容完整。
     */
    public static String marker(String path, int chars, String tool, String sha256) {
        if (path == null || path.isBlank()) return "";
        String payload = payload(path, chars, tool, sha256);
        return OPEN + payload + "&sig=" + sign(payload) + CLOSE;
    }

    /**
     * 取文本中最后一个**签名有效**的引用。没有、缺签名、签名不匹配一律返回 null。
     * 不保留无签名的旧格式兼容逻辑。
     */
    public static Ref parse(String text) {
        if (text == null || text.isEmpty()) return null;
        int end = text.lastIndexOf(CLOSE);
        if (end < 0) return null;
        int start = text.lastIndexOf(OPEN, end);
        if (start < 0 || start + OPEN.length() > end) return null;

        String body = text.substring(start + OPEN.length(), end);
        int sigAt = body.lastIndexOf("&sig=");
        if (sigAt < 0) return null;
        String payload = body.substring(0, sigAt);
        String sig = body.substring(sigAt + 5);
        // 签名必须是最后一个字段，且不能夹带分隔符；否则视为篡改。
        if (sig.isEmpty() || sig.indexOf('&') >= 0 || !verify(payload, sig)) return null;

        Map<String, String> kv = parseKv(payload);
        String path = kv.get("path");
        if (path == null || path.isBlank()) return null;
        int chars = -1;
        String rawChars = kv.get("chars");
        if (rawChars != null) {
            try {
                chars = Integer.parseInt(rawChars.trim());
            } catch (NumberFormatException ignored) {
                chars = -1;
            }
        }
        return new Ref(path, chars, kv.getOrDefault("tool", ""), kv.getOrDefault("sha256", ""));
    }

    /**
     * 一条文本里所有签名有效的引用，按出现顺序。一条消息里可以有多个。
     * 缺签名、签名不匹配或格式被篡改的标记跳过，不返回。
     *
     * @return null 表示合法引用超过 {@link #MAX_REFS_PER_MESSAGE}，调用方必须当作无法安全保留
     */
    public static List<Ref> parseAll(String text) {
        List<Ref> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int from = 0;
        while (from < text.length()) {
            int start = text.indexOf(OPEN, from);
            if (start < 0) break;
            int end = text.indexOf(CLOSE, start + OPEN.length());
            if (end < 0) break;
            Ref ref = parse(text.substring(start, end + CLOSE.length()));
            if (ref != null) {
                out.add(ref);
                if (out.size() > MAX_REFS_PER_MESSAGE) return null;
            }
            from = end + CLOSE.length();
        }
        return out;
    }

    /** 结果是否已经是某一种省略/清除标记。是则后续压缩器应原样保留，不能再次改写。 */
    public static boolean isOmissionMarker(String text) {
        if (text == null) return false;
        for (String prefix : OMISSION_PREFIXES) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 省略标记里的恢复说明：明确“有没有落盘、路径是什么、没落盘就恢复不了”。
     * 已落盘时同时带上**签名引用**，后续压缩器可以继续把它带下去。
     *
     * @param fallbackChars 引用里没写字符数时的兜底（通常是当前可见文本长度）
     */
    public static String recoverySuffix(Ref ref, int fallbackChars) {
        if (ref == null) {
            return "未落盘或无可信恢复引用，无法从该标记恢复；如需内容请重新调用工具。";
        }
        int chars = ref.chars() >= 0 ? ref.chars() : fallbackChars;
        return "完整内容已落盘（快照），路径：" + ref.path() + "（总 " + chars + " 字符）。"
                + "用 readFile 按 offset/limit 读取；若文件已被清理，请重新调用工具。"
                + " " + marker(ref.path(), chars, ref.tool(), ref.sha256());
    }

    /** 裁剪后若引用被截掉，补回末尾。引用本来就在文本里时不动。 */
    public static String appendIfMissing(String text, Ref ref) {
        if (text == null || ref == null) return text;
        String marker = marker(ref.path(), ref.chars(), ref.tool(), ref.sha256());
        if (marker.isEmpty() || text.contains(marker)) return text;
        return text + "\n" + marker;
    }

    // ==================== 密钥与签名（包内可见，供启动配置与测试） ====================

    /** 配置稳定子密钥。启动时由 {@link ArtifactRefKeyProvider} 调用。 */
    static void configure(byte[] key) {
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("签名密钥至少 " + MIN_KEY_BYTES + " 字节");
        }
        SIGNING_KEY = key.clone();
    }

    /** 没有稳定密钥时退回进程内随机密钥；重启后旧引用会验证失败。 */
    static void useProcessRandomKey(String reason) {
        byte[] random = new byte[RANDOM_KEY_BYTES];
        new SecureRandom().nextBytes(random);
        SIGNING_KEY = random;
        System.err.println("[ArtifactRef] 使用进程内随机签名密钥（" + reason
                + "）；应用重启后旧引用将无法验证");
    }

    /** 测试用：清空当前密钥，模拟进程重启。 */
    static void resetForTest() {
        SIGNING_KEY = null;
    }

    private static byte[] key() {
        byte[] k = SIGNING_KEY;
        if (k != null) return k;
        synchronized (ToolArtifactRef.class) {
            if (SIGNING_KEY == null) {
                useProcessRandomKey("未配置稳定密钥");
            }
            return SIGNING_KEY;
        }
    }

    private static String payload(String path, int chars, String tool, String sha256) {
        String payload = "path=" + encode(path) + "&chars=" + chars
                + "&tool=" + encode(tool == null ? "" : tool);
        if (sha256 != null && !sha256.isBlank()) payload += "&sha256=" + encode(sha256);
        return payload;
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static boolean verify(String payload, String sig) {
        String expected = sign(payload);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseKv(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return out;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
        }
        return out;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
