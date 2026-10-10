package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.config.DynamicConfigHolder;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.service.GatewayConfigService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import java.util.concurrent.TimeUnit;

/**
 * 上游熔断过滤器（order=-50，位于过滤器链最末端，紧靠上游转发）。
 *
 * <p><b>熔断器设计（两条链路不同）：</b>
 * <ul>
 *   <li><b>模型流量</b>：单一全局实例 {@code modelUpstream}，不按 modelId 隔离。
 *       一个模型上游挂掉会影响其余模型——这是已知的简化，改成按模型隔离是独立的一项变更。</li>
 *   <li><b>服务流量</b>：<b>按 serviceId 各自一个实例</b>（{@code svc:xxx}），
 *       配置取 {@code resilience4j.circuitbreaker.configs.serviceUpstream}。
 *       服务侧从第一天就隔离，是因为四五个接入系统本就是彼此无关的上游，
 *       让 CRM 挂掉去熔断知识库没有任何道理。</li>
 * </ul>
 * 见 {@link #resolveCircuitBreaker}。
 *
 * <p><b>触发条件：</b>
 * <ul>
 *   <li>上游返回 HTTP 5xx（服务错误）</li>
 *   <li>连接超时或网络异常（由 {@code doOnError} 捕获）</li>
 * </ul>
 *
 * <p><b>⚠️ 慢调用<u>不</u>触发熔断（2026-08-26 起）</b>：本网关的上游是 LLM，
 * "跑得久"是正常业务形态而非故障——常规请求 30~180s，复杂问题实测可跑满 20 分钟。
 * 而本过滤器 order=-50，包在 {@code NettyWriteResponseFilter}(order=-1) 外层，
 * 量的是<b>响应体全部写回客户端</b>的时长，一条 1200s 的<b>正常</b> SSE 流会被记成
 * "耗时 1200 秒的调用"。旧配置（{@code slowCallRateThreshold: 90} + {@code 180s}）
 * 会把这种正常流量判成上游故障并用 503 拒绝真实请求。
 * 现已通过 {@code slowCallDurationThreshold: 24h} 让慢调用率恒为 0，等同关闭；
 * 详见 application.yml 该段注释与 spec/modelgate/12 §11.2。
 *
 * <p><b>状态流转：</b>CLOSED（正常）→ OPEN（熔断拒绝，fast-fail）→ HALF_OPEN（探测）→ CLOSED（恢复）
 *
 * <p><b>开关：</b>支持两级开关：
 * <ul>
 *   <li>DB 配置（{@link GatewayConfigService#isCircuitBreakerEnabled}）：运营人员通过管理页面动态调整</li>
 *   <li>配置文件开关（{@link DynamicConfigHolder#isCircuitBreakerEnabled}）：modelgate.circuit-breaker.enabled，改后需重启</li>
 * </ul>
 */
