package com.pab.ficc.idp.modelgate.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.idp.modelgate.admin.IpMatcher;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.AccessRule;
import com.pab.ficc.idp.modelgate.service.AccessRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;

/**
 * IP/domain access control filter (order=-180).
 *
 * Reads X-Mg-App set by CredentialAuthFilter, loads access rules from cache,
 * and validates client IP. Empty rules = allow all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppAccessControlFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -180;

    private final AccessRuleService accessRuleService;
    private final IpMatcher ipMatcher;
    private final ObjectMapper objectMapper;

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

        String appId = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        if (appId == null) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange.getRequest());

        return Mono.fromCallable(() -> accessRuleService.listByAppIdCached(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rules -> {
                    if (rules.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    if (isAllowed(clientIp, rules)) {
                        return chain.filter(exchange);
                    }
                    log.info("[AccessControl] Denied appId={}, clientIp={}", appId, clientIp);
                    return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED);
                });
    }

    private boolean isAllowed(String clientIp, List<AccessRule> rules) {
        for (AccessRule rule : rules) {
            switch (rule.getRuleType()) {
                case "ip" -> {
                    if (clientIp.equals(rule.getRuleValue())) return true;
                }
                case "cidr" -> {
                    if (ipMatcher.matchesCidr(clientIp, rule.getRuleValue())) return true;
                }
            }
        }
        return false;
    }

    private String getClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = request.getHeaders().getFirst("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        return Objects.requireNonNull(request.getRemoteAddress()).getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + errorCode.getCode() + ",\"message\":\"" + errorCode.getMessage() + "\"}").getBytes();
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
