package com.pab.ficc.idp.modelgate.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.idp.modelgate.entity.ModelCredential;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Azure OpenAI adapter.
 * - URL pattern: https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version=...
 * - Auth: api-key header instead of Bearer
 * - Strips 'model' field from body (Azure uses deployment in URL)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureOpenAIAdapter implements ProviderAdapter {

    private final ObjectMapper objectMapper;

    @Override
    public String getApiSpec() {
        return "azure_openai";
    }

    @Override
    public byte[] transformRequestBody(byte[] originalBody, ModelInstance model, ModelCredential credential) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(originalBody);
            root.remove("model");
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            log.warn("[AzureAdapter] Failed to transform body, using original", e);
            return originalBody;
        }
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstance model, ModelCredential credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (credential != null && credential.getSecretKey() != null) {
            headers.set("api-key", credential.getSecretKey());
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstance model, URI originalUri) {
        // upstreamUrl should already be the full Azure deployment URL
        String base = model.getUpstreamUrl().replaceAll("/$", "");
        String query = originalUri.getRawQuery();
        String full = base + (query != null ? "?" + query : "");
        return URI.create(full);
    }
}
