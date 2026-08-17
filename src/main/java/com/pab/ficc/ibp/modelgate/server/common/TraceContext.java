package com.pab.ficc.ibp.modelgate.server.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪 ID 工具。
 *
 * <p><b>WebFlux 下 MDC 的正确用法（改造背景，务必读完再动这个类）</b>：
 * MDC 基于 ThreadLocal，而响应式链路会跨线程——请求在 Netty 事件循环线程上组装，
 * 阻塞逻辑经 {@code subscribeOn(boundedElastic)} 跑在另一批线程上。
 * 因此**直接在过滤器里 {@code MDC.put} 是错的**，会同时产生两个问题：
 * <ul>
 *   <li>切到 boundedElastic 后 MDC 为空 → 业务日志的 traceId 全部丢失；</li>
 *   <li>事件循环线程上残留的 traceId 不会被清理 → 被后续<b>其它请求</b>的日志继承，串号。</li>
 * </ul>
 *
 * <p>现方案：traceId 只写入 <b>Reactor Context</b>（见 {@code TraceWebFilter}），
 * 由 {@code ReactorContextPropagationConfig} 注册的 ThreadLocalAccessor 在每次线程切换时
 * 自动把它同步进/出 MDC。**业务代码不需要也不应该再手动操作 MDC。**
 *
 * <p>本类保留的静态方法仅供<b>非响应式上下文</b>（如 {@code @Scheduled} 定时任务）使用。
 */
public final class TraceContext {

    /** Reactor Context 与 MDC 共用的键名；logback pattern 里的 %X{traceId} 取的就是它。 */
    public static final String TRACE_ID_KEY = "traceId";

    /**
     * 行内 Result 把 logTraceId 取自 MDC 的 "rid"（CatLog 约定）。
     * 本系统没有独立的 CatLog 链路，直接复用同一个 traceId 填充，
     * 否则管理面响应里的 logTraceId 恒为 null，前端拿它对照后端日志的功能形同虚设。
     */
    public static final String LOG_TRACE_ID_KEY = "rid";

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private TraceContext() {}

    /** 生成 16 位 traceId（不写 MDC；写入由 Reactor Context 传播机制负责）。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 读当前线程 MDC 中的 traceId；响应式链路里由传播机制保证其正确性。 */
    public static String get() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 仅供非响应式上下文（定时任务等）手动设置。
     * <b>禁止</b>在 WebFilter / GlobalFilter / Service 的响应式链路中调用。
     */
    public static void set(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
        MDC.put(LOG_TRACE_ID_KEY, traceId);
    }

    /** 与 {@link #set} 配对使用，同样仅限非响应式上下文。 */
    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
        MDC.remove(LOG_TRACE_ID_KEY);
    }
}
