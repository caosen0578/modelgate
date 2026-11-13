package com.pab.ficc.ibp.modelgate.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.ibp.modelgate.server.adapter.OpenAIAdapter;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderAdapterRegistry;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderRequestPipeline;
import com.pab.ficc.ibp.modelgate.server.adapter.RequestParamMerger;
import com.pab.ficc.ibp.modelgate.server.common.AppSubject;
import com.pab.ficc.ibp.modelgate.server.common.ApplicationPaths;
import com.pab.ficc.ibp.modelgate.server.common.DefaultOperatorProvider;
import com.pab.ficc.ibp.modelgate.server.common.GlobalExceptionHandler;
import com.pab.ficc.ibp.modelgate.server.config.AuditFieldMetaObjectHandler;
import com.pab.ficc.ibp.modelgate.server.config.JacksonConfig;
import com.pab.ficc.ibp.modelgate.server.config.TdsqlMybatisPlusConfig;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.filter.*;
import com.pab.ficc.ibp.modelgate.server.service.*;
import com.pab.framework.redis.CacheProvider;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 HTTP 网关、MyBatis 与隔离库；上游只绑定 127.0.0.1，不读取行内数据源。 */
@SpringBootTest(classes = PersonalAccessIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/personal-access/sandbox.properties")
class PersonalAccessIntegrationTest {
    private static final String PERSONAL = "/modelgate/personal-access";
    private static final String CHAT = "/modelgate/v1/chat/completions";

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired DisposableServer upstream;
    @Autowired AtomicInteger upstreamCalls;
    @Autowired AuditService audit;
    @Autowired PayloadService payload;
    @Autowired CredentialAuthFilter credentialAuthFilter;
    @Autowired PersonalAccessService personalAccessService;

    @Test
    void applicationYamlExposesPersonalAccessSwitch() throws Exception {
        var config = new FileSystemResource("src/main/resources/application.yml");
        assertTrue(new YamlPropertySourceLoader().load("application", config).stream()
                .anyMatch(source -> source.getProperty("modelgate.personal-access.enabled") instanceof Boolean),
                "application.yml 必须显式提供布尔类型的个人接入开关");
    }

    @Test
    void recordsRealUsageByUmWithoutChangingSystemUsage() throws Exception {
        JsonNode seed;
        try (var input = new ClassPathResource("personal-access/scenario.json").getInputStream()) {
            seed = mapper.readTree(input);
        }
        String model = seed.path("modelId").asText();
        String ungranted = seed.path("ungrantedModelId").asText();
        String system = seed.path("system").path("appId").asText();
        String a = seed.path("personA").path("um").asText();
        String b = seed.path("personB").path("um").asText();
        var jdbc = new JdbcTemplate(dataSource);
        for (String id : new String[]{model, ungranted}) {
            jdbc.update("INSERT INTO mg_model (model_id, model_name, provider, upstream_url) VALUES (?, ?, ?, ?)",
                    id, "测试模型 " + id, "openai", "http://127.0.0.1:" + upstream.port());
        }

        client().post().uri("/modelgate/apps/create").bodyValue(seed.get("system"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.appId").isEqualTo(system);
        for (String person : new String[]{"personA", "personB"}) {
            client().post().uri(PERSONAL + "/create").bodyValue(seed.get(person)).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.data.um")
                    .isEqualTo(seed.path(person).path("um").asText()).jsonPath("$.data.appId").doesNotExist();
        }
        String aId = jdbc.queryForObject("SELECT app_id FROM mg_app WHERE person_um = ?", String.class, a);
        String bId = jdbc.queryForObject("SELECT app_id FROM mg_app WHERE person_um = ?", String.class, b);
        assertNotEquals(aId, bId);
        assertNotEquals(system, aId);
        assertTrue(aId.matches("P_[0-9a-f]{32}"));
        assertEquals(34, aId.length());
        try (var connection = dataSource.getConnection();
             var column = connection.getMetaData().getColumns(null, null, "mg_app", "app_id")) {
            assertTrue(column.next());
            assertEquals(64, column.getInt("COLUMN_SIZE"));
        }
        client().post().uri(PERSONAL + "/create")
                .bodyValue(Map.of("um", " " + a.toLowerCase(java.util.Locale.ROOT) + " ", "name", "重复账户"))
                .exchange().expectStatus().isBadRequest();
        ObjectNode reservedSystem = seed.path("system").deepCopy();
        client().post().uri("/modelgate/apps/create").bodyValue(reservedSystem.put("appId", "p_reserved"))
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/modelgate/apps/page").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.total").isEqualTo(1).jsonPath("$.data.items[0].appId").isEqualTo(system);
        client().get().uri(PERSONAL + "/page?keyword=MGQA_TEACHER").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.total").isEqualTo(2);

        client().put().uri(PERSONAL + "/auth/save")
                .bodyValue(Map.of("um", a, "modelId", model, "expiresAt", "2020-01-01 10:00:00"))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.resultCode").isEqualTo("10003")
                .jsonPath("$.resultMessage").value((String message) ->
                        assertTrue(message.contains("有效期必须晚于当前时间")));
        client().get().uri(PERSONAL + "/auth/list?um=" + a).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);

        for (String um : new String[]{a, b}) {
            client().put().uri(PERSONAL + "/auth/save").bodyValue(Map.of("um", um, "modelId", model, "qpsLimit", 0))
                    .exchange().expectStatus().isOk();
        }
        client().post().uri("/modelgate/auth/add").bodyValue(Map.of("appId", system, "modelIds", new String[]{model, ungranted}))
                .exchange().expectStatus().isOk();
        client().get().uri("/modelgate/auth/apps?modelId=" + model).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(1).jsonPath("$.data[0].appId").isEqualTo(system);

        JsonNode keyA = issue(a);
        JsonNode keyB = issue(b);
        String oldA = keyA.path("apiKey").asText();
        String bearerB = keyB.path("apiKey").asText();
        assertTrue(oldA.matches("mgp_[0-9a-f]{48}"));
        assertFalse(oldA.contains(aId));
        long keyAId = keyA.path("id").asLong();
        String encrypted = jdbc.queryForObject("SELECT secret_encrypted FROM mg_app_credential WHERE id = ?",
                String.class, keyAId);
        assertNotNull(encrypted);
        assertNotEquals(oldA, encrypted);
        String revealA = PERSONAL + "/credentials/secret?um=" + a + "&id=" + keyAId;
        client().get().uri(revealA).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.data").isEqualTo(oldA);
        client().get().uri(PERSONAL + "/credentials/secret?um=" + b + "&id=" + keyAId)
                .exchange().expectStatus().isBadRequest();
        var stockKey = json(client().post().uri("/modelgate/credentials/create")
                .bodyValue(Map.of("appId", system, "credName", "系统测试", "credType", "api_key"))
                .exchange().expectStatus().isOk());
        String sysBearer = stockKey.path("apiKey").asText();
        assertTrue(sysBearer.startsWith("mg-" + system + "-"));
        client().get().uri("/modelgate/credentials/secret?appId=" + system + "&credId=" + stockKey.path("id").asLong())
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.data").isEqualTo(sysBearer);

        call(oldA, model, false).expectStatus().isOk().expectHeader().doesNotExist("X-Mg-App")
                .expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20);
        String rotated = issue(a).path("apiKey").asText();
        client().delete().uri(PERSONAL + "/credentials/revoke?um=" + b + "&id=" + keyA.path("id").asLong())
                .exchange().expectStatus().isBadRequest();
        client().delete().uri(PERSONAL + "/credentials/revoke?um=" + a + "&id=" + keyA.path("id").asLong())
                .exchange().expectStatus().isOk();
        client().get().uri(revealA).exchange().expectStatus().isBadRequest();
        call(rotated, model, true).expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).value(content -> assertTrue(content.contains("[DONE]")));
        call(bearerB, model, false).expectStatus().isOk().expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20);
        call(sysBearer, model, false).expectStatus().isOk().expectHeader().valueEquals("X-Mg-App", system)
                .expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20);

        int reached = upstreamCalls.get();
        assertEquals(4, reached);
        call(oldA, model, false).expectStatus().isUnauthorized();
        call(rotated, ungranted, false).expectStatus().isForbidden();
        client().post().uri(CHAT).headers(h -> h.setBearerAuth(rotated))
                .bodyValue("{\"model\":\"" + ungranted + "\"}").exchange().expectStatus().isBadRequest();
        client().get().uri("/modelgate/svc/no-access").headers(h -> h.setBearerAuth(rotated))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/modelgate/credentials/secret?appId=" + aId + "&credId=" + keyA.path("id").asLong())
                .exchange().expectStatus().isForbidden();
        client().post().uri("/modelgate/auth/add").bodyValue(Map.of("appId", aId, "modelIds", new String[]{ungranted}))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/modelgate/apps/detail?appId=" + aId).exchange().expectStatus().isForbidden();
        client().patch().uri(PERSONAL + "/toggle?um=" + b + "&enabled=false").exchange().expectStatus().isOk();
        call(bearerB, model, false).expectStatus().isForbidden();
        ReflectionTestUtils.setField(credentialAuthFilter, "personalAccessEnabled", false);
        ReflectionTestUtils.setField(personalAccessService, "enabled", false);
        try {
            call(rotated, model, false).expectStatus().isForbidden();
            client().post().uri(PERSONAL + "/credentials/create").bodyValue(Map.of("um", a, "credName", "不应创建"))
                    .exchange().expectStatus().isForbidden();
        } finally {
            ReflectionTestUtils.setField(credentialAuthFilter, "personalAccessEnabled", true);
            ReflectionTestUtils.setField(personalAccessService, "enabled", true);
        }
        assertEquals(reached, upstreamCalls.get(), "拒绝的请求不得打到上游");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                audit.flush();
                Long tokens = jdbc.queryForObject("SELECT COALESCE(SUM(input_tokens + output_tokens),0) FROM mg_audit_log", Long.class);
                if (Long.valueOf(80).equals(tokens)) return;
                Thread.sleep(20);
            }
        });
        usage(a.toLowerCase(java.util.Locale.ROOT), a, 24, 16, 40);
        usage(b, b, 12, 8, 20);
        long keyBId = keyB.path("id").asLong();
        jdbc.update("UPDATE mg_app_credential SET secret_encrypted = NULL WHERE id = ?", keyBId);
        client().patch().uri(PERSONAL + "/toggle?um=" + b + "&enabled=true").exchange().expectStatus().isOk();
        client().get().uri(PERSONAL + "/credentials/secret?um=" + b + "&id=" + keyBId)
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.resultMessage").value((String message) ->
                        assertTrue(message.contains("仅存哈希无法找回")));
        client().get().uri(PERSONAL + "/credentials/list?um=" + b).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].secretRetrievable").isEqualTo(false);
        client().get().uri(PERSONAL + "/usage?um=UNKNOWN_UM").exchange().expectStatus().isBadRequest();
        client().get().uri(PERSONAL + "/usage?um=" + a + "&start=2020-01-01&end=" + LocalDate.now())
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/modelgate/stats/usage").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.totalInputTokens").isEqualTo(12).jsonPath("$.data.totalOutputTokens").isEqualTo(8);
        client().get().uri("/modelgate/stats/usage?appId=" + aId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.totalInputTokens").isEqualTo(0);
        client().get().uri("/modelgate/audit/query?appId=" + aId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.total").isEqualTo(0);
        client().get().uri(PERSONAL + "/credentials/list?um=" + a).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].secretRetrievable").isEqualTo(true)
                .jsonPath("$.data[0].secretEncrypted").doesNotExist()
                .jsonPath("$.data[0].apiKey").doesNotExist();
        assertTrue(mockingDetails(payload).getInvocations().stream()
                .noneMatch(invocation -> invocation.getMethod().getName().equals("submit")),
                "网关不得提交个人报文留档");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(8)).build();
    }

    private JsonNode issue(String um) throws Exception {
        return json(client().post().uri(PERSONAL + "/credentials/create")
                .bodyValue(Map.of("um", um, "credName", "验收 Key")).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    private JsonNode json(WebTestClient.ResponseSpec response) throws Exception {
        return mapper.readTree(response.expectBody().returnResult().getResponseBody()).path("data");
    }

    private WebTestClient.ResponseSpec call(String key, String model, boolean stream) {
        return client().post().uri(CHAT).headers(h -> h.setBearerAuth(key))
                .header("X-Mg-App", "00012001").header("X-UM", "FORGED_UM")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("model", model, "stream", stream,
                        "messages", new Object[]{Map.of("role", "user", "content", "隔离验收")}))
                .exchange();
    }

    private void usage(String inputUm, String canonicalUm, int input, int output, int total) {
        client().get().uri(PERSONAL + "/usage?um=" + inputUm).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.um").isEqualTo(canonicalUm)
                .jsonPath("$.data.totals.inputTokens").isEqualTo(input)
                .jsonPath("$.data.totals.outputTokens").isEqualTo(output)
                .jsonPath("$.data.totals.totalTokens").isEqualTo(total)
                .jsonPath("$.data.totals.upstreamTokens").isEqualTo(total)
                .jsonPath("$.data.totals.estimatedTokens").isEqualTo(0);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
    @Import({TdsqlMybatisPlusConfig.class, AuditFieldMetaObjectHandler.class, DefaultOperatorProvider.class,
            ApplicationPaths.class,
            JacksonConfig.class, CryptoService.class, GatewayCacheManager.class, RateLimitService.class,
            AppService.class, CredentialService.class, AccessRuleService.class, ModelAuthService.class,
            ServiceAuthService.class, ModelService.class, AppScopeResolver.class, PersonalAccessService.class,
            AuditService.class, RequestHeaderSnapshotter.class, UpstreamResponseParser.class,
            PersonalAccessController.class, AppController.class, CredentialController.class, ModelAuthController.class,
            ServiceAuthController.class, AccessRuleController.class, AuditLogController.class, UsageStatsController.class,
            GlobalExceptionHandler.class, TraceWebFilter.class, TraceFilter.class, CredentialAuthFilter.class,
            AppAccessControlFilter.class, BodyParseFilter.class, ModelAuthFilter.class, QuotaFilter.class,
            PayloadCaptureFilter.class, ModelRouteFilter.class,
            OpenAIAdapter.class, ProviderAdapterRegistry.class, ProviderRequestPipeline.class, RequestParamMerger.class})
    static class Config {
        @Bean("tdsqlDataSource")
        DataSource testDataSource() throws Exception {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:personal-" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
            // H2 验证真实 Mapper/CRUD，不声称兼容 TDSQL DDL。去掉 MySQL 字符集、表引擎和非标准外键。
            String schema = Files.readString(Path.of("sql/modelgate_schema.sql"))
                    .replaceAll("(?m)^\\s*--[^\\r\\n]*\\R", "")
                    .replaceAll("(?i)\\s+CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "")
                    .replaceAll("UNIQUE KEY \\w+ \\(", "UNIQUE (")
                    .replaceAll(",\\s*KEY \\w+\\s*\\([^)]+\\)", "")
                    .replaceAll(",\\s*CONSTRAINT \\w+\\s+FOREIGN KEY \\([^)]+\\)\\s+REFERENCES\\s+\\w+\\s*\\([^)]+\\)(?:\\s+ON UPDATE CASCADE)?", "");
            new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
            return ds;
        }

        @Bean CacheProvider isolatedRedis() { return mock(CacheProvider.class); }
        @Bean PayloadService ignoredPayload() { return mock(PayloadService.class); }
        @Bean ServiceRegistryService noServiceUpstream() { return mock(ServiceRegistryService.class); }
        @Bean com.pab.ficc.ibp.modelgate.server.admin.IpMatcher ipMatcher() {
            return new com.pab.ficc.ibp.modelgate.server.admin.IpMatcher();
        }
        @Bean AtomicInteger upstreamCalls() { return new AtomicInteger(); }

        @Bean(destroyMethod = "disposeNow")
        DisposableServer simulatedModel(AtomicInteger upstreamCalls) {
            return HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                    req.receive().aggregate().asString().flatMap(body -> {
                        upstreamCalls.incrementAndGet();
                        if (body.replace(" ", "").contains("\"stream\":true")) {
                            return res.sse().sendString(Flux.just(
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n",
                                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}\n\n",
                                    "data: [DONE]\n\n")).then();
                        }
                        return res.header("Content-Type", "application/json")
                                .sendString(Flux.just("""
                                        {"choices":[{"message":{"content":"ok"}}],
                                         "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
                                        """)).then();
                    })).bindNow();
        }
    }
}
