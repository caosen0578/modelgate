package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ConnectivityTestVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 通用服务的上游连通性探测（管理面用）。
 *
 * <p><b>与模型侧 {@code ModelConnectivityService} 的语义差别很大，不要照搬理解：</b>
 * 模型侧知道上游一定是 OpenAI/Anthropic 兼容接口，可以构造一个"最小可用请求"，
 * 拿到 2xx 就等于真的调通了。通用服务<b>没有统一的接口形状</b>——网关不知道
 * 哪个路径可用、要什么请求体，凭空构造只会得到一个必然失败的 404/405。
 *
 * <p>所以本探测只回答一个更弱但诚实的问题：<b>「网关能不能连到这个上游地址」</b>。
 * <ul>
 *   <li>拿到<b>任何</b> HTTP 响应（哪怕 404 / 405）→ <b>判定连通</b>：
 *       DNS 解析、网络策略、端口、TLS 全都通了，剩下的路径是否正确属于接入方自己的事</li>
 *   <li>连接层失败（超时 / DNS 解析不了 / 连接被拒）→ 判定不通，这才是运维要处理的</li>
 *   <li>5xx 单独标注：网络可达但上游自身报错</li>
 * </ul>
 *
 * <p>⚠️ <b>因此本测试通过 ≠ 接入方能调通业务接口。</b> 要验证端到端全链路
 * （凭证鉴权 → 访问控制 → 服务授权 → 配额 → 路由 → 上游），
 * 用「凭证管理 → 接入示例 → 测试连通」，那个是按接入方身份打真实的 {@code /svc/**}。
 * 两者的区别与模型侧「🧪 测试」和「接入示例测试」的区别是同一回事。
 */
@Slf4j
@Service
public class ServiceConnectivityService {

    /** 探测超时上限；比模型侧的 15s 短——通用服务不是 LLM，连不上就是连不上，不需要等那么久 */
    private static final int MAX_PROBE_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_PROBE_TIMEOUT_MS = 5_000;

    /** 响应体片段上限：探测只是为了让人看一眼上游到底回了什么，不需要完整报文 */
    private static final int SNIPPET_LIMIT = 2048;

    /**
     * 报文回放超时，<b>不能沿用探测的 10s</b>。
     *
     * <p>回放发的是真实业务请求（可能是 SSE、可能是慢接口），10s 会让它必然超时，
     * 而现象看起来像"上游不可达"——模型侧刚因为探测/对话共用 15s 踩过同一个坑。
     * 与模型对话测试取同一个量级（120s）。
     */
    private static final int REPLAY_TIMEOUT_MS = 120_000;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ServiceConnectivityService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 探测服务上游是否可达。
     *
     * <p>方法 / 路径 / 请求体<b>优先取入参，为空则回落到服务录入时登记的探测配置</b>
     * （{@code mg_service.probe_method / probe_path / probe_body}）。
     * 这样"平时点一下测试"用登记好的方式，"临时换个路径试试"也不用改配置。
     *
     * <p>⚠️ <b>非 GET 的探测会对上游产生真实副作用</b>：通用服务的写接口可能真的建一条数据。
     * 网关无法替业务判断哪个接口幂等，所以这件事交给录入者决定，管理页面上有显著警告。
     *
     * @param method   探测方法，为空则取服务登记值（再为空则 GET）
     * @param probePath 探测路径，为空则取服务登记值（再为空则根路径）
     * @param body     探测请求体，为空则取服务登记值；GET/HEAD 一律忽略
     */
    public Mono<ConnectivityTestVO> probe(ServiceEntity service, ServiceCredentialEntity credential,
                                          String method, String probePath, String body) {
        return send(service, credential, method, probePath, body,
                probeTimeoutMs(service.getTimeoutMs()), false);
    }

    /**
     * 报文回放：把留档里的那一发请求原样（或改过之后）重新打给上游。
     *
     * <p>与 {@link #probe} 共用同一段实现，<b>只有超时不同</b>——这一点是刻意的：
     * 探测发的是最小请求、10s 足够；回放发的是真实业务请求，SSE 服务或慢接口
     * 远不止 10 秒。两者共用短超时会让回放对慢接口<b>必然超时</b>，
     * 而报错看起来像"上游不可达"（模型侧刚踩过一模一样的坑，见 11 §34.10）。
     *
     * <p>⚠️ <b>回放会对上游产生真实副作用</b>：原请求是写操作时，回放会再写一次。
     * 管理页在发送前有显著警告，但网关无法替业务判断哪个接口幂等。
     */
    public Mono<ConnectivityTestVO> replay(ServiceEntity service, ServiceCredentialEntity credential,
                                           String method, String path, String body) {
        log.warn("[ServiceConnectivity] 报文回放：将向上游重新发送请求，可能产生副作用: "
                + "serviceId={}, method={}, path={}", service.getServiceId(), method, path);
        return send(service, credential, method, path, body, REPLAY_TIMEOUT_MS, true);
    }

    private Mono<ConnectivityTestVO> send(ServiceEntity service, ServiceCredentialEntity credential,
                                          String method, String probePath, String body,
                                          int timeoutMs, boolean replayMode) {
        String base = service.normalizedUpstreamUrl();
        if (base == null || base.isBlank()) {
            return Mono.just(ConnectivityTestVO.of(false, 0L, "上游地址为空，无法发起探测", null, null, null));
        }

        // 探测路径为空 / 只填了一个 "/" 都表示"打服务根"，统一归到空串，
        // 由 joinUpstream 按 upstreamUrl 带不带路径决定要不要补斜杠——
        // 这里自己补 "/" 会让 http://host:9000/chat/completions 被探成
        // /chat/completions/，上游回 404，看起来像地址填错了
        String effPath = firstNonBlank(probePath, service.getProbePath());
        String path = (effPath == null || effPath.isBlank()) ? "" : effPath.trim();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        if ("/".equals(path)) {
            path = "";
        }

        String verb = firstNonBlank(method, service.resolveProbeMethod());
        HttpMethod httpMethod = HttpMethod.valueOf(verb.toUpperCase());
        // GET / HEAD 不允许带请求体；带了会被 WebClient 忽略或直接报错，
        // 这里显式置空而不是交给下游猜
        boolean bodyAllowed = !HttpMethod.GET.equals(httpMethod) && !HttpMethod.HEAD.equals(httpMethod);
        String effBody = bodyAllowed ? firstNonBlank(body, service.getProbeBody()) : null;

        // 与 ServiceRouteFilter 共用同一套拼接：探测的价值就在于"打的地址和真实转发一致"，
        // 两边各拼各的会出现"探测通了但转发 404"这类最难查的不一致
        String target = service.joinUpstream(path);
        URI uri;
        try {
            uri = URI.create(target);
        } catch (Exception e) {
            // 上游地址里有非法字符时在这里就炸——这本身就是配置错误，直接告诉运营
            return Mono.just(ConnectivityTestVO.of(false, 0L,
                    "上游地址非法，无法构造请求：" + e.getMessage(), null, target, null));
        }

        Duration timeout = Duration.ofMillis(timeoutMs);
        HttpHeaders headers = buildHeaders(credential, service.getServiceId());
        String uriText = uri.toString();
        long startNanos = System.nanoTime();

        // 默认 GET（无副作用）；录入者显式登记了其它方法才会用其它方法。
        // 非 GET 打一条 WARN 留痕：万一探测在上游建了脏数据，日志里能查到是谁什么时候点的。
        if (bodyAllowed && !replayMode) {
            log.warn("[ServiceConnectivity] 使用非 GET 方法探测，可能对上游产生副作用: "
                    + "serviceId={}, method={}, uri={}", service.getServiceId(), verb, uriText);
        }
        WebClient.RequestBodySpec spec = webClient.method(httpMethod)
                .uri(uri)
                .headers(h -> h.addAll(headers));
        WebClient.RequestHeadersSpec<?> req = effBody == null || effBody.isBlank()
                ? spec
                : spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(effBody);

        return req
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(respBody -> classify(resp.statusCode().value(), respBody,
                                elapsedMs(startNanos), uriText)))
                .timeout(timeout)
                .onErrorResume(e -> Mono.just(
                        classifyError(e, elapsedMs(startNanos), uriText, timeout)));
    }

    /**
     * 构造探测请求头：与真实转发注入的是同一套上游凭证。
     *
     * <p>必须带上凭证——否则上游对未鉴权请求一律 401，探测结果永远是"上游拒绝鉴权"，
     * 而真实转发其实是通的，那就成了误报。这与 {@code ServiceRouteFilter.buildUpstreamAuthHeaders}
     * 是同一套逻辑，改一处要同步另一处。
     */
    private HttpHeaders buildHeaders(ServiceCredentialEntity cred, String serviceId) {
        HttpHeaders h = new HttpHeaders();
        if (cred == null) {
            return h;
        }
        String type = cred.getAuthType();
        if ("api_key".equals(type) && cred.getSecretKey() != null) {
            h.setBearerAuth(cred.getSecretKey());
        } else if ("ak_sk".equals(type)) {
            if (cred.getAccessKey() != null) {
                h.set("X-Access-Key", cred.getAccessKey());
            }
            if (cred.getSecretKey() != null) {
                h.set("X-Secret-Key", cred.getSecretKey());
            }
        }
        applyExtraHeaders(h, cred.getExtraHeaders(), serviceId);
        return h;
    }

    /** 附加头解析失败只记 WARN 并跳过，与转发链路的处理保持一致（配置问题不该让探测直接崩）。 */
    private void applyExtraHeaders(HttpHeaders target, String json, String serviceId) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, String> extra = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            extra.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    target.set(k, v);
                }
            });
        } catch (Exception e) {
            log.warn("[ServiceConnectivity] 附加请求头解析失败，已跳过: serviceId={}, cause={}",
                    serviceId, e.getMessage());
        }
    }

    /**
     * 归类 HTTP 结果。
     *
     * <p><b>与模型侧最大的不同：4xx 也判连通。</b> 网关并不知道这个服务哪个路径可用，
     * 探测打的是根路径，上游回 404/405 完全正常——那恰恰证明网络与地址都对。
     * 把它判成失败会让运营以为配错了地址，然后去改一个本来就对的配置。
     */
    private ConnectivityTestVO classify(int status, String body, long latencyMs, String uriText) {
        String snippet = snippet(body);

        if (status >= 200 && status < 300) {
            return ConnectivityTestVO.of(true, latencyMs,
                    "连通成功（HTTP " + status + "）：上游可达且返回正常", status, uriText, snippet);
        }
        if (status >= 500) {
            return ConnectivityTestVO.of(false, latencyMs,
                    "上游服务异常（HTTP " + status + "）：网络可达，上游自身报错", status, uriText, snippet);
        }
        if (status == 401 || status == 403) {
            return ConnectivityTestVO.of(true, latencyMs,
                    "上游可达（HTTP " + status + "）：网络与地址正确，但上游拒绝了本次探测的鉴权。"
                            + "若已配置上游凭证，请确认鉴权类型与密钥是否正确；未配置则属正常。",
                    status, uriText, snippet);
        }
        // 404 / 405 / 400 等：上游不认这个路径或方法很正常，网络这一层是通的
        return ConnectivityTestVO.of(true, latencyMs,
                "上游可达（HTTP " + status + "）：网络与地址正确，但上游不认这个路径或方法。"
                        + "如需更确切的结果，请在服务配置里登记正确的探测方法与路径。",
                status, uriText, snippet);
    }

    /** 连接层失败（压根没拿到 HTTP 状态码）——这才是真的不通。 */
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

    /**
     * 探测超时：取服务配置值并夹在 [1s, {@value #MAX_PROBE_TIMEOUT_MS}ms] 之间。
     *
     * <p>夹上限是必要的：{@code mg_service.timeout_ms} 对 SSE 服务可能配成几十分钟，
     * 管理面点一下"测试"要等半小时显然不合理，而且会长时间占住 AdminScheduler 的名额。
     */
    private int probeTimeoutMs(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_PROBE_TIMEOUT_MS;
        }
        return Math.min(Math.max(configured, 1_000), MAX_PROBE_TIMEOUT_MS);
    }

    /** 取第一个非空白值；两个都空返回 null。 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return body.length() <= SNIPPET_LIMIT ? body
                : body.substring(0, SNIPPET_LIMIT) + "…（已截断，共 " + body.length() + " 字符）";
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
