package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TraceContext;
import com.pab.ficc.ibp.modelgate.server.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Trace ID injection filter (order=-150).
 *
 * Generates a traceId (UUID prefix 16 chars), writes to MDC and X-Trace-Id response header.
 * Also propagates X-Mg-App and X-Mg-Model to response headers for observability.
 * Records an audit log entry for every proxied request (appId != null) on completion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -150;

    private final AuditService auditService;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.generate();
        MDC.put("traceId", traceId);

        // Capture request-side context before the chain runs.
        // BodyParseFilter (order=-155) executes before us, so mg.modelId is already set.
        // CredentialAuthFilter (order=-190) injects X-Mg-App and X-Mg-Cred-Type as request headers.
        String appId    = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        String credType = exchange.getRequest().getHeaders().getFirst("X-Mg-Cred-Type");
        String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : null;
        String requestPath = exchange.getRequest().getURI().getPath();
        long startMs = System.currentTimeMillis();

        // Propagate trace headers
        String modelIdForHeader = exchange.getAttribute("mg.modelId");
        exchange.getResponse().getHeaders().set(TraceContext.HEADER_TRACE_ID, traceId);
        if (appId != null)           exchange.getResponse().getHeaders().set("X-Mg-App", appId);
        if (modelIdForHeader != null) exchange.getResponse().getHeaders().set("X-Mg-Model", modelIdForHeader);

        var mutated = exchange.getRequest().mutate()
                .header(TraceContext.HEADER_TRACE_ID, traceId)
                .build();

        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> {
                    try {
                        // Only audit proxied (app-level) requests
                        if (appId != null) {
                            long latencyMs = System.currentTimeMillis() - startMs;
                            String resolvedModelId = exchange.getAttribute("mg.modelId");
                            String errorMsg        = exchange.getAttribute("mg.errorMsg");
                            HttpStatus status      = exchange.getResponse().getStatusCode();
                            int httpStatus         = status != null ? status.value() : 0;

                            auditService.log(appId, resolvedModelId, credType, clientIp,
                                    requestPath, httpStatus, latencyMs,
                                    0, 0, errorMsg, traceId);
                        }
                    } finally {
                        MDC.remove("traceId");
                        TraceContext.clear();
                    }
                });
    }
}
