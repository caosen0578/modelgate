package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
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
    public byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential) {
        try {
            JsonNode src = objectMapper.readTree(originalBody);
            ObjectNode dst = objectMapper.createObjectNode();

            // model
            // 正常链路会先由 RequestParamMerger 改写；这里的 fallback 也必须用上游真实 ID，
            // 防止管理面测试或降级调用缺少 model 字段时又退回对外别名。
            dst.put("model", src.path("model").asText(model.resolveUpstreamModelId()));

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
    public HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        if (ProviderAdapter.shouldAuthenticate(credential)) {
            headers.set("x-api-key", credential.getSecretKey());
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri) {
        String base = model.getUpstreamUrl().replaceAll("/$", "");
        // 配了 upstream_path 用它；留空则沿用 Anthropic 官方规范的 /v1/messages
        return URI.create(base + resolvePath(model.getUpstreamPath(), "/v1/messages"));
    }
}
