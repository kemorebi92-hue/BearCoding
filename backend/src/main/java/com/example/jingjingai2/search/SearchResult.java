package com.example.jingjingai2.search;

/**
 * 一条搜索结果。各搜索服务返回的 JSON 结构都不一样，统一收敛成这个三件套，
 * 上层（SearchService / webSearch 工具）就只依赖它，换 provider 不影响调用方。
 */
public record SearchResult(String title, String url, String snippet) {
}
