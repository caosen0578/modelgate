package com.pab.ficc.idp.modelgate.filter;

import com.pab.ficc.idp.modelgate.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Trace ID injection filter (order=-150).
 *
 * Generates a traceId (UUID prefix 16 chars), writes to MDC and X-Trace-Id response header.
 * Also propagates X-Mg-App and X-Mg-Model to response headers for observability.
 */
@Slf4j
@Component
public class TraceFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -150;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.generate();
        MDC.put("traceId", traceId);

        String appId = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        String modelId = exchange.getAttribute("mg.modelId");

        exchange.getResponse().getHeaders().set(TraceContext.HEADER_TRACE_ID, traceId);
        if (appId != null) exchange.getResponse().getHeaders().set("X-Mg-App", appId);
        if (modelId != null) exchange.getResponse().getHeaders().set("X-Mg-Model", modelId);

        var mutated = exchange.getRequest().mutate()
                .header(TraceContext.HEADER_TRACE_ID, traceId)
                .build();

        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> {
                    MDC.remove("traceId");
                    TraceContext.clear();
                });
    }
}
