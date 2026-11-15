package com.example.jingjingai2.security;

import com.example.jingjingai2.config.AppDataProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 本机搜索 API Key 的加密存储。
 *
 * <p>落盘位置：
 * <ul>
 *   <li>桌面模式：{@code %LOCALAPPDATA%\BearCoding\secrets\search-credential.bin}</li>
 *   <li>开发模式：{@code ./data/secrets/search-credential.bin}（可用
 *       {@code app.search.credential-file} 指定绝对路径）</li>
 * </ul>
 *
 * <p>文件里只有 DPAPI（CurrentUser）密文，没有明文、没有可跨机器复用的密钥。
 * 换 Windows 用户或换机器时解密会明确失败，<strong>不会</strong>静默生成一把新密钥
 * 去“读”旧密文。本类不记录、不打印密钥内容。
 *
 * <p>明文载荷格式：{@code provider + "\n" + apiKey}（两者都不允许包含换行）。
 * provider 可以为空，此时沿用 {@code app.search.provider}。
 */
@Component
public class SearchCredentialStore {

    public static final String MAGIC = "JJSK";
    public static final int VERSION = 1;
    public static final String FILE_NAME = "search-credential.bin";
    private static final int HEADER = MAGIC.length() + 1;
    /** 正常凭证远小于这个值；防止误把大文件当凭证读进内存。 */
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private final Path file;
    private final DpapiProtector protector;

    /** Spring 构造器：按数据根解析默认路径。 */
    @Autowired
    public SearchCredentialStore(AppDataProperties appDataProperties,
                                 @Value("${app.search.credential-file:}") String configuredFile) {
        this(resolveFile(appDataProperties, configuredFile), new WindowsDpapiProtector());
    }

