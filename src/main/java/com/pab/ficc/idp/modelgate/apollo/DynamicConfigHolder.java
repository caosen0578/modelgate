package com.pab.ficc.idp.modelgate.apollo;

import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apollo 动态配置持有者
 * <p>
 * 职责收窄：只保留 Apollo 专属的运维开关配置（鉴权开关、熔断开关等）。
 * <p>
 * 上游模型地址已迁移至数据库（gateway_config 表），
 * 由 GatewayConfigService 管理，支持实时修改，无需发版。
 * <p>
 * 监听的 namespace：application（默认）和 modelgate（网关专属）
 */
@Slf4j
@Component
public class DynamicConfigHolder {

    /**
     * 鉴权总开关
     * Apollo 可临时热关，用于紧急放行场景。
     * 日常开关建议走数据库（auth.enabled），Apollo 做应急兜底。
     */
    @Getter
    private volatile boolean authEnabled = true;

    /**
     * 熔断开关
     */
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

    /**
     * 监听 Apollo 配置变更，打印变更日志
     */
    @ApolloConfigChangeListener({"application", "modelgate"})
    public void onConfigChange(ConfigChangeEvent event) {
        for (String key : event.changedKeys()) {
            ConfigChange change = event.getChange(key);
            log.info("[Apollo] Config changed — key={}, old={}, new={}, type={}",
                    key, change.getOldValue(), change.getNewValue(), change.getChangeType());
        }
    }
}
