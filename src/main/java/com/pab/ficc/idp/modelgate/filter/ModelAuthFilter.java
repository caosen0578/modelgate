package com.pab.ficc.idp.modelgate.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.AppModelAuth;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import com.pab.ficc.idp.modelgate.service.ModelAuthService;
import com.pab.ficc.idp.modelgate.service.ModelService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Model authorization filter (order=-170).
 *
 * Reads the 'model' field from the JSON request body and validates:
 *   1. The model exists and is enabled
 *   2. The app has an active authorization for this model
 *   3. The authorization has not expired
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelAuthFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -170;

    private final ModelAuthService modelAuthService;
    private final ModelService modelService;
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

        // Read the cached body bytes set by ModelRouteFilter (if already parsed) or use path as fallback
        String modelId = exchange.getAttribute("mg.modelId");
        if (modelId == null) {
            // Body not yet parsed — skip model auth for non-chat paths
            return chain.filter(exchange);
        }

        return Mono.fromCallable(() -> checkAuth(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(error -> {
                    if (error == null) {
                        return chain.filter(exchange);
                    }
                    return reject(exchange, HttpStatus.FORBIDDEN, error);
                });
    }

    private ErrorCode checkAuth(String appId, String modelId) {
        Optional<ModelInstance> modelOpt = modelService.getModelCached(modelId);
        if (modelOpt.isEmpty()) return ErrorCode.MODEL_NOT_FOUND;
        if (Boolean.FALSE.equals(modelOpt.get().getEnabled())) return ErrorCode.MODEL_DISABLED;

        List<AppModelAuth> auths = modelAuthService.listByAppIdCached(appId);
        Optional<AppModelAuth> authOpt = auths.stream()
                .filter(a -> a.getModelId().equals(modelId))
                .findFirst();

        if (authOpt.isEmpty()) return ErrorCode.MODEL_NOT_AUTHORIZED;

        AppModelAuth auth = authOpt.get();
        if (Boolean.FALSE.equals(auth.getEnabled())) return ErrorCode.MODEL_NOT_AUTHORIZED;
        if (auth.getExpiresAt() != null && auth.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ErrorCode.AUTH_EXPIRED;
        }
        return null;
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
