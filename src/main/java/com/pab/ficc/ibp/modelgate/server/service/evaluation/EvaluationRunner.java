package com.pab.ficc.ibp.modelgate.server.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.ibp.modelgate.server.adapter.AdapterResponseContext;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderModelConfig;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderRequestPipeline;
import com.pab.ficc.ibp.modelgate.server.adapter.ResponseMode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadDetailVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import com.pab.ficc.ibp.modelgate.server.service.UpstreamResponseParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

@Service
public class EvaluationRunner {
    public record Outcome(String status, Integer httpStatus, long latencyMs, String rawBody, String responseText,
                          int inputTokens, int outputTokens, String tokenSource, String finishReason,
                          boolean complete, String message) {}
    private final ModelService models;
    private final ProviderRequestPipeline pipeline;
    private final UpstreamResponseParser parser;
    private final ObjectMapper mapper;
    private final WebClient client;
    private final int maxResponseBytes;

    public EvaluationRunner(ModelService models, ProviderRequestPipeline pipeline, UpstreamResponseParser parser,
            ObjectMapper mapper, WebClient.Builder builder,
            @Value("${modelgate.evaluation.max-response-bytes:4194304}") int maxResponseBytes) {
        this.models = models; this.pipeline = pipeline; this.parser = parser; this.mapper = mapper;
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
        client = builder.build();
    }

    public String prepare(ModelInstanceEntity model, String request, ObjectNode overrides) {
        var credential = models.getCredentialDecrypted(model.getModelId());
        var call = pipeline.prepare(model, credential, HttpMethod.POST, URI.create("/v1/chat/completions"),
                headers(), body(model, request, overrides), -1, -1).block(Duration.ofSeconds(30));
        if (call == null) throw EvaluationStore.invalid("模型请求无法转换");
        return new String(call.request().body(), StandardCharsets.UTF_8);
    }

    public Mono<Outcome> run(Map<String, Object> task, PayloadDetailVO sample, String preparedRequest,
                             Predicate<String> beforeSend) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            var model = read(task.get("modelSnapshot"), ModelInstanceEntity.class);
            var credential = models.getCredentialForSnapshot(model);
            var overrides = read(task.get("overridesJson"), ObjectNode.class);
            return pipeline.prepare(model, credential, HttpMethod.POST, URI.create("/v1/chat/completions"),
                    headers(), body(model, sample.getRequestBody(), overrides), -1, -1)
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(call -> {
                        String effective = new String(call.request().body(), StandardCharsets.UTF_8);
                        if (!effective.equals(preparedRequest)) throw EvaluationStore.invalid("最终请求与创建任务时的快照不一致");
                        if (!beforeSend.test(effective)) return Mono.error(new CancellationException("任务已取消"));
                        var config = call.config();
                        // 仅对响应解码使用统一格式，原始响应独立保存。
                        var normalized = new ProviderModelConfig(config.modelId(), config.upstreamModelId(), config.modelType(),
                                config.adapterId(), config.upstreamUrl(), config.upstreamPath(), ResponseMode.OPENAI_COMPATIBLE,
                                config.configVersion(), config.adapterConfig());
                        return client.method(call.request().method()).uri(call.request().uri())
                                .headers(h -> h.addAll(call.request().headers())).bodyValue(call.request().body())
                                .exchangeToMono(response -> {
                                    int status = response.statusCode().value();
                                    boolean stream = response.headers().contentType().map(type -> type.isCompatibleWith(
                                            org.springframework.http.MediaType.TEXT_EVENT_STREAM)).orElse(false);
                                    return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), maxResponseBytes)
                                            .map(buffer -> {
                                                try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
                                                finally { DataBufferUtils.release(buffer); }
                                            }).defaultIfEmpty(new byte[0]).flatMap(raw -> {
                                                var session = call.adapter().openResponse(new AdapterResponseContext(normalized,
                                                        call.operation(), status, response.headers().asHttpHeaders()));
                                                return DataBufferUtils.join(session.transform(Flux.just(
                                                                DefaultDataBufferFactory.sharedInstance.wrap(raw)), DefaultDataBufferFactory.sharedInstance),
                                                                maxResponseBytes)
                                                        .map(buffer -> {
                                                            try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
                                                            finally { DataBufferUtils.release(buffer); }
                                                        }).defaultIfEmpty(new byte[0])
                                                        .flatMap(bytes -> session.finish().thenReturn(bytes))
                                                        .map(bytes -> {
                                                            var parsed = parser.parse(bytes, stream, body(model, sample.getRequestBody(), overrides));
                                                            var telemetry = session.telemetry();
                                                            boolean exact = telemetry != null && telemetry.hasUsage();
                                                            boolean success = status >= 200 && status < 300
                                                                    && (telemetry == null || telemetry.errorCode() == null);
                                                            return new Outcome(success ? "SUCCESS" : "FAILED", status, elapsed(started),
                                                                    new String(raw, StandardCharsets.UTF_8), parsed.responseText(),
                                                                    exact ? telemetry.inputTokens() : parsed.inputTokens(),
                                                                    exact ? telemetry.outputTokens() : parsed.outputTokens(),
                                                                    exact ? "usage" : parsed.tokenSource(), parsed.finishReason(), true,
                                                                    success ? "执行完成" : "上游状态或协议异常");
                                                        }).doFinally(signal -> session.close());
                                            });
                                });
                    })
                    .timeout(Duration.ofSeconds(((Number) task.get("timeoutSeconds")).longValue()))
                    .onErrorResume(error -> Mono.just(failure(error, elapsed(started))));
        });
    }

    public static Outcome failure(Throwable error, long latency) {
        String status = error instanceof TimeoutException ? "TIMEOUT"
                : error instanceof DataBufferLimitException ? "TRUNCATED" : "FAILED";
        String message = switch (status) {
            case "TIMEOUT" -> "超过任务配置的单条超时时间";
            case "TRUNCATED" -> "响应超过评测留档大小上限，结果不完整";
            default -> error instanceof com.pab.ficc.ibp.modelgate.server.common.BusinessException
                    ? error.getMessage() : "执行异常: " + error.getClass().getSimpleName();
        };
        return new Outcome(status, null, latency, null, null, 0, 0, "none", null, false, message);
    }

    private byte[] body(ModelInstanceEntity model, String original, ObjectNode overrides) {
        var node = read(original, ObjectNode.class);
        node.setAll(overrides);
        node.put("model", model.getModelId());
        try { return mapper.writeValueAsBytes(node); }
        catch (Exception e) { throw EvaluationStore.invalid("请求体格式不正确"); }
    }

    private <T> T read(Object value, Class<T> type) {
        try { return mapper.readValue(String.valueOf(value), type); }
        catch (Exception e) { throw EvaluationStore.invalid("评测快照格式不正确"); }
    }

    private static HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
