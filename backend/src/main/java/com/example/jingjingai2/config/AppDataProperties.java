package com.example.jingjingai2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * 本地数据根目录的配置入口（{@code app.data.*}）。
 *
 * <p>本类只解析路径，不创建目录，也不改 SQLite、工作区、MCP 落盘的实际配置。
 * {@code development}（默认）不强制数据根，现有相对路径保持原样。
 * {@code desktop} 必须得到绝对根目录：{@code JINGJING_DATA_DIR}，否则
 * {@code %LOCALAPPDATA%\BearCoding}。两者都没有时失败，不回退到当前工作目录。
 */
@Component
@ConfigurationProperties(prefix = "app.data")
public class AppDataProperties {

    public static final String MODE_DEVELOPMENT = "development";
    public static final String MODE_DESKTOP = "desktop";
    public static final String ENV_DATA_DIR = "JINGJING_DATA_DIR";
    public static final String ENV_LOCAL_APPDATA = "LOCALAPPDATA";
    public static final String DESKTOP_DIR_NAME = "BearCoding";

    private String mode = MODE_DEVELOPMENT;
    private String root = "";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }

    /** 用当前进程环境变量解析。development 下 {@link Layout#root()} 为空。 */
    public Layout resolve() {
        return resolve(System.getenv());
    }

    /** 测试注入环境变量。不读真实进程环境，也不碰磁盘。 */
    public Layout resolve(Map<String, String> env) {
        String selected = mode == null || mode.isBlank() ? MODE_DEVELOPMENT : mode.trim();
        if (MODE_DEVELOPMENT.equalsIgnoreCase(selected)) {
            return new Layout(MODE_DEVELOPMENT, null);
        }
        if (!MODE_DESKTOP.equalsIgnoreCase(selected)) {
            throw new IllegalStateException("app.data.mode 只能是 development 或 desktop，当前为: " + selected);
        }
        return new Layout(MODE_DESKTOP, desktopRoot(env, root));
    }

    /**
     * 桌面模式的数据根。优先级：环境变量、配置项 {@code app.data.root}、LOCALAPPDATA。
     * 显式路径若是相对路径，直接失败，不用当前工作目录补全。
     */
    static Path desktopRoot(Map<String, String> env, String configuredRoot) {
        String explicit = firstNonBlank(
                env == null ? null : env.get(ENV_DATA_DIR),
                configuredRoot);
        if (explicit != null) {
            Path path = Path.of(explicit.trim());
            if (!path.isAbsolute()) {
                throw new IllegalStateException(
                        "桌面模式不接受相对数据目录（不会回退到当前工作目录）: " + explicit.trim());
            }
            return path.normalize();
        }
        String local = env == null ? null : env.get(ENV_LOCAL_APPDATA);
        if (local == null || local.isBlank()) {
            throw new IllegalStateException(
                    "桌面模式无法确定数据目录：未设置 " + ENV_DATA_DIR + "，且没有 " + ENV_LOCAL_APPDATA);
        }
        Path base = Path.of(local.trim());
        if (!base.isAbsolute()) {
            throw new IllegalStateException(ENV_LOCAL_APPDATA + " 不是绝对路径: " + local.trim());
        }
        return base.resolve(DESKTOP_DIR_NAME).normalize();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return null;
    }

    /** 一次解析结果。子目录只在桌面模式（已有绝对根）下可用。 */
    public static final class Layout {
        private final String mode;
        private final Path root;

        Layout(String mode, Path root) {
            this.mode = mode;
            this.root = root;
        }

        public String mode() { return mode; }

        public boolean enforcesRoot() { return root != null; }

        public Path root() {
            if (root == null) {
                throw new IllegalStateException("development 模式不强制数据根目录");
            }
            return root;
        }

        public Path data() { return child("data"); }
        public Path workspaces() { return child("workspaces"); }
        public Path mcpOutput() { return child("mcp-output"); }
        public Path logs() { return child("logs"); }
        public Path config() { return child("config"); }
        public Path secrets() { return child("secrets"); }

        private Path child(String name) {
            return root().resolve(name).normalize();
        }
    }
}
