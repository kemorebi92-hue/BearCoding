package com.example.jingjingai2.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import com.example.jingjingai2.security.DesktopMcpKey;
import com.example.jingjingai2.security.DpapiProtector;
import com.example.jingjingai2.security.WindowsDpapiProtector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sqlite profile 专用：在任何单例（尤其是 DataSource 与 {@code spring.sql.init}）创建之前，
 * 先确保数据库文件所在目录存在。
 *
 * <p>SQLite 的 JDBC 驱动不会自动创建父目录，路径不存在时直接报
 * 「path to './data/jingjing.db': … does not exist」。
 *
 * <p><strong>为什么是 BeanFactoryPostProcessor 而不是 {@code @PostConstruct}：</strong>
 * 后者依赖 Bean 实例化顺序，实测会晚于 {@code dataSourceScriptDatabaseInitializer}，
 * 导致首次启动必然失败。BeanFactoryPostProcessor 在<strong>所有单例实例化之前</strong>执行，
 * 与 Bean 注册顺序无关，是确定性的。
 *
 * <p>建不出来就抛异常让启动失败，<strong>不会静默回退到 MySQL</strong>。
 * 只在 {@code sqlite} profile 下注册，默认（MySQL）路径完全不受影响。
 */
@Configuration
public class SqliteDataDirectoryConfig implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";
    private static final String URL_KEY = "spring.datasource.url";
    static final String DESKTOP_URL_SOURCE = "sqliteDesktopUrl";
    /** 开发库的固定相对位置。桌面模式只从这里复制，不随启动目录改变语义。 */
    static final String LEGACY_DATABASE = "./data/jingjing.db";

    private Environment environment;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            ensureParentDirectory(environment.getProperty(URL_KEY));
            return;
        }
        prepare(configurable, System.getenv(), Paths.get(LEGACY_DATABASE));
    }

    /** 先按模式改 URL、准备文件，再创建最终 URL 的父目录。DataSource 尚未创建。 */
    static void prepare(ConfigurableEnvironment environment, Map<String, String> env, Path legacyDatabase) {
        applyDesktopUrl(environment, env, legacyDatabase, new WindowsDpapiProtector());
        ensureParentDirectory(environment.getProperty(URL_KEY));
    }

    static void applyDesktopUrl(ConfigurableEnvironment environment, Map<String, String> env,
                                Path legacyDatabase) {
        applyDesktopUrl(environment, env, legacyDatabase, new WindowsDpapiProtector());
    }

    /**
     * 桌面模式把 JDBC URL 改成绝对路径，并在任何连接之前准备数据库和 MCP 密钥。
     * development 什么都不做。密钥文件的判断发生在 SQLite 创建数据库文件之前。
     */
    static void applyDesktopUrl(ConfigurableEnvironment environment, Map<String, String> env,
                                Path legacyDatabase, DpapiProtector protector) {
        AppDataProperties props = new AppDataProperties();
        props.setMode(environment.getProperty("app.data.mode"));
        props.setRoot(environment.getProperty("app.data.root"));
        AppDataProperties.Layout layout = props.resolve(env);
        if (!layout.enforcesRoot()) return;
        Path target = SqliteDatabasePrep.databaseFile(layout.root());
        boolean copyLegacy = environment.getProperty("app.data.copy-legacy-database", Boolean.class, false);
        SqliteDatabasePrep.prepare(target, copyLegacy ? legacyDatabase : null);
        String keyMaterial = DesktopMcpKey.resolve(layout.root(), target, protector);
        String modelKey = com.example.jingjingai2.security.DpapiPurposeKey.resolve(
                layout.root(), target, protector, "model-aes.key", "模型 API Key 密钥");
        String artifactKey = com.example.jingjingai2.security.DpapiPurposeKey.resolve(
                layout.root(), target, protector, "artifact-ref.key", "工具引用签名密钥");
        Map<String, Object> overridden = desktopOverrides(target, layout, keyMaterial);
        overridden.put("app.model.key-material", modelKey);
        overridden.put("app.artifact.key-material", artifactKey);
        overridden.put("app.edit.audit-log", layout.logs().resolve("edit-audit.log").toString());
        environment.getPropertySources().addFirst(new MapPropertySource(DESKTOP_URL_SOURCE, overridden));
    }

    /** 桌面模式最终要覆盖的路径和密钥。调用前数据库复制已经完成，SQLite 尚未连接。 */
    public static Map<String, Object> desktopOverrides(Path database, AppDataProperties.Layout layout,
                                                       String keyMaterial) {
        Map<String, Object> overridden = new LinkedHashMap<>();
        overridden.put(URL_KEY, SQLITE_PREFIX + database);
        overridden.put("app.workspace.root", layout.workspaces().toString());
        overridden.put("app.mcp.output.dir", layout.mcpOutput().toString());
        overridden.put(DesktopMcpKey.PROPERTY, keyMaterial);
        return overridden;
    }

    /**
     * 创建 {@code jdbc:sqlite:<path>} 的父目录。
     *
     * @return 创建（或已存在）的目录；内存库或无法解析出目录时返回 {@code null}
     * @throws IllegalStateException 目录存在但无法创建
     */
    public static Path ensureParentDirectory(String jdbcUrl) {
        Path parent = parentDirectory(jdbcUrl);
        if (parent == null) return null;
        try {
            Files.createDirectories(parent);
            return parent;
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 SQLite 数据目录: " + parent, e);
        }
    }

    /** 解析 SQLite JDBC URL 的文件父目录。纯函数，不碰磁盘。 */
    public static Path parentDirectory(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        String raw = jdbcUrl.trim();
        if (!raw.startsWith(SQLITE_PREFIX)) return null;
        String path = raw.substring(SQLITE_PREFIX.length());
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if (path.isBlank()) return null;
        // 内存库：jdbc:sqlite::memory:
        if (path.startsWith(":")) return null;
        return Paths.get(path).toAbsolutePath().getParent();
    }
}
