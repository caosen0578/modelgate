package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.ficc.ibp.modelgate.server.common.TraceContext;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * 打通 Reactor Context 与 MDC，使 traceId 能跨线程随请求走。
 *
 * <p><b>解决的问题</b>：WebFlux 下请求在 Netty 事件循环线程组装、阻塞逻辑在 boundedElastic
 * 线程执行，MDC 这种 ThreadLocal 不会自动跟着走。改造前的表现是：
 * <ul>
 *   <li>所有业务日志的 {@code [%X{traceId}]} 都是空的 {@code []}，无法按请求串联；</li>
 *   <li>事件循环线程上 {@code MDC.put} 后无人清理，会被后续<b>其它请求</b>的日志继承（串号）。</li>
 * </ul>
 *
 * <p><b>做法</b>：注册 {@link ThreadLocalAccessor} 把 MDC 的 traceId / rid 两个键登记为
 * 「可传播的 ThreadLocal」，再开启 {@link Hooks#enableAutomaticContextPropagation()}。
 * 此后只要把值写进 Reactor Context（见 {@code TraceWebFilter}），
 * Reactor 会在**每一次线程切换**时自动写入目标线程的 MDC、并在任务结束后清理。
 *
 * <p><b>业务代码因此不需要、也不应该再手动 {@code MDC.put/remove}。</b>
 *
 * <p>依赖 {@code io.micrometer:context-propagation}（36KB，无传递依赖）。
 * ⚠️ 合入内网需确认该坐标可用，缺失会导致本类 ClassNotFound、启动失败。
 */
@Slf4j
@Configuration
public class ReactorContextPropagationConfig {

    @PostConstruct
    public void enableMdcPropagation() {
        ContextRegistry registry = ContextRegistry.getInstance();
        registerMdcKey(registry, TraceContext.TRACE_ID_KEY);
        registerMdcKey(registry, TraceContext.LOG_TRACE_ID_KEY);

        // 开启后，Reactor 在所有跨线程点自动搬运已登记的 ThreadLocal。
        // 该开关是全局且幂等的，重复调用无副作用。
        Hooks.enableAutomaticContextPropagation();

        log.info("[ContextPropagation] 已开启 Reactor 上下文自动传播, 登记 MDC 键: {}, {}",
                TraceContext.TRACE_ID_KEY, TraceContext.LOG_TRACE_ID_KEY);
    }

    /**
     * 把一个 MDC 键登记为可传播的 ThreadLocal。
     *
     * <p>三个回调分别对应：从当前线程读值、把值写入目标线程、任务结束后清理目标线程。
     * 清理必须走 {@code MDC.remove} 而非 {@code MDC.clear}——后者会把同线程上
     * 其它组件（如内网框架）写入的 MDC 一并抹掉。
     */
    private static void registerMdcKey(ContextRegistry registry, String key) {
        registry.registerThreadLocalAccessor(key,
                () -> MDC.get(key),
                value -> MDC.put(key, value),
                () -> MDC.remove(key));
    }
}
