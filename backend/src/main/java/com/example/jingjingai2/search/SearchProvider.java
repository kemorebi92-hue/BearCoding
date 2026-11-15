package com.example.jingjingai2.search;

import java.util.List;

/**
 * 搜索服务提供方接口 —— 这是"不要把某一家 API 写死"的关键抽象。
 *
 * 约定：
 *  - {@link #name()} 是配置项 {@code app.search.provider} 里填的值（小写），也是日志里的标识；
 *  - {@link #buildCall} 与 {@link #parse} 都是**纯函数**（不发网络请求），因此可以完全离线单测；
 *  - 换/加一家搜索服务，只需实现本接口并加 {@code @Component}，
 *    {@link SearchService} 会自动发现它，**调用方（webSearch 工具、ChatController）零改动**。
 */
public interface SearchProvider {

    /** provider 名称，用于配置选择（小写，如 tavily / serper / bocha） */
    String name();

    /**
     * 把一次查询构造成 HTTP 请求。
     *
     * @param query      查询词
     * @param maxResults 期望返回条数
     * @param apiKey     从配置注入的密钥（各家放的位置不同：有的在 body，有的在请求头）
     */
    HttpCall buildCall(String query, int maxResults, String apiKey);

    /**
     * 解析响应体，统一成 {@link SearchResult} 列表。
     * 约定：解析失败或结构不符时返回空列表，**不要抛异常**（网络内容不可信，不能让它把主流程炸掉）。
     */
    List<SearchResult> parse(String responseBody);
}
