package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderAdapter;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredential;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstance;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic model routing filter (order=-140).
 *
 * 1. Reads request body to extract 'model' field
 * 2. Loads ModelInstance + ModelCredential from cache
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
        if (path.startsWith("/admin/") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        // 只处理 /v1/** 的业务流量（chat/completions、embeddings 等）
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        // DataBufferUtils.join 将流式 DataBuffer 聚合为单个 buffer，读取完整请求体
        // 注意：读取后原始 body 流已消费，必须通过 ServerHttpRequestDecorator 重新注入给下游
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer); // 必须手动释放，防止内存泄漏
                    return processRequest(exchange, chain, bodyBytes);
                })
                .switchIfEmpty(Mono.defer(() -> reject(exchange, HttpStatus.BAD_REQUEST, ErrorCode.MODEL_FIELD_MISSING)));
    }

    private Mono<Void> processRequest(ServerWebExchange exchange, GatewayFilterChain chain, byte[] bodyBytes) {
        return Mono.fromCallable(() -> {
            JsonNode root = objectMapper.readTree(bodyBytes);
            JsonNode modelNode = root.get("model");
            if (modelNode == null || modelNode.isNull()) return null;
            String modelId = modelNode.asText();

            // 将 modelId 写入 exchange attribute，供 ModelAuthFilter、QuotaFilter、TraceFilter 读取
            exchange.getAttributes().put("mg.modelId", modelId);

            Optional<ModelInstance> modelOpt = modelService.getModelCached(modelId);
            if (modelOpt.isEmpty()) return null;
            ModelInstance model = modelOpt.get();

            // 从 DB 读取上游鉴权凭证并解密 SK（走 Caffeine 缓存，不重复解密）
            ModelCredential credential = modelService.getCredentialDecrypted(modelId);

            // 按 apiSpec 选择适配器（Strategy 模式）：openai/azure_openai/anthropic/custom
            ProviderAdapter adapter = adapters.stream()
                    .filter(a -> a.supports(model.getApiSpec()))
                    .findFirst()
                    .orElse(null);

            if (adapter == null) return null;

            // 适配器负责：请求体参数转换、上游鉴权 Header 构造、上游 URL 构建
            byte[] transformedBody = adapter.transformRequestBody(bodyBytes, model, credential);
            HttpHeaders transformedHeaders = adapter.buildUpstreamHeaders(model, credential);
            URI upstreamUri = adapter.buildUpstreamUri(model, exchange.getRequest().getURI());

            return new RouteInfo(transformedBody, transformedHeaders, upstreamUri);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(info -> {
            if (info == null) {
                return reject(exchange, HttpStatus.NOT_FOUND, ErrorCode.MODEL_NOT_FOUND);
            }

            // 覆盖 Gateway 的路由目标地址，使请求转发到模型对应的上游 URL
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, info.upstreamUri());

            // 用 ServerHttpRequestDecorator 将新的 body 和 headers 重新注入请求链
            // 必须通过 decorator 而非直接修改，因为 ServerHttpRequest 是不可变对象
            DataBuffer newBuffer = exchange.getResponse().bufferFactory().wrap(info.body());
            ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
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
        });
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
