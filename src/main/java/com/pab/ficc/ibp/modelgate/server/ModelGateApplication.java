package com.pab.ficc.ibp.modelgate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.web.reactive.server.ConfigurableReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * 本地业务配置来自 application.yml；内网配置中心由内网框架接入。
 * main 读取容器环境变量 PORT_NO，非空时直接设置监听端口，不覆盖配置中心的 server.port。
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
        SpringApplication application = new SpringApplication(ModelGateApplication.class);
        configurePort(application, System.getenv("PORT_NO"));
        application.run(args);
    }

    /** main 专用启动逻辑；不写系统属性或 Spring 配置源。 */
    static void configurePort(SpringApplication application, String portNo) {
        if (!StringUtils.hasText(portNo)) {
            return;
        }
        String normalized = portNo.trim();
        int port;
        try {
            if (!normalized.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            port = Integer.parseInt(normalized);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT_NO 必须为 1~65535 的整数端口；未设置或空白时使用 server.port", e);
        }
        int listeningPort = port;
        // 注册在 main 启动的上下文内，待默认端口配置完成后、Netty 绑定端口前执行。
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "portNoWebServerCustomizer", new PortNoWebServerCustomizer(listeningPort)));
    }

    private record PortNoWebServerCustomizer(int port)
            implements WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory>, Ordered {
        @Override
        public void customize(ConfigurableReactiveWebServerFactory factory) {
            factory.setPort(port);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
