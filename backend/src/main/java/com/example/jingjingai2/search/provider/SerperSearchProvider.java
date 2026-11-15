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
 * Serper（https://serper.dev）—— 直接返回 Google 结果。
 * key 放在请求头 X-API-KEY；响应 organic[] 带 title/link/snippet。
 */
@Component
public class SerperSearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://google.serper.dev/search";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "serper";
    }

    @Override
    public HttpCall buildCall(String query, int maxResults, String apiKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("q", query);
        payload.put("num", maxResults);
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("构造 Serper 请求失败: " + e.getMessage(), e);
        }
        return HttpCall.postJson(ENDPOINT, Map.of(
                "X-API-KEY", apiKey == null ? "" : apiKey,
                "Accept", "application/json"), body);
    }

    @Override
    public List<SearchResult> parse(String responseBody) {
        List<SearchResult> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(responseBody);
            for (JsonNode r : root.path("organic")) {
                if (!r.isObject()) continue;
                String title = r.path("title").asText("");
                String link = r.path("link").asText("");
                String snippet = r.path("snippet").asText("");
                if (title.isBlank() && link.isBlank()) continue;
                out.add(new SearchResult(title, link, snippet));
            }
        } catch (Exception ignored) {
            // 解析失败当无结果
        }
        return out;
    }
}
