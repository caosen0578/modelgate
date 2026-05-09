package com.pab.ficc.idp.modelgate.adapter;

import com.pab.ficc.idp.modelgate.entity.ModelCredential;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Passes OpenAI-compatible requests through unchanged.
 * Only adds the Authorization header with the upstream API key.
 */
@Component
public class OpenAIAdapter implements ProviderAdapter {

    @Override
    public String getApiSpec() {
        return "openai";
    }

    @Override
    public byte[] transformRequestBody(byte[] originalBody, ModelInstance model, ModelCredential credential) {
        return originalBody;
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstance model, ModelCredential credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (credential != null && credential.getSecretKey() != null) {
            headers.setBearerAuth(credential.getSecretKey());
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstance model, URI originalUri) {
        String base = model.getUpstreamUrl().replaceAll("/$", "");
        String path = originalUri.getPath();
        String query = originalUri.getRawQuery();
        String full = base + path + (query != null ? "?" + query : "");
        return URI.create(full);
    }
}
