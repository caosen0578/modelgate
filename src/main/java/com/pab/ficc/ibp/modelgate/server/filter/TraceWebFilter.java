package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * traceId 注入（WebFilter 层，作用于**全部**请求）。
 *
 * <p><b>为什么是 WebFilter 而不是 GlobalFilter</b>：Spring Cloud Gateway 的
 * {@code GlobalFilter} 只对**匹配到路由**的请求生效，即只有 {@code /v1/**}。
 * 管理面 {@code /modelgate/**} 由 Controller 处理、不匹配任何路由，
 * 因此此前完全拿不到 traceId——表现为管理接口响应里的
 * {@code requestId} / {@code logTraceId} 恒为 {@code null}，
 * 前端「失败提示带 logTraceId 便于对照后端日志」形同虚设。
 * WebFilter 位于 DispatcherHandler 之前，覆盖静态资源、管理接口、业务流量全部三类。
 *
 * <p><b>traceId 只写 Reactor Context，不直接写 MDC</b>：
 * 写 MDC 在响应式下既传不过去也清不掉（见 {@link TraceContext} 类注释）。
 * 由 {@code ReactorContextPropagationConfig} 注册的传播机制负责在每次线程切换时
 * 自动同步进 MDC，logback 的 {@code %X{traceId}} 因此在任何线程上都能取到值。
 *
 * <p>同时把 traceId 放进 exchange attribute，供下游需要同步读取的地方
 * （{@code TraceFilter} 写审计、{@code GlobalExceptionHandler} 写响应头）使用。
 *
 * <p>调用方可通过请求头 {@code X-Trace-Id} 自带 traceId 实现跨系统串联；
 * 未携带时本层生成。响应头一律回写，供调用方记录后反查。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceWebFilter implements WebFilter {

    /** exchange attribute 键；下游同步读取用 */
    public static final String ATTR_TRACE_ID = "mg.traceId";

    /** 调用方自带 traceId 的长度上限，防止超长/畸形值污染日志与审计表 */
    private static final int MAX_INBOUND_TRACE_ID_LEN = 64;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolveTraceId(exchange);

        exchange.getAttributes().put(ATTR_TRACE_ID, traceId);
        exchange.getResponse().getHeaders().set(TraceContext.HEADER_TRACE_ID, traceId);

        return chain.filter(exchange)
                // 两个键都写：traceId 供日志 pattern，rid 供行内 Result 的 logTraceId
                .contextWrite(Context.of(
                        TraceContext.TRACE_ID_KEY, traceId,
                        TraceContext.LOG_TRACE_ID_KEY, traceId));
    }

    /**
     * 优先沿用调用方传入的 traceId（跨系统串联），否则本层生成。
     * 传入值做长度与字符校验：它会进日志和审计表，不能让调用方塞任意内容。
     */
    private static String resolveTraceId(ServerWebExchange exchange) {
        String inbound = exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER_TRACE_ID);
        if (inbound != null && !inbound.isBlank()
                && inbound.length() <= MAX_INBOUND_TRACE_ID_LEN
                && inbound.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
            return inbound;
        }
        return TraceContext.generate();
    }
}
