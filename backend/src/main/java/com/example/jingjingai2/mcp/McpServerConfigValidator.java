package com.example.jingjingai2.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运行时新增服务的配置校验。只约束从管理接口进来的配置，
 * YAML 里的部署级服务不经过这里。
 *
 * <p>命令白名单只是首版的收窄，不是沙箱：{@code npx} 和 {@code uvx} 本身会拉取并执行
 * 第三方代码，允许它们不等于限制了能跑什么程序。
 *
 * <p>传输分支：{@code stdio}（缺省）走命令白名单；{@code streamable_http} 要求 baseUrl，
 * 且不得带 command。本阶段不持久化 Bearer 密钥，{@code authType=bearer} 直接拒绝保存。
 */
public final class McpServerConfigValidator {

    /** 精确命令名。不含路径、扩展名；Windows 上由启动层按 PATHEXT 解析成 .cmd。 */
    public static final Set<String> ALLOWED_COMMANDS = Set.of("npx", "uvx");

    public static final int MAX_NAME_LEN = 32;
    public static final int MAX_ARGS = 32;
    public static final int MAX_ARG_LEN = 512;
    public static final int MAX_ARGS_TOTAL = 4096;
    public static final int MAX_ENV = 16;
    public static final int MAX_ENV_KEY_LEN = 64;
    public static final int MAX_ENV_VALUE_LEN = 1024;
    public static final int MAX_DISABLED_TOOLS = 64;
    public static final int MAX_BASE_URL_LEN = 512;
    public static final int MAX_ENDPOINT_PATH_LEN = 256;
    public static final String DEFAULT_ENDPOINT_PATH = "/mcp";

    public static final String RUNTIME_HTTP_BEARER_NOT_READY =
            "远程 HTTP 目前仅支持 authType=none；Bearer 凭证尚未接入运行时";

