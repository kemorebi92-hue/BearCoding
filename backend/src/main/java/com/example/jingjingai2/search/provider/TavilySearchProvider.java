package com.example.jingjingai2.search.provider;

import com.example.jingjingai2.search.HttpCall;
import com.example.jingjingai2.search.SearchProvider;
import com.example.jingjingai2.search.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily（https://tavily.com）—— 面向 AI Agent 的搜索 API。
 * key 放在请求体 api_key 字段里；响应 results[] 直接带 title/url/content。
 */
@Component
public class TavilySearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://api.tavily.com/search";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public HttpCall buildCall(String query, int maxResults, String apiKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_key", apiKey);
        payload.put("query", query);
        payload.put("max_results", maxResults);
        payload.put("search_depth", "basic");   // basic 更便宜更快，够 Agent 用
        payload.put("include_answer", false);
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("构造 Tavily 请求失败: " + e.getMessage(), e);
        }
        return HttpCall.postJson(ENDPOINT, Map.of("Accept", "application/json"), body);
    }

    @Override
    public List<SearchResult> parse(String responseBody) {
        List<SearchResult> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(responseBody);
            for (JsonNode r : root.path("results")) {
                if (!r.isObject()) continue;
                String title = r.path("title").asText("");
                String url = r.path("url").asText("");
                String content = r.path("content").asText("");
                if (title.isBlank() && url.isBlank()) continue;
                out.add(new SearchResult(title, url, content));
            }
        } catch (Exception ignored) {
            // 外部响应不可信，解析失败就当没有结果
        }
        return out;
    }
}
