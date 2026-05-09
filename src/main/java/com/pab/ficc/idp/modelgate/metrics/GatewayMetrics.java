package com.pab.ficc.idp.modelgate.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 网关 Prometheus 指标
 * <p>
 * 暴露的核心指标：
 *  modelgate_requests_accepted_total{owner}   — 鉴权通过的请求数
 *  modelgate_requests_rejected_total{reason}  — 拒绝原因（missing_key/whitelist_miss/rate_limit/ip_block）
 *  modelgate_requests_latency_seconds{owner}  — 请求延迟分布
 *  modelgate_upstream_errors_total            — 上游错误数
 */
@Component
@RequiredArgsConstructor
public class GatewayMetrics {

    private final MeterRegistry registry;

    // ===== 接受 =====

    public void recordAccepted(String owner) {
        Counter.builder("modelgate.requests.accepted")
                .tag("owner", owner != null ? owner : "unknown")
                .description("Total accepted requests")
                .register(registry)
                .increment();
    }

    // ===== 拒绝 =====

    public void recordRejected(String reason) {
        Counter.builder("modelgate.requests.rejected")
                .tag("reason", reason)
                .description("Total rejected requests")
                .register(registry)
                .increment();
    }

    // ===== 延迟 =====

    public void recordLatency(String owner, long latencyMs) {
        Timer.builder("modelgate.requests.latency")
                .tag("owner", owner != null ? owner : "unknown")
                .description("Request latency")
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    // ===== 上游错误 =====

    public void recordUpstreamError(String reason) {
        Counter.builder("modelgate.upstream.errors")
                .tag("reason", reason)
                .description("Upstream errors")
                .register(registry)
                .increment();
    }

    // ===== 熔断状态 =====

    public void recordCircuitBreakerOpen() {
        Counter.builder("modelgate.circuit_breaker.open")
                .description("Circuit breaker open count")
                .register(registry)
                .increment();
    }
}
