package com.pab.ficc.ibp.modelgate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerBeanPostProcessorAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Collections;

/**
 * Apollo 通过 {@code ApolloConfig}（Profile != local）启用；local Profile 全部走配置文件，不接 Apollo。
 *
 * <p><b>默认 profile：</b>未显式指定时默认 {@code local}（本地 IDE/java -jar 直接可跑）。
 * 内网各环境通过环境变量 {@code SPRING_PROFILES_ACTIVE=dev/prod} 或启动参数显式覆盖即可，不受影响。
 */
@SpringBootApplication(exclude = {
        LoadBalancerBeanPostProcessorAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@EnableScheduling
@EnableAsync
public class ModelGateApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ModelGateApplication.class);
        // spring.profiles.default：仅当没有任何显式激活的 profile 时生效（最低优先级，env/-D/--参数都能覆盖）
        app.setDefaultProperties(Collections.singletonMap("spring.profiles.default", "local"));
        app.run(args);
    }
}
