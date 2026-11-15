package com.example.jingjingai2.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 按 JSON 键名脱敏。不是 JSON 对象/数组的原文原样返回（控制台日志请用 {@link #redactForLog}）。 */
public final class SensitiveRedactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SensitiveRedactor() {}

    public static String redact(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!(node instanceof ObjectNode) && !(node instanceof ArrayNode)) return raw;
            redactNode(node);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 控制台日志专用：比 {@link #redact} 更保守。
     *
     * <p>卡片和审计日志至少还有「给谁看」这层约束，控制台日志会被复制粘贴、被采集、长期留在终端里。
     * 所以这里<b>从不回退成原文</b>：键名脱敏不了的内容（非 JSON 的纯字符串、解析失败）只留长度。
     */
    public static String redactForLog(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!(node instanceof ObjectNode) && !(node instanceof ArrayNode)) {
                return "<非 JSON 参数，内容已省略，长度 " + raw.length() + ">";
            }
            redactNode(node);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "<参数不是合法 JSON，内容已省略，长度 " + raw.length() + ">";
        }
    }

    // ==================== 纯文本脱敏（命令、模型上报正文、日志行） ====================

    /** 私钥块：BEGIN / END 之间一律整段省略，不做逐行猜测。 */
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]{0,40}PRIVATE KEY-----");
    /** Authorization: Bearer xxx */
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    /** 命令行形式：--token=xxx / --password xxx / --api-key: xxx */
    private static final Pattern CLI_SECRET = Pattern.compile(
            "(?i)(--?(?:password|passwd|pwd|token|secret|api[-_]?key|access[-_]?key|client[-_]?secret)"
                    + "\\b\\s*(?:=|:|\\s)\\s*)[\"']?[^\\s\"',;]{1,}");
    /** 键值形式：token=xxx / "api_key": "xxx" / OPENAI_API_KEY=xxx / PASSWORD: xxx / 密码：xxx */
    private static final Pattern KEYED_SECRET = Pattern.compile(
            "(?i)(\\b[A-Za-z0-9_.-]{0,32}(?:password|passwd|pwd|token|secret|api[-_]?key|credentials?"
                    + "|authorization|密码|口令|令牌|密钥)\\b[\"']?\\s*[:=]\\s*)[\"']?[^\\s\"',;}\\]\\r\\n]{1,}");
    /**
     * 中文键名后面常常直接跟空格，没有 {@code :} / {@code =}：{@code 密码 Hunter2}、{@code 密钥 sk-xxx}。
     * 所以单独一条规则，并且只吃「像凭证的值」（ASCII 字母数字与常见符号、长度 ≥ 6），
     * 避免把 {@code 密码字段} 这种正常中文一起吃掉。
     */
    private static final Pattern CJK_KEYED_SECRET = Pattern.compile(
            "((?:密码|口令|令牌|密钥|私钥)\\s*[:=：]?\\s*)[A-Za-z0-9_\\-+=/.]{6,}");
    /** 常见厂商 Key 的形状（前缀 + 足够长的随机串）。只兜底，不能当密钥扫描器用。 */
    private static final Pattern WELL_KNOWN_TOKEN = Pattern.compile(
            "(?i)\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{12,}"
                    + "|\\bgh[pousr]_[A-Za-z0-9]{16,}"
                    + "|\\bgithub_pat_[A-Za-z0-9_]{20,}"
                    + "|\\bxox[abprs]-[A-Za-z0-9-]{10,}"
                    + "|\\bAKIA[0-9A-Z]{12,}"
                    + "|\\bAIza[0-9A-Za-z_-]{20,}");

    /**
     * 对<b>任意文本</b>做脱敏，用于不能按 JSON 键名判断的地方：
     * 计划证据里的命令原文、模型上报的 actualResult、写到控制台的日志行。
     *
     * <p>与 {@link #redact} 的分工：{@code redact} 只在能解析成 JSON 时按键名打星，
     * 非 JSON 原样返回（审批卡片依赖这个语义，不能改）；{@code redactText} 不解析结构，
     * 只按「秘密长什么样」替换，所以对命令行、自由文本也有效。
     *
     * <p><b>它不能替代"不要保存"</b>：正则认不出任意形态的秘密。
     * 因此原始命令/原始上报正文只在本次请求的内存里参与判定，落库与推送只用本方法处理后的结果，
     * 命令证据的键更是直接用不可逆指纹（见 {@link #fingerprint}）。
     *
     * @return 脱敏后的文本；入参为 null / 空白时原样返回
     */
    public static String redactText(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String out = PRIVATE_KEY_BLOCK.matcher(raw).replaceAll("[私钥内容已省略]");
        out = BEARER.matcher(out).replaceAll("$1***");
        out = CLI_SECRET.matcher(out).replaceAll("$1***");
        out = KEYED_SECRET.matcher(out).replaceAll("$1***");
        out = CJK_KEYED_SECRET.matcher(out).replaceAll("$1***");
        out = WELL_KNOWN_TOKEN.matcher(out).replaceAll("***");
        return out;
    }

    /**
     * 不可逆指纹（SHA-256 前 32 个十六进制字符）。
     *
     * <p>用于「需要判断两个东西是不是同一个，但不需要保留原文」的场景：
     * 命令执行证据的键、计划步骤的重复上报键。指纹不可逆，也不会因为脱敏把两条不同的输入
     * 变成同一个键（那会让证据错误复用）。
     *
     * @return 入参为 null 时返回 null；算法不可用时退回 hashCode（标准 JRE 上不会发生）
     */
    public static String fingerprint(String raw) {
        if (raw == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static void redactNode(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = obj.get(name);
                if (sensitive(name)) {
                    obj.put(name, "***");
                } else if (child != null && child.isTextual()) {
                    // 字符串值若本身是 JSON 对象/数组，递归脱敏其内部敏感键。
                    // 典型场景：工具参数 params 是一个 JSON 字符串，里面藏着 token/password。
                    // 只对能完整解析成对象或数组的字符串生效；普通文本、数字、解析失败都原样保留。
                    JsonNode inner = tryParseObjectOrArray(child.asText());
                    if (inner != null) {
                        redactNode(inner);
                        obj.put(name, inner.toString());
                    }
                } else if (child != null) {
                    redactNode(child);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child != null && child.isTextual()) {
                    JsonNode inner = tryParseObjectOrArray(child.asText());
                    if (inner != null) {
                        redactNode(inner);
                        arr.set(i, inner.toString());
                    }
                } else if (child != null) {
                    redactNode(child);
                }
            }
        }
    }

    /** 文本能完整解析成 JSON 对象或数组时返回该节点，否则 null。 */
    private static JsonNode tryParseObjectOrArray(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() < 2 || (t.charAt(0) != '{' && t.charAt(0) != '[')) return null;
        try {
            JsonNode n = MAPPER.readTree(t);
            return (n instanceof ObjectNode || n instanceof ArrayNode) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 键名是否敏感。先去掉 {@code _} / {@code -} 再判断，所以 {@code api_key}、{@code API-KEY}
     * 与 {@code apiKey} 一致；用后缀匹配覆盖 {@code access_token}、{@code refresh_token}、
     * {@code client_secret}、{@code x-api-key}、{@code set-cookie} 这类带前缀的写法。
     */
    static boolean sensitive(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return n.equals("passwd") || n.endsWith("password")
                || n.endsWith("token") || n.endsWith("secret")
                || n.endsWith("apikey") || n.endsWith("cookie")
                || n.equals("authorization") || n.endsWith("credentials");
    }
}
