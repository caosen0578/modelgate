package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.service.ServiceAuthService;
import com.pab.ficc.ibp.modelgate.server.service.ServiceRegistryService;
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
 * 通用服务授权过滤器（order=-170）。
 *
 * <p>与 {@code ModelAuthFilter} <b>同为 -170 且互斥</b>：本过滤器只处理
 * {@link TrafficType#SERVICE}，那个只处理 {@link TrafficType#MODEL}，
 * 同一请求必定只有一个真正执行、另一个立刻放行，所以相对顺序无意义。
 *
 * <p>本过滤器还承担<b>解析 serviceId</b> 的职责：从 path 第二段取出并写入
 * {@code mg.serviceId}，供 QuotaFilter(-160)、PayloadCaptureFilter(-145)、
 * ServiceRouteFilter(-140)、TraceFilter 的 doFinally 复用，全链路只解析一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceAuthFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -170;

    /** exchange attribute：本次请求的服务编码，由本过滤器解析并写入 */
    public static final String ATTR_SERVICE_ID = "mg.serviceId";

    private final ServiceRegistryService serviceRegistryService;
    private final ServiceAuthService serviceAuthService;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (TrafficType.resolve(exchange) != TrafficType.SERVICE) {
            return chain.filter(exchange);
        }

        String path = com.pab.ficc.ibp.modelgate.server.common.ApplicationPaths.path(exchange.getRequest());
        String serviceId = extractServiceId(path);
        if (serviceId == null) {
            // 形如 /svc/ 或 /svc —— 路由能匹配到，但没有服务编码可用
            log.info("[ServiceAuth] 拒绝: 路径缺少服务编码, path={}", path);
            return reject(exchange, ErrorCode.SERVICE_CODE_MISSING);
        }
        // 写进 attribute：后续 4 个消费方（Quota/-160、PayloadCapture/-145、
        // ServiceRoute/-140、TraceFilter 的 doFinally）直接读，不再各自切一遍字符串
        exchange.getAttributes().put(ATTR_SERVICE_ID, serviceId);

        String appId = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        if (appId == null) {
            // CredentialAuthFilter(-190) 没放行就到不了这里；到了却没 appId 说明链路被改动过。
            // 与模型侧一致：放行交给后续环节，不在这里越权拒绝。
            return chain.filter(exchange);
        }

        // ⚠️ 必须用 Optional 包一层：Mono.fromCallable 在 Callable 返回 null 时
        // **发出的是 empty 而非 null 元素**，下游 flatMap 根本不会执行，过滤器链就此断掉、
        // Gateway 直接返回 200 空响应体。而本方法恰恰用「返回 null」表示校验通过——
        // 结果会是所有通过校验的请求都拿不到响应。模型侧踩过这个坑，见 ModelAuthFilter。
        return Mono.fromCallable(() -> Optional.ofNullable(checkAuth(appId, serviceId)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    ErrorCode error = result.orElse(null);
                    if (error == null) {
                        log.debug("[ServiceAuth] 通过: appId={}, serviceId={}", appId, serviceId);
                        return chain.filter(exchange);
                    }
                    // 业务拒绝记 INFO：这是排查"某系统调不通某服务"的第一现场
                    log.info("[ServiceAuth] 拒绝: appId={}, serviceId={}, reason={}",
                            appId, serviceId, error.getMessage());
                    return reject(exchange, error);
                });
    }

    /**
     * 从 {@code /svc/{serviceId}/**} 里取出服务编码。
     *
     * <p>取不到（{@code /svc}、{@code /svc/}、{@code /svc//x}）返回 null，由调用方转 404。
     *
     * <p>⚠️ 这里<b>不做</b>矩阵参数剥离：路由谓词 {@code Path=/svc/**} 会把
     * {@code /svc;a=b/...} 也匹配进来，而那种路径的第二段并不是干净的服务编码。
     * 这类请求会因为查不到对应服务而落到 {@link ErrorCode#SERVICE_NOT_FOUND}，
     * 属于安全的失败方式（拒绝而不是错误放行），与模型侧对同类路径的处理口径一致，
     * 详见 spec/modelgate/12 §12.2b。
     */
    static String extractServiceId(String path) {
        // path 形如 /svc/{code}/... ，前缀长度固定
        int start = "/svc/".length();
        if (path.length() <= start) {
            return null;
        }
        int end = path.indexOf('/', start);
        String code = end < 0 ? path.substring(start) : path.substring(start, end);
        return code.isEmpty() ? null : code;
    }

    /**
     * 判定顺序与 {@code ModelAuthFilter.checkAuth} 一一对应：
     * 服务存在 → 服务启用 → 有授权行 → 授权启用 → 未过期。
     *
     * @return null 表示通过；非 null 是拒绝原因
     */
    private ErrorCode checkAuth(String appId, String serviceId) {
        Optional<ServiceEntity> svcOpt = serviceRegistryService.getServiceCached(serviceId);
        if (svcOpt.isEmpty()) {
            return ErrorCode.SERVICE_NOT_FOUND;
        }
        if (Boolean.FALSE.equals(svcOpt.get().getEnabled())) {
            return ErrorCode.SERVICE_DISABLED;
        }

        // 用普通 for 而不是 stream：这是每请求都走的热路径，stream 的 Spliterator/Lambda
        // 链每次都要分配，而授权列表通常只有个位数条目，顺序遍历更快也不分配。
        // 与 QuotaFilter.checkQuota 同一手法。
        List<AppServiceAuthEntity> auths = serviceAuthService.listByAppIdCached(appId);
        AppServiceAuthEntity auth = null;
        for (AppServiceAuthEntity a : auths) {
            if (serviceId.equals(a.getServiceId())) {
                auth = a;
                break;
            }
        }
        if (auth == null) {
            return ErrorCode.SERVICE_NOT_AUTHORIZED;
        }
        if (Boolean.FALSE.equals(auth.getEnabled())) {
            return ErrorCode.SERVICE_NOT_AUTHORIZED;
        }
        if (auth.getExpiresAt() != null && !auth.getExpiresAt().isAfter(java.time.LocalDateTime.now())) {
            return ErrorCode.SERVICE_AUTH_EXPIRED;
        }
        return null;
    }

    /**
     * HTTP 状态取自 {@link ErrorCode} 自身，<b>不写死 403</b>。
     *
     * <p>本过滤器的 5 个拒绝原因跨了 403/404/503 三种语义。模型侧踩过写死 403 的坑：
     * 会造成「状态码 403、响应体 code:404 Service not found」的自相矛盾，
     * 调用方按 403 去查授权与白名单，实际原因却是路径里的服务编码写错了。
     */
    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode errorCode) {
        // 供 TraceFilter 的 doFinally 写进审计的 error_msg
        exchange.getAttributes().put("mg.errorMsg", errorCode.getMessage());

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(errorCode.getCode()));
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
