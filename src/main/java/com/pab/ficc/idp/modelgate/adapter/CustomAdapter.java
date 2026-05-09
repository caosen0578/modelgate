package com.pab.ficc.idp.modelgate.adapter;

import com.pab.ficc.idp.modelgate.entity.ModelCredential;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
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
    public byte[] transformRequestBody(byte[] originalBody, ModelInstance model, ModelCredential credential) {
        return originalBody;
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstance model, ModelCredential credential) {
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
    public URI buildUpstreamUri(ModelInstance model, URI originalUri) {
        String base = model.getUpstreamUrl().replaceAll("/$", "");
        String path = originalUri.getPath();
        String query = originalUri.getRawQuery();
        return URI.create(base + path + (query != null ? "?" + query : ""));
    }
}
