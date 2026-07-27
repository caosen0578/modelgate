package com.pab.ficc.ibp.modelgate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerBeanPostProcessorAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Apollo 通过 {@code ApolloConfig}（Profile != local）启用；local Profile 全部走配置文件，不接 Apollo。
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
        SpringApplication.run(ModelGateApplication.class, args);
    }
}
