package com.example.jingjingai2.search;

import com.example.jingjingai2.search.provider.BochaSearchProvider;
import com.example.jingjingai2.search.provider.SerperSearchProvider;
import com.example.jingjingai2.search.provider.TavilySearchProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 各搜索服务 provider 的「请求构造 + 响应解析」测试（纯函数，不需要网络与 key）。
 */
class SearchProviderTest {

    private final TavilySearchProvider tavily = new TavilySearchProvider();
    private final SerperSearchProvider serper = new SerperSearchProvider();
    private final BochaSearchProvider bocha = new BochaSearchProvider();

    @Test
    @DisplayName("三家 provider 名称唯一，配置里能区分")
    void namesAreUnique() {
        assertEquals("tavily", tavily.name());
        assertEquals("serper", serper.name());
        assertEquals("bocha", bocha.name());
    }

    // ==================== Tavily ====================

    @Test
    @DisplayName("tavily：key 放在请求体，且带上 query/max_results")
    void tavilyBuildsBodyWithKey() {
        HttpCall call = tavily.buildCall("Spring AI 最新版本", 3, "tvly-xxx");
        assertEquals("POST", call.method());
        assertTrue(call.url().contains("tavily.com"), call.url());
        assertTrue(call.body().contains("\"api_key\":\"tvly-xxx\""), call.body());
        assertTrue(call.body().contains("\"query\":\"Spring AI 最新版本\""), call.body());
        assertTrue(call.body().contains("\"max_results\":3"), call.body());
    }

    @Test
    @DisplayName("tavily：解析 results[]")
    void tavilyParsesResults() {
        String json = "{\"results\":[{\"title\":\"标题一\",\"url\":\"https://a.com\",\"content\":\"摘要一\"},"
                + "{\"title\":\"标题二\",\"url\":\"https://b.com\",\"content\":\"摘要二\"}]}";
        List<SearchResult> rs = tavily.parse(json);
        assertEquals(2, rs.size());
        assertEquals("标题一", rs.get(0).title());
        assertEquals("https://a.com", rs.get(0).url());
        assertEquals("摘要二", rs.get(1).snippet());
    }

    @Test
    @DisplayName("tavily：畸形/空 JSON 返回空列表而不是抛异常")
    void tavilyToleratesBadJson() {
        assertTrue(tavily.parse("").isEmpty());
        assertTrue(tavily.parse("not json at all").isEmpty());
        assertTrue(tavily.parse("{}").isEmpty());
        assertTrue(tavily.parse(null).isEmpty());
    }

    @Test
    @DisplayName("tavily：缺字段的条目被跳过，非对象条目也不炸")
    void tavilySkipsIncompleteItems() {
        String json = "{\"results\":[{\"url\":\"https://a.com\"},{\"title\":\"\",\"url\":\"\"},\"oops\","
                + "{\"title\":\"有效\",\"url\":\"https://c.com\",\"content\":\"x\"}]}";
        List<SearchResult> rs = tavily.parse(json);
        assertEquals(2, rs.size());
        assertEquals("https://a.com", rs.get(0).url());
        assertEquals("有效", rs.get(1).title());
    }

    // ==================== Serper ====================

    @Test
    @DisplayName("serper：key 放在 X-API-KEY 请求头")
    void serperPutsKeyInHeader() {
        HttpCall call = serper.buildCall("hello", 4, "sk-serper");
        assertEquals("sk-serper", call.headers().get("X-API-KEY"));
        assertTrue(call.body().contains("\"q\":\"hello\""), call.body());
        assertTrue(call.body().contains("\"num\":4"), call.body());
    }

    @Test
    @DisplayName("serper：解析 organic[]（link 字段映射成 url）")
    void serperParsesOrganic() {
        String json = "{\"organic\":[{\"title\":\"T1\",\"link\":\"https://x.com\",\"snippet\":\"S1\"}],"
                + "\"answerBox\":{\"answer\":\"ignored\"}}";
        List<SearchResult> rs = serper.parse(json);
        assertEquals(1, rs.size());
        assertEquals("T1", rs.get(0).title());
        assertEquals("https://x.com", rs.get(0).url());
        assertEquals("S1", rs.get(0).snippet());
    }

    // ==================== Bocha ====================

    @Test
    @DisplayName("bocha：key 放在 Authorization: Bearer")
    void bochaPutsKeyInAuthorization() {
        HttpCall call = bocha.buildCall("具身智能", 5, "sk-bocha");
        assertEquals("Bearer sk-bocha", call.headers().get("Authorization"));
        assertTrue(call.body().contains("\"query\":\"具身智能\""), call.body());
        assertTrue(call.body().contains("\"count\":5"), call.body());
    }

    @Test
    @DisplayName("bocha：解析 data.webPages.value[]，summary 优先、缺失时退回 snippet")
    void bochaParsesWebPages() {
        String json = "{\"code\":200,\"data\":{\"webPages\":{\"value\":["
                + "{\"name\":\"N1\",\"url\":\"https://n1.com\",\"summary\":\"SUM1\"},"
                + "{\"name\":\"N2\",\"url\":\"https://n2.com\",\"snippet\":\"SNIP2\"}]}}}";
        List<SearchResult> rs = bocha.parse(json);
        assertEquals(2, rs.size());
        assertEquals("SUM1", rs.get(0).snippet());
        assertEquals("SNIP2", rs.get(1).snippet());
    }

    @Test
    @DisplayName("bocha：返回错误码/结构不符时返回空列表")
    void bochaToleratesErrorResponse() {
        assertTrue(bocha.parse("{\"code\":401,\"message\":\"invalid key\"}").isEmpty());
        assertTrue(bocha.parse("<html>502</html>").isEmpty());
    }
}
