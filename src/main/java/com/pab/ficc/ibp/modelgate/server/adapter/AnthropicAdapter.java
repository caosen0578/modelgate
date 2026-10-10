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
    public ResponseSession openResponse(AdapterResponseContext context) {
        if (context.model().responseMode() == ResponseMode.OPENAI_COMPATIBLE) return new AnthropicResponseSession(objectMapper, context);
        return ResponseSessions.inspect(context, new AnthropicResponseInspector(objectMapper));
    }

    @Override
    public byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential) {
        try {
            JsonNode src = objectMapper.readTree(originalBody);
            if (!src.isObject() || !src.path("messages").isArray()) throw new IllegalArgumentException();
            if (src.has("system") && !src.path("system").isTextual())
                throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                    com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_FEATURE_UNSUPPORTED);
            var allowed = java.util.Set.of("model", "messages", "n", "system", "max_tokens", "max_completion_tokens",
                "temperature", "stream", "stream_options", "top_p", "top_k", "metadata", "thinking", "stop", "tools", "tool_choice", "parallel_tool_calls");
            var fields = src.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next()))
                throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                    com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_FEATURE_UNSUPPORTED);
            if (src.path("n").asInt(1) != 1 || src.has("response_format") || src.has("logprobs") || src.has("functions"))
                throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                    com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_FEATURE_UNSUPPORTED);
            ObjectNode dst = objectMapper.createObjectNode();

            // model
            // 正常链路会先由 RequestParamMerger 改写；这里的 fallback 也必须用上游真实 ID，
            // 防止管理面测试或降级调用缺少 model 字段时又退回对外别名。
            dst.put("model", src.path("model").asText(model.resolveUpstreamModelId()));

            // separate system from messages
            ArrayNode srcMessages = (ArrayNode) src.get("messages");
            if (srcMessages != null) {
                StringBuilder systemContent = new StringBuilder();
                if (src.path("system").isTextual()) systemContent.append(src.path("system").asText());
                ArrayNode dstMessages = objectMapper.createArrayNode();
                for (JsonNode msg : srcMessages) {
                    if ("system".equals(msg.path("role").asText()) || "developer".equals(msg.path("role").asText())) {
                        if (!systemContent.isEmpty()) systemContent.append('\n');
                        if (!msg.path("content").isTextual()) throw new IllegalArgumentException();
                        systemContent.append(msg.path("content").asText());
                    } else {
                        dstMessages.add(convertMessage(msg));
                    }
                }
                if (!systemContent.isEmpty()) dst.put("system", systemContent.toString());
                dst.set("messages", dstMessages);
            }

            // max_tokens (required by Anthropic)
            if (src.has("max_completion_tokens")) {
                dst.set("max_tokens", src.get("max_completion_tokens"));
            } else if (src.has("max_tokens")) {
                dst.set("max_tokens", src.get("max_tokens"));
            } else {
                dst.put("max_tokens", 4096);
            }

            if (src.has("temperature")) dst.set("temperature", src.get("temperature"));
            if (src.has("stream")) dst.set("stream", src.get("stream"));
            for (String field : java.util.List.of("top_p", "top_k", "metadata", "thinking"))
                if (src.has(field)) dst.set(field, src.get(field));
            if (src.has("stop")) {
                var stop = src.get("stop");
                dst.set("stop_sequences", stop.isTextual() ? objectMapper.createArrayNode().add(stop.asText()) : stop);
            }
            if (src.has("tools")) {
                ArrayNode tools = dst.putArray("tools");
                for (JsonNode tool : src.path("tools")) {
                    JsonNode function = tool.path("function");
                    if (!"function".equals(tool.path("type").asText())) throw new IllegalArgumentException();
                    if (function.path("strict").asBoolean(false))
                        throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                            com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_FEATURE_UNSUPPORTED);
                    var converted = tools.addObject().put("name", function.path("name").asText());
                    if (function.has("description")) converted.set("description", function.get("description"));
                    converted.set("input_schema", function.has("parameters") ? function.get("parameters")
                        : objectMapper.createObjectNode().put("type", "object"));
                }
            }
            if (src.has("tool_choice")) {
                JsonNode choice = src.get("tool_choice");
                var converted = dst.putObject("tool_choice");
                if (choice.isTextual()) converted.put("type", "required".equals(choice.asText()) ? "any" : choice.asText());
                else converted.put("type", "tool").put("name", choice.path("function").path("name").asText());
                if (src.has("parallel_tool_calls")) converted.put("disable_parallel_tool_use", !src.path("parallel_tool_calls").asBoolean());
            }
            if (src.has("parallel_tool_calls") && dst.has("tools") && !dst.has("tool_choice"))
                dst.putObject("tool_choice").put("type", "auto").put("disable_parallel_tool_use", !src.path("parallel_tool_calls").asBoolean());

            return objectMapper.writeValueAsBytes(dst);
        } catch (Exception e) {
            if (e instanceof com.pab.ficc.ibp.modelgate.server.common.BusinessException business) throw business;
            throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_REQUEST_INVALID);
        }
    }

    private ObjectNode convertMessage(JsonNode message) throws Exception {
        String role = message.path("role").asText();
        if ("tool".equals(role)) {
            if (message.path("tool_call_id").asText().isBlank()) throw new IllegalArgumentException();
            var result = objectMapper.createObjectNode().put("role", "user");
            var block = result.putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", message.path("tool_call_id").asText());
            JsonNode content = message.path("content");
            if (content.isArray()) {
                var carrier = objectMapper.createObjectNode().put("role", "user"); carrier.set("content", content);
                block.set("content", convertMessage(carrier).get("content"));
            } else if (content.isTextual()) block.set("content", content);
            else throw new IllegalArgumentException();
            return result;
        }
        if (!"user".equals(role) && !"assistant".equals(role)) throw new IllegalArgumentException();
        var result = objectMapper.createObjectNode().put("role", role);
        JsonNode content = message.path("content");
        if (!message.has("tool_calls") && content.isTextual()) { result.set("content", content); return result; }
        var blocks = result.putArray("content");
        if (content.isTextual() && !content.asText().isEmpty()) blocks.addObject().put("type", "text").put("text", content.asText());
        else if (content.isArray()) for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) blocks.add(block);
            else if ("image_url".equals(block.path("type").asText())) {
                String url = block.path("image_url").path("url").asText();
                var source = blocks.addObject().put("type", "image").putObject("source");
                if (url.startsWith("data:")) {
                    int split = url.indexOf(";base64,");
                    if (split < 0) throw new IllegalArgumentException();
                    source.put("type", "base64").put("media_type", url.substring(5, split)).put("data", url.substring(split + 8));
                } else {
                    if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalArgumentException();
                    source.put("type", "url").put("url", url);
                }
            } else throw new com.pab.ficc.ibp.modelgate.server.common.BusinessException(
                com.pab.ficc.ibp.modelgate.server.common.ErrorCode.PROVIDER_FEATURE_UNSUPPORTED);
        }
        if (message.has("tool_calls")) for (JsonNode tool : message.path("tool_calls")) {
            var converted = blocks.addObject().put("type", "tool_use").put("id", tool.path("id").asText())
                .put("name", tool.path("function").path("name").asText());
            var input = objectMapper.readTree(tool.path("function").path("arguments").asText("{}"));
            if (!input.isObject()) throw new IllegalArgumentException();
            converted.set("input", input);
        }
        return result;
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
        // 配了 upstream_path 用它；留空则沿用 Anthropic 官方规范的 /v1/messages
        return ProviderEndpointResolver.resolve(model.getUpstreamUrl(), resolvePath(model.getUpstreamPath(), "/v1/messages"), null);
    }
}
