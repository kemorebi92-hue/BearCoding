package com.example.jingjingai2.search;

import java.util.Map;

/**
 * 一次待发送的 HTTP 调用描述。
 *
 * provider 只负责"把查询拼成请求"（纯函数，可离线单测），真正发包由 {@link SearchService} 统一做，
 * 这样换 provider 不用重复写网络代码，也便于测试。
 */
public record HttpCall(String method, String url, Map<String, String> headers, String body) {

    public static HttpCall postJson(String url, Map<String, String> headers, String body) {
        return new HttpCall("POST", url, headers, body);
    }
}
