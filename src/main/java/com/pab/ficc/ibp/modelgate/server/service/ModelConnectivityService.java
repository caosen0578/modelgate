package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.adapter.ProviderAdapter;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ConnectivityTestVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 模型连通性探测。
 *
 * <p>向上游发送一次**真实**的最小推理请求，复用 {@link ProviderAdapter}——即请求体转换、
 * 鉴权 Header 构造、上游 URL 拼接与 {@code ModelRouteFilter} 走同一套逻辑。这样"测通"
 * 才等价于"这条配置真能路由"，而不是仅仅证明网络可达。
 *
 * <p>判定原则：只有上游返回 2xx 才算连通。鉴权失败、地址错误、超时、DNS 失败一律判失败，
 * 并给出具体原因，避免错误配置被误判为可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConnectivityService {

    /** 探测超时上限：模型本身可能配了很长的超时（如 570s），但连通性测试必须尽快给结论 */
    private static final int MAX_PROBE_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_PROBE_TIMEOUT_MS = 10_000;
    /** 上游响应体片段最大保留长度，避免把整篇报错灌进返回值与日志 */
    /**
     * 上游响应体回传上限（字符）。
     *
     * <p>原为 500，导致管理页「测试」看到的模型回答永远是半截，
     * 而排查上游问题恰恰需要看完整报文（错误详情往往在 JSON 末尾）。
     * 现放宽到 1MB：本通道是管理员手动触发的单次调试请求，不是业务热路径，
     * 回传大一点没有性能问题；仍保留上限是为了防止上游异常吐出超大响应把管理页卡死。
     * 可经 modelgate.connectivity-test.max-body-chars 调整。
     */
    private static final int DEFAULT_BODY_LIMIT = 1024 * 1024;

    private final List<ProviderAdapter> adapters;
    private final WebClient.Builder webClientBuilder;

    /** 见 {@link #DEFAULT_BODY_LIMIT} 的说明；可配以便应对超长上下文模型 */
    @Value("${modelgate.connectivity-test.max-body-chars:1048576}")
    private int maxBodyChars = DEFAULT_BODY_LIMIT;

    /**
     * 探测一条模型配置。
     *
     * @param model      模型配置（可以是未入库的草稿）
     * @param credential 上游凭证，secretKey 必须是**明文**（已保存模型需先解密）
     */
    public Mono<ConnectivityTestVO> probe(ModelInstanceEntity model, ModelCredentialEntity credential) {
        byte[] body = probeBody(model.getModelId(), model.getModelType()).getBytes(StandardCharsets.UTF_8);
        return exchange(model, credential, body, probePath(model));
    }

    /**
     * 构造并发送一次上游请求，统一做结果归类与日志。
     * {@link #probe}（最小探测体）与 {@link #chat}（调用方指定请求体）共用此实现，
     * 保证两者的适配器逻辑、超时策略、失败判定完全一致。
     */
    private Mono<ConnectivityTestVO> exchange(ModelInstanceEntity model, ModelCredentialEntity credential,
                                              byte[] requestBody, String path) {
        String apiSpec = model.getApiSpec() != null ? model.getApiSpec() : "openai";

        ProviderAdapter adapter = adapters.stream()
                .filter(a -> a.supports(apiSpec))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            log.warn("[Connectivity] 无匹配适配器: modelId={}, apiSpec={}", model.getModelId(), apiSpec);
            return Mono.just(ConnectivityTestVO.of(false, 0L,
                    "不支持的 API 规范: " + apiSpec + "，请检查 apiSpec 配置", null, null, null));
        }

        if (model.getUpstreamUrl() == null || model.getUpstreamUrl().isBlank()) {
            return Mono.just(ConnectivityTestVO.of(false, 0L, "上游地址为空，无法发起探测", null, null, null));
        }

        byte[] finalBody;
        HttpHeaders headers;
        URI upstreamUri;
        try {
            finalBody = adapter.transformRequestBody(requestBody, model, credential);
            headers = adapter.buildUpstreamHeaders(model, credential);
            upstreamUri = adapter.buildUpstreamUri(model, URI.create(path));
        } catch (Exception e) {
            // 上游地址非法、paramMapping JSON 语法错误等，在构造阶段就会炸——这本身就是配置错误
            log.warn("[Connectivity] 构造探测请求失败: modelId={}, apiSpec={}, err={}",
                    model.getModelId(), apiSpec, e.toString());
            return Mono.just(ConnectivityTestVO.of(false, 0L,
                    "配置无法构造上游请求: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    null, null, null));
        }

        Duration timeout = Duration.ofMillis(probeTimeoutMs(model.getTimeoutMs()));
        String uriText = upstreamUri.toString();
        long start = System.nanoTime();

        log.info("[Connectivity] 开始探测: modelId={}, apiSpec={}, authType={}, uri={}, timeout={}ms",
                model.getModelId(), apiSpec,
                credential != null ? credential.getAuthType() : "none", uriText, timeout.toMillis());

        return webClientBuilder.build()
                .post()
                .uri(upstreamUri)
                .headers(h -> h.addAll(headers))
                .bodyValue(finalBody)
                // exchangeToMono 不会对 4xx/5xx 抛异常，便于把上游状态码和报错原文一并带回前端
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> classify(resp.statusCode().value(), body, elapsedMs(start), uriText)))
                .timeout(timeout)
                .onErrorResume(e -> Mono.just(classifyError(e, elapsedMs(start), uriText, timeout)))
                .doOnNext(vo -> {
                    if (Boolean.TRUE.equals(vo.getSuccess())) {
                        log.info("[Connectivity] 探测成功: modelId={}, latency={}ms, httpStatus={}",
                                model.getModelId(), vo.getLatencyMs(), vo.getHttpStatus());
                    } else {
                        log.warn("[Connectivity] 探测失败: modelId={}, latency={}ms, httpStatus={}, message={}, body={}",
                                model.getModelId(), vo.getLatencyMs(), vo.getHttpStatus(),
                                vo.getMessage(), vo.getUpstreamBody());
                    }
                });
    }

    /**
     * 管理面对话测试：用调用方给定的请求体，走与真实转发一致的适配器逻辑直连上游。
     *
     * <p>与 {@link #probe} 的区别只在于请求体由调用方指定（messages / temperature 等），
     * 判定与错误归类完全复用。
     *
     * <p><b>密钥不出服务端</b>：上游凭证在此处解密并注入请求头，浏览器全程不接触。
     * 这也是本接口存在的理由——管理页无法、也不应持有模型的上游密钥。
     *
     * <p><b>注意</b>：本通道**跳过**凭证鉴权、访问控制、模型授权、配额限流等业务过滤器，
     * 验证的是"模型配置本身能否调通"，不等价于业务系统的完整调用链路。
     *
     * @param rawBody OpenAI 规范的请求体（非 openai 规范由对应适配器转换）
     */
    public Mono<ConnectivityTestVO> chat(ModelInstanceEntity model, ModelCredentialEntity credential, byte[] rawBody) {
        return exchange(model, credential, rawBody, probePath(model));
    }

    // ===== 结果判定 =====


    /**
     * 按上游 HTTP 状态码判定。只有 2xx 算连通；其余一律失败并指出排查方向。
     */
    private ConnectivityTestVO classify(int status, String body, long latencyMs, String uriText) {
        String snippet = snippet(body);

        if (status >= 200 && status < 300) {
            return ConnectivityTestVO.of(true, latencyMs, "连通成功", status, uriText, snippet);
        }

        String message;
        switch (status) {
            case 401, 403 -> message = "上游拒绝鉴权（HTTP " + status + "）：API Key 或鉴权类型不正确";
            case 404 -> message = "上游地址不存在（HTTP 404）：请检查上游地址与 API 规范是否匹配";
            case 400, 422 -> message = "上游拒绝请求参数（HTTP " + status + "）：网络与鉴权正常，"
                    + "但模型 ID 或参数映射可能不被上游接受";
            case 429 -> message = "上游限流（HTTP 429）：网络与鉴权正常，当前不可用";
            default -> {
                if (status >= 500) {
                    message = "上游服务异常（HTTP " + status + "）：网络可达，上游自身报错";
                } else {
                    message = "上游返回非预期状态（HTTP " + status + "）";
                }
            }
        }
        return ConnectivityTestVO.of(false, latencyMs, message, status, uriText, snippet);
    }

    /**
     * 按异常类型判定连接层失败（未拿到 HTTP 状态码）。
     */
    private ConnectivityTestVO classifyError(Throwable e, long latencyMs, String uriText, Duration timeout) {
        Throwable root = rootCause(e);
        String message;
        if (e instanceof TimeoutException || root instanceof TimeoutException) {
            message = "连接超时（超过 " + timeout.toMillis() + "ms）：上游无响应，请检查地址与网络策略";
        } else if (root instanceof UnknownHostException) {
            message = "域名无法解析：" + root.getMessage() + "，请检查上游地址";
        } else if (root instanceof ConnectException) {
            message = "连接被拒绝：" + root.getMessage() + "，请检查上游端口与网络策略";
        } else {
            message = "请求上游失败：" + root.getClass().getSimpleName() + ": " + root.getMessage();
        }
        return ConnectivityTestVO.of(false, latencyMs, message, null, uriText, null);
    }

    // ===== 探测请求构造 =====

    /**
     * 探测路径。
     *
     * <p>模型配了 {@code upstream_path} 就用它——**必须与真实转发走同一路径**，
     * 否则会出现"测试通过但业务 404"（或反过来）这种最误导人的情况。
     * 留空时按模型类型回落到 OpenAI 兼容默认路径。
     *
     * <p>注意这里返回的是"调用方视角"的路径，最终 URI 仍由适配器的
     * {@code buildUpstreamUri} 决定：azure_openai 会忽略它，anthropic 会替换成自己的默认值。
     */
    private String probePath(ModelInstanceEntity model) {
        if (model.getUpstreamPath() != null && !model.getUpstreamPath().isBlank()) {
            return model.getUpstreamPath().trim();
        }
        if ("embedding".equals(model.getModelType())) {
            return "/v1/embeddings";
        }
        return "/v1/chat/completions";
    }

    /**
     * 最小探测请求体：chat 限制 max_tokens=1，尽量少消耗上游 token 与算力。
     * 以 OpenAI 规范构造，非 openai 规范由对应适配器转换。
     */
    private String probeBody(String modelId, String modelType) {
        String id = modelId != null ? modelId : "";
        if ("embedding".equals(modelType)) {
            return "{\"model\":\"" + id + "\",\"input\":\"ping\"}";
        }
        return "{\"model\":\"" + id + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],"
                + "\"max_tokens\":1,\"stream\":false}";
    }

    private int probeTimeoutMs(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_PROBE_TIMEOUT_MS;
        }
        return Math.min(configured, MAX_PROBE_TIMEOUT_MS);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return body.length() <= maxBodyChars ? body
                : body.substring(0, maxBodyChars) + "...(响应过长已截断，完整内容见上游日志)";
    }

    private Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
