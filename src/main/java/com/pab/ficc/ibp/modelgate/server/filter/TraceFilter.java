package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.service.AuditService;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import com.pab.ficc.ibp.modelgate.server.service.PayloadService;
import com.pab.ficc.ibp.modelgate.server.service.UpstreamResponseParser;

import java.io.ByteArrayOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 调用审计过滤器（order=-200，业务流量链路最先执行）。
 *
 * <p><b>order 由 -150 前移至 -200（本轮变更，原 05 §6「禁止改 order」经决策解除）</b>：
 * 审计写在 {@code doFinally} 里，而 GlobalFilter 是链式的——排在 -150 时，
 * 任何在它之前拒绝的请求（CredentialAuth -190 的 401、AccessControl -180 的 403、
 * ModelAuth -170 的 403、Quota -160 的 429）都会直接返回、根本不会执行到本过滤器，
 * 结果是**所有失败尝试一条审计都没有**——而安全审计恰恰最需要记录失败。
 * 前移到 -200 后，本过滤器成为链路第一环，其 {@code doFinally} 覆盖全部结局。
 *
 * <p><b>随之而来的约束</b>：本过滤器执行时，下游还没写入 appId / modelId，
 * 因此这些字段一律在 {@code doFinally} 里从 exchange attribute 读取（届时已被下游填好）。
 * <b>不要改回在方法开头读请求头</b>——那样读到的永远是 null。
 *
 * <p>traceId 由 {@code TraceWebFilter}（WebFilter 层，更靠前）统一生成，本类只消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceFilter implements GlobalFilter, Ordered {

    /** 必须是业务流量链路的第一个过滤器，否则被拒绝的请求不会进审计 */
    private static final int ORDER = -200;

    private final AuditService auditService;
    private final PayloadService payloadService;
    private final ModelAuthService modelAuthService;
    private final UpstreamResponseParser responseParser;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // GlobalFilter 只对匹配到路由的请求执行，故这里必然是 /v1/** 业务流量
        String traceId = exchange.getAttribute(TraceWebFilter.ATTR_TRACE_ID);
        String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : null;
        String requestPath = exchange.getRequest().getURI().getPath();
        long startMs = System.currentTimeMillis();

        // 可观测响应头必须在 beforeCommit 里写：
        // 响应一旦提交，getHeaders() 就变成 ReadOnlyHttpHeaders，set() 直接抛
        // UnsupportedOperationException。放在 doFinally 里写过一版，结果成功请求的响应早已提交，
        // 异常把同一个 try 块里的审计一起带走 —— 表现为"只有被拒请求进审计、成功请求全丢"。
        exchange.getResponse().beforeCommit(() -> {
            String appId   = exchange.getAttribute(CredentialAuthFilter.ATTR_APP_ID);
            String modelId = exchange.getAttribute("mg.modelId");
            if (appId != null)   exchange.getResponse().getHeaders().set("X-Mg-App", appId);
            if (modelId != null) exchange.getResponse().getHeaders().set("X-Mg-Model", modelId);
            return Mono.empty();
        });

        return chain.filter(exchange)
                .doFinally(signal -> {
                    try {
                        // 下游填充的上下文，此刻才可用；请求被中途拒绝时对应字段为 null，属正常
                        String appId    = exchange.getAttribute(CredentialAuthFilter.ATTR_APP_ID);
                        String credType = exchange.getAttribute(CredentialAuthFilter.ATTR_CRED_TYPE);
                        String modelId  = exchange.getAttribute("mg.modelId");
                        String errorMsg = exchange.getAttribute("mg.errorMsg");

                        long latencyMs = System.currentTimeMillis() - startMs;
                        // Spring 6：getStatusCode() 返回 HttpStatusCode 接口（可承载非标准码），
                        // 不再是 HttpStatus 枚举；这里只取数值写审计，无需转成枚举
                        HttpStatusCode status = exchange.getResponse().getStatusCode();
                        int httpStatus = status != null ? status.value() : 0;

                        byte[] requestBytes  = exchange.getAttribute("mg.bodyBytes");
                        byte[] responseBytes = collectedResponse(exchange);
                        boolean stream = PayloadCaptureFilter.isStreamResponse(exchange);

                        // Token 解析**不受报文开关控制**，始终执行：
                        // 月度配额（QuotaFilter.checkMonthlyTokens）与全部用量统计都依赖它。
                        // 此前这两个参数是硬编码的 0，导致审计表 token 恒为 0、月度配额从未生效过。
                        // 只取 usage 不拼正文，成本可忽略——2026-08-18 前 parseUsageOnly 名不副实，
                        // 实际会全量拼正文，压测证实是并发升高时拖慢事件循环线程的主因之一，已修复为
                        // 尾部有限帧数反向扫描（见 UpstreamResponseParser）。本 doFinally 之所以能继续
                        // 同步跑在调用线程（不 subscribeOn 到 boundedElastic），前提就是这里每一步都是
                        // 有界的：parseUsageOnly 现在最多解析尾部 20 帧，audit/payload 均只是队列 offer。
                        // 谁再往这个 doFinally 里加无界或阻塞操作，必须同步挪线程，不能想当然继续同步跑。
                        UpstreamResponseParser.ParsedResponse parsed = responseBytes == null
                                ? UpstreamResponseParser.ParsedResponse.empty()
                                : responseParser.parseUsageOnly(responseBytes, stream, requestBytes);

                        // 全量审计：appId 为空表示鉴权阶段即被拒（mg_audit_log.app_id 本就可空）。
                        // 这类记录是排查"谁在拿无效凭证反复试"的唯一依据，不能因为没有 appId 就丢掉。
                        // 本块内不得再放任何可能抛异常的操作，审计必须是 doFinally 里的第一等公民
                        auditService.log(appId, modelId, credType, clientIp,
                                requestPath, httpStatus, latencyMs,
                                parsed.inputTokens(), parsed.outputTokens(), parsed.tokenSource(),
                                errorMsg, traceId);

                        // 报文留档：仅当该 (appId, modelId) 授权行开了开关。
                        // submit 只入队不解析，O(1)，不阻塞
                        if (appId != null && modelId != null && responseBytes != null
                                && modelAuthService.isPayloadLogEnabled(appId, modelId)) {
                            payloadService.submit(traceId, appId, modelId, requestBytes, responseBytes,
                                    stream, Boolean.TRUE.equals(exchange.getAttribute(
                                            PayloadCaptureFilter.ATTR_RESPONSE_TRUNCATED)),
                                    httpStatus);
                        }

                        log.debug("[Trace] 完成: appId={}, modelId={}, status={}, latency={}ms, tokens={}/{}({}), signal={}",
                                appId, modelId, httpStatus, latencyMs,
                                parsed.inputTokens(), parsed.outputTokens(), parsed.tokenSource(), signal);
                    } catch (Exception e) {
                        // doFinally 抛出的异常会打断响应收尾，而审计失败绝不该影响已经完成的业务请求。
                        // 必须就地吞掉并记 WARN——静默吞则审计断流时无人知晓
                        log.warn("[Trace] 审计记录写入失败（不影响本次请求）: traceId={}", traceId, e);
                    }
                });
    }

    /** 取 PayloadCaptureFilter 采集到的响应字节；未采集到（如请求被拒、非业务路径）返回 null。 */
    private static byte[] collectedResponse(ServerWebExchange exchange) {
        Object buf = exchange.getAttribute(PayloadCaptureFilter.ATTR_RESPONSE_BYTES);
        return buf instanceof ByteArrayOutputStream out ? out.toByteArray() : null;
    }
}
