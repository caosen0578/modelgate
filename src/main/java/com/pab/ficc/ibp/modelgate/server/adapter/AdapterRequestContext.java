package com.pab.ficc.ibp.modelgate.server.adapter;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** body 已完成网关默认/强制参数和路由身份处理；认证在最终请求确定后执行。 */
public record AdapterRequestContext(ProviderOperation operation, HttpMethod method, URI originalUri,
        HttpHeaders headers, ProviderRequestBody payload, ProviderModelConfig model, ProviderCredential credential) {
    public AdapterRequestContext(ProviderOperation operation, HttpMethod method, URI uri, HttpHeaders headers,
            byte[] body, ProviderModelConfig model, ProviderCredential credential) {
        this(operation, method, uri, headers, new ProviderRequestBody.Buffered(body), model, credential);
    }
    public byte[] body() { return payload.bytes(); }
    public boolean streamRequested(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try { return mapper.readTree(body()).path("stream").asBoolean(false); }
        catch (Exception e) { throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
            com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_REQUEST_INVALID); }
    }
    public AdapterRequestContext {
        HttpHeaders copy = new HttpHeaders(); copy.putAll(headers);
        headers = HttpHeaders.readOnlyHttpHeaders(copy);
    }
    @Override public String toString() { return "AdapterRequestContext[operation=" + operation + "]"; }
}
