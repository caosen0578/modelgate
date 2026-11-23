package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.admin.PersonalSourcePolicy;

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
import java.time.YearMonth;
import java.util.List;
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
    private static final String CODEBUDDY_UUID = "11112222-3333-4444-5555-666677778888";

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired DisposableServer upstream;
    @Autowired AtomicInteger upstreamCalls;
    @Autowired AuditService audit;
    @Autowired PayloadService payload;
    @Autowired CredentialAuthFilter credentialAuthFilter;
    @Autowired PersonalAccessService personalAccessService;
    @Autowired CacheProvider cacheProvider;

    @Test
    void applicationYamlExposesPersonalAccessSwitch() throws Exception {
        var config = new FileSystemResource("src/main/resources/application.yml");
        assertTrue(new YamlPropertySourceLoader().load("application", config).stream()
                .anyMatch(source -> source.getProperty("modelgate.personal-access.enabled") instanceof Boolean),
                "application.yml 必须显式提供布尔类型的个人接入开关");
    }

    @Test
    void modelPublicBaseUrlReadsOnlyItsOwnConfigAndRejectsInvalidChanges() {
        String readPath = "/modelgate/config/model-public-base-url";
        String savePath = "/modelgate/config/save";
        String key = "gateway.public.base-url";
        client().get().uri(readPath).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.data.baseUrl").doesNotExist();

        String externalBase = "https://models.example.test:8443/llm/modelgate";
        client().put().uri(savePath).bodyValue(Map.of("key", key, "value", "  " + externalBase + "///  "))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.configValue").isEqualTo(externalBase);
        for (String invalid : List.of("/modelgate", "ftp://models.example.test", "https://user@models.example.test",
                "https://models.example.test:65536", externalBase + "?key=1", externalBase + "#section",
                externalBase + "/v1/chat/completions", externalBase + "/%2e%2e/modelgate")) {
            client().put().uri(savePath).bodyValue(Map.of("key", key, "value", invalid))
                    .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.resultCode").isEqualTo("10003");
        }
        client().get().uri(readPath).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.baseUrl").isEqualTo(externalBase)
                .jsonPath("$.data.configKey").doesNotExist()
                .jsonPath("$.data.updater").doesNotExist();
        client().delete().uri("/modelgate/config/delete?key=" + key).exchange().expectStatus().isOk();
        client().get().uri(readPath).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.baseUrl").doesNotExist();
    }

    @Test
    void importsProfilesSeparatelyAndAddsOnlyMissingModelGrants() {
        String model = "mgqa-batch-model";
        String first = "MGQA_BATCH_A";
        String second = "MGQA_BATCH_B";
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO mg_model (model_id, model_name, provider, upstream_url) VALUES (?, ?, ?, ?)",
                model, "批量验收模型", "openai", "http://127.0.0.1:" + upstream.port());
        var users = List.of(Map.of("um", first, "name", "测试甲"), Map.of("um", second, "name", "测试乙"),
                Map.of("um", first.toLowerCase(java.util.Locale.ROOT), "name", "重复甲"));
        client().post().uri(PERSONAL + "/import").bodyValue(Map.of("users", users)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.added").isEqualTo(2).jsonPath("$.data.skipped").isEqualTo(1);
        client().get().uri(PERSONAL + "/credentials/list?um=" + first).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);
        client().get().uri(PERSONAL + "/auth/list?um=" + first).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);
        client().put().uri(PERSONAL + "/auth/save").bodyValue(Map.of("um", first, "modelId", model, "qpsLimit", 9))
                .exchange().expectStatus().isOk();
        client().patch().uri(PERSONAL + "/auth/toggle?um=" + first + "&modelId=" + model + "&enabled=false")
                .exchange().expectStatus().isOk();
        var grant = Map.of("ums", List.of(first, second), "modelIds", List.of(model), "qpsLimit", 3);
        client().post().uri(PERSONAL + "/auth/batch").bodyValue(grant).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.added").isEqualTo(1).jsonPath("$.data.skipped").isEqualTo(1);
        client().get().uri(PERSONAL + "/auth/list?um=" + first).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].qpsLimit").isEqualTo(9).jsonPath("$.data[0].enabled").isEqualTo(false);
        client().get().uri(PERSONAL + "/auth/list?um=" + second).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].qpsLimit").isEqualTo(3);
        client().post().uri(PERSONAL + "/auth/batch").bodyValue(grant).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.added").isEqualTo(0).jsonPath("$.data.skipped").isEqualTo(2);
        // 其余验收场景复用同一个隔离库，恢复该场景录入的资料。
        var appIds = jdbc.queryForList("SELECT app_id FROM mg_app WHERE person_um IN (?, ?)", String.class, first, second);
        for (String id : appIds) jdbc.update("DELETE FROM mg_app_model_auth WHERE app_id = ?", id);
        jdbc.update("DELETE FROM mg_app WHERE person_um IN (?, ?)", first, second);
        jdbc.update("DELETE FROM mg_model WHERE model_id = ?", model);
    }

    @Test
    void sortsUsageBeforePaginationAndSummaryExport() {
        String first = "MGQA_USAGE_SORT_A";
        String second = "MGQA_USAGE_SORT_B";
        String larger = "mgqa-sort-larger";
        String smaller = "mgqa-sort-smaller";
        String trace = UUID.randomUUID().toString();
        var jdbc = new JdbcTemplate(dataSource);
        try {
            for (String um : List.of(first, second)) {
                jdbc.update("""
                        INSERT INTO mg_app (app_id, subject_type, person_um, system_name, app_name, sys_code, app_code, owner)
                        VALUES (?, 'PERSONAL', ?, 'QA', 'QA', 'QA', ?, 'QA')
                        """, "P_" + um, um, um);
            }
            jdbc.update("""
                    INSERT INTO mg_audit_log (trace_id, app_id, model_id, input_tokens, token_source)
                    VALUES (?, ?, ?, 28, 'usage'), (?, ?, ?, 14, 'usage'), (?, ?, ?, 7, 'usage')
                    """, trace, "P_" + first, larger, trace, "P_" + second, larger, trace, "P_" + second, smaller);

            String models = "/modelgate/stats/model-usage?modelIds=" + larger + "&modelIds=" + smaller
                    + "&sortBy=totalTokens&pageSize=1";
            client().get().uri(models + "&sortOrder=ascending")
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.data.items[0].modelId").isEqualTo(smaller)
                    .jsonPath("$.data.totals.totalTokens").isEqualTo(49);
            client().get().uri(models + "&sortOrder=descending")
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[0].modelId").isEqualTo(larger);
            client().get().uri(models + "&sortOrder=ascending&pageIndex=2")
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[0].modelId").isEqualTo(larger);
            client().get().uri(models + "&sortOrder=ascending&format=csv")
                    .exchange().expectStatus().isOk().expectBody(String.class).value(csv ->
                            assertTrue(csv.lines().skip(1).findFirst().orElseThrow().startsWith("\"" + smaller + "\",")));
            client().get().uri(models + "&sortOrder=invalid").exchange().expectStatus().isBadRequest();

            String users = "/modelgate/stats/user-usage?modelId=" + larger + "&sortBy=totalTokens&pageSize=1";
            client().get().uri(users + "&sortOrder=ascending")
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[0].um").isEqualTo(second);
            client().get().uri(users + "&sortOrder=descending")
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[0].um").isEqualTo(first);
        } finally {
            jdbc.update("DELETE FROM mg_audit_log WHERE trace_id = ?", trace);
            jdbc.update("DELETE FROM mg_app WHERE person_um IN (?, ?)", first, second);
        }
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
        String deleteKeyA = PERSONAL + "/credentials/delete?um=" + a + "&id=" + keyAId;
        client().delete().uri(deleteKeyA).exchange().expectStatus().isBadRequest();
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
        client().get().uri(PERSONAL + "/page?keyword=" + a).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].authCount").isEqualTo(1)
                .jsonPath("$.data.items[0].credCount").isEqualTo(2)
                .jsonPath("$.data.items[0].appId").doesNotExist();
        client().delete().uri(PERSONAL + "/credentials/revoke?um=" + b + "&id=" + keyA.path("id").asLong())
                .exchange().expectStatus().isBadRequest();
        client().delete().uri(PERSONAL + "/credentials/revoke?um=" + a + "&id=" + keyA.path("id").asLong())
                .exchange().expectStatus().isOk();
        client().get().uri(revealA).exchange().expectStatus().isBadRequest();
        client().delete().uri(PERSONAL + "/credentials/delete?um=" + b + "&id=" + keyAId)
                .exchange().expectStatus().isBadRequest();
        client().delete().uri(deleteKeyA).exchange().expectStatus().isOk();
        client().get().uri(PERSONAL + "/page?keyword=" + a).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].credCount").isEqualTo(1);
        client().post().uri(PERSONAL + "/access/add")
                .bodyValue(Map.of("um", a, "ruleType", "cidr", "ruleValue", "127.0.0.1/33"))
                .exchange().expectStatus().isBadRequest();
        var localRule = json(client().post().uri(PERSONAL + "/access/add")
                .bodyValue(Map.of("um", a, "ruleType", "cidr", "ruleValue", "127.0.0.0/24"))
                .exchange().expectStatus().isOk());
        long localRuleId = localRule.path("id").asLong();
        client().get().uri(PERSONAL + "/access/list?um=" + a).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].ruleValue").isEqualTo("127.0.0.0/24")
                .jsonPath("$.data[0].appId").doesNotExist();
        client().delete().uri(PERSONAL + "/access/delete?um=" + b + "&id=" + localRuleId)
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.resultCode").isEqualTo("10004");
        client().get().uri("/modelgate/access/list?appId=" + aId).exchange().expectStatus().isForbidden();

        client().post().uri(PERSONAL + "/access/add")
                .bodyValue(Map.of("um", a, "ruleType", "domain", "ruleValue", "https://staff.internal.com/path"))
                .exchange().expectStatus().isBadRequest();
        var domainRule = json(client().post().uri(PERSONAL + "/access/add")
                .bodyValue(Map.of("um", a, "ruleType", "domain_wildcard", "ruleValue", "*.Internal.COM"))
                .exchange().expectStatus().isOk());
        assertEquals("*.internal.com", domainRule.path("ruleValue").asText());
        call(rotated, model, false).expectStatus().isForbidden();
        call(rotated, model, false, "https://elsewhere.example").expectStatus().isForbidden();
        call(rotated, model, true, "https://staff.internal.com").expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).value(content -> assertTrue(content.contains("[DONE]")));
        call(bearerB, model, false).expectStatus().isOk().expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20);
        call(sysBearer, model, false).expectStatus().isOk().expectHeader().valueEquals("X-Mg-App", system)
                .expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20);

        int reached = upstreamCalls.get();
        assertEquals(4, reached);
        client().post().uri(PERSONAL + "/access/add")
                .bodyValue(Map.of("um", a, "ruleType", "ip", "ruleValue", "198.51.100.10"))
                .exchange().expectStatus().isOk();
        client().delete().uri(PERSONAL + "/access/delete?um=" + a + "&id=" + localRuleId)
                .exchange().expectStatus().isOk();
        call(rotated, model, false).expectStatus().isForbidden();
        client().post().uri(CHAT).headers(h -> h.setBearerAuth(rotated))
                .header("Origin", "https://staff.internal.com")
                .header("X-Forwarded-For", "198.51.100.10")
                .header("X-Real-IP", "198.51.100.10")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("model", model, "stream", true))
                .exchange().expectStatus().isForbidden();
        var remainingRules = json(client().get().uri(PERSONAL + "/access/list?um=" + a)
                .exchange().expectStatus().isOk());
        for (var rule : remainingRules) {
            if ("ip".equals(rule.path("ruleType").asText())) {
                client().delete().uri(PERSONAL + "/access/delete?um=" + a + "&id=" + rule.path("id").asLong())
                        .exchange().expectStatus().isOk();
            }
        }
        call(rotated, model, false, "https://staff.internal.com").expectStatus().isForbidden();
        client().delete().uri(PERSONAL + "/access/delete?um=" + a + "&id=" + domainRule.path("id").asLong())
                .exchange().expectStatus().isOk();
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
        payload.flush();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mg_request_payload WHERE app_id = ?", Integer.class, aId),
                "未开启的个人授权不落报文");

        var systemMonthlyKey = "quota:monthly:" + system + ":" + model + ":" + YearMonth.now();
        var personalMonthlyKey = "quota:monthly:" + aId + ":" + model + ":" + YearMonth.now();
        when(cacheProvider.get(systemMonthlyKey)).thenReturn("100");
        when(cacheProvider.get(personalMonthlyKey)).thenReturn("100");
        for (int monthlyLimit : new int[]{100, 200}) {
            client().put().uri("/modelgate/auth/update").bodyValue(Map.of("appId", system, "modelId", model,
                            "qpsLimit", 0, "dailyLimit", 0, "monthlyTokens", monthlyLimit))
                    .exchange().expectStatus().isOk();
            client().put().uri(PERSONAL + "/auth/save").bodyValue(Map.of("um", a, "modelId", model,
                            "qpsLimit", 0, "dailyLimit", 0, "monthlyTokens", monthlyLimit))
                    .exchange().expectStatus().isOk();
            for (String bearer : List.of(sysBearer, rotated)) {
                var response = call(bearer, model, false);
                if (monthlyLimit == 100) {
                    response.expectStatus().isEqualTo(429).expectBody()
                            .jsonPath("$.message").isEqualTo("Monthly token quota exceeded for this app");
                } else {
                    response.expectStatus().isOk().expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20);
                }
            }
        }
        verify(cacheProvider, never()).del(systemMonthlyKey);
        verify(cacheProvider, never()).del(personalMonthlyKey);

        var grant = new java.util.HashMap<>(Map.<String, Object>of("um", a, "modelId", model, "qpsLimit", 0,
                "dailyLimit", 0, "monthlyTokens", 200));
        grant.put("payloadLogEnabled", true);
        client().put().uri(PERSONAL + "/auth/save").bodyValue(grant).exchange().expectStatus().isOk();
        grant.remove("payloadLogEnabled");
        client().put().uri(PERSONAL + "/auth/save").bodyValue(grant).exchange().expectStatus().isOk();
        client().get().uri(PERSONAL + "/auth/list?um=" + a).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].payloadLogEnabled").isEqualTo(true);

        String trace = "8888777766665555";
        client().post().uri(CHAT + "?sensitive=skip-me").headers(h -> h.setBearerAuth(rotated))
                .header("X-Trace-Id", trace).header("Cookie", "ignore-this-cookie")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", model, "stream", true, "messages", List.of(Map.of("role", "user", "content", "录制测试"))))
                .exchange().expectStatus().isOk().expectBody(String.class).value(body -> assertTrue(body.contains("[DONE]")));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (jdbc.queryForObject("SELECT COUNT(*) FROM mg_request_payload WHERE trace_id = ?", Integer.class, trace) == 0) {
                payload.flush();
                Thread.sleep(50);
            }
        });
        audit.flush();
        long payloadId = jdbc.queryForObject("SELECT id FROM mg_request_payload WHERE trace_id = ?", Long.class, trace);
        client().get().uri("/modelgate/payload/personal/query?um=" + a + "&traceId=" + trace)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].um").isEqualTo(a).jsonPath("$.data.items[0].appId").doesNotExist();
        client().get().uri("/modelgate/payload/personal/query?pageIndex=1&pageSize=1")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].traceId").isEqualTo(trace)
                .jsonPath("$.data.items[0].um").isEqualTo(a)
                .jsonPath("$.data.items[0].personName").isEqualTo(seed.path("personA").path("name").asText())
                .jsonPath("$.data.items[0].appId").doesNotExist();
        client().get().uri("/modelgate/payload/personal/query?requestKeyword=录制测试")
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/modelgate/payload/personal/detail?um=" + a + "&id=" + payloadId)
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store").expectBody()
                .jsonPath("$.data.requestPath").isEqualTo(CHAT)
                .jsonPath("$.data.requestHeaders").value((String value) -> {
                    assertFalse(value.toLowerCase(java.util.Locale.ROOT).contains("authorization"));
                    assertFalse(value.contains("ignore-this-cookie"));
                }).jsonPath("$.data.responseRaw").value((String value) -> assertTrue(value.contains("[DONE]")));
        client().get().uri("/modelgate/payload/personal/detail?um=" + b + "&id=" + payloadId)
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/modelgate/payload/query?traceId=" + trace).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(0);
        client().get().uri("/modelgate/payload/detail?id=" + payloadId).exchange().expectStatus().isBadRequest();
        client().post().uri("/modelgate/payload/replay?id=" + payloadId).exchange().expectStatus().isBadRequest();

        String range = "&startTime=" + LocalDate.now().minusDays(1) + "T00:00:00&endTime="
                + LocalDate.now().plusDays(1) + "T00:00:00";
        for (String path : List.of("/modelgate/audit/personal/users", "/modelgate/payload/personal/users")) {
            client().get().uri(path + "?keyword=teacher_a")
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.data.length()").isEqualTo(1)
                    .jsonPath("$.data[0].um").isEqualTo(a)
                    .jsonPath("$.data[0].name").isEqualTo(seed.path("personA").path("name").asText())
                    .jsonPath("$.data[0].appId").doesNotExist();
            client().get().uri(path + "?keyword=%25").exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.data.length()").isEqualTo(0);
        }
        client().get().uri("/modelgate/audit/personal/query?um=" + a + "&traceId=" + trace + range)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[0].um").isEqualTo(a);
        client().get().uri("/modelgate/stats/personal/usage?um=" + a + "&traceId=" + trace + range)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.totalInputTokens").isEqualTo(12).jsonPath("$.data.totalOutputTokens").isEqualTo(8);
        client().get().uri("/modelgate/stats/personal/usage?um=" + a + "&endTime=2001-01-02T00:00:00&startTime=2001-01-01T00:00:00")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.totalRequests").isEqualTo(0);
        client().get().uri("/modelgate/stats/usage?appId=" + system + "&endTime=2001-01-02T00:00:00&startTime=2001-01-01T00:00:00")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.totalRequests").isEqualTo(0);

        String personalList = "/modelgate/audit/personal/query?pageSize=200&modelId=" + model + range;
        JsonNode all = json(client().get().uri(personalList).exchange().expectStatus().isOk());
        long total = 0;
        for (String um : List.of(a, b)) {
            JsonNode byUm = json(client().get().uri(personalList + "&um=" + um).exchange().expectStatus().isOk());
            assertTrue(byUm.path("total").asLong() > 0);
            total += byUm.path("total").asLong();
        }
        assertEquals(total, all.path("total").asLong());
        for (JsonNode item : all.path("items")) {
            assertTrue(List.of(a, b).contains(item.path("um").asText()));
            assertTrue(item.path("appId").isMissingNode() || item.path("appId").isNull());
        }
        client().get().uri("/modelgate/stats/personal/usage?modelId=" + model + range)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.totalRequests").isEqualTo(total);
        client().get().uri(personalList + "&um=UNKNOWN_UM")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.total").isEqualTo(0);
        client().get().uri("/modelgate/payload/personal/query?um=UNKNOWN_UM")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.total").isEqualTo(0);

        List<String> selectableModels = List.of("mgqa-filter-a", "mgqa-filter-b", "mgqa-filter-excluded");
        for (String appId : List.of(system, aId)) {
            for (String modelId : selectableModels) {
                String fixtureTrace = "mgqa-filter-" + UUID.randomUUID();
                jdbc.update("INSERT INTO mg_audit_log (trace_id, app_id, model_id, input_tokens) VALUES (?, ?, ?, ?)",
                        fixtureTrace, appId, modelId, 7);
                jdbc.update("INSERT INTO mg_request_payload (trace_id, app_id, model_id) VALUES (?, ?, ?)",
                        fixtureTrace, appId, modelId);
            }
        }
        String modelFilter = "?modelIds=mgqa-filter-a&modelIds=mgqa-filter-b";
        for (String path : List.of("/modelgate/audit/query", "/modelgate/audit/personal/query",
                "/modelgate/payload/query", "/modelgate/payload/personal/query")) {
            JsonNode filtered = json(client().get().uri(path + modelFilter).exchange().expectStatus().isOk());
            assertEquals(2, filtered.path("total").asInt());
            assertEquals("mgqa-filter-b", filtered.path("items").get(0).path("modelId").asText());
            assertEquals("mgqa-filter-a", filtered.path("items").get(1).path("modelId").asText());
        }
        for (String path : List.of("/modelgate/stats/usage", "/modelgate/stats/personal/usage")) {
            client().get().uri(path + modelFilter).exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.data.totalRequests").isEqualTo(2).jsonPath("$.data.totalInputTokens").isEqualTo(14);
        }
        client().get().uri("/modelgate/audit/query?modelId=mgqa-filter-a")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.total").isEqualTo(1);

        jdbc.update("INSERT INTO mg_audit_log (trace_id, app_id, model_id, input_tokens, token_source, codebuddy_user_id)"
                        + " VALUES (?, '95731', 'mgqa-filter-a', 11, 'usage', ?)", trace, CODEBUDDY_UUID);
        jdbc.update("INSERT INTO mg_audit_log (trace_id, app_id, model_id, output_tokens, token_source)"
                        + " VALUES (?, '95731', 'mgqa-filter-a', 3, 'estimated')", trace);
        client().get().uri("/modelgate/stats/model-usage?modelId=mgqa-filter-a&pageSize=1")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.totals.requests").isEqualTo(4)
                .jsonPath("$.data.totals.totalTokens").isEqualTo(28)
                .jsonPath("$.data.totals.upstreamTokens").isEqualTo(11)
                .jsonPath("$.data.totals.estimatedTokens").isEqualTo(3);
        client().get().uri("/modelgate/stats/user-usage?modelId=mgqa-filter-a&pageSize=1")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store").expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].um").isEqualTo(a)
                .jsonPath("$.data.items[0].usage.totalTokens").isEqualTo(18)
                .jsonPath("$.data.items[0].models.length()").isEqualTo(2)
                .jsonPath("$.data.totals.totalTokens").isEqualTo(18)
                .jsonPath("$.data.unassignedRequests").isEqualTo(1)
                .jsonPath("$.data.unassignedTokens").isEqualTo(3)
                .jsonPath("$.data.unassignedReasons[0].reason").isEqualTo("MISSING_UUID");
        client().get().uri("/modelgate/stats/user-usage?modelId=mgqa-filter-a&source=codebuddy&um=" + a)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(1).jsonPath("$.data.totals.totalTokens").isEqualTo(11);

        client().get().uri("/modelgate/stats/model-usage" + modelFilter + "&format=csv&pageSize=1")
                .exchange().expectStatus().isOk().expectHeader().contentType("text/csv;charset=UTF-8")
                .expectHeader().valueMatches("Content-Disposition", "attachment;.*model-usage.csv.*")
                .expectBody(String.class).value(csv -> {
                    assertTrue(csv.startsWith("\uFEFF模型 ID,请求数"));
                    assertTrue(csv.contains("\"mgqa-filter-a\",4,25,3,28,11,3,2"));
                    assertTrue(csv.contains("\"mgqa-filter-b\",2,14,0,14,0,0,2"));
                    assertFalse(csv.contains("mgqa-filter-excluded"));
                });
        client().get().uri("/modelgate/stats/user-usage?modelId=mgqa-filter-a&format=csv&pageSize=1")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(String.class).value(csv -> {
                    assertTrue(csv.contains("\"" + a + "\",2,18,0,18,11,0,1"));
                    assertFalse(csv.contains("未归属"));
                });
        client().get().uri("/modelgate/stats/user-usage?modelId=mgqa-filter-a&format=csv&detail=true&source=codebuddy&um=" + a)
                .exchange().expectStatus().isOk().expectBody(String.class).value(csv -> {
                    assertTrue(csv.startsWith("\uFEFFUM,模型 ID,来源,请求数"));
                    assertTrue(csv.contains("\"" + a + "\",\"mgqa-filter-a\",CodeBuddy,1,11,0,11,11,0,0"));
                    assertFalse(csv.contains("未归属"));
                    assertFalse(csv.contains("个人 Key"));
                });

        String exportModel = "mgqa-export";
        for (String appId : List.of(system, aId)) {
            for (int i = 0; i < 205; i++) {
                String exportTrace = "export-" + appId + "-" + i;
                jdbc.update("INSERT INTO mg_audit_log (trace_id, app_id, model_id, http_status) VALUES (?, ?, ?, 200)",
                        exportTrace, appId, exportModel);
                jdbc.update("INSERT INTO mg_request_payload (trace_id, app_id, model_id, request_body) VALUES (?, ?, ?, ?)",
                        exportTrace, appId, exportModel, "export fixture");
            }
        }
        for (String path : List.of("/modelgate/audit/query", "/modelgate/audit/personal/query",
                "/modelgate/payload/query", "/modelgate/payload/personal/query")) {
            boolean personal = path.contains("/personal/");
            String expectedAppId = personal ? aId : system;
            String excludedAppId = personal ? system : aId;
            client().get().uri(path + "?format=csv&modelIds=" + exportModel + "&pageSize=1&pageIndex=200")
                    .exchange().expectStatus().isOk()
                    .expectHeader().contentType("text/csv;charset=UTF-8")
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectBody(String.class).value(csv -> {
                        assertTrue(csv.startsWith("\uFEFF时间,TraceID,"));
                        assertEquals(205, csv.split("export-" + expectedAppId + "-", -1).length - 1);
                        assertFalse(csv.contains("export-" + excludedAppId + "-"));
                        if (personal) {
                            assertTrue(csv.contains(a));
                            assertFalse(csv.contains("\"" + aId + "\""));
                        }
                    });
        }
        client().get().uri("/modelgate/payload/personal/query?format=csv&requestKeyword=fixture")
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/modelgate/audit/personal/query?format=csv&um=UNKNOWN_UM")
                .exchange().expectStatus().isOk().expectBody(String.class).value(csv -> assertEquals(2, csv.split("\r\n", -1).length));
    }

    @Test
    void capturesCodeBuddyIdentityFromGatewayRequestInAuditAndPayload() throws Exception {
        String app = "95731";
        String model = "mgqa-codebuddy-header";
        String uuid = UUID.randomUUID().toString();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO mg_model (model_id, model_name, provider, upstream_url) VALUES (?, ?, 'openai', ?)",
                model, "CodeBuddy 归属测试", "http://127.0.0.1:" + upstream.port());
        jdbc.update("INSERT INTO mg_app (app_id, system_name, app_name, sys_code, app_code, owner) VALUES (?, ?, ?, ?, ?, ?)",
                app, "MGQA", "CodeBuddy 测试", "MGQA", model, "test");
        try {
            jdbc.update("INSERT INTO mg_app_model_auth (app_id, model_id, payload_log_enabled) VALUES (?, ?, 1)", app, model);
            String key = json(client().post().uri("/modelgate/credentials/create")
                    .bodyValue(Map.of("appId", app, "credName", "归属测试", "credType", "api_key"))
                    .exchange().expectStatus().isOk()).path("apiKey").asText();
            var response = client().post().uri(CHAT).headers(headers -> headers.setBearerAuth(key))
                    .header("X-Codebuddy-User-Id", uuid.toUpperCase(java.util.Locale.ROOT))
                    .bodyValue(Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", "归属测试"))))
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.usage.total_tokens").isEqualTo(20).returnResult();
            String trace = response.getResponseHeaders().getFirst("X-Trace-Id");
            assertNotNull(trace);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (jdbc.queryForObject("SELECT COUNT(*) FROM mg_request_payload WHERE trace_id=?", Integer.class, trace) == 0
                        || jdbc.queryForObject("SELECT COUNT(*) FROM mg_audit_log WHERE trace_id=?", Integer.class, trace) == 0) {
                    audit.flush();
                    payload.flush();
                    Thread.sleep(50);
                }
            });
            assertEquals(uuid, jdbc.queryForObject("SELECT codebuddy_user_id FROM mg_audit_log WHERE trace_id=? AND app_id=?",
                    String.class, trace, app));
            String rawHeaders = jdbc.queryForObject("SELECT request_headers FROM mg_request_payload WHERE trace_id=?", String.class, trace);
            var headerFields = mapper.readTree(rawHeaders).fields();
            String recorded = null;
            while (headerFields.hasNext()) {
                var field = headerFields.next();
                if (field.getKey().equalsIgnoreCase("X-Codebuddy-User-Id")) recorded = field.getValue().asText();
            }
            assertEquals(uuid.toUpperCase(java.util.Locale.ROOT), recorded);
        } finally {
            jdbc.update("DELETE FROM mg_request_payload WHERE model_id=?", model);
            jdbc.update("DELETE FROM mg_audit_log WHERE model_id=?", model);
            jdbc.update("DELETE FROM mg_app_model_auth WHERE app_id=? AND model_id=?", app, model);
            jdbc.update("DELETE FROM mg_app_credential WHERE app_id=?", app);
            jdbc.update("DELETE FROM mg_app WHERE app_id=?", app);
            jdbc.update("DELETE FROM mg_model WHERE model_id=?", model);
        }
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
        return call(key, model, stream, null);
    }

    private WebTestClient.ResponseSpec call(String key, String model, boolean stream, String origin) {
        return client().post().uri(CHAT).headers(h -> {
                    h.setBearerAuth(key);
                    if (origin != null) h.setOrigin(origin);
                })
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
            ApplicationPaths.class, PersonalSourcePolicy.class,
            JacksonConfig.class, CryptoService.class, GatewayCacheManager.class, RateLimitService.class,
            AppService.class, CredentialService.class, AccessRuleService.class, ModelAuthService.class,
            ServiceAuthService.class, ModelService.class, AppScopeResolver.class, PersonalAccessService.class,
            AuditService.class, RequestHeaderSnapshotter.class, UpstreamResponseParser.class, GatewayConfigService.class,
            PersonalAccessController.class, AppController.class, CredentialController.class, ModelAuthController.class,
            ServiceAuthController.class, AccessRuleController.class, AuditLogController.class, UsageStatsController.class,
            GatewayConfigController.class, AuditQueryService.class, PayloadController.class, PayloadService.class,
            UsageStatsService.class,
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
        @Bean ModelConnectivityService mockModelConnectivity() { return mock(ModelConnectivityService.class); }
        @Bean ServiceConnectivityService mockServiceConnectivity() { return mock(ServiceConnectivityService.class); }
        @Bean ServiceRegistryService noServiceUpstream() { return mock(ServiceRegistryService.class); }
        @Bean com.pab.ficc.ibp.modelgate.server.admin.IpMatcher ipMatcher() {
            return new com.pab.ficc.ibp.modelgate.server.admin.IpMatcher();
        }
        @Bean AtomicInteger upstreamCalls() { return new AtomicInteger(); }

        @Bean CodeBuddyUsageIdentity codeBuddyIdentity(ObjectMapper mapper, DisposableServer server) {
            return new CodeBuddyUsageIdentity(mapper, "95731", "http://127.0.0.1:" + server.port() + "/identity", "fixture");
        }

        @Bean(destroyMethod = "disposeNow")
        DisposableServer simulatedModel(AtomicInteger upstreamCalls) {
            return HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                    req.receive().aggregate().asString().flatMap(body -> {
                        if (req.uri().equals("/identity")) {
                            if (!"Bearer fixture".equals(req.requestHeaders().get("Authorization"))
                                    || !body.contains(CODEBUDDY_UUID)) return res.status(401).send().then();
                            return res.header("Content-Type", "application/json").sendString(Flux.just(
                                    "{\"resultCode\":\"00000000\",\"data\":{\"" + CODEBUDDY_UUID + "\":\""
                                            + "MGQA_TEACHER_A" + "\"}}")).then();
                        }
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
