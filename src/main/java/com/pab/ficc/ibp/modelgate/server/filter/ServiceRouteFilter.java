package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.service.ServiceRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 通用服务路由过滤器（order=-140）。与 {@code ModelRouteFilter} 同层且互斥。
 *
 * <p><b>比模型链路轻一个量级</b>，因为它不做这些事：
 * <ul>
 *   <li>不读请求体（路由键在 path 上，不在 body 的 {@code model} 字段里）</li>
 *   <li>不解析 JSON、不做参数合并、无适配器</li>
 *   <li><b>不覆写 {@code getBody()}</b> —— 请求体原样流式透传，这是全链路最省的一步</li>
 *   <li>不碰 {@code Content-Length} —— 碰它就得先知道 body 多长，等于把省下的开销付回去</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceRouteFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -140;

    /** 上游鉴权类型常量，取值与 {@code mg_service_credential.auth_type} 一致 */
    private static final String AUTH_API_KEY = "api_key";
    private static final String AUTH_AK_SK = "ak_sk";

    private final ServiceRegistryService serviceRegistryService;
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

        String serviceId = exchange.getAttribute(ServiceAuthFilter.ATTR_SERVICE_ID);
        if (serviceId == null) {
            // ServiceAuthFilter(-170) 已经解析并校验过；到这里还是 null 说明它没生效
            // （order 被改动或 bean 未装配），属链路异常而非调用方问题，记 WARN
            log.warn("[ServiceRoute] mg.serviceId 缺失，ServiceAuthFilter(-170) 可能未生效: path={}",
                    exchange.getRequest().getURI().getPath());
            return reject(exchange, ErrorCode.SERVICE_CODE_MISSING);
        }

        // 装载服务与凭证要查缓存（miss 时打 DB），必须切到 boundedElastic —— 不能在
        // Netty 事件循环线程上做可能阻塞的调用，那会拖垮整个进程的转发能力。
        // Optional 包一层的理由同 ServiceAuthFilter：Callable 返回 null 会让 Mono 变 empty、
        // flatMap 不执行、链路静默断掉。
        return Mono.fromCallable(() -> Optional.ofNullable(buildRouteInfo(exchange, serviceId)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    RouteInfo info = result.orElse(null);
                    if (info == null) {
                        return reject(exchange, ErrorCode.SERVICE_NOT_FOUND);
                    }
                    return applyRoute(exchange, chain, info, serviceId);
                });
    }

    /** 装载服务 + 凭证，拼出上游 URI 与要注入的请求头。服务不存在时返回 null（调用方转 404）。 */
    private RouteInfo buildRouteInfo(ServerWebExchange exchange, String serviceId) {
        Optional<ServiceEntity> svcOpt = serviceRegistryService.getServiceCached(serviceId);
        if (svcOpt.isEmpty()) {
            log.info("[ServiceRoute] 拒绝: 服务不存在, serviceId={}", serviceId);
            return null;
        }
        ServiceEntity svc = svcOpt.get();
        ServiceCredentialEntity cred = serviceRegistryService.getCredentialDecrypted(serviceId);

        URI original = exchange.getRequest().getURI();
        String upstreamUri = svc.joinUpstream(
                        resolveRemainder(original.getPath(), serviceId, svc.shouldStripPrefix()))
                + (original.getRawQuery() != null ? "?" + original.getRawQuery() : "");

        return new RouteInfo(URI.create(upstreamUri), buildUpstreamAuthHeaders(cred, serviceId), svc.getTimeoutMs());
    }

    /**
     * 算出要拼到 {@code upstreamUrl} 之后的那段路径。
     *
     * <p>{@code stripPrefix=true}（默认）：剥掉 {@code /svc/{serviceId}}，
     * {@code /svc/crm/api/v2/query} → {@code /api/v2/query}，接入方内部路径一行都不用改。
     * {@code false}：完整路径原样转发。
     *
     * <p><b>剥完为空、或只剩一个 {@code "/"}，一律返回空串</b>——{@code /svc/crm} 与
     * {@code /svc/crm/} 指的是同一个资源（服务根），这里不该产生差异。
     * 补不补斜杠交给 {@link ServiceEntity#joinUpstream(String)} 决定，因为那要看
     * {@code upstreamUrl} 自己带不带路径：上游地址是 {@code http://host:9000/chat/completions}
     * 时在这里补出 {@code /chat/completions/}，多数框架会当成另一条路由并回 404。
     */
    static String resolveRemainder(String path, String serviceId, boolean stripPrefix) {
        if (!stripPrefix) {
            return path;
        }
        String prefix = "/svc/" + serviceId;
        String remainder = path.length() > prefix.length() ? path.substring(prefix.length()) : "";
        return "/".equals(remainder) ? "" : remainder;
    }

    /**
     * 按 {@code auth_type} 构造要注入上游的鉴权头，并合并 {@code extraHeaders}。
     *
     * <p>四种 authType 都会额外注入 extraHeaders（若非空）——包括 {@code none}，
     * 因为"上游不鉴权"和"上游要几个固定业务头"是两件正交的事。
     */
    private HttpHeaders buildUpstreamAuthHeaders(ServiceCredentialEntity cred, String serviceId) {
        HttpHeaders h = new HttpHeaders();
        if (cred == null) {
            return h;
        }
        String type = cred.getAuthType();
        if (AUTH_API_KEY.equals(type) && cred.getSecretKey() != null) {
            h.setBearerAuth(cred.getSecretKey());
        } else if (AUTH_AK_SK.equals(type)) {
            if (cred.getAccessKey() != null) {
                h.set("X-Access-Key", cred.getAccessKey());
            }
            if (cred.getSecretKey() != null) {
                h.set("X-Secret-Key", cred.getSecretKey());
            }
        }
        applyExtraHeaders(h, cred.getExtraHeaders(), serviceId);
        return h;
    }

    /**
     * 把 {@code extraHeaders} 的 JSON 解析成请求头。
     *
     * <p>解析失败只记 WARN 并跳过，<b>不让整条转发链路失败</b>：格式错误是配置问题，
     * 表现为"少了几个头"（上游多半回 401/400，信息量比网关抛 500 大得多）。
     * 录入时 {@code ServiceRegistryService.validateExtraHeaders} 已挡过一道，
     * 走到这里说明是直接改库或换了加密 key。
     */
    private void applyExtraHeaders(HttpHeaders target, String json, String serviceId) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, String> extra = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            extra.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    target.set(k, v);
                }
            });
        } catch (Exception e) {
            log.warn("[ServiceRoute] 附加请求头解析失败，已跳过: serviceId={}, cause={}", serviceId, e.getMessage());
        }
    }

    private Mono<Void> applyRoute(ServerWebExchange exchange, GatewayFilterChain chain,
                                  RouteInfo info, String serviceId) {
        URI upstreamUri = info.upstreamUri();

        // ⚠️ 只设 GATEWAY_REQUEST_URL_ATTR 是不够的：内置的 RouteToRequestUrlFilter(order=10000)
        // 会用 Route 的**静态 uri** 重新计算并覆盖该属性，结果请求被打到 application.yml 里
        // 那个占位地址，表现为 Connection refused 或 200 空响应。必须把 Route 本身换掉。
        // 这一段与 ModelRouteFilter 同源，那边踩过坑并留了同样的注释。
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null) {
            Route.AsyncBuilder builder = Route.async()
                    .id(route.getId())
                    .uri(upstreamUri)
                    .order(route.getOrder())
                    .asyncPredicate(route.getPredicate())
                    .filters(route.getFilters());

            // 每服务独立超时：NettyRoutingFilter 从 Route metadata 读 "response-timeout"
            // （该常量已在其字节码常量池中确认）。0 表示沿用全局，不下发 metadata。
            // 注意其真实语义是「等上游吐出响应头的最长时间」，管不到响应体（SSE 帧不受约束）。
            Map<String, Object> metadata = new HashMap<>(route.getMetadata());
            if (info.timeoutMs() != null && info.timeoutMs() > 0) {
                metadata.put("response-timeout", info.timeoutMs().longValue());
            }
            builder.metadata(metadata);

            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, builder.build());
        }
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, upstreamUri);
        log.debug("[ServiceRoute] 转发: serviceId={}, upstream={}", serviceId, upstreamUri);

        ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public URI getURI() {
                // 让 RouteToRequestUrlFilter 合并出的 path/query 也指向上游
                return upstreamUri;
            }

            @Override
            public HttpHeaders getHeaders() {
                // 不带 contentLength 的重载：请求体没被改写，**绝不能**碰 Content-Length。
                // 调用方原始的 Content-Length 或 Transfer-Encoding: chunked 原样保留。
                return UpstreamHeaderBuilder.build(super.getHeaders(), info.headers());
            }

            // 刻意**不覆写 getBody()**：请求体原样流式透传，不缓冲、不复制。
            // 这正是服务链路比模型链路轻一个量级的关键——模型链路必须
            // DataBufferUtils.join 把几百 KB ~ 几 MB 的 body 读全才能拿到路由键。
        };

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode errorCode) {
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

    /** timeoutMs 为 null 或 ≤0 表示沿用全局 response-timeout。 */
    private record RouteInfo(URI upstreamUri, HttpHeaders headers, Integer timeoutMs) {}
}
