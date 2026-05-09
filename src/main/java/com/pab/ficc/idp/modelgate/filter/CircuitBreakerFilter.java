package com.pab.ficc.idp.modelgate.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.idp.modelgate.apollo.DynamicConfigHolder;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.metrics.GatewayMetrics;
import com.pab.ficc.idp.modelgate.service.GatewayConfigService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

/**
 * Circuit breaker filter (order=-50).
 * Per-model circuit breaker using Resilience4j.
 * Trips on upstream 5xx or connection errors.
 */
@Slf4j
@Component
public class CircuitBreakerFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -50;

    private final CircuitBreaker circuitBreaker;
    private final GatewayConfigService gatewayConfigService;
    private final DynamicConfigHolder apolloConfigHolder;
    private final GatewayMetrics metrics;
    private final ObjectMapper objectMapper;

    public CircuitBreakerFilter(CircuitBreakerRegistry registry,
                                GatewayConfigService gatewayConfigService,
                                DynamicConfigHolder apolloConfigHolder,
                                GatewayMetrics metrics,
                                ObjectMapper objectMapper) {
        this.circuitBreaker = registry.circuitBreaker("modelUpstream");
        this.gatewayConfigService = gatewayConfigService;
        this.apolloConfigHolder = apolloConfigHolder;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/admin/") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        if (!gatewayConfigService.isCircuitBreakerEnabled() || !apolloConfigHolder.isCircuitBreakerEnabled()) {
            return chain.filter(exchange);
        }

        if (!circuitBreaker.tryAcquirePermission()) {
            metrics.recordCircuitBreakerOpen();
            log.warn("[CircuitBreaker] Circuit OPEN, fast-fail: path={}", path);
            return reject(exchange);
        }

        long startNano = System.nanoTime();
        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value() : 200;
                    if (status >= 500) {
                        circuitBreaker.onError(System.nanoTime() - startNano,
                                TimeUnit.NANOSECONDS,
                                new RuntimeException("Upstream HTTP " + status));
                        metrics.recordUpstreamError("http_" + status);
                    } else {
                        circuitBreaker.onSuccess(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
                    }
                })
                .doOnError(ex -> {
                    circuitBreaker.onError(System.nanoTime() - startNano, TimeUnit.NANOSECONDS, ex);
                    metrics.recordUpstreamError("exception");
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(ErrorCode.UPSTREAM_CIRCUIT_OPEN));
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":503,\"message\":\"service unavailable\"}".getBytes();
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
