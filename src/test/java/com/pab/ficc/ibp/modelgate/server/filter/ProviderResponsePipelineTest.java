package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.*;
import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderResponsePipelineTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JSON = "{\"id\":\"msg1\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
    private static final String STREAM = "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg1\",\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n"
        + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
        + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"
        + "data: {\"type\":\"message_stop\"}\n\n";
    private ProviderRequestPipeline.PreparedCall call(String mode) {
        var model = new ModelInstanceEntity(); model.setModelId("alias"); model.setApiSpec("anthropic");
        model.setModelType("chat"); model.setUpstreamUrl("https://example.test"); model.setResponseMode(mode);
        var adapter = new AnthropicAdapter(MAPPER);
        return new ProviderRequestPipeline.PreparedCall(adapter, ProviderModelConfig.from(model), ProviderOperation.CHAT_COMPLETIONS, null);
    }
    @Test void payloadCapturePreservesNativeFlushGroupsAndDoesNotConsumeReadPointers() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        var groups = new ArrayList<String>();
        var sink = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return Flux.from(body).concatMap(group -> DataBufferUtils.join(Flux.from(group)).map(buffer -> {
                    try { groups.add(buffer.toString(StandardCharsets.UTF_8)); return true; }
                    finally { DataBufferUtils.release(buffer); }
                })).then();
            }
            @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) { return Mono.error(new AssertionError("flush 分组被降级")); }
        };
        var capturedExchange = exchange.mutate().response(sink).build();
        // TrafficType 在真实链路由 TraceFilter 提供。
        TrafficType.resolve(capturedExchange);
        var capture = new PayloadCaptureFilter(null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(capture, "payloadLogEnabled", false);
        capture.filter(capturedExchange, next -> {
            next.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            var response = new ProviderResponseDecorator(next, call("PASSTHROUGH"));
            return response.writeAndFlushWith(Flux.just(
                Mono.just(next.getResponse().bufferFactory().wrap(STREAM.substring(0, 50).getBytes(StandardCharsets.UTF_8))),
                Mono.just(next.getResponse().bufferFactory().wrap(STREAM.substring(50).getBytes(StandardCharsets.UTF_8)))));
        }).block(Duration.ofSeconds(5));
        assertEquals(2, groups.size()); assertEquals(STREAM, String.join("", groups));
        ResponseCapture stored = capturedExchange.getAttribute(PayloadCaptureFilter.ATTR_RESPONSE_BYTES);
        assertNotNull(stored); assertEquals(STREAM, new String(stored.toByteArray(), StandardCharsets.UTF_8));
        var telemetry = (ResponseTelemetry) capturedExchange.getAttribute(ResponseTelemetry.EXCHANGE_ATTRIBUTE);
        assertNotNull(telemetry); assertEquals(3, telemetry.inputTokens()); assertEquals(2, telemetry.outputTokens());
    }
    @Test void capturedJsonIsClientResponseAndTelemetryExistsBeforeCompletion() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        var capture = new PayloadCaptureFilter(null, null);
        for (String mode : List.of("PASSTHROUGH", "OPENAI_COMPATIBLE")) {
            var current = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
            capture.filter(current, next -> {
                next.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                next.getResponse().getHeaders().setContentLength(JSON.length());
                return new ProviderResponseDecorator(next, call(mode)).writeWith(Mono.just(next.getResponse().bufferFactory().wrap(JSON.getBytes())));
            }).block(Duration.ofSeconds(5));
            String client = current.getResponse().getBodyAsString().block();
            ResponseCapture stored = current.getAttribute(PayloadCaptureFilter.ATTR_RESPONSE_BYTES);
            assertNotNull(stored); assertEquals(client, new String(stored.toByteArray(), StandardCharsets.UTF_8));
            if ("PASSTHROUGH".equals(mode)) assertEquals(JSON, client);
            else { assertEquals("chat.completion", MAPPER.readTree(client).path("object").asText()); assertEquals(-1, current.getResponse().getHeaders().getContentLength()); }
            var telemetry = (ResponseTelemetry) current.getAttribute(ResponseTelemetry.EXCHANGE_ATTRIBUTE);
            assertNotNull(telemetry); assertTrue(telemetry.hasUsage()); assertEquals(3, telemetry.inputTokens()); assertEquals(2, telemetry.outputTokens());
        }
    }
    @Test void convertedSseFlushesEachEventAndForwardsBeforeUpstreamCompletes() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        var writes = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var firstWrite = new java.util.concurrent.CountDownLatch(1);
        var sink = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return Flux.from(body).concatMap(group -> DataBufferUtils.join(Flux.from(group)).map(buffer -> {
                    try { writes.add(buffer.toString(StandardCharsets.UTF_8)); firstWrite.countDown(); return true; }
                    finally { DataBufferUtils.release(buffer); }
                })).then();
            }
        };
        var current = exchange.mutate().response(sink).build();
        sink.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        var response = new ProviderResponseDecorator(current, call("OPENAI_COMPATIBLE"));
        var upstream = reactor.core.publisher.Sinks.many().unicast().<DataBuffer>onBackpressureBuffer();
        int boundary = STREAM.indexOf("\n\n") + 2;
        reactor.test.StepVerifier.create(response.writeWith(upstream.asFlux())).then(() -> {
            upstream.tryEmitNext(sink.bufferFactory().wrap(STREAM.substring(0, boundary).getBytes(StandardCharsets.UTF_8)));
            try { assertTrue(firstWrite.await(5, java.util.concurrent.TimeUnit.SECONDS), "流尚未结束时必须输出首个事件"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            assertEquals(1, writes.size());
            upstream.tryEmitNext(sink.bufferFactory().wrap(STREAM.substring(boundary).getBytes(StandardCharsets.UTF_8)));
            upstream.tryEmitComplete();
        }).verifyComplete();
        assertEquals(4, writes.size()); assertTrue(writes.get(0).contains("chat.completion.chunk"));
        assertEquals("data: [DONE]\n\n", writes.get(3));
    }
}
