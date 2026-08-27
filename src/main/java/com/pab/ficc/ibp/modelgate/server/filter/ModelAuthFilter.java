package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
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
        // 模型专属：只处理模型流量。服务流量由 ServiceAuthFilter 承担（同为 -170，两者互斥）。
        if (TrafficType.resolve(exchange) != TrafficType.MODEL) {
            return chain.filter(exchange);
        }

        String appId = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        if (appId == null) {
            return chain.filter(exchange);
        }

        // BodyParseFilter (order=-175) runs before us, so mg.modelId is already extracted.
        // Null here means non-JSON or non-POST request (e.g. GET /v1/models) — no model auth needed.
        String modelId = exchange.getAttribute("mg.modelId");
        if (modelId == null) {
            return chain.filter(exchange);
        }

                // ⚠️ 必须用 Optional 包一层：Mono.fromCallable 在 Callable 返回 null 时
                // **发出的是 empty 而非 null 元素**，下游 flatMap 根本不会执行，
                // 过滤器链就此断掉、Gateway 直接返回 200 空响应体。
                // 而本方法恰恰用「返回 null」表示校验通过 —— 结果是**所有通过校验的请求
                // 都拿不到模型响应**，只有被拒的请求（返回非 null）才走得通。
                // Optional 永远非 null，保证 flatMap 必定执行。
                return Mono.fromCallable(() -> Optional.ofNullable(checkAuth(appId, modelId)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    ErrorCode error = result.orElse(null);
                    if (error == null) {
                        log.debug("[ModelAuth] 通过: appId={}, modelId={}", appId, modelId);
                        return chain.filter(exchange);
                    }
                    // 业务拒绝按 03 §7 记 INFO：这是排查"某系统调不通某模型"的第一现场。
                    // 注：TraceFilter 现在是 -200、**早于**本过滤器（-170），被拒请求同样会进审计
                    // （app_id 可能为空）。这条 INFO 补充的是拒绝原因，不再是"唯一痕迹"——
                    // 原注释写的 TraceFilter(-150) 是 order 前移之前的旧世界观，已过时。
                    log.info("[ModelAuth] 拒绝: appId={}, modelId={}, reason={}", appId, modelId, error.getMessage());
                    // HTTP 状态取自 ErrorCode 自身，不再一律 FORBIDDEN：
                    // 本过滤器的 4 个拒绝原因里 MODEL_NOT_FOUND 是 404，其余 3 个才是 403。
                    // 写死 403 会造成「状态码 403、响应体 code:404 Model not found」的自相矛盾，
                    // 调用方按 403 去查授权与白名单，实际原因却是 model 字段写错了——极其误导。
                    return reject(exchange, HttpStatus.valueOf(error.getCode()), error);
                });
    }

    private ErrorCode checkAuth(String appId, String modelId) {
        Optional<ModelInstanceEntity> modelOpt = modelService.getModelCached(modelId);
        if (modelOpt.isEmpty()) return ErrorCode.MODEL_NOT_FOUND;
        if (Boolean.FALSE.equals(modelOpt.get().getEnabled())) return ErrorCode.MODEL_DISABLED;

        List<AppModelAuthEntity> auths = modelAuthService.listByAppIdCached(appId);
        Optional<AppModelAuthEntity> authOpt = auths.stream()
                .filter(a -> a.getModelId().equals(modelId))
                .findFirst();

        if (authOpt.isEmpty()) return ErrorCode.MODEL_NOT_AUTHORIZED;

        AppModelAuthEntity auth = authOpt.get();
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
