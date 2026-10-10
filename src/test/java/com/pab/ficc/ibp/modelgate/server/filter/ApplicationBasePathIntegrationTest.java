package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.*;
import com.pab.ficc.ibp.modelgate.server.common.ServiceState;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.controller.ContainerDetectionController;
import com.pab.ficc.ibp.modelgate.server.controller.ProviderAdapterController;
import com.pab.ficc.ibp.modelgate.server.domain.entity.*;
import com.pab.ficc.ibp.modelgate.server.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** 独立 Netty/Gateway 上下文，无生产组件扫描、无 DB/Redis/供应商调用。 */
@SpringBootTest(classes = ApplicationBasePathIntegrationTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN"})
class ApplicationBasePathIntegrationTest {
    @LocalServerPort int port;
    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(5)).build();
    }
    @Test void managementStaticAndAllProbesUseGlobalBasePath() {
        client().get().uri("/modelgate/models/provider-adapters").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data[0].adapterId").isEqualTo("openai");
        client().get().uri("/modelgate/admin.html").exchange().expectStatus().isOk();
        client().get().uri("/modelgate/architecture.html").exchange().expectStatus().isOk();
        client().get().uri("/modelgate/actuator/health").exchange().expectStatus().isOk();
        for (String endpoint : List.of("running", "available", "shutdown")) {
            client().get().uri("/modelgate/actuator/detection/" + endpoint).exchange().expectStatus().isOk();
            client().post().uri("/modelgate/actuator/detection/" + endpoint).exchange().expectStatus().isOk();
        }
    }
    @Test void unprefixedAndDoubledPathsAreNotExposed() {
        for (String path : List.of("/models/provider-adapters",
                "/modelgate/modelgate/models/provider-adapters", "/admin.html", "/actuator/health",
                "/modelgate/modelgate/v1/chat/completions"))
            client().get().uri(path).exchange().expectStatus().isNotFound();
    }
    @Test void modelRequestIsAuthenticatedAndBasePathDoesNotReachProvider() {
        client().post().uri("/modelgate/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"model\":\"alias\"}").exchange().expectStatus().isUnauthorized();
        client().post().uri(java.net.URI.create("http://127.0.0.1:" + port + "/modelgate/v1/chat/completions?trace=a%2Fb")).header("Authorization", "Bearer client-key")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"model\":\"alias\",\"messages\":[]}")
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.path").isEqualTo("/v1/chat/completions")
            .jsonPath("$.query").isEqualTo("trace=a%2Fb")
            .jsonPath("$.body.model").isEqualTo("real");
    }
    @Test void serviceRoutesUseLogicalPathAndPreserveStreamingFlush() {
        client().post().uri("/modelgate/svc/crm/events").exchange().expectStatus().isUnauthorized();
        var result = client().post().uri("/modelgate/svc/crm/events?x=1").header("Authorization", "Bearer client-key")
            .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(String.class);
        StepVerifier.create(result.getResponseBody().take(1))
            .expectNext("/events?x=1").verifyComplete(); // 上游不结束也能收到第一帧。
    }
    @Test void stripPrefixFalseStillNeverForwardsDeploymentRoot() {
        var result = client().post().uri("/modelgate/svc/full/events").header("Authorization", "Bearer client-key")
            .exchange().expectStatus().isOk().returnResult(String.class);
        StepVerifier.create(result.getResponseBody().take(1)).expectNext("/svc/full/events").verifyComplete();
    }
    @Test void routeMatchingBoundaryPathsCannotBypassCredentialAuth() {
        for (String path : List.of("/v1", "/v1;a=b/chat/completions", "/svc", "/svc;x=y/events"))
            client().post().uri("/modelgate" + path).exchange().expectStatus().isUnauthorized();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
    static class Config {
        @Bean ProviderAdapterRegistry registry(ObjectMapper mapper) { return new ProviderAdapterRegistry(List.of(new OpenAIAdapter(mapper)), mapper); }
        @Bean ProviderRequestPipeline pipeline(ProviderAdapterRegistry registry, ObjectMapper mapper) {
            return new ProviderRequestPipeline(registry, new RequestParamMerger(mapper), mapper);
        }
        @Bean ProviderAdapterController adapters(ProviderAdapterRegistry registry) { return new ProviderAdapterController(registry); }
        @Bean ContainerDetectionController detection() {
            return new ContainerDetectionController(new ServiceState(), mock(AuditService.class), mock(PayloadService.class));
        }
        @Bean CredentialAuthFilter credentialAuth(ObjectMapper mapper) {
            var credentials = mock(CredentialService.class); var crypto = mock(CryptoService.class); var apps = mock(AppService.class);
            var credential = new AppCredentialEntity(); credential.setAppId("app"); credential.setCredType("api_key");
            var app = new AppInfoEntity(); app.setAppId("app");
            when(crypto.sha256Hex("client-key")).thenReturn("hash");
            when(credentials.findBySecretHash("hash")).thenReturn(Optional.of(credential));
            when(apps.findByAppIdCached("app")).thenReturn(app);
            return new CredentialAuthFilter(credentials, apps, crypto, mapper);
        }
        @Bean BodyParseFilter bodyParse(ObjectMapper mapper) { return new BodyParseFilter(mapper); }
        @Bean ModelRouteFilter modelRoute(ObjectMapper mapper, ProviderRequestPipeline pipeline) {
            var service = mock(ModelService.class); var model = new ModelInstanceEntity();
            model.setModelId("alias"); model.setUpstreamModelId("real"); model.setModelType("chat"); model.setApiSpec("openai");
            model.setUpstreamUrl("http://provider.test");
            when(service.getModelCached("alias")).thenReturn(Optional.of(model));
            return new ModelRouteFilter(service, mapper, pipeline);
        }
        @Bean ServiceRegistryService services() {
            var services = mock(ServiceRegistryService.class);
            for (String id : List.of("crm", "full")) {
                var service = new ServiceEntity(); service.setServiceId(id); service.setUpstreamUrl("http://service.test");
                service.setStripPrefix(!id.equals("full"));
                when(services.getServiceCached(id)).thenReturn(Optional.of(service));
            }
            return services;
        }
        @Bean ServiceAuthFilter serviceAuth(ServiceRegistryService services, ObjectMapper mapper) {
            var auths = mock(ServiceAuthService.class);
            var crm = new AppServiceAuthEntity(); crm.setServiceId("crm");
            var full = new AppServiceAuthEntity(); full.setServiceId("full");
            when(auths.listByAppIdCached("app")).thenReturn(List.of(crm, full));
            return new ServiceAuthFilter(services, auths, mapper);
        }
        @Bean ServiceRouteFilter serviceRoute(ServiceRegistryService services, ObjectMapper mapper) { return new ServiceRouteFilter(services, mapper); }
        @Bean GlobalFilter mockUpstream(ObjectMapper mapper) {
            return new MockUpstream(mapper);
        }
    }
    private static final class MockUpstream implements GlobalFilter, Ordered {
        private final ObjectMapper mapper;
        private MockUpstream(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public int getOrder() { return -130; }
                @Override public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange,
                        org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
                    var uri = exchange.getRequest().getURI();
                    if (uri.getHost().equals("service.test")) {
                        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                        String value = uri.getPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                        var buffer = exchange.getResponse().bufferFactory().wrap(("data: " + value + "\n\n").getBytes(StandardCharsets.UTF_8));
                        return exchange.getResponse().writeAndFlushWith(Flux.concat(Flux.just(Mono.just(buffer)), Flux.never()));
                    }
                    return org.springframework.core.io.buffer.DataBufferUtils.join(exchange.getRequest().getBody()).flatMap(buffer -> {
                        try {
                            var json = mapper.createObjectNode().put("path", uri.getPath()).put("query", uri.getRawQuery());
                            json.set("body", mapper.readTree(buffer.toString(StandardCharsets.UTF_8)));
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(mapper.writeValueAsBytes(json))));
                        } catch (Exception e) { return Mono.error(e); }
                        finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer); }
                    });
                }
    }
}
