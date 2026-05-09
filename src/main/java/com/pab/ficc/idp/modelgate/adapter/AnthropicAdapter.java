package com.pab.ficc.idp.modelgate.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Anthropic Claude adapter.
 * Converts OpenAI chat format to Anthropic Messages API format.
 * - Separates system message from user/assistant messages
 * - Maps max_tokens from model config if not provided
 * - Auth: x-api-key + anthropic-version headers
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicAdapter implements ProviderAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;

    @Override
    public String getApiSpec() {
        return "anthropic";
    }

    @Override
    public byte[] transformRequestBody(byte[] originalBody, ModelInstance model, ModelCredential credential) {
        try {
            JsonNode src = objectMapper.readTree(originalBody);
            ObjectNode dst = objectMapper.createObjectNode();

            // model
            dst.put("model", src.path("model").asText(model.getModelId()));

            // separate system from messages
            ArrayNode srcMessages = (ArrayNode) src.get("messages");
            if (srcMessages != null) {
                String systemContent = null;
                ArrayNode dstMessages = objectMapper.createArrayNode();
                for (JsonNode msg : srcMessages) {
                    if ("system".equals(msg.path("role").asText())) {
                        systemContent = msg.path("content").asText();
                    } else {
                        dstMessages.add(msg);
                    }
                }
                if (systemContent != null) dst.put("system", systemContent);
                dst.set("messages", dstMessages);
            }

            // max_tokens (required by Anthropic)
            if (src.has("max_tokens")) {
                dst.set("max_tokens", src.get("max_tokens"));
            } else {
                dst.put("max_tokens", 4096);
            }

            if (src.has("temperature")) dst.set("temperature", src.get("temperature"));
            if (src.has("stream")) dst.set("stream", src.get("stream"));

            return objectMapper.writeValueAsBytes(dst);
        } catch (Exception e) {
            log.warn("[AnthropicAdapter] Failed to transform body, using original", e);
            return originalBody;
        }
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstance model, ModelCredential credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        if (credential != null && credential.getSecretKey() != null) {
            headers.set("x-api-key", credential.getSecretKey());
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstance model, URI originalUri) {
        String base = model.getUpstreamUrl().replaceAll("/$", "");
        return URI.create(base + "/v1/messages");
    }
}
