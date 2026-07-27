package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
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
    public byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential) {
        return originalBody;
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (credential != null && credential.getSecretKey() != null) {
            headers.setBearerAuth(credential.getSecretKey());
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri) {
        String base = model.getUpstreamUrl().replaceAll("/$", "");
        String path = originalUri.getPath();
        String query = originalUri.getRawQuery();
        String full = base + path + (query != null ? "?" + query : "");
        return URI.create(full);
    }
}
