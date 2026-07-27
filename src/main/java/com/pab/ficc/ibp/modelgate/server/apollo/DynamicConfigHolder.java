package com.pab.ficc.ibp.modelgate.server.apollo;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 动态配置持有者（运维开关：鉴权开关、熔断开关等）。
 *
 * <p>值通过 {@code @Value} 承接：非 local Profile 下由 Apollo 热更新刷新（见 {@link ApolloConfig}）；
 * local Profile 下由 application-local.yml 提供，静态生效。
 */
@Slf4j
@Component
public class DynamicConfigHolder {

    /** 鉴权总开关。Apollo 可临时热关，用于紧急放行场景。 */
    @Getter
    private volatile boolean authEnabled = true;

    /** 熔断开关。 */
    @Getter
    private volatile boolean circuitBreakerEnabled = true;

    @Value("${modelgate.auth.enabled:true}")
    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Value("${modelgate.circuit-breaker.enabled:true}")
    public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
    }
}
