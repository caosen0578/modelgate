package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
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
        // 通用能力：模型流量与服务流量都要走，只跳过管理面/运维端点。
        // 流量类型由 TraceFilter(-200) 算一次存 attribute，这里只读不算。
        if (!TrafficType.resolve(exchange).isBusiness()) {
            return chain.filter(exchange);
        }

        String appId = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        if (appId == null) {
            return chain.filter(exchange);
        }

        String modelId = exchange.getAttribute("mg.modelId");

                // ⚠️ 必须用 Optional 包一层：Mono.fromCallable 在 Callable 返回 null 时
                // **发出的是 empty 而非 null 元素**，下游 flatMap 根本不会执行，
                // 过滤器链就此断掉、Gateway 直接返回 200 空响应体。
                // 而本方法恰恰用「返回 null」表示校验通过 —— 结果是**所有通过校验的请求
                // 都拿不到模型响应**，只有被拒的请求（返回非 null）才走得通。
                // Optional 永远非 null，保证 flatMap 必定执行。
                return Mono.fromCallable(() -> Optional.ofNullable(checkQuota(appId, modelId)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    ErrorCode error = result.orElse(null);
                    if (error == null) return chain.filter(exchange);
                    // 429 是接入方最需要自查的拒绝类型（QPS/日调用量/月 Token 三选一），
                    // 必须打出是哪一层配额触发的，否则对方只知道被限流、不知道该调哪个参数
                    log.info("[QuotaFilter] 拒绝: appId={}, modelId={}, reason={}",
                            appId, modelId, error.getMessage());
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

        // 用普通 for 而不是 stream：这是每请求都走的热路径，
        // stream 的 Spliterator/Lambda 链每次都要分配，授权列表通常只有个位数条目，
        // 顺序遍历更快也不分配
        List<AppModelAuthEntity> auths = modelAuthService.listByAppIdCached(appId);
        AppModelAuthEntity auth = null;
        for (AppModelAuthEntity a : auths) {
            if (modelId.equals(a.getModelId())) {
                auth = a;
                break;
            }
        }
        if (auth == null) return null;

        // 三层配额检查。短路语义：QPS 超限就不再动日计数器，日超限不再动月计数器。
        // ⚠️ 2026-08-24 内网禁用 Lua 后，这里由「一次 eval 往返」变成「按层最多 3 次原生命令往返」，
        // 代价体现在 Jedis 连接借还次数而非端到端延迟，详见 RateLimitService 类注释。
        int verdict = rateLimitService.checkAllQuotas(appId, modelId,
                auth.getQpsLimit(), auth.getDailyLimit(), auth.getMonthlyTokens());
        return switch (verdict) {
            case RateLimitService.QUOTA_QPS_EXCEEDED -> ErrorCode.QUOTA_EXCEEDED_QPS;
            case RateLimitService.QUOTA_DAILY_EXCEEDED -> ErrorCode.QUOTA_EXCEEDED_DAILY;
            case RateLimitService.QUOTA_MONTHLY_EXCEEDED -> ErrorCode.QUOTA_EXCEEDED_MONTHLY;
            default -> null;
        };
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
