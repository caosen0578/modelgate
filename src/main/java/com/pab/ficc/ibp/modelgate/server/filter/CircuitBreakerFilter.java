package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.apollo.DynamicConfigHolder;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.metrics.GatewayMetrics;
import com.pab.ficc.ibp.modelgate.server.service.GatewayConfigService;
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
 * 上游熔断过滤器（order=-50，位于过滤器链最末端，紧靠上游转发）。
 *
 * <p><b>熔断器设计：</b>使用 Resilience4j，名称 {@code modelUpstream}，
 * 配置在 {@code application.yml} 的 {@code resilience4j.circuitbreaker.instances.modelUpstream}。
 * 当前实现为单一全局熔断器（不按 modelId 隔离），简化了管理复杂度；
 * 若需按模型独立熔断，可改为 {@code registry.circuitBreaker("model:" + modelId)}。
 *
 * <p><b>触发条件：</b>
 * <ul>
 *   <li>上游返回 HTTP 5xx（服务错误）</li>
 *   <li>连接超时或网络异常（由 {@code doOnError} 捕获）</li>
 *   <li>慢调用（响应时间超过 {@code slowCallDurationThreshold}，由 Resilience4j 内部检测）</li>
 * </ul>
 *
 * <p><b>状态流转：</b>CLOSED（正常）→ OPEN（熔断拒绝，fast-fail）→ HALF_OPEN（探测）→ CLOSED（恢复）
 *
 * <p><b>开关：</b>支持两级开关：
 * <ul>
 *   <li>DB 配置（{@link GatewayConfigService#isCircuitBreakerEnabled}）：运营人员通过管理页面动态调整</li>
 *   <li>Apollo 配置（{@link DynamicConfigHolder#isCircuitBreakerEnabled}）：配置中心热推送</li>
 * </ul>
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

        // tryAcquirePermission()：OPEN 状态时返回 false（快速失败），CLOSED/HALF_OPEN 时返回 true
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
                        // 上游 5xx 视为调用失败，通知熔断器累计错误率
                        circuitBreaker.onError(System.nanoTime() - startNano,
                                TimeUnit.NANOSECONDS,
                                new RuntimeException("Upstream HTTP " + status));
                        metrics.recordUpstreamError("http_" + status);
                    } else {
                        // 上游 2xx/4xx 视为成功调用（4xx 是业务拒绝，非上游故障）
                        circuitBreaker.onSuccess(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
                    }
                })
                .doOnError(ex -> {
                    // 网络异常、连接超时等，通知熔断器累计错误率
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
