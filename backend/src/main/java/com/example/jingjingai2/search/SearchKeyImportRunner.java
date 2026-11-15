package com.example.jingjingai2.search;

import com.example.jingjingai2.security.SearchCredentialStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 一次性把搜索 API Key 写入本机 DPAPI 加密文件，然后退出。
 *
 * <p>用法（在 Electron 打包前由用户自己执行一次）：
 * <pre>
 *   set BEARCODING_IMPORT_SEARCH_KEY=1
 *   java -jar bailianAI-3.0.0.jar --spring.main.web-application-type=none
 * </pre>
 *
 * <p>Key 来源优先级：{@code --app.search.import-from-file=<绝对路径>} →
 * 环境变量 {@code SEARCH_API_KEY} → 控制台隐藏输入（不回显、不进命令行历史）。
 * 不会把 Key 写进日志、不会回显、不会覆盖已有密文（除非显式
 * {@code --app.search.import-force=true}）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SearchKeyImportRunner implements ApplicationRunner {

    public static final String ENV_SEARCH_KEY = "SEARCH_API_KEY";

    private final SearchCredentialStore store;
    private final Environment environment;
    private final boolean importRequested;
    private final boolean removeRequested;
    private final boolean force;
    private final boolean prompt;
    private final boolean exitAfter;
    private final String provider;
    private final String fromFile;

    public SearchKeyImportRunner(SearchCredentialStore store,
                                 Environment environment,
                                 @Value("${app.search.import-key:false}") boolean importRequested,
                                 @Value("${app.search.remove-key:false}") boolean removeRequested,
                                 @Value("${app.search.import-force:false}") boolean force,
                                 @Value("${app.search.import-prompt:true}") boolean prompt,
                                 @Value("${app.search.import-exit:true}") boolean exitAfter,
                                 @Value("${app.search.import-provider:}") String provider,
                                 @Value("${app.search.import-from-file:}") String fromFile) {
        this.store = store;
        this.environment = environment;
        this.importRequested = importRequested;
        this.removeRequested = removeRequested;
        this.force = force;
        this.prompt = prompt;
        this.exitAfter = exitAfter;
        this.provider = provider;
        this.fromFile = fromFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!importRequested && !removeRequested) return;

        SearchKeyImportService.Result result;
        if (removeRequested) {
            result = SearchKeyImportService.remove(store);
        } else {
            String key;
            try {
                key = resolveKey();
            } catch (RuntimeException e) {
                result = new SearchKeyImportService.Result(false, "读取搜索 Key 失败：" + e.getMessage());
                printAndMaybeExit(result);
                return;
            }
            if (key == null || key.isBlank()) {
                result = new SearchKeyImportService.Result(false,
                        "没有拿到搜索 API Key。请设置环境变量 " + ENV_SEARCH_KEY
                                + "、用 --app.search.import-from-file=<绝对路径> 指定文件，"
                                + "或在交互式控制台按提示输入。");
            } else {
                result = SearchKeyImportService.importKey(store, key, resolveProvider(), force);
            }
        }
        printAndMaybeExit(result);
    }

    private void printAndMaybeExit(SearchKeyImportService.Result result) {
        System.out.println("[SearchImport] " + result.message());
        System.out.flush();
        if (exitAfter) {
            System.exit(result.success() ? 0 : 1);
        }
    }

    /** 只读取，不打印。文件或环境变量都没有时，交互式控制台会隐藏输入。 */
    private String resolveKey() {
        if (fromFile != null && !fromFile.isBlank()) {
            Path path = Path.of(fromFile.trim());
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                return firstNonBlankLine(text);
            } catch (IOException e) {
                throw new IllegalStateException("无法读取 Key 文件: " + path);
            }
        }
        String fromEnv = environment.getProperty(ENV_SEARCH_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        if (!prompt) return null;
        Console console = System.console();
        if (console == null) return null;
        char[] typed = console.readPassword("请输入搜索 API Key（输入不回显，直接回车取消）：");
        if (typed == null || typed.length == 0) return null;
        try {
            return new String(typed).trim();
        } finally {
            Arrays.fill(typed, '\0');
        }
    }

    private String resolveProvider() {
        if (provider != null && !provider.isBlank()) return provider.trim();
        String fromEnv = environment.getProperty("SEARCH_PROVIDER");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        return environment.getProperty("app.search.provider", "");
    }

    private static String firstNonBlankLine(String text) {
        if (text == null) return null;
        for (String line : text.split("\\R")) {
            String value = line.replace("\uFEFF", "").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }
}
