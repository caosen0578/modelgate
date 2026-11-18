package com.pab.ficc.ibp.modelgate.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadDetailVO;
import com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationRunner;
import com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationService;
import com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationStore;
import com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PersonalAccessIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/personal-access/sandbox.properties")
@Import({EvaluationController.class, EvaluationStore.class, EvaluationService.class, EvaluationRunner.class})
class EvaluationIntegrationTest {
    private static final String BASE = "/modelgate/evaluation";
    @LocalServerPort int port;
    @Autowired DataSource source;
    @Autowired DisposableServer upstream;
    @Autowired ObjectMapper mapper;
    @Autowired EvaluationStore store;
    @Autowired EvaluationRunner runner;
    @Autowired ModelService models;

    @Test
    @SuppressWarnings("unchecked")
    void snapshotsSurviveCleanupAndTaskAttemptsAreDurable() throws Exception {
        var jdbc = new JdbcTemplate(source);
        String model = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO mg_model(model_id,model_name,provider,upstream_url) VALUES(?,?,?,?)",
                model, "评测测试模型", "openai", "http://127.0.0.1:" + upstream.port());
        String body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"fixture\"}],\"stream\":false}";
        String trace = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) jdbc.update("INSERT INTO mg_request_payload(trace_id,app_id,model_id,request_body,response_raw,response_text,request_headers)"
                + " VALUES(?,'eval_app',?,?,?,?,'{\"Authorization\":\"secret-must-not-copy\"}')",
                trace, model, body, "{\"choices\":[{\"message\":{\"content\":\"before\"}}]}", "before");
        var ids = jdbc.queryForList("SELECT id FROM mg_request_payload WHERE model_id=? ORDER BY id", Long.class, model);
        client().post().uri(BASE + "/validate").bodyValue(Map.of("payloadIds", ids))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data[0].usable").isEqualTo(true);
        client().post().uri(BASE + "/validate-personal").bodyValue(Map.of("payloadIds", ids))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data[0].usable").isEqualTo(false);
        String dataset = value(client().post().uri(BASE + "/create-dataset")
                .bodyValue(Map.of("name", "固定样本", "payloadIds", ids)).exchange().expectStatus().isOk());
        var cases = store.rows("SELECT * FROM mg_eval_case WHERE dataset_id=? ORDER BY ordinal_no", dataset);
        assertEquals(2, cases.size());
        assertFalse(String.valueOf(cases.get(0).get("snapshotJson")).contains("secret-must-not-copy"));
        jdbc.update("DELETE FROM mg_request_payload WHERE model_id=?", model);
        client().get().uri(BASE + "/sample?id=" + cases.get(0).get("id")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.snapshotJson.responseText").isEqualTo("before");
        String revision = value(client().post().uri(BASE + "/revise-dataset").bodyValue(Map.of("datasetId", dataset,
                "cases", List.of(Map.of("caseId", cases.get(0).get("id"), "referenceText", "expected"))))
                .exchange().expectStatus().isOk());
        assertEquals(2, store.count("SELECT version FROM mg_eval_dataset WHERE id=?", revision));

        String task = value(client().post().uri(BASE + "/create-task").bodyValue(Map.of("name", "执行测试",
                "datasetId", dataset, "modelId", model, "overrides", Map.of("temperature", 0.2), "concurrency", 1))
                .exchange().expectStatus().isOk());
        var first = store.claim(task, "node-a", 45);
        assertNotNull(first);
        assertNull(store.claim(task, "node-b", 45), "任务并发由数据库在实例间约束");
        String resultId = String.valueOf(first.get("id"));
        var taskSnapshot = (Map<String, Object>) first.get("task");
        var sample = (Map<String, Object>) first.get("sample");
        var outcome = runner.run(taskSnapshot, store.read(sample.get("snapshotJson"), PayloadDetailVO.class),
                String.valueOf(first.get("requestJson")), request -> store.saveRequest(resultId, "node-a", request))
                .block(Duration.ofSeconds(10));
        assertNotNull(outcome);
        assertEquals("SUCCESS", outcome.status());
        assertEquals("ok", outcome.responseText());
        assertEquals(12, outcome.inputTokens());
        jdbc.update("UPDATE mg_model SET upstream_url=? WHERE model_id=?", "http://127.0.0.1:1", model);
        var drift = assertThrows(com.pab.ficc.ibp.modelgate.server.common.BusinessException.class, () ->
                runner.run(taskSnapshot, store.read(sample.get("snapshotJson"), PayloadDetailVO.class),
                        String.valueOf(first.get("requestJson")), request -> fail("变更后不得发送评测请求"))
                        .block(Duration.ofSeconds(10)));
        assertTrue(drift.getMessage().contains("连接配置已变更"));
        jdbc.update("UPDATE mg_model SET upstream_url=? WHERE model_id=?", "http://127.0.0.1:" + upstream.port(), model);
        store.finish(task, resultId, "node-a", outcome.status(), outcome);
        var second = store.claim(task, "node-b", 45);
        assertNotNull(second);
        store.update("UPDATE mg_eval_result SET lease_until=? WHERE id=?", LocalDateTime.now().minusMinutes(1), second.get("id"));
        store.recover();
        assertEquals("UNCERTAIN", store.one("SELECT status FROM mg_eval_result WHERE id=?", second.get("id")).get("status"));
        assertEquals("PARTIAL", store.one("SELECT status FROM mg_eval_task WHERE id=?", task).get("status"));
        client().post().uri(BASE + "/score").bodyValue(Map.of("resultId", resultId, "verdict", "PASS", "score", 90, "note", "reviewed"))
                .exchange().expectStatus().isOk();
        client().get().uri(BASE + "/export?taskId=" + task).exchange().expectStatus().isOk()
                .expectBody(String.class).value(csv -> assertTrue(csv.contains("PASS")));
        client().get().uri(BASE + "/export?taskId=" + task + "&format=jsonl").exchange().expectStatus().isOk()
                .expectBody(String.class).value(json -> { assertTrue(json.contains("before")); assertTrue(json.contains("expected") == false); });

        String retryId = String.valueOf(second.get("id"));
        client().post().uri(BASE + "/retry").bodyValue(Map.of("taskId", task, "resultIds", List.of(retryId)))
                .exchange().expectStatus().isOk();
        var retry = store.claim(task, "node-c", 45);
        assertEquals(2, ((Number) retry.get("attempt")).intValue());
        client().post().uri(BASE + "/cancel?id=" + task).exchange().expectStatus().isOk();
        assertFalse(store.saveRequest(String.valueOf(retry.get("id")), "node-c", "not-sent"));
        store.finish(task, String.valueOf(retry.get("id")), "node-c", "SUCCESS", outcome);
        assertEquals("CANCELLED", store.one("SELECT status FROM mg_eval_result WHERE id=?", retry.get("id")).get("status"));
        assertEquals(3, store.count("SELECT COUNT(*) FROM mg_eval_result WHERE task_id=?", task));
        client().post().uri(BASE + "/retry").bodyValue(Map.of("taskId", task, "resultIds", List.of(retry.get("id"))))
                .exchange().expectStatus().isOk();
        try (var worker = new EvaluationWorker(store, runner, 1, true)) {
            worker.tick();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (!"COMPLETED".equals(store.one("SELECT status FROM mg_eval_task WHERE id=?", task).get("status")))
                    Thread.sleep(30);
            });
        }
        client().post().uri(BASE + "/delete-dataset?id=" + dataset).exchange().expectStatus().isBadRequest();
        client().post().uri(BASE + "/delete-task?id=" + task).exchange().expectStatus().isOk();
        client().post().uri(BASE + "/delete-dataset?id=" + revision).exchange().expectStatus().isOk();
        client().post().uri(BASE + "/delete-dataset?id=" + dataset).exchange().expectStatus().isOk();
        jdbc.update("DELETE FROM mg_model WHERE model_id=?", model);
    }

    @Test
    void replayKeepsRawStreamAndBuildsComparableAnswer() {
        var upstreamStream = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.header("Content-Type", "text/event-stream").sendString(Flux.just(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"first \"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"second\"},\"finish_reason\":\"stop\"}]}\n\n",
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2}}\n\n",
                        "data: [DONE]\n\n"))).bindNow();
        var jdbc = new JdbcTemplate(source);
        String modelId = "eval-stream-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            jdbc.update("INSERT INTO mg_model(model_id,model_name,provider,upstream_url) VALUES(?,?,?,?)",
                    modelId, "评测流式模型", "openai", "http://127.0.0.1:" + upstreamStream.port());
            var model = models.findByModelId(modelId);
            var sample = new PayloadDetailVO();
            sample.setRequestBody("{\"messages\":[{\"role\":\"user\",\"content\":\"favorite\"}],\"stream\":true}");
            var prepared = runner.prepare(model, sample.getRequestBody(), mapper.createObjectNode());
            var task = Map.<String, Object>of("modelSnapshot", store.json(model), "overridesJson", "{}", "timeoutSeconds", 10);
            var result = runner.run(task, sample, prepared, request -> true).block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertEquals("SUCCESS", result.status());
            assertEquals("first second", result.responseText());
            assertEquals(8, result.inputTokens());
            assertEquals(2, result.outputTokens());
            assertTrue(result.rawBody().contains("data: [DONE]"));
        } finally {
            upstreamStream.disposeNow();
            jdbc.update("DELETE FROM mg_model WHERE model_id=?", modelId);
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }

    private String value(WebTestClient.ResponseSpec response) throws Exception {
        return mapper.readTree(response.expectBody().returnResult().getResponseBody()).path("data").asText();
    }

}