    /** 测试/CLI 构造器：直接指定文件与 DPAPI 实现。 */
    public SearchCredentialStore(Path file, DpapiProtector protector) {
        if (file == null) throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "搜索凭证路径不能为空");
        if (protector == null) throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "缺少 DPAPI 实现");
        this.file = file.toAbsolutePath().normalize();
        this.protector = protector;
    }

    /**
     * 解析凭证文件位置。显式配置优先；否则桌面模式落在数据根 {@code secrets\}
     * 下，开发模式落在 {@code ./data/secrets}（与现有开发数据目录同级）。
     */
    public static Path resolveFile(AppDataProperties appDataProperties, String configuredFile) {
        if (configuredFile != null && !configuredFile.isBlank()) {
            Path explicit = Path.of(configuredFile.trim());
            if (!explicit.isAbsolute()) {
                throw new IllegalStateException("app.search.credential-file 必须是绝对路径: " + configuredFile.trim());
            }
            return explicit.normalize();
        }
        if (appDataProperties != null) {
            AppDataProperties.Layout layout = appDataProperties.resolve();
            if (layout.enforcesRoot()) {
                return layout.secrets().resolve(FILE_NAME).normalize();
            }
        }
        return Path.of("data", "secrets", FILE_NAME).toAbsolutePath().normalize();
    }

    public Path path() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * 保存一份新凭证。默认不覆盖已有文件；{@code replace=true} 才允许替换。
     *
     * @throws DpapiException 已存在且未允许覆盖、DPAPI 失败、写盘失败或回读校验失败
     */
    public void save(String provider, String apiKey, boolean replace) {
        String key = normalizeKey(apiKey);
        String prov = normalizeProvider(provider);
        if (Files.exists(file) && !replace) {
            throw new DpapiException(DpapiException.Kind.IO,
                    "本机已存在加密搜索凭证，拒绝覆盖。要替换请显式允许覆盖。");
        }
        byte[] plaintext = (prov + "\n" + key).getBytes(StandardCharsets.UTF_8);
        byte[] protectedBytes = protector.protect(plaintext);
        byte[] body = new byte[HEADER + protectedBytes.length];
        System.arraycopy(MAGIC.getBytes(StandardCharsets.US_ASCII), 0, body, 0, MAGIC.length());
        body[MAGIC.length()] = VERSION;
        System.arraycopy(protectedBytes, 0, body, HEADER, protectedBytes.length);

        Path temp = file.resolveSibling(file.getFileName() + ".writing");
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            write(temp, body);
            DpapiKeyFile.restrictToCurrentUser(temp);
            byte[] readBack = unprotectBody(readAll(temp));
            if (!Arrays.equals(plaintext, readBack)) {
                throw new DpapiException(DpapiException.Kind.IO, "搜索凭证回读校验失败");
            }
            move(temp, file);
        } catch (DpapiException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new DpapiException(DpapiException.Kind.IO, "无法写入搜索凭证文件", e);
        }
    }

    /**
     * 读取并解密。文件不存在抛 {@code NOT_FOUND}；损坏/换用户/换机器抛
     * {@code FORMAT}/{@code UNPROTECT_FAILED}，消息里只有原因，不含密钥或密文。
     */
    public StoredCredential load() {
        if (!Files.isRegularFile(file)) {
            throw new DpapiException(DpapiException.Kind.NOT_FOUND, "本机还没有保存搜索凭证");
        }
        byte[] plaintext = unprotectBody(readAll(file));
        String text = new String(plaintext, StandardCharsets.UTF_8);
        int split = text.indexOf('\n');
        if (split < 0) {
            throw new DpapiException(DpapiException.Kind.FORMAT, "搜索凭证内容格式不正确");
        }
        String provider = text.substring(0, split).trim();
        String apiKey = text.substring(split + 1).trim();
        if (apiKey.isEmpty()) {
            throw new DpapiException(DpapiException.Kind.FORMAT, "搜索凭证内容为空");
        }
        return new StoredCredential(provider, apiKey);
    }

    /** 删除本机凭证（停用搜索）。返回是否真的删除了文件。 */
    public boolean delete() {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new DpapiException(DpapiException.Kind.IO, "无法删除搜索凭证文件", e);
        }
    }

    private byte[] readAll(Path path) {
        try {
            long size = Files.size(path);
            if (size <= HEADER || size > MAX_FILE_BYTES) {
                throw new DpapiException(DpapiException.Kind.FORMAT, "搜索凭证文件为空、过短或过大");
            }
            return Files.readAllBytes(path);
        } catch (DpapiException e) {
            throw e;
        } catch (IOException e) {
            throw new DpapiException(DpapiException.Kind.IO, "无法读取搜索凭证文件", e);
        }
    }

    private byte[] unprotectBody(byte[] body) {
        if (body.length <= HEADER) {
            throw new DpapiException(DpapiException.Kind.FORMAT, "搜索凭证文件为空或过短");
        }
        String magic = new String(body, 0, MAGIC.length(), StandardCharsets.US_ASCII);
        if (!MAGIC.equals(magic)) {
            throw new DpapiException(DpapiException.Kind.FORMAT, "搜索凭证文件格式不正确");
        }
        int version = body[MAGIC.length()] & 0xff;
        if (version != VERSION) {
            throw new DpapiException(DpapiException.Kind.VERSION, "不支持的搜索凭证文件版本");
        }
        byte[] protectedBytes = Arrays.copyOfRange(body, HEADER, body.length);
        try {
            return protector.unprotect(protectedBytes);
        } catch (DpapiException e) {
            throw new DpapiException(e.kind(),
                    "搜索凭证无法解密：可能不是当前 Windows 用户加密的，或文件已损坏。请重新导入。");
        }
    }

    private static void write(Path temp, byte[] body) throws IOException {
        Files.deleteIfExists(temp);
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(body);
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 保留原始失败原因。
        }
    }

    private static String normalizeKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "搜索 API Key 不能为空");
        }
        String key = apiKey.trim();
        if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "搜索 API Key 不能包含换行");
        }
        return key;
    }

    private static String normalizeProvider(String provider) {
        if (provider == null) return "";
        String value = provider.trim();
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "搜索服务方名称不能包含换行");
        }
        return value;
    }

    /** 解密后的凭证。{@link #toString()} 不包含密钥，避免误打日志。 */
    public static final class StoredCredential {
        private final String provider;
        private final String apiKey;

        StoredCredential(String provider, String apiKey) {
            this.provider = provider == null ? "" : provider;
            this.apiKey = apiKey;
        }

        public String provider() { return provider; }

        public String apiKey() { return apiKey; }

        @Override
        public String toString() {
            return "StoredCredential{provider='" + provider + "', apiKey=<已隐藏>}";
        }
    }

    /** 仅用于诊断：文件存在时给出大小，不读取内容。 */
    public String describeFile() {
        if (!Files.isRegularFile(file)) return "不存在";
        try {
            return Files.size(file) + " 字节（DPAPI 密文）";
        } catch (IOException e) {
            return "存在（大小不可读）";
        }
    }

}
