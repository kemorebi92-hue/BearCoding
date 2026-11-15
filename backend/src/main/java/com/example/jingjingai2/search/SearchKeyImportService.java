package com.example.jingjingai2.search;

import com.example.jingjingai2.security.DpapiException;
import com.example.jingjingai2.security.SearchCredentialStore;

/**
 * 搜索 Key 的一次性导入/停用逻辑（纯函数，便于离线测试）。
 *
 * <p>消息里只出现文件路径和原因，绝不包含 Key 本身。
 */
public final class SearchKeyImportService {

    private SearchKeyImportService() {}

    public record Result(boolean success, String message) {}

    public static Result importKey(SearchCredentialStore store, String apiKey,
                                   String provider, boolean force) {
        if (store == null) return new Result(false, "搜索凭证存储未初始化");
        try {
            store.save(provider, apiKey, force);
            return new Result(true,
                    "已保存加密搜索凭证：" + store.path() + "（不显示内容）");
        } catch (DpapiException e) {
            return new Result(false, "保存失败：" + e.getMessage());
        } catch (RuntimeException e) {
            return new Result(false, "保存失败：" + e.getMessage());
        }
    }

    public static Result remove(SearchCredentialStore store) {
        if (store == null) return new Result(false, "搜索凭证存储未初始化");
        try {
            boolean removed = store.delete();
            return new Result(true, removed
                    ? "已删除本机加密搜索凭证：" + store.path() + "（搜索 Key 已停用）"
                    : "本机没有加密搜索凭证，无需删除");
        } catch (DpapiException e) {
            return new Result(false, "删除失败：" + e.getMessage());
        }
    }
}
