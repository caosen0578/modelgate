package com.pab.ficc.ibp.modelgate.server.adapter;

import java.util.List;
import org.springframework.http.HttpHeaders;

/** 模型侧专用，服务侧原样流式透传策略不受影响。 */
public final class ProviderHeaderPolicy {
    private ProviderHeaderPolicy() { }
    public static HttpHeaders sanitize(HttpHeaders inbound) {
        var headers = new HttpHeaders(); headers.putAll(inbound);
        for (String connection : inbound.getOrEmpty(HttpHeaders.CONNECTION))
            for (String token : connection.split(",")) headers.remove(token.trim());
        List.of("Authorization", "api-key", "x-api-key", "x-goog-api-key", "X-Access-Key", "X-Secret-Key",
            "anthropic-version", "x-amz-security-token", "x-amz-date", "Host", "Connection", "Keep-Alive",
            "Proxy-Authorization", "Proxy-Authenticate", "TE", "Trailer", "Transfer-Encoding", "Upgrade",
            "Content-Length", "Content-Encoding", "Accept-Encoding").forEach(headers::remove);
        headers.keySet().stream().filter(name -> name.regionMatches(true, 0, "X-Mg-", 0, 5))
            .toList().forEach(headers::remove);
        // 请求体转换器只接收明文 JSON，响应转换器不解 gzip。
        headers.set("Accept-Encoding", "identity");
        return headers;
    }
}