@Slf4j
@Component
public class CircuitBreakerFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -50;

    private final CircuitBreakerRegistry registry;
    private final CircuitBreaker modelCircuitBreaker;
    private final GatewayConfigService gatewayConfigService;
    private final DynamicConfigHolder dynamicConfigHolder;
    private final ObjectMapper objectMapper;

    public CircuitBreakerFilter(CircuitBreakerRegistry registry,
                                GatewayConfigService gatewayConfigService,
                                DynamicConfigHolder dynamicConfigHolder,
                                ObjectMapper objectMapper) {
        this.registry = registry;
        this.modelCircuitBreaker = registry.circuitBreaker("modelUpstream");
        this.gatewayConfigService = gatewayConfigService;
        this.dynamicConfigHolder = dynamicConfigHolder;
        this.objectMapper = objectMapper;
    }

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
        String path = exchange.getRequest().getURI().getPath();
        // 服务侧从第一天就按服务隔离：一个上游挂掉不影响其它服务。
        // 模型侧仍是单一全局熔断器（改成按模型隔离是独立的一项变更，不在本批范围内）。
        CircuitBreaker circuitBreaker = resolveCircuitBreaker(exchange);

        if (!gatewayConfigService.isCircuitBreakerEnabled() || !dynamicConfigHolder.isCircuitBreakerEnabled()) {
            return chain.filter(exchange);
        }

        // tryAcquirePermission()：OPEN 状态时返回 false（快速失败），CLOSED/HALF_OPEN 时返回 true
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("[CircuitBreaker] Circuit OPEN, fast-fail: path={}", path);
            return reject(exchange);
        }

        long startNano = System.nanoTime();
        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value() : 200;
                    var telemetry = (com.pab.ficc.ibp.modelgate.server.adapter.ResponseTelemetry)
                        exchange.getAttribute(com.pab.ficc.ibp.modelgate.server.adapter.ResponseTelemetry.EXCHANGE_ATTRIBUTE);
                    if (status >= 500 || telemetry != null && telemetry.errorCode() != null) {
                        // 上游 5xx 视为调用失败，通知熔断器累计错误率
                        circuitBreaker.onError(System.nanoTime() - startNano,
                                TimeUnit.NANOSECONDS,
                                new RuntimeException("Upstream HTTP " + status));
                        // 原先此处只打指标不打日志；Prometheus 已移除，改为 WARN，
                        // 否则上游 5xx 在日志里完全不可见（熔断触发前无从察觉）
                        log.warn("[CircuitBreaker] Upstream error: status={}, path={}", status, path);
                    } else {
                        // 上游 2xx/4xx 视为成功调用（4xx 是业务拒绝，非上游故障）
                        circuitBreaker.onSuccess(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
                    }
                })
                .doOnError(ex -> {
                    // 网络异常、连接超时等，通知熔断器累计错误率
                    circuitBreaker.onError(System.nanoTime() - startNano, TimeUnit.NANOSECONDS, ex);
                    log.warn("[CircuitBreaker] Upstream exception: path={}, err={}", path, ex.toString());
                })
                // ⚠️ doOnCancel 不能省：调用方中途断开时，Mono 走的是 **cancel** 信号，
                // doOnSuccess / doOnError **都不会触发**。而上面已经 tryAcquirePermission() 借走了一个 permit。
                //
                // 在 HALF_OPEN 状态下这个 permit 是**有限的**（permittedNumberOfCallsInHalfOpenState: 5，
                // 由 CircuitBreakerStateMachine$HalfOpenState 里的 AtomicInteger 计数，
                // tryAcquirePermission 递减、releasePermission 递增）。少还一次就永久少一个。
                // 而 resilience4j 的 DEFAULT_WAIT_DURATION_IN_HALF_OPEN_STATE = 0 表示
                // **HALF_OPEN 没有超时自动退出**（本项目未配置该项）——5 个 permit 漏光之后，
                // 熔断器会永久停在 HALF_OPEN 并拒绝一切流量，**只能重启进程恢复**。
                //
                // SSE 场景下调用方中途断开（用户点停止、页面关闭、客户端读超时）是家常便饭，
                // 所以这条路径必然会被走到，不是理论风险。
                //
                // 用 releasePermission() 而不是 onSuccess/onError：调用被调用方主动放弃，
                // 上游到底成没成功是未知的。记成失败会冤枉上游、记成成功会掩盖真实故障，
                // 只有"把 permit 原样还回去、不计入统计"才是正确语义。
                .doOnCancel(() -> {
                    circuitBreaker.releasePermission();
                    log.debug("[CircuitBreaker] 调用方取消，归还 permit: path={}", path);
                });
    }

    /**
     * 取本次请求该用哪个熔断器。
     *
     * <p>服务流量按 {@code serviceId} 各自一个实例（名字 {@code svc:xxx}），配置取
     * {@code serviceUpstream}；模型流量沿用全局的 {@code modelUpstream}。
     *
     * <p>{@code registry.circuitBreaker(name, configName)} 底层是
     * {@code ConcurrentHashMap.computeIfAbsent}，每请求一次的开销可接受。
     * 服务数量是个位数（四五个接入系统），不会把 registry 撑爆。
     *
     * <p>serviceId 缺失时退回全局实例而不是新建一个匿名的：那种情况只在链路异常时出现，
     * 不该顺带在 registry 里堆出一堆一次性实例。
     */
    private CircuitBreaker resolveCircuitBreaker(ServerWebExchange exchange) {
        if (TrafficType.resolve(exchange) != TrafficType.SERVICE) {
            return modelCircuitBreaker;
        }
        String serviceId = exchange.getAttribute(ServiceAuthFilter.ATTR_SERVICE_ID);
        if (serviceId == null) {
            return modelCircuitBreaker;
        }
        return registry.circuitBreaker("svc:" + serviceId, "serviceUpstream");
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(ErrorCode.UPSTREAM_CIRCUIT_OPEN));
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":503,\"message\":\"service unavailable\"}".getBytes();
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
