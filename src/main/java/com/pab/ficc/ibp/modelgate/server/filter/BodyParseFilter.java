package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Caches the request body so downstream filters can read it without consuming the stream.
 * Sets exchange attribute "mg.modelId" from the JSON "model" field.
 * Only applies to POST /v1/** with JSON content type.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BodyParseFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -155;

    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/v1/") || exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    exchange.getAttributes().put("mg.bodyBytes", bytes);
                    extractModelId(exchange, bytes);

                    ServerHttpRequestDecorator mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            DataBuffer buf = exchange.getResponse().bufferFactory().wrap(bytes);
                            return Flux.just(buf);
                        }
                    };
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    private void extractModelId(ServerWebExchange exchange, byte[] bytes) {
        try {
            String body = new String(bytes, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(body);
            JsonNode modelNode = node.get("model");
            if (modelNode != null && modelNode.isTextual()) {
                exchange.getAttributes().put("mg.modelId", modelNode.asText());
            }
        } catch (Exception e) {
            log.debug("[BodyParseFilter] Could not parse model field: {}", e.getMessage());
        }
    }
}
