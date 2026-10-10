package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.*;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import com.pab.ficc.ibp.modelgate.server.service.ModelConnectivityService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRouteV2IntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void probeAndGatewayShareFinalRequestAndBothResponseContracts() throws Exception {
        var incoming = new AtomicReference<String>();
        var upstreamKey = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var callerLeak = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            incoming.set(body); upstreamKey.set(request.getRequestHeaders().getFirst("x-api-key"));
            callerLeak.set(request.getRequestHeaders().getFirst("Authorization"));
            path.set(request.getRequestURI().getPath()); method.set(request.getRequestMethod());
            boolean stream = mapper.readTree(body).path("stream").asBoolean();
            String output = stream
                ? "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg1\",\"usage\":{\"input_tokens\":3}}}\n\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                : "{\"id\":\"msg1\",\"content\":[{\"type\":\"text\",\"text\":\"answer\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            request.sendResponseHeaders(200, bytes.length); request.getResponseBody().write(bytes); request.close();
        }); server.start();
        try {
            var registry = new ProviderAdapterRegistry(List.of(new AnthropicAdapter(mapper)), mapper);
            var pipeline = new ProviderRequestPipeline(registry, new RequestParamMerger(mapper), mapper);
            var connectivity = new ModelConnectivityService(registry, pipeline, WebClient.builder(), mapper);
            var model = new ModelInstanceEntity(); model.setModelId("alias"); model.setUpstreamModelId("real");
            model.setApiSpec("anthropic"); model.setModelType("chat"); model.setUpstreamPath("/proxy/messages");
            model.setUpstreamUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var credential = new ModelCredentialEntity(); credential.setAuthType("api_key"); credential.setSecretKey("provider-key");
            var models = mock(ModelService.class);
            when(models.getModelCached("alias")).thenReturn(Optional.of(model));
            when(models.getCredentialDecrypted("alias")).thenReturn(credential);
            var route = new ModelRouteFilter(models, mapper, pipeline);
            for (String mode : List.of("PASSTHROUGH", "OPENAI_COMPATIBLE")) {
                model.setResponseMode(mode);
                var probe = connectivity.probe(model, credential).block(Duration.ofSeconds(5));
                assertNotNull(probe); assertTrue(probe.getSuccess());
                String probeRequest = incoming.get();
                assertEquals("/proxy/messages", path.get());
                for (boolean stream : List.of(false, true)) {
                    var plan = registry.resolve("anthropic").createProbe(ProviderModelConfig.from(model), mapper);
                    var body = mapper.readTree(plan.body()); ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("stream", stream);
                    byte[] raw = mapper.writeValueAsBytes(body);
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/modelgate/v1/chat/completions").contextPath("/modelgate")
                        .contentType(MediaType.APPLICATION_JSON).header("Authorization", "caller-key").header("api-key", "spoof"));
                    exchange.getAttributes().put("mg.bodyBytes", raw); exchange.getAttributes().put("mg.modelId", "alias");
                    route.filter(exchange, next -> WebClient.create().method(next.getRequest().getMethod()).uri(next.getRequest().getURI())
                        .headers(headers -> headers.addAll(next.getRequest().getHeaders()))
                        .body(BodyInserters.fromDataBuffers(next.getRequest().getBody())).exchangeToMono(response -> {
                            next.getResponse().setStatusCode(response.statusCode());
                            next.getResponse().getHeaders().putAll(response.headers().asHttpHeaders());
                            return next.getResponse().writeWith(response.bodyToFlux(DataBuffer.class));
                        })).block(Duration.ofSeconds(5));
                    assertEquals("POST", method.get()); assertEquals("provider-key", upstreamKey.get()); assertNull(callerLeak.get());
                    if (!stream) assertEquals(probeRequest, incoming.get());
                    String result = exchange.getResponse().getBodyAsString().block();
                    assertNotNull(result);
                    if (mode.equals("PASSTHROUGH")) assertTrue(result.contains(stream ? "message_stop" : "\"content\""));
                    else assertTrue(result.contains(stream ? "chat.completion.chunk" : "chat.completion"));
                    assertTrue(result.contains("answer"));
                }
            }
        } finally { server.stop(0); }
    }
    @Test void unknownAdapterReturnsConfigurationErrorWithoutForwarding() {
        var model = new ModelInstanceEntity(); model.setModelId("alias"); model.setModelType("chat");
        model.setApiSpec("not-installed"); model.setUpstreamUrl("https://provider.test");
        var registry = new ProviderAdapterRegistry(List.of(new OpenAIAdapter()), mapper);
        var pipeline = new ProviderRequestPipeline(registry, new RequestParamMerger(mapper), mapper);
        var models = mock(ModelService.class); when(models.getModelCached("alias")).thenReturn(Optional.of(model));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/modelgate/v1/chat/completions").contextPath("/modelgate"));
        exchange.getAttributes().put("mg.modelId", "alias"); exchange.getAttributes().put("mg.bodyBytes", "{\"model\":\"alias\"}".getBytes());
        new ModelRouteFilter(models, mapper, pipeline).filter(exchange, next -> Mono.error(new AssertionError("不得调用上游"))).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("Provider adapter not installed"));
    }
}
