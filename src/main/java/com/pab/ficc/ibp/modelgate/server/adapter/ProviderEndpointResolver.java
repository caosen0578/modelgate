package com.pab.ficc.ibp.modelgate.server.adapter;

import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;

final class ProviderEndpointResolver {
    private ProviderEndpointResolver() { }
    static URI resolve(String base, String path, String originalQuery) {
        var builder = UriComponentsBuilder.fromUri(URI.create(base));
        if (path != null) {
            String prefix = URI.create(base).getRawPath();
            builder.replacePath((prefix == null ? "" : prefix.replaceAll("/$", "")) + path);
        }
        if (originalQuery != null) {
            // 配置的 api-version 等参数优先，调用方不能覆盖它；保留 raw 编码。
            var configured = builder.build(true).getQueryParams();
            var incoming = UriComponentsBuilder.newInstance().query(originalQuery).build(true).getQueryParams();
            incoming.forEach((key, values) -> { if (!configured.containsKey(key)) builder.queryParam(key, values.toArray()); });
        }
        return builder.build(true).toUri();
    }
}
