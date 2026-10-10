package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Anthropic Messages → OpenAI Chat Completions；状态只属于本次响应。 */
final class AnthropicResponseSession implements ResponseSession {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final AdapterResponseContext context;
    private final boolean streaming;
    private final ResponseTelemetry telemetry = new ResponseTelemetry();
    private final SseFrameDecoder decoder = new SseFrameDecoder(1024 * 1024);
    private final Map<Integer, Integer> toolIndices = new HashMap<>();
    private final Set<Integer> toolsWithArguments = new HashSet<>();
    private String id = "chatcmpl-provider";
    private final long created = Instant.now().getEpochSecond();
    private boolean stopped;
    private int nextToolIndex;
    AnthropicResponseSession(ObjectMapper mapper, AdapterResponseContext context) {
        this.mapper = mapper; this.context = context;
        MediaType type = context.headers().getContentType();
        this.streaming = type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);
        if (context.headers().containsKey(HttpHeaders.CONTENT_ENCODING)
                && !"identity".equalsIgnoreCase(context.headers().getFirst(HttpHeaders.CONTENT_ENCODING)))
            throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID, "上游忽略 identity，返回压缩响应");
        if (!streaming && type != null && !MediaType.APPLICATION_JSON.isCompatibleWith(type))
            throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Anthropic 响应不是 JSON/SSE");
    }
    @Override public ResponseTelemetry telemetry() { return telemetry; }
    @Override public boolean rewritesBody() { return true; }
    @Override public void adaptHeaders(HttpHeaders headers) {
        for (String name : List.of("Content-Length", "Content-Encoding", "ETag", "Content-MD5", "Digest")) headers.remove(name);
        headers.setContentType(streaming ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
    }
    @Override public Flux<DataBuffer> transform(Flux<DataBuffer> upstream, DataBufferFactory factory) {
        if (!streaming) {
            return DataBufferUtils.join(upstream, MAX_JSON_BYTES).map(this::consume)
                .publishOn(Schedulers.parallel()).map(bytes -> factory.wrap(convertJson(bytes))).flux()
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        }
        return upstream.map(this::consume)
            .publishOn(Schedulers.parallel(), 1)
            .concatMapIterable(bytes -> convertFrames(decoder.accept(bytes)), 1)
            .concatWith(Flux.defer(() -> stopped && !decoder.hasPartialFrame() ? Flux.empty()
                : Flux.error(new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Anthropic SSE 提前结束"))))
            .map(bytes -> factory.wrap(bytes))
            .doOnError(e -> telemetry.error("protocol_error"))
            .doFinally(signal -> decoder.clear())
            .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }
    private byte[] consume(DataBuffer buffer) {
        try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
        finally { DataBufferUtils.release(buffer); }
    }
    private byte[] convertJson(byte[] bytes) {
        try {
            JsonNode src = mapper.readTree(bytes);
            if (context.status() >= 400 || src.has("error")) return json(error(src));
            if (!src.path("content").isArray() || !src.path("id").isTextual()) throw new IllegalArgumentException();
            id = src.path("id").asText(); observeUsage(src.path("usage"));
            var result = envelope(false);
            var choice = result.putArray("choices").addObject().put("index", 0);
            var message = choice.putObject("message").put("role", "assistant");
            var text = new StringBuilder();
            for (JsonNode block : src.path("content")) {
                switch (block.path("type").asText()) {
                    case "text" -> text.append(block.path("text").asText());
                    case "tool_use" -> {
                        var tool = message.withArray("tool_calls").addObject().put("id", block.path("id").asText()).put("type", "function");
                        tool.putObject("function").put("name", block.path("name").asText()).put("arguments", block.path("input").toString());
                    }
                    default -> message.withArray("provider_content").add(block);
                }
            }
            if (text.isEmpty() && message.has("tool_calls")) message.putNull("content");
            else message.put("content", text.toString());
            String reason = finishReason(src.path("stop_reason").asText()); telemetry.finish(reason);
            choice.put("finish_reason", reason); result.set("usage", usage());
            return json(result);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { telemetry.error("protocol_error"); throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID); }
    }
    private List<byte[]> convertFrames(List<SseFrameDecoder.Frame> frames) {
        var output = new ArrayList<byte[]>();
        for (var frame : frames) {
            if (stopped) throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID, "message_stop 之后仍有数据");
            try {
                JsonNode src = mapper.readTree(frame.data());
                String type = src.path("type").asText(frame.event());
                ObjectNode delta = mapper.createObjectNode();
                String reason = null;
                switch (type) {
                    case "message_start" -> {
                        id = src.path("message").path("id").asText(id);
                        observeUsage(src.path("message").path("usage")); delta.put("role", "assistant").put("content", "");
                    }
                    case "content_block_start" -> {
                        JsonNode block = src.path("content_block");
                        if ("tool_use".equals(block.path("type").asText())) {
                            int index = nextToolIndex++; toolIndices.put(src.path("index").asInt(), index);
                            var tool = delta.putArray("tool_calls").addObject().put("index", index)
                                .put("id", block.path("id").asText()).put("type", "function");
                            var function = tool.putObject("function").put("name", block.path("name").asText());
                            JsonNode input = block.path("input");
                            function.put("arguments", input.isObject() && !input.isEmpty() ? input.toString() : "");
                            if (input.isObject() && !input.isEmpty()) toolsWithArguments.add(index);
                        } else if ("text".equals(block.path("type").asText()) && !block.path("text").asText().isEmpty())
                            delta.put("content", block.path("text").asText());
                        else if (!"text".equals(block.path("type").asText())) delta.set("provider_event", src);
                        else continue;
                    }
                    case "content_block_delta" -> {
                        JsonNode change = src.path("delta");
                        switch (change.path("type").asText()) {
                            case "text_delta" -> delta.put("content", change.path("text").asText());
                            case "input_json_delta" -> {
                                Integer index = toolIndices.get(src.path("index").asInt());
                                if (index == null) throw new IllegalArgumentException();
                                if (!change.path("partial_json").asText().isEmpty()) toolsWithArguments.add(index);
                                delta.putArray("tool_calls").addObject().put("index", index).putObject("function")
                                    .put("arguments", change.path("partial_json").asText());
                            }
                            default -> delta.set("provider_event", src);
                        }
                    }
                    case "message_delta" -> {
                        observeUsage(src.path("usage"));
                        if (src.path("delta").hasNonNull("stop_reason")) {
                            reason = finishReason(src.path("delta").path("stop_reason").asText()); telemetry.finish(reason);
                        }
                    }
                    case "message_stop" -> { stopped = true; output.add("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); continue; }
                    case "error" -> {
                        output.add(sse(error(src))); stopped = true;
                        output.add("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); continue;
                    }
                    case "content_block_stop" -> {
                        Integer index = toolIndices.remove(src.path("index").asInt());
                        if (index != null && !toolsWithArguments.remove(index))
                            delta.putArray("tool_calls").addObject().put("index", index).putObject("function").put("arguments", "{}");
                        else { output.add(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); continue; }
                    }
                    case "ping" -> { output.add(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); continue; }
                    default -> delta.set("provider_event", src);
                }
                var chunk = envelope(true);
                var choice = chunk.putArray("choices").addObject().put("index", 0);
                choice.set("delta", delta);
                if (reason == null) choice.putNull("finish_reason"); else choice.put("finish_reason", reason);
                if ("message_delta".equals(type)) chunk.set("usage", usage());
                output.add(sse(chunk));
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID); }
        }
        return output;
    }
    private ObjectNode envelope(boolean stream) {
        return mapper.createObjectNode().put("id", id).put("object", stream ? "chat.completion.chunk" : "chat.completion")
            .put("created", created).put("model", context.model().modelId());
    }
    private ObjectNode usage() {
        return mapper.createObjectNode().put("prompt_tokens", telemetry.inputTokens()).put("completion_tokens", telemetry.outputTokens())
            .put("total_tokens", telemetry.inputTokens() + telemetry.outputTokens());
    }
    private void observeUsage(JsonNode usage) {
        if (usage.has("input_tokens") || usage.has("output_tokens")) {
            Integer input = usage.has("input_tokens") ? usage.path("input_tokens").asInt()
                + usage.path("cache_creation_input_tokens").asInt() + usage.path("cache_read_input_tokens").asInt() : null;
            telemetry.usage(input, usage.has("output_tokens") ? usage.path("output_tokens").asInt() : null);
        }
    }
    private ObjectNode error(JsonNode src) {
        JsonNode nativeError = src.path("error");
        String type = nativeError.path("type").asText("upstream_error"); telemetry.error(type);
        var result = mapper.createObjectNode();
        result.putObject("error").put("message", nativeError.path("message").asText("Upstream error"))
            .put("type", type).putNull("param").put("code", type);
        return result;
    }
    private static String finishReason(String reason) {
        return switch (reason) { case "max_tokens" -> "length"; case "tool_use" -> "tool_calls"; default -> "stop"; };
    }
    private byte[] json(JsonNode node) {
        try { return mapper.writeValueAsBytes(node); }
        catch (Exception e) { throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID); }
    }
    private byte[] sse(JsonNode node) { return ("data: " + node + "\n\n").getBytes(StandardCharsets.UTF_8); }
}
