package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
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
 * 请求体缓存过滤器（order=-175）。
 *
 * <p><b>存在原因：</b>WebFlux 的请求体是 Reactive Stream（只能读一次），
 * 而过滤器链中有多个环节需要读取 body（解析 model 字段、参数转换等）。
 * 本过滤器在链路最前端读取并缓存 body 字节数组，
 * 同时通过 {@link ServerHttpRequestDecorator} 将字节数组重新封装回请求流，
 * 使后续过滤器仍可正常读取，解决"只读一次"的问题。
 *
 * <p><b>处理结果：</b>
 * <ul>
 *   <li>{@code exchange.getAttributes().put("mg.bodyBytes", bytes)}：原始字节数组，供后续使用。</li>
 *   <li>{@code exchange.getAttributes().put("mg.modelId", ...)}：从 JSON 的 {@code model}
 *       字段提取，供 ModelAuthFilter、QuotaFilter、TraceFilter 读取，无需再次解析。</li>
 * </ul>
 *
 * <p><b>作用范围：</b>仅对 POST /v1/** 且 Content-Type 为 JSON 的请求生效，
 * GET 请求和 /modelgate/** 接口直接放行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BodyParseFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -175;

    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(UrlConstant.BUSINESS_PREFIX) || exchange.getRequest().getMethod() != HttpMethod.POST) {
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
            log.debug("[BodyParse] 请求体未能解析出 model 字段: {}", e.getMessage());
        }
    }
}
