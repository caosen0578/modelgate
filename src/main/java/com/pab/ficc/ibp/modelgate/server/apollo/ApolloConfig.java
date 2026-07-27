package com.pab.ficc.ibp.modelgate.server.apollo;

import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import com.ctrip.framework.apollo.spring.annotation.EnableApolloConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Apollo 装配与变更监听（Profile != local）。
 *
 * <p>local Profile 不加载本类，Apollo 完全不接入，所有配置走 application-local.yml。
 */
@Slf4j
@Configuration
@Profile("!local")
@EnableApolloConfig
public class ApolloConfig {

    /**
     * 监听 Apollo 配置变更，打印变更日志。
     * 具体开关值由 {@link DynamicConfigHolder} 通过 @Value 承接（Apollo 热更新会刷新 @Value）。
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
