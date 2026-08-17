package com.pab.ficc.ibp.modelgate.server.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 动态配置持有者（运维开关：鉴权开关、熔断开关等）。
 *
 * <p>值通过 {@code @Value} 从配置文件承接。
 *
 * <p>原先本类位于 apollo 包、由 ApolloConfig 的 @EnableApolloConfig 驱动热更新。
 * Apollo 依赖已移除（行内不需要本项目自行引入，此前只是占位、未实际起作用），
 * 现完全由配置文件提供，**变更需重启生效**。
 */
@Slf4j
@Component
public class DynamicConfigHolder {

    /** 熔断开关。 */
    @Getter
    private volatile boolean circuitBreakerEnabled = true;

    @Value("${modelgate.circuit-breaker.enabled:true}")
    public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
    }

    /**
     * 启动时打出开关实际生效值。
     *
     * <p>这两个开关改后**需重启**才生效（Apollo 已移除，不再热更），
     * 因此"当前到底是什么值"只能在启动这一刻确认——不打出来运维只能靠翻配置文件猜。
     */
    @PostConstruct
    public void logEffectiveSwitches() {
        log.info("[DynamicConfig] 开关生效值: modelgate.circuit-breaker.enabled={}（改后需重启）",
                circuitBreakerEnabled);
    }
}
