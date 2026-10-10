package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AnthropicResponseInspector implements ResponseInspector {
    private final ObjectMapper mapper;
    private final ResponseTelemetry telemetry = new ResponseTelemetry();
    AnthropicResponseInspector(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public ResponseTelemetry telemetry() { return telemetry; }
    @Override public void inspectJson(byte[] bytes) {
        try { inspect(mapper.readTree(bytes)); }
        catch (Exception ignored) { /* 原生透传的旁路解析故障不影响业务响应。 */ }
    }
    @Override public void inspectEvent(SseFrameDecoder.Frame event) {
        inspectJson(event.data().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void inspect(JsonNode root) {
        if (root.has("error")) telemetry.error(root.path("error").path("type").asText("upstream_error"));
        JsonNode usage = root.has("message") ? root.path("message").path("usage") : root.path("usage");
        if (usage.has("input_tokens") || usage.has("output_tokens"))
            telemetry.usage(usage.has("input_tokens") ? usage.path("input_tokens").asInt()
                + usage.path("cache_read_input_tokens").asInt() + usage.path("cache_creation_input_tokens").asInt() : null,
                usage.has("output_tokens") ? usage.path("output_tokens").asInt() : null);
        if (root.hasNonNull("stop_reason")) telemetry.finish(root.path("stop_reason").asText());
        if (root.path("delta").hasNonNull("stop_reason")) telemetry.finish(root.path("delta").path("stop_reason").asText());
    }
}
