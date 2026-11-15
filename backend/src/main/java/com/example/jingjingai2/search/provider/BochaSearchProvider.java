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
 * 博查（https://bochaai.com）—— 国内搜索 API，中文结果质量好。
 * key 放在请求头 Authorization: Bearer xxx；响应 data.webPages.value[] 带 name/url/summary/snippet。
 */
@Component
public class BochaSearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "bocha";
    }

    @Override
    public HttpCall buildCall(String query, int maxResults, String apiKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("summary", true);   // 让返回带摘要，Agent 不必再抓网页
        payload.put("count", maxResults);
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("构造博查请求失败: " + e.getMessage(), e);
        }
        return HttpCall.postJson(ENDPOINT, Map.of(
                "Authorization", "Bearer " + (apiKey == null ? "" : apiKey),
                "Accept", "application/json"), body);
    }

    @Override
    public List<SearchResult> parse(String responseBody) {
        List<SearchResult> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(responseBody);
            for (JsonNode r : root.path("data").path("webPages").path("value")) {
                if (!r.isObject()) continue;
                String title = r.path("name").asText("");
                String url = r.path("url").asText("");
                String summary = r.path("summary").asText("");
                if (summary.isBlank()) summary = r.path("snippet").asText("");
                if (title.isBlank() && url.isBlank()) continue;
                out.add(new SearchResult(title, url, summary));
            }
        } catch (Exception ignored) {
            // 解析失败当无结果
        }
        return out;
    }
}
