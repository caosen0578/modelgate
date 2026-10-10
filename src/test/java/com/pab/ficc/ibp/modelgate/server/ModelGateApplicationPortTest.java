package com.pab.ficc.ibp.modelgate.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.boot.web.reactive.server.ConfigurableReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.server.reactive.HttpHandler;
import reactor.core.publisher.Mono;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelGateApplicationPortTest {
    @Test
    void absentOrBlankDoesNotRegisterPortCustomizer() {
        for (String value : new String[]{null, "", " ", "\t\n"}) {
            var application = new SpringApplication();
            int initializers = application.getInitializers().size();
            ModelGateApplication.configurePort(application, value);
            assertEquals(initializers, application.getInitializers().size());
        }
    }

    @Test
    void validPortTrimsWhitespaceAndSetsFactoryPort() {
        for (String value : new String[]{"18088", " 18088 ", "\t18088\n", "018088"}) {
            assertFactoryPort(value, 18088);
        }
    }

    @Test
    void acceptsPortRangeBoundaries() {
        assertFactoryPort("1", 1);
        assertFactoryPort("65535", 65535);
    }

    @Test
    void rejectsInvalidPortBeforeApplicationRuns() {
        for (String value : new String[]{"0", "-1", "65536", "2147483648", "abc", "18 088", "8080.0", "+8080", "${OTHER}"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ModelGateApplication.configurePort(new SpringApplication(), value), value);
            assertTrue(error.getMessage().contains("PORT_NO"));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertFactoryPort(String value, int expected) {
        var application = new SpringApplication();
        var original = application.getInitializers();
        ModelGateApplication.configurePort(application, value);
        try (var context = new GenericApplicationContext()) {
            // 仅执行 main 新增的初始化器，不启动服务或装配生产组件。
            application.getInitializers().stream().filter(initializer -> !original.contains(initializer))
                    .forEach(initializer -> ((org.springframework.context.ApplicationContextInitializer) initializer).initialize(context));
            context.refresh();
            var customizer = context.getBean("portNoWebServerCustomizer", WebServerFactoryCustomizer.class);
            assertEquals(Ordered.LOWEST_PRECEDENCE, ((Ordered) customizer).getOrder());
            var factory = mock(ConfigurableReactiveWebServerFactory.class);
            customizer.customize(factory);
            verify(factory).setPort(expected);
        }
    }

    @Test
    void nettyUsesPortNoWithoutChangingLateApolloConfiguration() throws Exception {
        assertNettyPort(Integer.toString(unusedPort()), true);
    }

    @Test
    void nettyKeepsConfiguredPortWhenPortNoIsAbsent() throws Exception {
        assertNettyPort(null, false);
    }

    @Test
    void nettyKeepsConfiguredPortWhenPortNoIsBlank() throws Exception {
        assertNettyPort(" \t", false);
    }

    private int unusedPort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void assertNettyPort(String portNo, boolean override) throws Exception {
        int configuredPort = unusedPort();
        var application = new SpringApplication(WebTestConfiguration.class);
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.REACTIVE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        ModelGateApplication.configurePort(application, portNo);
        // 模拟 Apollo 比 main 中的初始化器更晚加入高优先级配置源。
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("simulatedApollo", Map.of("server.port", configuredPort))));
        try (var context = (ReactiveWebServerApplicationContext) application.run(
                "--spring.config.location=optional:classpath:/port-no-test.yml", "--server.port=0",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN", "--server.shutdown=immediate")) {
            int actualPort = context.getWebServer().getPort();
            assertEquals(override ? Integer.parseInt(portNo) : configuredPort, actualPort);
            // 实际监听端口可以不同，但 Apollo 配置和 ServerProperties 都保持原值。
            assertEquals(configuredPort, context.getEnvironment().getProperty("server.port", Integer.class));
            assertEquals(configuredPort, context.getBean(ServerProperties.class).getPort());
            assertFalse(context.getEnvironment().getPropertySources().contains("modelgatePortNo"));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + actualPort + "/"))
                    .timeout(Duration.ofSeconds(5)).build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ready", response.body());
        }
    }

    /** 仅启动真实 Netty，不装配业务、数据库或 Redis。 */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ReactiveWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class})
    static class WebTestConfiguration {
        @Bean
        HttpHandler httpHandler() {
            return (request, response) -> response.writeWith(Mono.just(
                    response.bufferFactory().wrap("ready".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
