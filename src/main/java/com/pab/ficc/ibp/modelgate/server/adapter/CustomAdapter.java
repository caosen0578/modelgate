package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Custom/private model adapter.
 * Passes requests through as-is; auth header configurable via credential.authType and accessKey.
 */
@Component
public class CustomAdapter implements ProviderAdapter {

    @Override
    public String getApiSpec() {
        return "custom";
    }

    @Override
    public byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential) {
        return originalBody;
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (credential != null) {
            if ("api_key".equals(credential.getAuthType()) && credential.getSecretKey() != null) {
                headers.setBearerAuth(credential.getSecretKey());
            } else if ("ak_sk".equals(credential.getAuthType()) && credential.getAccessKey() != null) {
                headers.set("X-Access-Key", credential.getAccessKey());
                if (credential.getSecretKey() != null) {
                    headers.set("X-Secret-Key", credential.getSecretKey());
                }
            }
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri) {
        // 配了 upstream_path 用它；留空则沿用原行为——透传调用方路径
        String path = resolvePath(model.getUpstreamPath(), originalUri.getPath());
        String query = originalUri.getRawQuery();
        return ProviderEndpointResolver.resolve(model.getUpstreamUrl(), path, query);
    }
}
