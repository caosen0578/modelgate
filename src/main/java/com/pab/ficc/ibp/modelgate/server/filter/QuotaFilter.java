package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import com.pab.ficc.ibp.modelgate.server.service.RateLimitService;
import lombok.RequiredArgsConstructor;
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
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/**
 * Quota enforcement filter (order=-160).
 *
 * Checks per-app+model QPS, daily, and monthly-token quotas from mg_app_model_auth.
 * Falls back to global default rate when modelId is not yet resolved.
 * Fail-open on Redis unavailability.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -160;

    private final RateLimitService rateLimitService;
    private final ModelAuthService modelAuthService;
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

        String modelId = exchange.getAttribute("mg.modelId");

        return Mono.fromCallable(() -> checkQuota(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(error -> {
                    if (error == null) return chain.filter(exchange);
                    return reject(exchange, error);
                });
    }

    private ErrorCode checkQuota(String appId, String modelId) {
        if (modelId == null) {
            // Body not yet parsed — apply global per-app rate limit
            if (!rateLimitService.isAllowed("app:" + appId, rateLimitService.getDefaultRate())) {
                return ErrorCode.QUOTA_EXCEEDED_QPS;
            }
            return null;
        }

        List<AppModelAuthEntity> auths = modelAuthService.listByAppIdCached(appId);
        Optional<AppModelAuthEntity> authOpt = auths.stream()
                .filter(a -> a.getModelId().equals(modelId))
                .findFirst();

        if (authOpt.isEmpty()) return null;
        AppModelAuthEntity auth = authOpt.get();

        if (!rateLimitService.isQpsAllowed(appId, modelId, auth.getQpsLimit())) {
            log.info("[QuotaFilter] QPS exceeded appId={} modelId={}", appId, modelId);
            return ErrorCode.QUOTA_EXCEEDED_QPS;
        }

        if (!rateLimitService.isDailyAllowed(appId, modelId, auth.getDailyLimit())) {
            log.info("[QuotaFilter] Daily limit exceeded appId={} modelId={}", appId, modelId);
            return ErrorCode.QUOTA_EXCEEDED_DAILY;
        }

        if (!rateLimitService.checkMonthlyTokens(appId, modelId, auth.getMonthlyTokens())) {
            log.info("[QuotaFilter] Monthly token quota exceeded appId={} modelId={}", appId, modelId);
            return ErrorCode.QUOTA_EXCEEDED_MONTHLY;
        }

        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode errorCode) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
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