    public static final String AUTH_BEARER_NOT_PERSISTABLE =
            "Bearer 凭证安全存储尚未启用，本阶段只能保存无认证（authType=none）的远程配置";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    /** Windows 环境变量名可以带括号，例如 {@code ProgramFiles(x86)}。仍拒绝空格、等号和路径分隔符。 */
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_()]*");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {};

    private McpServerConfigValidator() {}

    /** 服务名：小写字母数字和连字符，且要能放进 {@code mcp__{name}__} 的名字预算。 */
    public static String checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return "服务名只能是小写字母、数字和连字符，最长 " + MAX_NAME_LEN + " 个字符";
        }
        return null;
    }

    /**
     * 精确命令名。拒绝路径、路径分隔符、扩展名和首尾空白。
     * 返回 null 表示通过。
     */
    public static String checkCommand(String command) {
        if (command == null) return "命令不能为空";
        if (!command.equals(command.trim())) return "命令首尾不能有空白";
        if (command.indexOf('/') >= 0 || command.indexOf('\\') >= 0 || command.indexOf('.') >= 0) {
            return "命令只能是命令名，不能是路径";
        }
        if (!ALLOWED_COMMANDS.contains(command)) {
            return "命令不在允许范围内：" + String.join("、", ALLOWED_COMMANDS);
        }
        return null;
    }

    /** 参数数量、单项长度、总长度。不拼成 shell 字符串，启动时仍走参数数组。 */
    public static String checkArgs(List<String> args) {
        if (args == null || args.isEmpty()) return null;
        if (args.size() > MAX_ARGS) return "参数最多 " + MAX_ARGS + " 个";
        int total = 0;
        for (String a : args) {
            if (a == null) return "参数不能含空项";
            if (a.length() > MAX_ARG_LEN) return "单个参数最长 " + MAX_ARG_LEN + " 个字符";
            total += a.length();
        }
        if (total > MAX_ARGS_TOTAL) return "参数总长最多 " + MAX_ARGS_TOTAL + " 个字符";
        return null;
    }

    /** 环境变量的数量、键格式、值长度。不记录值。 */
    public static String checkEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) return null;
        if (env.size() > MAX_ENV) return "环境变量最多 " + MAX_ENV + " 个";
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey();
            if (key == null || !ENV_KEY.matcher(key).matches() || key.length() > MAX_ENV_KEY_LEN) {
                return "环境变量名不合法";
            }
            String value = e.getValue();
            if (value == null) return "环境变量值不能为空";
            if (value.length() > MAX_ENV_VALUE_LEN) return "环境变量值最长 " + MAX_ENV_VALUE_LEN + " 个字符";
        }
        return null;
    }

    public static String checkDisabledTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) return null;
        if (tools.size() > MAX_DISABLED_TOOLS) return "排除的工具最多 " + MAX_DISABLED_TOOLS + " 个";
        for (String t : tools) {
            if (t == null || t.isBlank() || t.length() > 128) return "排除的工具名不合法";
        }
        return null;
    }

    /** trust 只接受这两个值，别的写法一律拒绝，避免悄悄变成默认。 */
    public static String checkTrust(String trust) {
        if (trust == null) return null;
        String t = trust.trim().toLowerCase(Locale.ROOT);
        if ("trusted".equals(t) || "untrusted".equals(t)) return null;
        return "trust 只能是 trusted 或 untrusted";
    }

    public static String checkTimeout(Integer seconds) {
        if (seconds == null) return null;
        if (seconds < 0 || seconds > 600) return "连接超时必须在 0 到 600 秒之间";
        return null;
    }

    public static String checkMaxResultChars(Integer chars) {
        if (chars == null) return null;
        if (chars < 0 || chars > 1_000_000) return "结果长度上限不合法";
        return null;
    }

    /**
     * 传输类型：缺省/空白视为 stdio（兼容）；显式非法值拒绝，不静默映射。
     */
    public static String checkTransport(String transport) {
        if (transport == null || transport.isBlank()) return null;
        String t = transport.trim();
        if (com.example.jingjingai2.entity.McpServerConfig.TRANSPORT_STDIO.equals(t)
                || com.example.jingjingai2.entity.McpServerConfig.TRANSPORT_STREAMABLE_HTTP.equals(t)) {
            return null;
        }
        return "transport 只能是 stdio 或 streamable_http";
    }

    /**
     * 基础 URL 格式校验（非 SSRF 防护）：要求绝对 URI、http(s)、有 host、无 userinfo。
     */
    public static String checkBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "远程 baseUrl 不能为空";
        String raw = baseUrl.trim();
        if (raw.length() > MAX_BASE_URL_LEN) return "baseUrl 过长";
        if (!raw.equals(baseUrl)) return "baseUrl 首尾不能有空白";
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (Exception e) {
            return "baseUrl 无法解析";
        }
        if (!uri.isAbsolute()) return "baseUrl 必须是绝对地址";
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return "baseUrl 只允许 http 或 https";
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
            return "baseUrl 不能含用户信息（userinfo）";
        }
        String authority = uri.getRawAuthority();
        if (authority != null && authority.contains("@")) {
            return "baseUrl 不能含用户信息（userinfo）";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) return "baseUrl 缺少主机名";
        // baseUrl 不应携带 query/fragment；path 应放在 endpointPath。
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "baseUrl 不能含 query 或 fragment；路径请放在 endpointPath";
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return "baseUrl 不要带路径；路径请放在 endpointPath（默认 /mcp）";
        }
        return null;
    }

    public static String checkEndpointPath(String endpointPath) {
        if (endpointPath == null || endpointPath.isBlank()) return null;
        String p = endpointPath.trim();
        if (p.length() > MAX_ENDPOINT_PATH_LEN) return "endpointPath 过长";
        if (!p.equals(endpointPath)) return "endpointPath 首尾不能有空白";
        if (!p.startsWith("/")) return "endpointPath 必须以 / 开头";
        if (p.contains("://") || p.contains("?") || p.contains("#") || p.contains(" ")) {
            return "endpointPath 不合法";
        }
        return null;
    }

    public static String checkAuthType(String authType, boolean streamableHttp) {
        if (authType == null || authType.isBlank()) return null;
        String a = authType.trim();
        if (com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE.equals(a)) return null;
        if (com.example.jingjingai2.entity.McpServerConfig.AUTH_BEARER.equals(a)) {
            // 可以保存认证方式。令牌不在这张配置里，运行时另从凭证表读取。
            return null;
        }
        return "authType 只能是 none 或 bearer";
    }

    /**
     * 只用于新建。把空 trust、空传输、HTTP 的空路径和认证写法收成与 {@code saveDisabled} 原来一致的结果，
     * HTTP 时清掉不适用的 command/args/env。不设置归属和 enabled。
     * 不用于部分更新：更新里「没传的键」必须保留原值，不能在这里被补默认值或清空。
     * 对已经规范化过的合法配置再调用一次，传输、命令、参数、URL、路径和认证类型不变。
     */
    public static void normalizeForCreate(com.example.jingjingai2.entity.McpServerConfig config) {
        if (config == null) return;
        if (config.getTrust() == null || config.getTrust().isBlank()) {
            config.setTrust("untrusted");
        }
        if (config.getTransport() == null || config.getTransport().isBlank()) {
            config.setTransport(com.example.jingjingai2.entity.McpServerConfig.TRANSPORT_STDIO);
        } else {
            config.setTransport(config.getTransport().trim());
        }
        if (config.isStreamableHttp()) {
            config.setCommand(null);
            if (config.getEndpointPath() == null || config.getEndpointPath().isBlank()) {
                config.setEndpointPath(DEFAULT_ENDPOINT_PATH);
            } else {
                config.setEndpointPath(config.getEndpointPath().trim());
            }
            if (config.getBaseUrl() != null) config.setBaseUrl(config.getBaseUrl().trim());
            config.setAuthType(com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE.equals(config.normalizedAuthType())
                    ? com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE
                    : config.getAuthType());
            config.setArgsJson(null);
            config.setEnvJson(null);
        } else if (config.getAuthType() == null || config.getAuthType().isBlank()) {
            config.setAuthType(com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE);
        }
    }

    /** 全部校验，返回第一条错误；通过返回 null。不修改配置。 */
    public static String check(com.example.jingjingai2.entity.McpServerConfig config) {
        if (config == null) return "配置不能为空";
        String err = checkName(config.getName());
        if (err != null) return err;
        err = checkTransport(config.getTransport());
        if (err != null) return err;

        boolean http = config.isStreamableHttp();
        if (http) {
            err = checkBaseUrl(config.getBaseUrl());
            if (err != null) return err;
            // SSRF 边界：与工具参数 UrlGuard 分离；失败原因可直接返回给管理 API。
            err = McpEndpointGuard.rejectReason(config.getBaseUrl());
            if (err != null) return "远程 endpoint 不被允许（" + err + "）";
            err = checkEndpointPath(config.getEndpointPath());
            if (err != null) return err;
            err = checkAuthType(config.getAuthType(), true);
            if (err != null) return err;
            if (config.getCommand() != null && !config.getCommand().isBlank()) {
                return "streamable_http 配置不能填写 command";
            }
            List<String> args = readStringList(config.getArgsJson());
            if (args != null && !args.isEmpty()) {
                return "streamable_http 配置不能填写 args";
            }
            Map<String, String> env = readStringMap(config.getEnvJson());
            if (env != null && !env.isEmpty()) {
                return "streamable_http 配置不能填写 env（凭证不落库）";
            }
        } else {
            err = checkCommand(config.getCommand());
            if (err != null) return err;
            err = checkArgs(readStringList(config.getArgsJson()));
            if (err != null) return err;
            err = checkEnv(readStringMap(config.getEnvJson()));
            if (err != null) return err;
            if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                return "stdio 配置不能填写 baseUrl";
            }
            if (config.getEndpointPath() != null && !config.getEndpointPath().isBlank()) {
                return "stdio 配置不能填写 endpointPath";
            }
            // stdio 忽略 authType，但显式 bearer 仍拒绝，避免误存
            if (config.getAuthType() != null && !config.getAuthType().isBlank()
                    && !com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE.equals(config.normalizedAuthType())) {
                return "stdio 配置的 authType 只能是 none 或留空";
            }
        }

        err = checkDisabledTools(readStringList(config.getDisabledToolsJson()));
        if (err != null) return err;
        err = checkTrust(config.getTrust());
        if (err != null) return err;
        err = checkTimeout(config.getConnectTimeoutSeconds());
        if (err != null) return err;
        return checkMaxResultChars(config.getMaxResultChars());
    }

    /**
     * 当前运行时是否允许把该配置拉进 Slot/连接。
     * HTTP 配置可落库，但阶段 2C 尚未接入 {@code HttpMcpSession}。
     */
    public static String checkRuntimeSupported(com.example.jingjingai2.entity.McpServerConfig config) {
        if (config == null) return "配置不能为空";
        return null;
    }

    /** 校验通过后转成启动用的规格。调用方必须先 {@link #check}。 */
    public static McpServerSpec toSpec(com.example.jingjingai2.entity.McpServerConfig config) {
        McpServerSpec spec = new McpServerSpec();
        String transport = config.normalizedTransport();
        spec.setTransport(transport);
        if (config.isStreamableHttp()) {
            spec.setBaseUrl(config.getBaseUrl() == null ? null : config.getBaseUrl().trim());
            String path = config.getEndpointPath();
            if (path == null || path.isBlank()) path = DEFAULT_ENDPOINT_PATH;
            else path = path.trim();
            spec.setEndpointPath(path);
            spec.setAuthType(config.normalizedAuthType());
            spec.setServerConfigId(config.getId());
            // command/args/env 保持默认空
        } else {
            spec.setCommand(config.getCommand());
            spec.setArgs(readStringList(config.getArgsJson()));
            spec.setEnv(readStringMap(config.getEnvJson()));
            spec.setAuthType(com.example.jingjingai2.entity.McpServerConfig.AUTH_NONE);
        }
        spec.setDisabledTools(readStringList(config.getDisabledToolsJson()));
        if (config.getTrust() != null && !config.getTrust().isBlank()) spec.setTrust(config.getTrust());
        if (config.getConnectTimeoutSeconds() != null) {
            spec.setConnectTimeoutSeconds(config.getConnectTimeoutSeconds());
        }
        if (config.getMaxResultChars() != null) spec.setMaxResultChars(config.getMaxResultChars());
        return spec;
    }

    public static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> list = MAPPER.readValue(json, STRING_LIST);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> map = MAPPER.readValue(json, STRING_MAP);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 只用于日志：敏感键只留键名。绝不返回值。 */
    public static String envKeysForLog(Map<String, String> env) {
        if (env == null || env.isEmpty()) return "无";
        List<String> parts = new ArrayList<>();
        for (String key : env.keySet()) {
            parts.add(McpClientManager.isSensitiveEnvKey(key) ? key + "(敏感)" : key);
        }
        return String.join(",", parts);
    }
}
