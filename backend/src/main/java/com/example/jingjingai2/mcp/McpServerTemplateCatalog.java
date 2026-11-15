package com.example.jingjingai2.mcp;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经过审核、可对普通用户公开的 MCP 服务模板目录。
 *
 * <p>模板是后端硬编码的受控配置，<strong>不是</strong>模型自由生成的命令。第一版只收
 * 本项目已经实际使用、无需秘密凭证的模板——{@code playwright}（headless Edge，本地浏览器，
 * 已在 {@code mcp-run.yml} / {@code docs/MCP-ARCHITECTURE.md} / {@code PlaywrightPermissionLiveTest}
 * 中验证）。不要为凑数引入未经审核的第三方 MCP 包。
 *
 * <p>env 值在构建时从 {@link System#getenv(String)} 解析：playwright 在 Windows 上需要
 * USERPROFILE / APPDATA 等系统路径才能让 msedge 找到配置目录——这些是系统路径，不是秘密。
 * 解析时跳过本机不存在的键（跨平台兼容）。值会原样进 {@code env_json}，运行时由
 * {@link StdioMcpSession#applyMinimalEnv} 叠在最小 PATH 之上。
 *
 * <p>方法非 final：测试可子类化覆盖 {@link #all()} / {@link #findById(String)} 注入测试模板。
 */
@Component
public class McpServerTemplateCatalog {

    private final List<McpServerTemplate> templates;

    public McpServerTemplateCatalog() {
        this.templates = List.of(playwright());
    }

    /** 可对用户展示的全部模板。不含秘密。 */
    public List<McpServerTemplate> all() {
        return templates;
    }

    /** 按稳定 ID 查；不存在返回 null。 */
    public McpServerTemplate findById(String id) {
        if (id == null || id.isBlank()) return null;
        for (McpServerTemplate t : templates) {
            if (id.equals(t.id())) return t;
        }
        return null;
    }

    /**
     * playwright 模板：固定 {@code npx -y @playwright/mcp@0.0.83 --isolated --browser msedge
     * --output-dir target/playwright-mcp-output}，固定 8 个 Windows 系统路径 env，无用户可填参数。
     * {@code ProgramFiles(x86)} 不能省：本机 Edge 常装在这个目录，而 Playwright 不会从父进程继承它。
     * 用户只提供 serviceName。
     */
    private McpServerTemplate playwright() {
        Map<String, String> env = new LinkedHashMap<>();
        // 顺序与 mcp-run.yml 一致；null 值跳过（非 Windows 或本机缺失时）。
        putSystemEnv(env, "USERPROFILE");
        putSystemEnv(env, "APPDATA");
        putSystemEnv(env, "LOCALAPPDATA");
        putSystemEnv(env, "HOMEDRIVE");
        putSystemEnv(env, "HOMEPATH");
        putSystemEnv(env, "ProgramFiles");
        putSystemEnv(env, "ProgramFiles(x86)");
        putSystemEnv(env, "ProgramW6432");
        return new McpServerTemplate(
                "playwright",
                "Playwright 浏览器（headless Edge）",
                "用 npx 拉起 @playwright/mcp@0.0.83（本项目已验证），headless Edge 驱动，无需秘密凭证。"
                        + "Windows 平台相关：env 需 USERPROFILE/APPDATA 等系统路径，模板已固定解析。"
                        + "保存后默认禁用，需到 MCP 管理页手动启用才会启动 msedge 子进程。",
                "npx",
                List.of("-y", "@playwright/mcp@0.0.83", "--isolated", "--browser", "msedge",
                        "--output-dir", "target/playwright-mcp-output"),
                env,
                "untrusted",
                60,
                6000,
                List.of()       // 该模板不接受任何用户参数
        );
    }

    private static void putSystemEnv(Map<String, String> env, String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) env.put(key, v);
    }
}
