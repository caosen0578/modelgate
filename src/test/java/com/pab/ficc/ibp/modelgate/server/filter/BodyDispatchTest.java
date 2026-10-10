package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.*;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BodyDispatchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void populatedModelBodyDispatchesExactlyOnce() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/modelgate/v1/chat/completions")
            .contextPath("/modelgate").contentType(MediaType.APPLICATION_JSON).body("{\"model\":\"alias\"}"));
        var count = new AtomicInteger();
        new BodyParseFilter(mapper).filter(exchange, next -> Mono.fromRunnable(count::incrementAndGet)).block(Duration.ofSeconds(5));
        assertEquals(1, count.get());
        assertEquals("alias", exchange.getAttribute("mg.modelId"));
    }

    @Test void emptyModelBodyDispatchesExactlyOnce() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/modelgate/v1/chat/completions")
            .contextPath("/modelgate").contentType(MediaType.APPLICATION_JSON));
        var count = new AtomicInteger();
        new BodyParseFilter(mapper).filter(exchange, next -> Mono.fromRunnable(count::incrementAndGet)).block(Duration.ofSeconds(5));
        assertEquals(1, count.get());
        assertNull(exchange.getAttribute("mg.bodyBytes"));
    }

    @Test void modelRouteFallbackDoesNotRejectAfterSuccessfulCompletion() {
        var model = new ModelInstanceEntity(); model.setModelId("alias"); model.setApiSpec("openai");
        model.setModelType("chat"); model.setUpstreamUrl("http://provider.test");
        var models = mock(ModelService.class); when(models.getModelCached("alias")).thenReturn(Optional.of(model));
        var registry = new ProviderAdapterRegistry(List.of(new OpenAIAdapter(mapper)), mapper);
        var pipeline = new ProviderRequestPipeline(registry, new RequestParamMerger(mapper), mapper);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/modelgate/v1/chat/completions")
            .contextPath("/modelgate").body("{\"model\":\"alias\"}"));
        var count = new AtomicInteger();
        new ModelRouteFilter(models, mapper, pipeline).filter(exchange, next -> Mono.fromRunnable(count::incrementAndGet))
            .block(Duration.ofSeconds(5));
        assertEquals(1, count.get());
        assertNull(exchange.getResponse().getStatusCode());
    }
}
