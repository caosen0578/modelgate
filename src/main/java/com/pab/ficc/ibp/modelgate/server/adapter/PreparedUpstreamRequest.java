package com.pab.ficc.ibp.modelgate.server.adapter;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

public record PreparedUpstreamRequest(HttpMethod method, URI uri, HttpHeaders headers, ProviderRequestBody payload) {
    public PreparedUpstreamRequest(HttpMethod method, URI uri, HttpHeaders headers, byte[] body) {
        this(method, uri, headers, new ProviderRequestBody.Buffered(body));
    }
    public byte[] body() { return payload.bytes(); }
    public PreparedUpstreamRequest {
        HttpHeaders copy = new HttpHeaders(); copy.putAll(headers);
        headers = HttpHeaders.readOnlyHttpHeaders(copy);
    }
    @Override public String toString() { return "PreparedUpstreamRequest[method=" + method + ", host=" + uri.getHost() + "]"; }
}
