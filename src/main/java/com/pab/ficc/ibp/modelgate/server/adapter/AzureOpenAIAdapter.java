package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Override public ResponseSession openResponse(AdapterResponseContext context) {
        return ResponseSessions.inspect(context, new OpenAIResponseInspector(objectMapper));
    }

    @Override
    public String getApiSpec() {
        return "azure_openai";
    }

    @Override
    public byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(originalBody);
            root.remove("model");
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_REQUEST_INVALID);
        }
    }

    @Override
    public HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ProviderAdapter.shouldAuthenticate(credential)) {
            headers.set("api-key", credential.getSecretKey());
        }
        return headers;
    }

    @Override
    public URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri) {
        // upstreamUrl should already be the full Azure deployment URL
        // （含 /openai/deployments/{id}/chat/completions?api-version=...）
        // 故 azure_openai 规范**有意忽略** upstream_path：再拼一段路径只会破坏部署 URL。
        // 前端在 apiSpec=azure_openai 时会把该输入框置灰并说明原因。
        String query = originalUri.getRawQuery();
        return ProviderEndpointResolver.resolve(model.getUpstreamUrl(), null, query);
    }
}
