package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderAdapterV2Test {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicAdapter anthropic = new AnthropicAdapter(mapper);
    private final ProviderAdapterRegistry registry = new ProviderAdapterRegistry(List.of(
        new OpenAIAdapter(), new AzureOpenAIAdapter(mapper), anthropic, new CustomAdapter()), mapper);
    private final ProviderRequestPipeline pipeline = new ProviderRequestPipeline(registry, new RequestParamMerger(mapper), mapper);
    private static final DefaultDataBufferFactory FACTORY = DefaultDataBufferFactory.sharedInstance;
    private static final String STREAM = "event: message_start\r\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_123\",\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}\r\n\r\n"
        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"中文🙂\"}}\n\n"
        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n\n"
        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    private ModelInstanceEntity model(String id, String mode) {
        var model = new ModelInstanceEntity(); model.setModelId("alias"); model.setModelType("chat");
        model.setApiSpec(id); model.setUpstreamUrl("https://provider.test"); model.setResponseMode(mode);
        return model;
    }
    private ResponseSession session(String mode, MediaType type, int status) {
        var headers = new HttpHeaders(); headers.setContentType(type);
        return anthropic.openResponse(new AdapterResponseContext(ProviderModelConfig.from(model("anthropic", mode)),
            ProviderOperation.CHAT_COMPLETIONS, status, headers));
    }
    private static String text(Flux<DataBuffer> output) {
        return DataBufferUtils.join(output).map(buffer -> {
            try { return buffer.toString(StandardCharsets.UTF_8); }
            finally { DataBufferUtils.release(buffer); }
        }).block(Duration.ofSeconds(5));
    }
    @Test void registryRejectsDuplicatesAndUnknownAdapters() {
        assertThrows(IllegalStateException.class, () -> new ProviderAdapterRegistry(List.of(new OpenAIAdapter(), new OpenAIAdapter()), mapper));
        assertEquals(ErrorCode.PROVIDER_UNSUPPORTED, assertThrows(BusinessException.class, () -> registry.resolve("absent")).getErrorCode());
        assertEquals(4, registry.descriptors().size());
        assertFalse(registry.resolve("custom").descriptor().responseModes().contains("OPENAI_COMPATIBLE"));
    }
    @Test void requestPipelineKeepsZeroCopyAndCleansProviderCredentials() {
        var model = model("openai", null);
        var credential = new ModelCredentialEntity(); credential.setAuthType("api_key"); credential.setSecretKey("upstream-secret");
        byte[] body = "{\"model\":\"alias\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "caller-key"); headers.set("api-key", "injected"); headers.set("X-Mg-Extra", "internal");
        headers.set("X-Tenant", "business"); headers.set("Connection", "X-Drop"); headers.set("X-Drop", "hop");
        var prepared = pipeline.prepare(model, credential, HttpMethod.POST, URI.create("/v1/chat/completions"), headers, body, -1, -1).block();
        assertNotNull(prepared); assertSame(body, prepared.request().body());
        assertEquals(ResponseMode.PASSTHROUGH, prepared.config().responseMode());
        var out = prepared.request().headers();
        assertEquals("Bearer upstream-secret", out.getFirst("Authorization"));
        assertFalse(out.containsKey("api-key")); assertFalse(out.containsKey("X-Mg-Extra")); assertFalse(out.containsKey("X-Drop"));
        assertEquals("business", out.getFirst("X-Tenant")); assertEquals(body.length, out.getContentLength());
        assertEquals("identity", out.getFirst("Accept-Encoding"));
        assertFalse(ProviderCredential.from(credential).toString().contains("upstream-secret"));
    }
    @Test void configuredPolicyCannotFailOpen() {
        var model = model("openai", null); model.setForceParams("{\"temperature\":0.5}");
        StepVerifier.create(pipeline.prepare(model, null, HttpMethod.POST, URI.create("/v1/chat/completions"),
            new HttpHeaders(), "{broken".getBytes(StandardCharsets.UTF_8), -1, -1))
            .expectErrorMatches(e -> e instanceof BusinessException b && b.getErrorCode() == ErrorCode.PROVIDER_REQUEST_INVALID).verify();
        model.setForceParams("{\"model\":\"bypass\"}");
        assertThrows(BusinessException.class, () -> pipeline.validateForSave(model, null));
        model.setForceParams(null); model.setResponseMode("native-typo");
        assertThrows(BusinessException.class, () -> pipeline.validateForSave(model, null));
        assertThrows(BusinessException.class, () -> anthropic.transformRequestBody("{}".getBytes(), model, null));
        assertThrows(BusinessException.class, () -> new AzureOpenAIAdapter(mapper).transformRequestBody("[]".getBytes(), model, null));
    }
    @Test void azureQueryIsMergedAndConfiguredVersionWins() {
        var model = model("azure_openai", null);
        model.setUpstreamUrl("https://azure.test/openai/deployments/a/chat/completions?api-version=2024-01-01");
        var uri = registry.resolve("azure_openai").buildUpstreamUri(model, URI.create("/v1/chat/completions?api-version=bad&trace=a%2Fb"));
        assertEquals("api-version=2024-01-01&trace=a%2Fb", uri.getRawQuery());
    }
    @Test void nativeResponseIsByteForByteAndDoesNotReleasePassedBuffers() {
        for (var type : List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)) {
            var input = FACTORY.wrap(STREAM.getBytes(StandardCharsets.UTF_8));
            var session = session("PASSTHROUGH", type, 200);
            StepVerifier.create(session.transform(Flux.just(input), FACTORY)).assertNext(buffer -> {
                assertSame(input, buffer); DataBufferUtils.release(buffer);
            }).verifyComplete();
        }
    }
    @Test void jsonMapsContentToolsFinishReasonAndUsage() throws Exception {
        String nativeJson = "{\"id\":\"msg_test\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"tool_use\",\"id\":\"tool1\",\"name\":\"lookup\",\"input\":{\"x\":1}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":7,\"cache_read_input_tokens\":2,\"output_tokens\":3}}";
        var session = session("OPENAI_COMPATIBLE", MediaType.APPLICATION_JSON, 200);
        var converted = mapper.readTree(text(session.transform(Flux.just(FACTORY.wrap(nativeJson.getBytes())), FACTORY)));
        assertEquals("chat.completion", converted.path("object").asText());
        assertEquals("alias", converted.path("model").asText());
        assertEquals("hello", converted.at("/choices/0/message/content").asText());
        assertEquals("{\"x\":1}", converted.at("/choices/0/message/tool_calls/0/function/arguments").asText());
        assertEquals("tool_calls", converted.at("/choices/0/finish_reason").asText());
        assertEquals(12, converted.at("/usage/total_tokens").asInt());
        var headers = new HttpHeaders(); headers.setContentLength(100); headers.setETag("\"old\""); headers.set("Digest", "old");
        session.adaptHeaders(headers); assertEquals(-1, headers.getContentLength()); assertFalse(headers.containsKey("ETag"));
        assertFalse(headers.containsKey("Digest")); assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
    }
    @Test void sseWorksAtEveryByteSplitIncludingUtf8AndCrlf() {
        byte[] bytes = STREAM.getBytes(StandardCharsets.UTF_8);
        for (int split = 1; split < bytes.length; split++) {
            var session = session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200);
            String output = text(session.transform(Flux.just(FACTORY.wrap(Arrays.copyOfRange(bytes, 0, split)),
                FACTORY.wrap(Arrays.copyOfRange(bytes, split, bytes.length))), FACTORY));
            assertTrue(output.contains("中文🙂"), "split=" + split); assertTrue(output.endsWith("data: [DONE]\n\n"));
            assertEquals(7, session.telemetry().inputTokens()); assertEquals(3, session.telemetry().outputTokens());
        }
        var buffers = new ArrayList<DataBuffer>(); for (byte value : bytes) buffers.add(FACTORY.wrap(new byte[]{value}));
        assertTrue(text(session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200).transform(Flux.fromIterable(buffers), FACTORY)).contains("中文🙂"));
    }
    @Test void framerMultilineHeartbeatAndBoundedPartialFrames() {
        var decoder = new SseFrameDecoder(100);
        assertEquals("one\ntwo", decoder.accept(": ping\r\nevent: x\r\ndata: one\r\ndata:two\r\n\r\n".getBytes()).get(0).data());
        assertThrows(BusinessException.class, () -> decoder.accept("a".repeat(101).getBytes()));
    }
    @Test void sseToolArgumentsAndInStreamError() {
        String tool = "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool1\",\"name\":\"lookup\",\"input\":{}}}\n\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}\n\n"
            + "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}\n\n";
        var session = session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200);
        String output = text(session.transform(Flux.just(FACTORY.wrap(tool.getBytes())), FACTORY));
        assertTrue(output.contains("tool_calls")); assertTrue(output.contains("partial_json") == false);
        assertTrue(output.contains("overloaded_error")); assertEquals("overloaded_error", session.telemetry().errorCode());
        assertTrue(output.endsWith("data: [DONE]\n\n"));
    }
    @Test void sseToolArgumentsAlwaysFormJsonIncludingNoArgumentTools() throws Exception {
        for (String input : List.of("{}", "{\"x\":1}")) {
            for (boolean hasDelta : List.of(false, true)) {
                if (hasDelta && !input.equals("{}")) continue;
                String stream = "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool1\",\"name\":\"lookup\",\"input\":" + input + "}}\n\n"
                    + (hasDelta ? "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}\n\n" : "")
                    + "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
                    + "data: {\"type\":\"message_stop\"}\n\n";
                String output = text(session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200)
                    .transform(Flux.just(FACTORY.wrap(stream.getBytes(StandardCharsets.UTF_8))), FACTORY));
                var arguments = new StringBuilder();
                for (var frame : new SseFrameDecoder(1024 * 1024).accept(output.getBytes(StandardCharsets.UTF_8))) {
                    if (!"[DONE]".equals(frame.data()))
                        arguments.append(mapper.readTree(frame.data()).at("/choices/0/delta/tool_calls/0/function/arguments").asText(""));
                }
                assertEquals(mapper.readTree(hasDelta ? "{\"x\":1}" : input), mapper.readTree(arguments.toString()));
            }
        }
    }
    @Test void truncatedStreamAndMalformedJsonFailExplicitly() {
        var sse = session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200);
        StepVerifier.create(sse.transform(Flux.just(FACTORY.wrap("data: {\"type\":\"ping\"}\n\n".getBytes())), FACTORY))
            .thenConsumeWhile(buffer -> { DataBufferUtils.release(buffer); return true; }).expectError(BusinessException.class).verify();
        StepVerifier.create(session("OPENAI_COMPATIBLE", MediaType.APPLICATION_JSON, 200)
            .transform(Flux.just(FACTORY.wrap("garbage".getBytes())), FACTORY)).expectError(BusinessException.class).verify();
    }
    @Test void pooledInputsReleasedAndCancellationStopsUpstream() {
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        NettyDataBuffer input = factory.allocateBuffer(); input.write(STREAM.getBytes(StandardCharsets.UTF_8));
        assertTrue(text(session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200)
            .transform(Flux.just(input), factory)).contains("中文🙂"));
        assertEquals(0, input.getNativeBuffer().refCnt());
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var upstream = Flux.<DataBuffer>never().doOnCancel(() -> cancelled.set(true));
        StepVerifier.create(session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200).transform(upstream, FACTORY), 0)
            .thenCancel().verify(); assertTrue(cancelled.get());
    }
    @Test void perRequestStateIsIsolatedAndHttpErrorsUseJsonEvenForStreamingClient() throws Exception {
        var first = session("OPENAI_COMPATIBLE", MediaType.TEXT_EVENT_STREAM, 200);
        var second = session("OPENAI_COMPATIBLE", MediaType.APPLICATION_JSON, 403);
        String output = text(second.transform(Flux.just(FACTORY.wrap("{\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}".getBytes())), FACTORY));
        assertEquals("authentication_error", mapper.readTree(output).at("/error/code").asText());
        assertNull(first.telemetry().errorCode());
    }
    @Test void toolsVisionAndMultipleSystemRequestsAreTranslated() throws Exception {
        String request = "{\"model\":\"real\",\"messages\":[{\"role\":\"system\",\"content\":\"a\"},{\"role\":\"developer\",\"content\":\"b\"},{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"parameters\":{\"type\":\"object\"}}}],\"stop\":\"end\"}";
        var converted = mapper.readTree(anthropic.transformRequestBody(request.getBytes(), model("anthropic", null), null));
        assertEquals("a\nb", converted.path("system").asText());
        assertEquals("base64", converted.at("/messages/0/content/0/source/type").asText());
        assertEquals("object", converted.at("/tools/0/input_schema/type").asText());
        assertEquals("end", converted.at("/stop_sequences/0").asText());
    }
    @Test void jsonBodyCanBeResubscribedWithFreshBuffers() {
        var payload = new ProviderRequestBody.Buffered("body".getBytes());
        assertNotSame(payload.publisher(FACTORY).blockFirst(), payload.publisher(FACTORY).blockFirst());
    }
}
