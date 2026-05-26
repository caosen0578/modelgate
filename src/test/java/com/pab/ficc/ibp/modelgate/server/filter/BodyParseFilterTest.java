package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BodyParseFilterTest {

    @Mock GatewayFilterChain chain;

    @InjectMocks BodyParseFilter bodyParseFilter;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(bodyParseFilter, "objectMapper", new ObjectMapper());
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // -------------------------------------------------------------------------
    // skip conditions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filter — GET method → skips, chain called directly")
    void filter_getMethod_skips() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/chat/completions").build());

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getAttribute("mg.modelId")).isNull();
    }

    @Test
    @DisplayName("filter — /admin/ path → skips")
    void filter_adminPath_skips() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/admin/models").build());

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("filter — non-JSON content type → skips")
    void filter_nonJsonContentType_skips() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("hello"));

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(exchange.getAttribute("mg.modelId")).isNull();
    }

    // -------------------------------------------------------------------------
    // body parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filter — valid JSON with 'model' field → sets mg.modelId attribute")
    void filter_validJson_setsModelId() {
        String body = "{\"model\":\"gpt-4o\",\"messages\":[]}";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getAttribute("mg.modelId")).isEqualTo("gpt-4o");
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("filter — JSON without 'model' field → mg.modelId remains null")
    void filter_jsonNoModelField_noAttribute() {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getAttribute("mg.modelId")).isNull();
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("filter — invalid JSON → no exception, chain still called")
    void filter_invalidJson_noException() {
        String body = "not-valid-json";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getAttribute("mg.modelId")).isNull();
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("filter — caches body bytes in exchange attribute")
    void filter_cachesBodBytes() {
        String body = "{\"model\":\"claude-3\"}";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        StepVerifier.create(bodyParseFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.<byte[]>getAttribute("mg.bodyBytes")).isNotNull();
        assertThat(new String((byte[]) exchange.getAttribute("mg.bodyBytes"))).isEqualTo(body);
    }

    @Test
    @DisplayName("filter — order is -155")
    void filter_orderIs155() {
        assertThat(bodyParseFilter.getOrder()).isEqualTo(-155);
    }
}
