package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 升级前四协议的正常请求契约；只纠正错误处理/URI 合并，不静默改变响应模式。 */
class LegacyAdapterContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fourRequestContracts() throws Exception {
        var model = new ModelInstanceEntity();
        model.setModelId("alias");
        model.setUpstreamModelId("real");
        model.setUpstreamUrl("https://provider.test");
        var cred = new ModelCredentialEntity();
        cred.setAuthType("api_key");
        cred.setSecretKey("test-secret");
        byte[] body = "{\"model\":\"real\",\"messages\":[{\"role\":\"system\",\"content\":\"rules\"},{\"role\":\"user\",\"content\":\"ping\"}],\"stream\":false}".getBytes(StandardCharsets.UTF_8);
        URI original = URI.create("/v1/chat/completions?trace=1");
        var openai = new OpenAIAdapter();
        assertSame(body, openai.transformRequestBody(body, model, cred));
        assertEquals("Bearer test-secret", openai.buildUpstreamHeaders(model, cred).getFirst("Authorization"));
        assertEquals("https://provider.test/v1/chat/completions?trace=1", openai.buildUpstreamUri(model, original).toString());
        var custom = new CustomAdapter();
        assertSame(body, custom.transformRequestBody(body, model, cred));
        assertEquals(openai.buildUpstreamUri(model, original), custom.buildUpstreamUri(model, original));
        var azure = new AzureOpenAIAdapter(mapper);
        assertFalse(mapper.readTree(azure.transformRequestBody(body, model, cred)).has("model"));
        assertEquals("test-secret", azure.buildUpstreamHeaders(model, cred).getFirst("api-key"));
        var anthropic = new AnthropicAdapter(mapper);
        var converted = mapper.readTree(anthropic.transformRequestBody(body, model, cred));
        assertEquals("rules", converted.path("system").asText());
        assertEquals(1, converted.path("messages").size());
        assertEquals(4096, converted.path("max_tokens").asInt());
        assertEquals("real", converted.path("model").asText());
        assertEquals("https://provider.test/v1/messages", anthropic.buildUpstreamUri(model, original).toString());
        assertEquals("test-secret", anthropic.buildUpstreamHeaders(model, cred).getFirst("x-api-key"));
        cred.setAuthType("none");
        for (ProviderAdapter adapter : java.util.List.of(openai, azure, anthropic, custom)) {
            var headers = adapter.buildUpstreamHeaders(model, cred);
            assertFalse(headers.containsKey("Authorization"));
            assertFalse(headers.containsKey("x-api-key"));
            assertFalse(headers.containsKey("api-key"));
        }
    }
}
