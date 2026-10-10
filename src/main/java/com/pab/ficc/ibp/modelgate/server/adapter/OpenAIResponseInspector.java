package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class OpenAIResponseInspector implements ResponseInspector {
    private final ObjectMapper mapper;
    private final ResponseTelemetry telemetry = new ResponseTelemetry();
    OpenAIResponseInspector(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public ResponseTelemetry telemetry() { return telemetry; }
    @Override public void inspectEvent(SseFrameDecoder.Frame event) { inspectJson(event.data().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @Override public void inspectJson(byte[] bytes) {
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node.has("error")) telemetry.error(node.path("error").path("code").asText("upstream_error"));
            JsonNode usage = node.has("response") ? node.path("response").path("usage") : node.path("usage");
            if (usage.has("prompt_tokens") || usage.has("completion_tokens"))
                telemetry.usage(usage.has("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null,
                    usage.has("completion_tokens") ? usage.path("completion_tokens").asInt() : null);
            else if (usage.has("input_tokens") || usage.has("output_tokens"))
                telemetry.usage(usage.has("input_tokens") ? usage.path("input_tokens").asInt() : null,
                    usage.has("output_tokens") ? usage.path("output_tokens").asInt() : null);
            JsonNode reason = node.path("choices").path(0).path("finish_reason");
            if (reason.isTextual()) telemetry.finish(reason.asText());
        } catch (Exception ignored) { /* 旁路遥测允许缺省/扩展字段，不改变响应。 */ }
    }
}
