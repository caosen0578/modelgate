package com.pab.ficc.ibp.modelgate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 配置全部来自 application.yml（单一固定文档，不使用 Spring Profile 机制）。
 * 原 {@code ApolloConfig} 与 apollo-client 依赖已移除——行内不需要本项目自行引入 Apollo，
 * 此前只是占位、未实际起作用。
 */
/*
 * LoadBalancer 的排除改用 excludeName 字符串形式：
 * spring-cloud-commons 4.x 对 LoadBalancerBeanPostProcessorAutoConfiguration 的包路径做过调整，
 * 用类字面量会在类不存在时直接编译失败。excludeName 只在类**确实存在于 classpath** 时才参与校验，
 * 类已移走/删除时静默忽略，因此能同时兼容新旧两代 spring-cloud-commons。
 * 两个包路径都列上：命中哪个算哪个，都不命中也不报错。
 */
@SpringBootApplication(
        exclude = {
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        },
        excludeName = {
                "org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerBeanPostProcessorAutoConfiguration",
                "org.springframework.cloud.client.loadbalancer.LoadBalancerBeanPostProcessorAutoConfiguration"
        })
@EnableScheduling
@EnableAsync
public class ModelGateApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelGateApplication.class, args);
    }
}
