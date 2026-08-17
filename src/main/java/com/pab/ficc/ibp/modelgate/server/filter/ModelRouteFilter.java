package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderAdapter;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic model routing filter (order=-140).
 *
 * 1. Reads request body to extract 'model' field
 * 2. Loads ModelInstanceEntity + ModelCredentialEntity from cache
 * 3. Delegates to ProviderAdapter for request transformation
 * 4. Sets GATEWAY_REQUEST_URL_ATTR for upstream routing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouteFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -140;

    private final ModelService modelService;
    private final List<ProviderAdapter> adapters;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith(UrlConstant.MANAGE_PREFIX) || path.startsWith(UrlConstant.ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }
        // 只处理 /v1/** 的业务流量（chat/completions、embeddings 等）
        if (!path.startsWith(UrlConstant.BUSINESS_PREFIX)) {
            return chain.filter(exchange);
        }

        // BodyParseFilter (-175) 已将请求体读入 mg.bodyBytes；直接复用，避免二次读取（流已消费）
        byte[] cached = exchange.getAttribute("mg.bodyBytes");
        if (cached != null) {
            return processRequest(exchange, chain, cached);
        }
        // 降级路径：mg.bodyBytes 未设置时（理论上不应出现），重新读取 body。
        // 记 WARN 而非静默降级：走到这里说明 BodyParseFilter(-175) 没生效
        // （order 被改动、Content-Type 非 JSON、或请求非 POST），属于配置/链路异常而非调用方问题
        log.warn("[ModelRoute] mg.bodyBytes 缺失，走降级重读请求体路径: path={}, method={}, contentType={}",
                path, exchange.getRequest().getMethod(), exchange.getRequest().getHeaders().getContentType());
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);
                    return processRequest(exchange, chain, bodyBytes);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[ModelRoute] 拒绝: 请求体为空, path={}", path);
                    return reject(exchange, HttpStatus.BAD_REQUEST, ErrorCode.MODEL_FIELD_MISSING);
                }));
    }

    private Mono<Void> processRequest(ServerWebExchange exchange, GatewayFilterChain chain, byte[] bodyBytes) {
        // 提取 model 字段（纯 CPU 操作，不涉及 IO，可在当前线程执行）
        JsonNode root;
        try {
            root = objectMapper.readTree(bodyBytes);
        } catch (Exception e) {
            log.info("[ModelRoute] 拒绝: 请求体非合法 JSON, bytes={}, cause={}", bodyBytes.length, e.getMessage());
            return reject(exchange, HttpStatus.BAD_REQUEST, ErrorCode.MODEL_FIELD_MISSING);
        }
        JsonNode modelNode = root.get("model");
        if (modelNode == null || modelNode.isNull() || modelNode.asText().isBlank()) {
            // 请求体中缺少 model 字段，明确返回 400（而非让下面的 null 流转到 404）
            log.info("[ModelRoute] 拒绝: 请求体缺少 model 字段");
            return reject(exchange, HttpStatus.BAD_REQUEST, ErrorCode.MODEL_FIELD_MISSING);
        }
        final String modelId = modelNode.asText();
        // BodyParseFilter (-175) 已提取并写入，此处保持幂等（兼容直接调用场景）
        exchange.getAttributes().put("mg.modelId", modelId);

        // 同 ModelAuthFilter：用 Optional 包一层，避免 Callable 返回 null 时 Mono 变 empty、
        // flatMap 不执行导致链路断掉（此处 null 表示"模型不存在或无适配器"，本该走 reject）
        return Mono.fromCallable(() -> Optional.ofNullable(buildRouteInfo(exchange, bodyBytes, modelId)))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(result -> {
            RouteInfo info = result.orElse(null);
            if (info == null) {
                return reject(exchange, HttpStatus.NOT_FOUND, ErrorCode.MODEL_NOT_FOUND);
            }
            return applyRoute(exchange, chain, info, modelId);
        });
    }

    /** 装载模型+凭证、选适配器、构造上游请求；不可用时返回 null（调用方转 404）。 */
    private RouteInfo buildRouteInfo(ServerWebExchange exchange, byte[] bodyBytes, String modelId) {
        {
            Optional<ModelInstanceEntity> modelOpt = modelService.getModelCached(modelId);
            if (modelOpt.isEmpty()) {
                log.info("[ModelRoute] 拒绝: 模型不存在, modelId={}", modelId);
                return null;
            }
            ModelInstanceEntity model = modelOpt.get();

            // 从缓存读取上游鉴权凭证并解密 SK
            ModelCredentialEntity credential = modelService.getCredentialDecrypted(modelId);

            // 按 apiSpec 选择适配器（Strategy 模式）：openai/azure_openai/anthropic/custom
            ProviderAdapter adapter = adapters.stream()
                    .filter(a -> a.supports(model.getApiSpec()))
                    .findFirst()
                    .orElse(null);

            if (adapter == null) {
                // 与"模型不存在"同为 404，但成因完全不同：这是库里 api_spec 填了没人实现的值，
                // 属配置错误而非调用方问题，必须能从日志区分，否则排查会一直往调用方身上找
                log.warn("[ModelRoute] 无可用适配器: modelId={}, apiSpec={}, 已注册={}",
                        modelId, model.getApiSpec(),
                        adapters.stream().map(ProviderAdapter::getApiSpec).toList());
                return null;
            }

            // 适配器负责：请求体参数转换、上游鉴权 Header 构造、上游 URL 构建
            byte[] transformedBody = adapter.transformRequestBody(bodyBytes, model, credential);
            HttpHeaders transformedHeaders = adapter.buildUpstreamHeaders(model, credential);
            URI upstreamUri = adapter.buildUpstreamUri(model, exchange.getRequest().getURI());

            return new RouteInfo(transformedBody, transformedHeaders, upstreamUri);
        }
    }

    private Mono<Void> applyRoute(ServerWebExchange exchange, GatewayFilterChain chain,
                                  RouteInfo info, String modelId) {
        {
            URI upstreamUri = info.upstreamUri();

            // ⚠️ 只设 GATEWAY_REQUEST_URL_ATTR 是不够的：
            // Gateway 内置的 RouteToRequestUrlFilter（order=10000，排在本过滤器 -140 之后）
            // 会用 Route 的**静态 uri** 重新计算并**覆盖**该属性，
            // 结果请求被打到 application.yml 里那个占位地址（${MODEL_UPSTREAM_URL}），
            // 表现为 Connection refused 或 200 空响应。
            // 正确做法是把 Route 本身换掉：RouteToRequestUrlFilter 取 scheme/host/port 自 Route、
            // 取 path/query 自请求 URI，两者都指向上游，合并结果才正确。
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route != null) {
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        Route.async().id(route.getId())
                                .uri(upstreamUri)
                                .order(route.getOrder())
                                .asyncPredicate(route.getPredicate())
                                .filters(route.getFilters())
                                .build());
            }
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, upstreamUri);
            // 转发目标是排查"请求到底打去了哪"的关键信息；DEBUG 级，生产默认关闭
            log.debug("[ModelRoute] 转发: modelId={}, upstream={}", modelId, upstreamUri);

            // 用 ServerHttpRequestDecorator 将新的 body 和 headers 重新注入请求链
            // 必须通过 decorator 而非直接修改，因为 ServerHttpRequest 是不可变对象
            DataBuffer newBuffer = exchange.getResponse().bufferFactory().wrap(info.body());
            ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public URI getURI() {
                    // 让 RouteToRequestUrlFilter 合并出的 path/query 也指向上游
                    // （upstream_path 配了自定义路径时，这一步是必需的）
                    return upstreamUri;
                }

                @Override
                public HttpHeaders getHeaders() {
                    HttpHeaders merged = new HttpHeaders();
                    merged.putAll(super.getHeaders());
                    merged.putAll(info.headers());        // 上游鉴权 Header 合并
                    merged.setContentLength(info.body().length); // 更新 Content-Length，避免上游解析失败
                    return merged;
                }

                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.just(newBuffer);
                }
            };

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + errorCode.getCode() + ",\"message\":\"" + errorCode.getMessage() + "\"}").getBytes();
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private record RouteInfo(byte[] body, HttpHeaders headers, URI upstreamUri) {}
}
